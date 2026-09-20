package com.cpaphone.engine.oauth

import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * OAuth 本地回调监听器
 * 在提供商官方注册的 redirect 端口上监听浏览器回跳（127.0.0.1 回环），
 * 捕获 code/state/error 后交由回调处理，并返回自动关闭的成功页
 * 对齐 CLIProxyAPI 的 WebUI 端口转发器 + 本地 OAuthServer 机制
 */
class OAuthCallbackServer(
    private val hostScope: CoroutineScope
) {
    private var server: EmbeddedServer<io.ktor.server.cio.CIOApplicationEngine, io.ktor.server.cio.CIOApplicationEngine.Configuration>? = null
    private val codeConsumed = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 启动（或重启）回调监听
     * @param port 提供商官方注册端口（Claude 54545 / Codex 1455 / Antigravity 51121）
     */
    fun start(port: Int, onCallback: (code: String?, state: String?, error: String?) -> Unit) {
        stop()
        codeConsumed.set(false)
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            routing {
                // 提供商回跳路径不固定（/callback、/auth/callback、/oauth-callback），通配捕获
                get("/{path...}") {
                    val code = call.request.queryParameters["code"]
                    val state = call.request.queryParameters["state"]
                    val error = call.request.queryParameters["error_description"]
                        ?: call.request.queryParameters["error"]
                    try {
                        call.respondText(SUCCESS_HTML, ContentType.Text.Html)
                    } finally {
                        // 浏览器可能对回跳 URL 发起重复请求（重试/预取/favicon 之外的二次加载），
                        // 授权码一次性，重复兑换必然 invalid_grant 并覆盖首次结果，这里保证仅首次兑换生效
                        val isFirstValidCode = !code.isNullOrBlank() && codeConsumed.compareAndSet(false, true)
                        if (isFirstValidCode) {
                            this@OAuthCallbackServer.launchSafe { onCallback(code, state, error) }
                        }
                    }
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        try {
            server?.stop(500, 1_000)
        } catch (_: Exception) {
            // 端口已释放或未启动，忽略
        }
        server = null
        codeConsumed.set(false)
    }

    private fun launchSafe(block: suspend () -> Unit) {
        hostScope.launch {
            try {
                block()
            } catch (_: Exception) {
                // 回调处理异常由 OAuthLoginManager 内部捕获落盘到会话
            }
        }
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
