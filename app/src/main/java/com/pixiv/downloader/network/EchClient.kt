package com.pixiv.downloader.network

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

/** ECH 相关日志标签（EchClient / SniBypassClient 共用） */
internal const val ECH_LOG_TAG = "pxdl-ech"

/** API 请求的浏览器 UA（ECH 路径与旧直连路径共用，Cloudflare 依赖它做人机评分） */
internal const val API_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/125.0.0.0 Mobile Safari/537.36"

/**
 * 诊断日志封装：release 包的 proguard-android-optimize 规则会把
 * android.util.Log 的部分调用当"无副作用"移除，导致 ECH 失败时无迹可循。
 * 包一层对象让 R8 无法证明其无副作用，诊断日志得以保留。
 */
internal object EchLog {
    fun i(msg: String) = Log.i(ECH_LOG_TAG, msg)
    fun w(msg: String) = Log.w(ECH_LOG_TAG, msg)
    fun d(msg: String) = Log.d(ECH_LOG_TAG, msg)
}

/**
 * ECH 直连客户端 —— 元数据 API 专用（原理同 PixEz/rhttp）。
 *
 * 背景：pixiv 已将 www/API 迁至 Cloudflare 并在源站拒绝"无 SNI 直连"(403)，
 * 而 GFW 会重置一切携带 pixiv/pximg SNI 的 TLS 连接，旧的固定 IP 直连手法
 * 对 API 已失效（图片服务器不受影响，仍走 SniBypassClient）。
 *
 * ECH (Encrypted Client Hello, RFC 9580)：外层 TLS 握手的 SNI 是
 * cloudflare-ech.com（GFW 黑名单之外），真实域名加密于内层，
 * Cloudflare 解密后正常路由 —— 免代理直连。
 *
 * TLS 细节由 Rust 库 libpxdl_ech.so 完成（rustls 实现，源码见 app/ech_jni/）；
 * ECHConfig 从阿里/腾讯 DoH 的 HTTPS 记录获取（普通 HTTPS，不受污染），
 * 带 TTL 缓存；连接目标为 Cloudflare 边缘 anycast IP，失败自动轮换。
 */
object EchClient {
    private var loaded = false
    private var loadError: String? = null

    init {
        try {
            System.loadLibrary("pxdl_ech")
            loaded = true
        } catch (t: Throwable) {
            loadError = t.message
            EchLog.w("libpxdl_ech.so 加载失败: $loadError")
        }
    }

    /** .so 是否加载成功（失败时调用方回退旧的 SNI-less 直连） */
    val available: Boolean get() = loaded

    // Cloudflare 边缘 anycast IP（任一可服务 pixiv.net 的 IP 均可，失败自动轮换）
    private val cfIps = listOf(
        "104.18.10.118", "104.18.11.118", "104.18.42.239", "172.64.145.17"
    )

    @Volatile private var workingIp: String? = null

    // DoH 端点（普通 HTTPS，不受污染/封锁影响）
    private val dohUrls = listOf(
        "https://dns.alidns.com/resolve?name=cloudflare-ech.com&type=HTTPS",
        "https://223.5.5.5/resolve?name=cloudflare-ech.com&type=HTTPS",
        "https://1.12.12.12/resolve?name=cloudflare-ech.com&type=HTTPS"
    )

    private val echConfigPattern = Pattern.compile("""ech="([^"]+)"""")

    @Volatile private var echConfig: String? = null
    @Volatile private var echConfigExpire = 0L

    /** Cookie 被服务端拒绝（401）后置 true，改用匿名请求（pixiv 对失效会话回 401） */
    @Volatile var cookieBad = false

    private external fun nativeRequest(
        url: String,
        echConfig: String,
        resolveIp: String,
        headersJson: String,
        timeoutMs: Int
    ): String?

    /**
     * 以 ECH 方式请求 API。成功返回 FetchResult（status/body），
     * 所有 IP 均失败时抛异常（调用方可回退旧直连）。
     */
    fun request(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int = 25000
    ): SniBypassClient.FetchResult {
        check(loaded) { "libpxdl_ech.so 未加载: $loadError" }
        val cfg = getEchConfig()
            ?: throw RuntimeException("ECHConfig 获取失败 (DoH 不可达或记录缺失)")
        val hdrs = if (cookieBad) {
            headers.filterKeys { !it.equals("Cookie", ignoreCase = true) }
        } else {
            headers
        }
        val headersJson = JSONObject().apply {
            hdrs.forEach { (k, v) -> put(k, v) }
        }.toString()

        val ips = listOfNotNull(workingIp) + cfIps.filter { it != workingIp }
        var lastErr: String? = null
        for (ip in ips) {
            try {
                val out = nativeRequest(url, cfg, ip, headersJson, timeoutMs)
                    ?: throw RuntimeException("native 返回空")
                val j = JSONObject(out)
                if (j.optInt("status", 0) == 0) {
                    throw RuntimeException(j.optString("error", "未知错误"))
                }
                val body = Base64.decode(j.getString("body_b64"), Base64.DEFAULT)
                workingIp = ip
                return SniBypassClient.FetchResult(j.getInt("status"), emptyMap(), body)
            } catch (e: Exception) {
                lastErr = e.message
                EchLog.d("ECH via $ip 失败: ${e.message}")
            }
        }
        throw RuntimeException("ECH 连接失败: ${lastErr ?: "未知"}")
    }

    /** 获取并缓存 ECHConfig（TTL 按 DNS 记录，最短 10 分钟） */
    private fun getEchConfig(): String? {
        val now = System.currentTimeMillis()
        echConfig?.let { if (now < echConfigExpire) return it }
        for (u in dohUrls) {
            try {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("accept", "application/json")
                val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val j = JSONObject(text)
                if (j.optInt("Status", -1) != 0) continue
                val answers = j.optJSONArray("Answer") ?: continue
                for (i in 0 until answers.length()) {
                    val a = answers.getJSONObject(i)
                    if (a.optInt("type") != 65) continue
                    val m = echConfigPattern.matcher(a.optString("data"))
                    if (m.find()) {
                        echConfig = m.group(1)
                        echConfigExpire = now + maxOf(600_000L, a.optLong("TTL", 3600L) * 1000L)
                        return echConfig
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }
}
