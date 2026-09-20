package com.cpaphone.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.test.assertTrue

/**
 * 局域网接入绑定行为验证（与本机/真机同一 Ktor CIO 代码路径）
 *
 * - 用例 1：绑定 0.0.0.0 后，通过本机局域网 IPv4 访问必须可达（修复目标行为）
 * - 用例 2：仅绑定 127.0.0.1 时，局域网 IPv4 必须拒绝（复现旧缺陷症状，防止回归）
 *
 * 客户端接入路径与 LocalProxyServer.start 一致（embeddedServer CIO + host 参数）
 */
class LanBindingVerificationTest {

    private fun lanIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }

    private fun isTcpReachable(host: String, port: Int): Boolean = try {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(host, port), 800)
            true
        }
    } catch (_: Exception) {
        false
    }

    @Test
    fun `server bound to wildcard serves LAN interface requests`() {
        val lanIp = lanIpv4()
        Assume.assumeTrue("本机无局域网 IPv4，跳过该用例", !lanIp.isNullOrBlank())

        val engine = embeddedServer(CIO, port = 18399, host = "0.0.0.0") {
            routing {
                get("/healthz") { call.respondText("""{"status":"ok"}""") }
            }
        }.start(wait = false)
        try {
            assertTrue(isTcpReachable("127.0.0.1", 18399), "回环应可达")
            assertTrue(isTcpReachable(lanIp!!, 18399), "0.0.0.0 绑定后局域网接口($lanIp)必须可达")

            val body = runBlocking {
                HttpClient(ClientCIO).get("http://$lanIp:18399/healthz").bodyAsText()
            }
            assertTrue(body.contains("ok"), "局域网接口 HTTP 响应异常: $body")
        } finally {
            engine.stop(0, 500)
        }
    }

    @Test
    fun `server bound to loopback only refuses LAN connections`() {
        val lanIp = lanIpv4()
        Assume.assumeTrue("本机无局域网 IPv4，跳过该用例", !lanIp.isNullOrBlank())

        val engine = embeddedServer(CIO, port = 18400, host = "127.0.0.1") {
            routing {
                get("/healthz") { call.respondText("ok") }
            }
        }.start(wait = false)
        try {
            assertTrue(isTcpReachable("127.0.0.1", 18400), "回环应可达")
            assertTrue(
                !isTcpReachable(lanIp!!, 18400),
                "仅绑定回环时局域网接口($lanIp)不应可达——若可达说明本机网络环境异常，用例前提失效"
            )
        } finally {
            engine.stop(0, 500)
        }
    }
}
