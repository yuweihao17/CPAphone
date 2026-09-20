package com.cpaphone.engine.server

import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*

/**
 * 网关鉴权中间件（对齐 CLIProxyAPI sdkaccess/config_access provider.go:55-104）
 *
 * 凭据候选共 5 处，逐一与配置 api-keys 集合精确匹配：
 * Authorization: Bearer / X-Goog-Api-Key / X-Api-Key / ?key= / ?auth_token=
 * - 未配置 api-keys 集合时放行（保持开箱即用）
 * - 全缺失返回 401 {"error":"Missing API key"}；不匹配返回 401 {"error":"Invalid API key"}
 * - /healthz 与 /v0/management 管理平面不受此层约束（management 有独立密钥体系）
 */
class GatewayAuthMiddleware(private val keysProvider: () -> Set<String>) {

    fun installInto(routing: Application) {
        routing.intercept(ApplicationCallPipeline.Plugins) {
            val path = call.request.path()
            if (path == "/healthz" || path.startsWith("/v0/management") || path.startsWith("/anthropic") ||
                path.startsWith("/codex") || path.startsWith("/antigravity")
            ) {
                return@intercept
            }
            val configured = keysProvider()
            if (configured.isEmpty()) return@intercept

            val header = call.request.header(HttpHeaders.Authorization)
            val bearer = if (header != null && header.startsWith("Bearer ", ignoreCase = true)) {
                header.substring(7).trim()
            } else null
            val candidate = bearer
                ?: call.request.header("X-Goog-Api-Key")?.trim()
                ?: call.request.header("X-Api-Key")?.trim()
                ?: call.request.queryParameters["key"]?.trim()
                ?: call.request.queryParameters["auth_token"]?.trim()

            if (candidate.isNullOrBlank()) {
                call.respondText("""{"error":"Missing API key"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                finish()
            } else if (candidate !in configured) {
                call.respondText("""{"error":"Invalid API key"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                finish()
            }
        }
    }
}
