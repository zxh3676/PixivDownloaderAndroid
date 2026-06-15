package com.pixiv.downloader.network

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.pixiv.downloader.model.ImageInfo
import com.pixiv.downloader.model.PixivWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

/**
 * Pixiv API 封装 —— 基于 SniBypassClient 直连。
 *
 * 支持：
 * - 获取作品详情（含时间戳反推原图）
 * - 获取画师作品列表
 * - 下载图片到 MediaStore / 文件系统
 */
class PixivApi(
    private val client: SniBypassClient,
    private val cookies: Map<String, String> = emptyMap(),
    private val context: android.content.Context? = null
) {
    // 下载并发数
    private val downloadSemaphore = Semaphore(5)

    /** Cookie 请求头 */
    private val cookieHeader: String?
        get() {
            if (cookies.isEmpty()) return null
            return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

    /** 公共请求头 */
    private val baseHeaders: Map<String, String>
        get() {
            val h = mutableMapOf<String, String>()
            cookieHeader?.let { h["Cookie"] = it }
            return h
        }

    // ─── 公开 API ───────────────────────────────────────────

    /**
     * 获取作品信息（含图片 URL 列表）
     */
    suspend fun getIllust(illustId: String): PixivWork = withContext(Dispatchers.IO) {
        val path = "/ajax/illust/$illustId?lang=zh"
        val result = client.fetch("www.pixiv.net", path, extraHeaders = baseHeaders)
        ensureSuccess(result)

        val body = JSONObject(String(result.body, Charsets.UTF_8))
        if (body.optBoolean("error", false)) {
            throw RuntimeException("API 错误: ${body.optString("message", "未知错误")}")
        }

        val data = body.getJSONObject("body")
        val pageCount = data.optInt("pageCount", 1)
        val authorName = sanitizeFilename(data.optString("userName", "Unknown"))
        val authorId = data.optString("userId", "0")

        // 尝试获取原图链接
        val urlObj = data.optJSONObject("urls")
        var firstUrl: String? = urlObj?.optString("original")
            ?.takeIf { it.isNotEmpty() && it != "null" }
        var isFallback = false

        // 原图为空 → 从缩略图时间戳反推
        if (firstUrl == null) {
            val userIllusts = data.optJSONObject("userIllusts")
            val thumbEntry = userIllusts?.optJSONObject(illustId)
            val thumbUrl = thumbEntry?.optString("url")
                ?.takeIf { it.isNotEmpty() && it != "null" }

            if (thumbUrl != null) {
                val timestamp = extractTimestamp(thumbUrl)
                if (timestamp != null) {
                    firstUrl = "https://i.pximg.net/img-original/img/$timestamp/${illustId}_p0.jpg"
                    isFallback = true
                }
            }
        }

        // 构建多页图片列表
        val imageUrls = mutableListOf<ImageInfo>()
        if (firstUrl != null) {
            val ext = firstUrl.substringAfterLast('.', "jpg")
            for (page in 0 until pageCount) {
                val u = if (page == 0) firstUrl
                else firstUrl.replace("_p0.$ext", "_p$page.$ext")
                imageUrls.add(
                    ImageInfo(
                        url = u,
                        filename = "${illustId}_p$page.$ext",
                        page = page,
                        isFallback = isFallback
                    )
                )
            }
        }

        PixivWork(
            id = illustId,
            title = data.optString("illustTitle", "Untitled"),
            authorName = authorName,
            authorId = authorId,
            imageUrls = imageUrls
        )
    }

    /**
     * 获取画师所有作品 ID
     */
    suspend fun getAuthorIds(userId: String): List<String> = withContext(Dispatchers.IO) {
        val path = "/ajax/user/$userId/profile/all?lang=zh"
        val result = client.fetch("www.pixiv.net", path, extraHeaders = baseHeaders)
        ensureSuccess(result)

        val body = JSONObject(String(result.body, Charsets.UTF_8))
        if (body.optBoolean("error", false)) {
            throw RuntimeException("API 错误: ${body.optString("message", "未知错误")}")
        }

        val data = body.getJSONObject("body")
        val illustIds = mutableSetOf<String>()
        data.optJSONObject("illusts")?.let { obj ->
            val iter = obj.keys()
            while (iter.hasNext()) illustIds.add(iter.next())
        }
        data.optJSONObject("manga")?.let { obj ->
            val iter = obj.keys()
            while (iter.hasNext()) illustIds.add(iter.next())
        }
        return@withContext illustIds.sortedByDescending { it.toIntOrNull() ?: 0 }
    }

    /**
     * 下载单张图片到 MediaStore 或文件系统。
     * @param saveDir 目标目录名（如 "画师名_ID"）
     * @param onProgress 进度回调 (current, total)
     */
    suspend fun downloadImage(
        img: ImageInfo,
        saveDir: String
    ): Boolean = downloadSemaphore.withPermit {
        withContext(Dispatchers.IO) {
            val host = "i.pximg.net"

            // 反推图片需要盲测后缀
            val urlsToTry = mutableListOf(img.url)
            val filenamesToTry = mutableListOf(img.filename)
            if (img.isFallback && img.filename.endsWith(".jpg")) {
                urlsToTry.add(img.url.dropLast(4) + ".png")
                urlsToTry.add(img.url.dropLast(4) + ".gif")
                filenamesToTry.add(img.filename.dropLast(4) + ".png")
                filenamesToTry.add(img.filename.dropLast(4) + ".gif")
            }

            for (idx in urlsToTry.indices) {
                val u = urlsToTry[idx]
                val fname = filenamesToTry[idx]
                try {
                    val downloadPath = u.replace("https://$host", "")
                    val imageBytes = client.downloadImage(host, downloadPath)
                    saveImageToStorage(imageBytes, fname, saveDir, context)
                    return@withContext true
                } catch (e: Exception) {
                    if (idx < urlsToTry.size - 1) continue
                    throw e
                }
            }
            return@withContext false
        }
    }

    // ─── 内部辅助 ───────────────────────────────────────────

    private fun ensureSuccess(result: SniBypassClient.FetchResult) {
        if (result.statusCode != 200) {
            throw RuntimeException(
                "HTTP ${result.statusCode}: ${String(result.body, Charsets.UTF_8).take(200)}"
            )
        }
    }

    /**
     * 从缩略图 URL 提取时间戳
     */
    private fun extractTimestamp(url: String): String? {
        val m = timestampPattern.matcher(url)
        return if (m.find()) m.group(1) else null
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("""[\\/:*?"<>|]"""), "_")
    }

    companion object {
        private val timestampPattern =
            Pattern.compile("""(\d{4}/\d{2}/\d{2}/\d{2}/\d{2}/\d{2})""")
    }
}

/** 保存图片到 Pictures/PixivDownloader/{saveDir}/{filename} */
internal fun saveImageToStorage(
    imageBytes: ByteArray, filename: String, saveDir: String,
    context: android.content.Context? = null
) {
    if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        legacySave(imageBytes, filename, saveDir)
    } else {
        val relativePath = "${Environment.DIRECTORY_PICTURES}/PixivDownloader/$saveDir"
        val mimeType = when {
            filename.endsWith(".png") -> "image/png"
            filename.endsWith(".gif") -> "image/gif"
            else -> "image/jpeg"
        }
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        try {
            val uri: Uri? = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
            )
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(imageBytes)
                    os.flush()
                }
                val update = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                context.contentResolver.update(uri, update, null, null)
            } else {
                legacySave(imageBytes, filename, saveDir)
            }
        } catch (_: Exception) {
            legacySave(imageBytes, filename, saveDir)
        }
    }
}

private fun legacySave(imageBytes: ByteArray, filename: String, saveDir: String) {
    val dir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        "PixivDownloader/$saveDir"
    )
    dir.mkdirs()
    File(dir, filename).writeBytes(imageBytes)
}
