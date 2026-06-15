package com.pixiv.downloader.viewmodel

import android.app.Application
import android.webkit.CookieManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pixiv.downloader.model.DarkMode
import com.pixiv.downloader.model.DownloadState
import com.pixiv.downloader.model.LogEntry
import com.pixiv.downloader.network.PixivApi
import com.pixiv.downloader.network.SniBypassClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // ─── 输入 ───────────────────────────────────────────────

    var inputUrl by mutableStateOf("")
    var illustId by mutableStateOf("")
    var userId by mutableStateOf("")
    var maxPages by mutableStateOf("")

    // ─── 状态 ───────────────────────────────────────────────

    var downloadState by mutableStateOf<DownloadState>(DownloadState.Idle)
        private set
    var logs by mutableStateOf(listOf<LogEntry>())
        private set
    var isLoggedIn by mutableStateOf(false)
        private set

    // ─── Cookie ─────────────────────────────────────────────

    var cookies by mutableStateOf(mapOf<String, String>())
        private set
    var showCookieEditor by mutableStateOf(false)
    var cookieEditText by mutableStateOf("")

    // ─── 登录 ───────────────────────────────────────────────

    var showLoginDialog by mutableStateOf(false)
    var pendingLoginCookies by mutableStateOf<Map<String, String>?>(null)
        private set

    // ─── 外观 ───────────────────────────────────────────────

    var darkMode by mutableStateOf(DarkMode.SYSTEM)
    var dynamicColor by mutableStateOf(true)
    var customColor by mutableStateOf<Color?>(null)
    var showColorPicker by mutableStateOf(false)

    // ─── 页面导航 ──────────────────────────────────────────

    var showSettingsPage by mutableStateOf(false)

    // ─── 实例 ───────────────────────────────────────────────

    private val client = SniBypassClient()
    private val appContext = getApplication<Application>()
    private var logCounter = 0L

    init {
        loadCookies()
        loadPreferences()
    }

    // ══════════════════════════════════════════════════════════
    //  登录
    // ══════════════════════════════════════════════════════════

    fun onLoginDialogDismiss() {
        showLoginDialog = false
        // 检查是否有通过 WebView 传来的 cookie
        pendingLoginCookies?.let { updateCookies(it) }
        pendingLoginCookies = null
    }

    fun onWebViewLoginSuccess(cookieMap: Map<String, String>) {
        addLog("INFO", "WebView 登录成功，获取到 Cookie: ${cookieMap.keys.joinToString()}")
        pendingLoginCookies = cookieMap
        showLoginDialog = false
        updateCookies(cookieMap)
    }

    // ══════════════════════════════════════════════════════════
    //  Cookie 管理
    // ══════════════════════════════════════════════════════════

    private fun updateCookies(newCookies: Map<String, String>) {
        cookies = newCookies
        isLoggedIn = newCookies.containsKey("PHPSESSID") &&
                newCookies["PHPSESSID"]?.isNotBlank() == true
        saveCookies()
        addLog("INFO", if (isLoggedIn) "已登录 Pixiv（PHPSESSID 有效）" else "Cookie 已设置但可能无效")
    }

    fun openCookieEditor() {
        cookieEditText = JSONObject(cookies).toString(2)
        showCookieEditor = true
    }

    fun dismissCookieEditor() {
        showCookieEditor = false
    }

    fun saveCookieEdit(text: String) {
        try {
            val json = JSONObject(text)
            val map = mutableMapOf<String, String>()
            json.keys().forEach { key -> map[key] = json.getString(key) }
            updateCookies(map)
            addLog("INFO", "Cookie 已手动更新，共 ${map.size} 个条目")
        } catch (e: Exception) {
            addLog("ERROR", "Cookie 格式错误: ${e.message}")
        }
        showCookieEditor = false
    }

    private fun saveCookies() {
        try {
            val json = JSONObject(cookies.toMap())
            val file = File(getApplication<Application>().filesDir, COOKIE_FILE)
            file.writeText(json.toString(2))
        } catch (e: Exception) {
            addLog("WARN", "保存 Cookie 失败: ${e.message}")
        }
    }

    private fun loadCookies() {
        try {
            val file = File(getApplication<Application>().filesDir, COOKIE_FILE)
            if (file.exists()) {
                val json = JSONObject(file.readText())
                val map = mutableMapOf<String, String>()
                json.keys().forEach { key -> map[key] = json.getString(key) }
                cookies = map
                isLoggedIn = map.containsKey("PHPSESSID") &&
                        map["PHPSESSID"]?.isNotBlank() == true
                if (isLoggedIn) addLog("INFO", "已加载本地 Cookie（已登录）")
            }
        } catch (e: Exception) {
            addLog("WARN", "加载 Cookie 失败: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════
    //  偏好设置
    // ══════════════════════════════════════════════════════════

    fun updateDarkMode(mode: DarkMode) {
        darkMode = mode
        savePreferences()
    }

    fun updateDynamicColor(enabled: Boolean) {
        dynamicColor = enabled
        if (!enabled) showColorPicker = true
        savePreferences()
    }

    fun setCustomColor(color: Color) {
        customColor = color
        dynamicColor = false
        showColorPicker = false
        savePreferences()
    }

    fun clearCustomColor() {
        customColor = null
        dynamicColor = true
        showColorPicker = false
        savePreferences()
    }

    private fun savePreferences() {
        try {
            val json = JSONObject().apply {
                put("darkMode", darkMode.name)
                put("dynamicColor", dynamicColor)
                customColor?.let { put("customColor", it.toArgb()) }
            }
            val file = File(getApplication<Application>().filesDir, PREF_FILE)
            file.writeText(json.toString(2))
        } catch (_: Exception) {}
    }

    private fun loadPreferences() {
        try {
            val file = File(getApplication<Application>().filesDir, PREF_FILE)
            if (file.exists()) {
                val json = JSONObject(file.readText())
                darkMode = DarkMode.valueOf(json.optString("darkMode", "SYSTEM"))
                dynamicColor = json.optBoolean("dynamicColor", true)
                if (json.has("customColor")) {
                    customColor = Color(json.getInt("customColor"))
                }
            }
        } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════
    //  下载
    // ══════════════════════════════════════════════════════════

    fun startDownload() {
        if (downloadState is DownloadState.Downloading) return

        // 解析输入
        val (type, id) = parseInput()
        if (type == "unknown") {
            addLog("ERROR", "请填写有效的 Pixiv URL 或作品ID/画师ID")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            downloadState = DownloadState.Downloading
            addLog("INFO", "━━━ 开始下载 ━━━")

            try {
                val api = PixivApi(client, cookies, appContext)
                val maxP = maxPages.toIntOrNull() ?: 0

                when (type) {
                    "illust" -> downloadSingleIllust(api, id)
                    "user" -> downloadUserWorks(api, id, maxP)
                }
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                addLog("ERROR", "下载失败: $msg")
                downloadState = DownloadState.Error(msg)
            }
        }
    }

    private suspend fun downloadSingleIllust(api: PixivApi, illustId: String) {
        addLog("INFO", "正在获取作品 $illustId ...")
        val work = api.getIllust(illustId)
        if (work.imageUrls.isEmpty()) {
            addLog("WARN", "作品 $illustId 无有效原图链接")
            downloadState = DownloadState.Completed(0, 0)
            return
        }

        addLog("INFO", "作品: ${work.title} | 画师: ${work.authorName}")
        addLog("INFO", "共 ${work.imageUrls.size} 张图片，开始下载...")
        var successCount = 0
        val saveDir = "${work.authorName}_${work.authorId}"

        for ((i, img) in work.imageUrls.withIndex()) {
            try {
                val tag = if (img.isFallback) "(反推+盲测)" else ""
                addLog("INFO", "[${i + 1}/${work.imageUrls.size}] 下载中 $tag ${img.filename}")
                api.downloadImage(img, saveDir)
                addLog("INFO", "  ✓ 已保存: ${img.filename}")
                successCount++
            } catch (e: Exception) {
                addLog("ERROR", "  ✗ ${img.filename}: ${e.message?.take(80)}")
            }
        }

        addLog("INFO", "✓ 完成: 成功 $successCount / ${work.imageUrls.size} 张")
        downloadState = DownloadState.Completed(1, successCount)
    }

    private suspend fun downloadUserWorks(
        api: PixivApi,
        userId: String,
        maxPages: Int
    ) {
        addLog("INFO", "正在获取画师 $userId 作品列表...")
        val allIds = api.getAuthorIds(userId)
        if (allIds.isEmpty()) {
            addLog("WARN", "该画师无任何作品")
            downloadState = DownloadState.Completed(0, 0)
            return
        }
        val ids = if (maxPages > 0) allIds.take(maxPages) else allIds
        addLog("INFO", "共 ${allIds.size} 个作品，将下载前 ${ids.size} 个")

        var totalSuccess = 0
        var totalIllusts = 0
        for ((idx, iid) in ids.withIndex()) {
            try {
                addLog("INFO", "── [${idx + 1}/${ids.size}] 作品 $iid ──")
                val work = api.getIllust(iid)
                if (work.imageUrls.isEmpty()) continue

                totalIllusts++
                val saveDir = "${work.authorName}_${work.authorId}"
                for ((j, img) in work.imageUrls.withIndex()) {
                    try {
                        val tag = if (img.isFallback) "(反推+盲测)" else ""
                        addLog("INFO", "  [${j + 1}/${work.imageUrls.size}] $tag ${img.filename}")
                        api.downloadImage(img, saveDir)
                        totalSuccess++
                    } catch (e: Exception) {
                        addLog("ERROR", "  ✗ ${img.filename}: ${e.message?.take(60)}")
                    }
                }
            } catch (e: Exception) {
                addLog("ERROR", "获取作品 $iid 失败: ${e.message?.take(60)}")
            }
        }

        addLog("INFO", "✓ 批量下载完成: $totalIllusts 个作品, $totalSuccess 张图片")
        downloadState = DownloadState.Completed(totalIllusts, totalSuccess)
    }

    // ══════════════════════════════════════════════════════════
    //  辅助
    // ══════════════════════════════════════════════════════════

    private fun parseInput(): Pair<String, String> {
        val url = inputUrl.trim()
        if (url.isNotEmpty()) {
            val patterns = listOf(
                Regex("artworks/(\\d+)") to "illust",
                Regex("users/(\\d+)") to "user",
                Regex("member\\.php\\?id=(\\d+)") to "user",
                Regex("illust_id=(\\d+)") to "illust"
            )
            for ((regex, type) in patterns) {
                regex.find(url)?.let {
                    return type to it.groupValues[1]
                }
            }
        }
        val iid = illustId.trim()
        if (iid.isNotEmpty() && iid.all { it.isDigit() }) {
            return "illust" to iid
        }
        val uid = userId.trim()
        if (uid.isNotEmpty() && uid.all { it.isDigit() }) {
            return "user" to uid
        }
        return "unknown" to ""
    }

    fun clearLogs() {
        logs = emptyList()
    }

    fun addLog(level: String, message: String) {
        logCounter++
        logs = logs + LogEntry(logCounter, System.currentTimeMillis(), level, message)
    }

    fun resetState() {
        downloadState = DownloadState.Idle
    }

    companion object {
        private const val COOKIE_FILE = "pixiv_cookies.json"
        private const val PREF_FILE = "pixiv_prefs.json"
    }
}
