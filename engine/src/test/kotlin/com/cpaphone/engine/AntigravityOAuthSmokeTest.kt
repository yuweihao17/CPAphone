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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertTrue

/**
 * 本机端到端 OAuth 冒烟测试（需人工参与，CI 自动跳过）
 *
 * 运行方式（本机验证时）：
 *   $env:OAUTH_SMOKE = "1"
 *   .\gradlew.bat :engine:testDebugUnitTest --tests "*AntigravityOAuthSmokeTest*"
 *
 * 流程：生成真实授权 URL（控制台打印）→ 测试者在 PC 浏览器完成 Google 登录 →
 *       浏览器回跳 localhost:51121 进入内嵌 OAuthCallbackServer → 兑换 access_token →
 *       拉取账号 email → 热更新模型目录 → 打印聚合后的 Antigravity 模型清单
 */
class AntigravityOAuthSmokeTest {

    @Test
    fun antigravityEndToEnd() {
        Assume.assumeTrue(System.getenv("OAUTH_SMOKE") == "1")

        val spec = PROVIDER_SPECS[ProviderType.ANTIGRAVITY]
            ?: error("antigravity spec missing")
        val sessionManager = OAuthSessionManager()
        val session = sessionManager.startSession(ProviderType.ANTIGRAVITY, spec)
        val authorizeUrl = session.authorizeUrl ?: error("authorize url missing")

        val callbackCode = AtomicReference<String?>()
        val callbackError = AtomicReference<String?>()
        val latch = CountDownLatch(1)

        val server = OAuthCallbackServer(CoroutineScope(Dispatchers.IO))
        try {
            server.start(spec.callbackPort) { code, _, error ->
                callbackCode.set(code)
                callbackError.set(error)
                latch.countDown()
            }
            println("================================================================")
            println("请在 PC 浏览器打开以下链接并完成 Google 登录：")
            println(authorizeUrl)
            println("等待 localhost:${spec.callbackPort} 回调（最长 5 分钟）...")
            println("================================================================")

            assertTrue(latch.await(5, TimeUnit.MINUTES), "5 分钟内未收到浏览器回调")

            val error = callbackError.get()
            assertTrue(error.isNullOrBlank(), "授权端返回错误: $error")
            val code = callbackCode.get()
            assertTrue(!code.isNullOrBlank(), "回调未携带授权码")
            println(">>> 收到授权码（前 12 位）: ${code!!.take(12)}...")

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
        }
        println(">>> 冒烟测试全部通过")
    }
}
