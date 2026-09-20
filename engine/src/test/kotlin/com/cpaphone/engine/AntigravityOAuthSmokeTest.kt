package com.cpaphone.engine

import com.cpaphone.core.model.ModelCatalog
import com.cpaphone.core.model.ProviderType
import com.cpaphone.core.oauth.PROVIDER_SPECS
import com.cpaphone.core.session.OAuthSessionManager
import com.cpaphone.engine.oauth.OAuthCallbackServer
import com.cpaphone.engine.oauth.OAuthTokenClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import kotlin.test.assertTrue

/**
 * 本机端到端 OAuth 冒烟测试（需人工参与，CI 自动跳过）
 *
 * 运行方式（本机验证时）：
 *   $env:OAUTH_SMOKE = "1"
 *   .\gradlew.bat :engine:testDebugUnitTest --tests "*AntigravityOAuthSmokeTest*"
 *
 * 两种模式（OAUTH_SMOKE_MODE）：
 * - callback（默认）：内嵌 ServerSocket 监听 localhost 回调端口，浏览器授权后自动兑换
 * - manual：端口被系统级占用时自动降级，用户把浏览器最终地址栏完整 URL 保存到
 *   D:\Projects\CPAphone\.trae\oauth-manual.txt，测试轮询该文件完成兑换
 *
 * 全链路验证：授权 URL → code → 兑换 access_token → 账号 email → 模型目录热更新
 */
class AntigravityOAuthSmokeTest {

    private val manualFile = java.io.File("D:\\Projects\\CPAphone\\.trae\\oauth-manual.txt")

    @Test
    fun antigravityEndToEnd() {
        Assume.assumeTrue(System.getenv("OAUTH_SMOKE") == "1")

        val spec = PROVIDER_SPECS[ProviderType.ANTIGRAVITY]
            ?: error("antigravity spec missing")
        val sessionManager = OAuthSessionManager()
        val session = sessionManager.startSession(ProviderType.ANTIGRAVITY, spec)
        val authorizeUrl = session.authorizeUrl ?: error("authorize url missing")

        // 尝试自动回调模式；端口被占用则降级手动模式
        val server = OAuthCallbackServer(CoroutineScope(Dispatchers.IO))
        var callbackMode = true
        try {
            server.start(spec.callbackPort) { code, _, error ->
                if (!code.isNullOrBlank()) {
                    manualFile.parentFile?.mkdirs()
                    // 记录回调结果供测试主线程消费（同时兼容手动模式文件协议）
                    manualFile.writeText("state=${session.state}&code=$code" + (error?.let { "&error=$it" } ?: ""))
                }
            }
        } catch (e: Exception) {
            callbackMode = false
            println(">>> 自动回调端口不可用（${e.message}），降级为手动粘贴模式")
        }

        try {
            manualFile.delete()
            println("================================================================")
            println("请在 PC 浏览器打开以下链接并完成 Google 登录：")
            println(authorizeUrl)
            println("================================================================")
            if (callbackMode) {
                println("等待 localhost:${spec.callbackPort} 自动回调（最长 5 分钟）...")
            } else {
                println("授权完成后，浏览器会跳到 localhost 错误页——把地址栏完整网址复制，")
                println("保存到文件：$manualFile")
                println("（测试每 5 秒轮询该文件，最长 10 分钟）")
            }

            val deadline = if (callbackMode) 5L else 10L
            var code: String? = null
            var error: String? = null
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < deadline * 60_000L) {
                // 处理用户粘贴的完整 URL：先去掉路径部分（首个 ? 之前），再解析键值对
                if (manualFile.exists()) {
                    val content = manualFile.readText().substringAfter('?', content)
                    val params = content.split("&").filter { it.contains('=') }.associate {
                        it.substringBefore('=') to (it.substringAfter('=', "").let { v ->
                            java.net.URLDecoder.decode(v, "UTF-8")
                        })
                    }
                    if (params["state"] == session.state && !params["code"].isNullOrBlank()) {
                        code = params["code"]
                        error = params["error"]
                        break
                    }
                }
                Thread.sleep(5000)
            }

            assertTrue(!code.isNullOrBlank(), "${deadline} 分钟内未取得授权码（授权是否完成？文件是否写入？）")
            println(">>> 收到授权码（前 12 位）: ${code!!.take(12)}...")
            assertTrue(error.isNullOrBlank(), "授权端返回错误: $error")

            runBlocking {
                val client = OAuthTokenClient()
                val tokens = client.exchangeCode(ProviderType.ANTIGRAVITY, spec, code, null)
                assertTrue(tokens.accessToken.isNotBlank(), "兑换结果缺少 access_token")
                println(">>> access_token 兑换成功（前 12 位）: ${tokens.accessToken.take(12)}...")
                println(">>> refresh_token 存在: ${!tokens.refreshToken.isNullOrBlank()}")

                val email = client.fetchEmail(ProviderType.ANTIGRAVITY, tokens.accessToken, tokens.idToken)
                println(">>> 账号 email: $email")
                assertTrue(!email.isNullOrBlank(), "未能获取账号 email")

                val remoteCatalog = client.fetchRemoteModelCatalog()
                val applied = remoteCatalog?.let { ModelCatalog.applyRemoteCatalog(it) } ?: false
                println(">>> 模型目录热更新: ${if (applied) "已应用远程最新目录" else "拉取失败，回退内嵌目录"}")
                val models = ModelCatalog.aggregateForProviders(setOf(ProviderType.ANTIGRAVITY)).map { it.first }
                println(">>> Antigravity 可用模型（${models.size} 个）:")
                models.forEach { println("    - $it") }
                assertTrue(models.isNotEmpty(), "模型聚合结果为空")
            }
        } finally {
            server.stop()
            manualFile.delete()
        }
        println(">>> 冒烟测试全部通过")
    }
}
