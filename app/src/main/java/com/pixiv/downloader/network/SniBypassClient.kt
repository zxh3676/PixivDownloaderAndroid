package com.pixiv.downloader.network

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 无 SNI 的裸 HTTP/1.1 客户端。
 *
 * 核心原理：
 * 1. 直接用 IP 建立 TCP 连接（跳过 DNS 污染）
 * 2. SSLSocket 在无参创建时不设置 server_name → TLS 握手无 SNI 扩展
 * 3. 发送裸 HTTP/1.1 请求，靠 Host 头让服务器路由
 *
 * 参照：SNI_BYPASS_ANDROID.md
 */
class SniBypassClient {

    /**
     * 域名 → 直连 IP（可更新）
     */
    var hostIpMap: Map<String, String> = HOST_IP_MAP
        private set

    data class FetchResult(
        val statusCode: Int,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    /**
     * 执行 HTTP 请求，自动处理 SNI 绕过。
     * @param host 原始域名（用于 Host 头 + IP 查找）
     * @param path 请求路径（如 /ajax/illust/12345?lang=zh）
     * @param method HTTP 方法
     * @param extraHeaders 额外请求头（如 Cookie）
     * @param requestBody POST 请求体
     */
    fun fetch(
        host: String,
        path: String,
        method: String = "GET",
        extraHeaders: Map<String, String> = emptyMap(),
        requestBody: ByteArray? = null
    ): FetchResult {
        val ip = hostIpMap[host] ?: throw IllegalArgumentException("未知域名: $host")
        var lastException: Exception? = null

        // 重试最多 3 次，每次尝试所有备用 IP
        for (attempt in 0 until 3) {
            val ipsToTry = if (attempt == 0) listOf(ip) else fallbackIps
            for (tryIp in ipsToTry) {
                try {
                    return tryFetch(tryIp, host, path, method, extraHeaders, requestBody)
                } catch (e: Exception) {
                    lastException = e
                }
            }
            if (attempt < 2) Thread.sleep(500L * (attempt + 1))
        }
        throw lastException ?: RuntimeException("所有连接尝试均失败")
    }

    /**
     * 图片下载专用：返回原始字节数组
     */
    fun downloadImage(
        host: String,
        path: String,
        referer: String = "https://www.pixiv.net/"
    ): ByteArray {
        val result = fetch(host, path, extraHeaders = mapOf("Referer" to referer))
        if (result.statusCode != 200) {
            throw RuntimeException("下载失败: HTTP ${result.statusCode}")
        }
        return result.body
    }

    private fun tryFetch(
        ip: String,
        host: String,
        path: String,
        method: String,
        extraHeaders: Map<String, String>,
        requestBody: ByteArray?
    ): FetchResult {
        val socket = sslContext.socketFactory.createSocket() as SSLSocket
        try {
            // 关键：只连 IP，不设 hostname → 无 SNI
            socket.connect(InetSocketAddress(ip, 443), CONNECT_TIMEOUT)
            socket.soTimeout = READ_TIMEOUT
            socket.useClientMode = true
            socket.startHandshake()

            // 构建 HTTP/1.1 请求
            val request = buildRequest(method, host, path, extraHeaders, requestBody)
            socket.outputStream.write(request)
            socket.outputStream.flush()

            return parseResponse(socket)
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun buildRequest(
        method: String,
        host: String,
        path: String,
        extraHeaders: Map<String, String>,
        body: ByteArray?
    ): ByteArray {
        val sb = StringBuilder()
        sb.append("$method $path HTTP/1.1\r\n")
        sb.append("Host: $host\r\n")
        sb.append("User-Agent: Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36\r\n")
        // 图片用通用 Accept，API 用 JSON
        if (host == "i.pximg.net" || host == "s.pximg.net") {
            sb.append("Accept: image/webp,image/apng,image/*,*/*;q=0.8\r\n")
        } else {
            sb.append("Accept: application/json, text/plain, */*\r\n")
        }
        sb.append("Accept-Language: zh-CN,zh;q=0.9\r\n")
        // Referer 由调用方通过 extraHeaders 传入，这里不自动加
        extraHeaders.forEach { (k, v) ->
            sb.append("$k: $v\r\n")
        }
        if (body != null) {
            sb.append("Content-Length: ${body.size}\r\n")
        }
        sb.append("Connection: close\r\n")
        sb.append("\r\n")

        val headerBytes = sb.toString().toByteArray(Charsets.US_ASCII)
        return if (body != null) headerBytes + body else headerBytes
    }

    /**
     * 纯 BufferedInputStream 解析 HTTP 响应 ——
     * 绝不用 BufferedReader，避免预读吞掉图片正文。
     */
    private fun parseResponse(socket: SSLSocket): FetchResult {
        val input = BufferedInputStream(socket.inputStream)

        // 状态行
        val statusLine = readLineAscii(input) ?: "HTTP/1.1 0 Unknown"
        val parts = statusLine.split(" ")
        val statusCode = parts.getOrNull(1)?.toIntOrNull() ?: 0

        // 响应头
        val headers = mutableMapOf<String, String>()
        var contentLength = -1
        var isChunked = false
        while (true) {
            val line = readLineAscii(input) ?: break
            if (line.isEmpty()) break
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                headers[key] = value
                when {
                    key.equals("Content-Length", ignoreCase = true) ->
                        contentLength = value.toIntOrNull() ?: -1
                    key.equals("Transfer-Encoding", ignoreCase = true) &&
                            value.equals("chunked", ignoreCase = true) ->
                        isChunked = true
                }
            }
        }

        // 读正文
        val body = when {
            contentLength > 0 -> readExact(input, contentLength)
            isChunked -> readChunked(input)
            else -> readUntilEof(input)
        }

        return FetchResult(statusCode, headers, body)
    }

    private fun readExact(input: BufferedInputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(buf, offset, length - offset)
            if (n == -1) break
            offset += n
        }
        return if (offset < length) buf.copyOf(offset) else buf
    }

    private fun readChunked(input: BufferedInputStream): ByteArray {
        val output = ByteArrayOutputStream()
        while (true) {
            val chunkSizeLine = readLineAscii(input) ?: break
            val chunkSize = chunkSizeLine.trim().toIntOrNull(16) ?: break
            if (chunkSize == 0) break
            output.write(readExact(input, chunkSize))
            readLineAscii(input) // 消耗 chunk 末尾的 \r\n
        }
        // 消耗尾部 trailer
        while (true) {
            val line = readLineAscii(input) ?: break
            if (line.isEmpty()) break
        }
        return output.toByteArray()
    }

    private fun readUntilEof(input: BufferedInputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n == -1) break
            output.write(buf, 0, n)
        }
        return output.toByteArray()
    }

    /**
     * 从 BufferedInputStream 手动读一行（\r\n 结尾）。
     * 关键：ByteArrayOutputStream，不用 Reader/BufferedReader。
     */
    private fun readLineAscii(input: BufferedInputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            when {
                b == -1 -> return if (line.size() == 0) null
                else line.toString(Charsets.US_ASCII.name())
                b == '\r'.code -> {
                    input.read() // 消费紧跟的 \n
                    return line.toString(Charsets.US_ASCII.name())
                }
                b == '\n'.code -> return line.toString(Charsets.US_ASCII.name())
                else -> line.write(b)
            }
        }
    }

    fun updateHostIp(host: String, ip: String) {
        hostIpMap = hostIpMap + (host to ip)
    }

    companion object {
        /** 连接超时 */
        private const val CONNECT_TIMEOUT = 15_000
        /** 读取超时 */
        private const val READ_TIMEOUT = 30_000

        // 域名 → IP 映射
        private val HOST_IP_MAP = mapOf(
            "www.pixiv.net" to "210.140.139.155",
            "accounts.pixiv.net" to "210.140.139.155",
            "app-api.pixiv.net" to "210.140.139.155",
            "oauth.secure.pixiv.net" to "210.140.139.155",
            "i.pximg.net" to "210.140.139.133",
            "s.pximg.net" to "210.140.139.133"
        )

        /** 备用 IP 列表 */
        private val fallbackIps = listOf(
            "210.140.139.133",
            "210.140.139.155",
            "210.140.92.151",
            "210.140.92.144"
        )

        // 信任所有证书（相当于 Python ssl.CERT_NONE）
        private val trustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?
            ) = Unit

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?
            ) = Unit

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        private val sslContext: SSLContext by lazy {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
            ctx
        }
    }
}
