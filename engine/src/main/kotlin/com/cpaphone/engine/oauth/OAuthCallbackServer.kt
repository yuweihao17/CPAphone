package com.cpaphone.engine.oauth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OAuth 本地回调监听器（纯 ServerSocket 实现）
 * 在提供商官方注册的 redirect 端口上监听浏览器回跳（127.0.0.1 回环），
 * 捕获 code/state/error 后交由回调处理，并返回自动关闭的成功页
 * 对齐 CLIProxyAPI 的 WebUI 端口转发器 + 本地 OAuthServer 机制
 *
 * 生命周期保证：
 * - bind 为同步操作：start() 返回即已监听，端口被占用立即抛出（不静默）
 * - stop() 关闭 ServerSocket 立即释放端口，支持快速重启
 * - 仅解析 GET 请求行与 query，无需引入完整 HTTP 服务栈
 */
class OAuthCallbackServer(
    private val hostScope: CoroutineScope
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val codeConsumed = AtomicBoolean(false)
    @Volatile
    private var running = false

    /**
     * 启动（或重启）回调监听
     * @param port 提供商官方注册端口（Claude 54545 / Codex 1455 / Antigravity 51121）
     * @throws IllegalStateException 端口绑定失败（被占用或系统拒绝）
     */
    fun start(port: Int, onCallback: (code: String?, state: String?, error: String?) -> Unit) {
        stop()
        codeConsumed.set(false)
        val socket = try {
            ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            throw IllegalStateException("本地回调端口 $port 绑定失败：${e.message}", e)
        }
        serverSocket = socket
        running = true
        acceptThread = Thread {
            while (running) {
                try {
                    val client = socket.accept()
                    handleClient(client, onCallback)
                } catch (_: Exception) {
                    if (!running) break // stop() 关闭 socket 触发，正常退出
                }
            }
        }.apply {
            isDaemon = true
            name = "OAuthCallbackServer-$port"
            start()
        }
    }

    private fun handleClient(client: Socket, onCallback: (code: String?, state: String?, error: String?) -> Unit) {
        // 每连接独立线程处理，避免浏览器并发请求（favicon/预取）阻塞 accept 循环
        Thread {
            try {
                client.use { s ->
                    s.soTimeout = 5000
                    val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val requestLine = reader.readLine() ?: return@use
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) break // 头部结束
                    }

                    val pathAndQuery = requestLine.split(" ").getOrNull(1) ?: ""
                    val query = pathAndQuery.substringAfter("?", "")
                    val params = query.split("&")
                        .filter { it.contains('=') }
                        .associate {
                            val key = it.substringBefore('=')
                            val value = URLDecoder.decode(it.substringAfter('='), "UTF-8")
                            key to value
                        }

                    val html = SUCCESS_HTML
                    val bodyBytes = html.toByteArray(Charsets.UTF_8)
                    val response = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: text/html; charset=utf-8\r\n")
                        append("Content-Length: ").append(bodyBytes.size).append("\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    s.getOutputStream().apply {
                        write(response.toByteArray(Charsets.ISO_8859_1))
                        write(bodyBytes)
                        flush()
                    }

                    // 浏览器可能对回跳 URL 发起重复请求（重试/预取/favicon 之外的二次加载），
                    // 授权码一次性，重复兑换必然 invalid_grant 并覆盖首次结果，这里保证仅首次兑换生效
                    val code = params["code"]
                    val error = params["error_description"] ?: params["error"]
                    val isFirstValidCode = !code.isNullOrBlank() && codeConsumed.compareAndSet(false, true)
                    if (isFirstValidCode) {
                        hostScope.launch {
                            try {
                                onCallback(code, params["state"], error)
                            } catch (_: Exception) {
                                // 回调处理异常由 OAuthLoginManager 内部捕获落盘到会话
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // 单连接异常不影响监听循环
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        acceptThread = null
        codeConsumed.set(false)
    }

    private companion object {
        val SUCCESS_HTML = """
            <!DOCTYPE html>
            <html lang="zh">
            <head>
                <meta charset="UTF-8">
                <title>CPAphone 授权成功</title>
                <style>
                    body { display:flex; align-items:center; justify-content:center; height:100vh;
                           font-family: sans-serif; background:#0f1115; color:#e6e6e6; }
                    .card { text-align:center; }
                    h1 { font-size:22px; margin-bottom:8px; }
                    p { color:#9aa0a6; font-size:14px; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h1>授权成功</h1>
                    <p>CPAphone 已获取凭据，本页面可自动关闭</p>
                </div>
                <script>setTimeout(function(){ window.close(); }, 3000);</script>
            </body>
            </html>
        """.trimIndent()
    }
}
