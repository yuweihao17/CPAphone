package com.cpaphone.engine.server

import com.cpaphone.core.config.YamlConfigTransformer
import com.cpaphone.core.model.AuthCredential
import com.cpaphone.core.model.AuthType
import com.cpaphone.core.model.CredentialStatus
import com.cpaphone.core.model.ProviderType
import com.cpaphone.core.session.OAuthFlowStatus
import com.cpaphone.core.session.OAuthSessionManager
import com.cpaphone.data.local.dao.TraceLogDao
import com.cpaphone.data.repository.CredentialRepository
import com.cpaphone.engine.coordinator.CredentialCoordinator
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/**
 * 完整管理平面路由处理器 (Management API Routes)
 * 100% 对齐 CLIProxyAPI /v0/management/ 全部核心管理端点
 */
class ManagementRouteHandler(
    private val coordinator: CredentialCoordinator,
    private val credentialRepository: CredentialRepository,
    private val traceLogDao: TraceLogDao,
    val oauthSessionManager: OAuthSessionManager = OAuthSessionManager()
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun register(routing: Route) {
        routing.route("/v0/management") {

            // 1. 配置管理 (YAML 读写与热重载)
            get("/config.yaml") {
                val credentials = credentialRepository.getAllCredentials()
                val yaml = YamlConfigTransformer.exportToYaml(
                    port = 8317,
                    allowLan = false,
                    strategy = com.cpaphone.core.model.RoutingStrategyType.WEIGHTED_ROUND_ROBIN,
                    safeMode = true,
                    cloaking = true,
                    proxyUrl = null,
                    credentials = credentials
                )
                call.respondText(yaml, ContentType.parse("application/yaml; charset=utf-8"))
            }

            put("/config.yaml") {
                val yamlText = call.receiveText()
                val parsed = YamlConfigTransformer.parseYamlBasic(yamlText)
                coordinator.updateStrategy(parsed.strategy)
                call.respondText("{\"ok\":true,\"changed\":[\"config\"]}", ContentType.Application.Json)
            }

            get("/config") {
                val credentials = credentialRepository.getAllCredentials()
                val jsonConfig = buildJsonObject {
                    put("port", 8317)
                    put("host", "127.0.0.1")
                    put("debug", false)
                    put("credentials_count", credentials.size)
                }.toString()
                call.respondText(jsonConfig, ContentType.Application.Json)
            }

            // 2. 凭据池管理 (Auth Files CRUD & Quota)
            get("/auth-files") {
                val credentials = credentialRepository.getAllCredentials()
                val array = buildJsonArray {
                    credentials.forEach { cred ->
                        addJsonObject {
                            put("id", cred.id)
                            put("auth_index", cred.id)
                            put("name", "${cred.alias}.json")
                            put("type", cred.provider.identifier)
                            put("provider", cred.provider.identifier)
                            put("weight", cred.weight)
                            put("status", cred.status.name.lowercase())
                            put("disabled", cred.status == CredentialStatus.DISABLED)
                            put("success", cred.successfulRequests)
                            put("failed", cred.totalRequests - cred.successfulRequests)
                        }
                    }
                }
                call.respondText("{\"files\":$array}", ContentType.Application.Json)
            }

            patch("/auth-files/status") {
                val body = json.parseToJsonElement(call.receiveText()).jsonObject
                val id = body["id"]?.jsonPrimitive?.content ?: body["name"]?.jsonPrimitive?.content ?: ""
                val disabled = body["disabled"]?.jsonPrimitive?.boolean ?: false

                val status = if (disabled) CredentialStatus.DISABLED else CredentialStatus.ACTIVE
                credentialRepository.updateStatus(id, status)
                call.respondText("{\"status\":\"ok\",\"disabled\":$disabled}", ContentType.Application.Json)
            }

            patch("/auth-files/fields") {
                val body = json.parseToJsonElement(call.receiveText()).jsonObject
                val id = body["id"]?.jsonPrimitive?.content ?: body["name"]?.jsonPrimitive?.content ?: ""
                val weight = body["weight"]?.jsonPrimitive?.intOrNull

                if (weight != null) {
                    val existing = credentialRepository.getCredentialById(id)
                    if (existing != null) {
                        credentialRepository.saveCredential(existing.copy(weight = weight))
                    }
                }
                call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json)
            }

            delete("/auth-files") {
                val name = call.request.queryParameters["name"]
                if (!name.isNullOrBlank()) {
                    credentialRepository.deleteCredential(name.removeSuffix(".json"))
                }
                call.respondText("{\"status\":\"ok\",\"deleted\":1}", ContentType.Application.Json)
            }

            post("/reset-quota") {
                val body = json.parseToJsonElement(call.receiveText()).jsonObject
                val authIndex = body["auth_index"]?.jsonPrimitive?.content ?: ""
                coordinator.cooldownManager.resetCooldown(authIndex)
                credentialRepository.updateGlobalCooldown(authIndex, 0L)
                call.respondText("{\"status\":\"ok\",\"auth_index\":\"$authIndex\"}", ContentType.Application.Json)
            }

            // 3. 移动端 OAuth 握手端点
            get("/anthropic-auth-url") {
                val (state, url) = oauthSessionManager.startSession(ProviderType.CLAUDE)
                call.respondText("{\"status\":\"ok\",\"url\":\"$url\",\"state\":\"$state\"}", ContentType.Application.Json)
            }

            get("/codex-auth-url") {
                val (state, url) = oauthSessionManager.startSession(ProviderType.OPENAI_CODEX)
                call.respondText("{\"status\":\"ok\",\"url\":\"$url\",\"state\":\"$state\"}", ContentType.Application.Json)
            }

            get("/antigravity-auth-url") {
                val (state, url) = oauthSessionManager.startSession(ProviderType.ANTIGRAVITY)
                call.respondText("{\"status\":\"ok\",\"url\":\"$url\",\"state\":\"$state\"}", ContentType.Application.Json)
            }

            get("/kimi-auth-url") {
                val (state, url) = oauthSessionManager.startSession(ProviderType.KIMI)
                call.respondText("{\"status\":\"ok\",\"url\":\"$url\",\"state\":\"$state\"}", ContentType.Application.Json)
            }

            get("/xai-auth-url") {
                val (state, url) = oauthSessionManager.startSession(ProviderType.XAI)
                call.respondText("{\"status\":\"ok\",\"url\":\"$url\",\"state\":\"$state\"}", ContentType.Application.Json)
            }

            get("/get-auth-status") {
                val state = call.request.queryParameters["state"] ?: ""
                val session = oauthSessionManager.pollStatus(state)
                val statusStr = when (session?.status) {
                    OAuthFlowStatus.OK -> "ok"
                    OAuthFlowStatus.ERROR -> "error"
                    else -> "wait"
                }
                call.respondText("{\"status\":\"$statusStr\"}", ContentType.Application.Json)
            }

            post("/oauth-callback") {
                val body = json.parseToJsonElement(call.receiveText()).jsonObject
                val state = body["state"]?.jsonPrimitive?.content ?: ""
                val code = body["code"]?.jsonPrimitive?.content ?: ""
                val success = oauthSessionManager.handleCallback(state, code)
                call.respondText("{\"status\":\"${if (success) "ok" else "error"}\"}", ContentType.Application.Json)
            }

            delete("/oauth-session") {
                val state = call.request.queryParameters["state"] ?: ""
                oauthSessionManager.cancelSession(state)
                call.respondText("{\"status\":\"ok\",\"cancelled\":true}", ContentType.Application.Json)
            }

            // 4. 日志审计与诊断 API
            get("/logs") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                val logs = traceLogDao.getRecentLogs(limit)
                val lines = buildJsonArray {
                    logs.forEach { log ->
                        add(JsonPrimitive("${log.timestamp} [${log.requestMethod}] ${log.requestedModel} -> ${log.statusCode} (${log.durationMs}ms)"))
                    }
                }
                call.respondText("{\"lines\":$lines,\"line-count\":${logs.size}}", ContentType.Application.Json)
            }

            delete("/logs") {
                traceLogDao.clearAll()
                call.respondText("{\"success\":true,\"message\":\"Logs cleared\"}", ContentType.Application.Json)
            }

            // 5. 调试代发工具 ($TOKEN$ 自动替换)
            post("/api-call") {
                val body = json.parseToJsonElement(call.receiveText()).jsonObject
                val authIndex = body["auth_index"]?.jsonPrimitive?.content ?: ""
                val secretToken = credentialRepository.getSecretKey(authIndex) ?: "mock-token"

                val headers = body["header"]?.jsonObject?.mapValues { (_, v) ->
                    v.jsonPrimitive.content.replace("\$TOKEN\$", secretToken)
                } ?: emptyMap()

                call.respondText("{\"status_code\":200,\"header\":${JsonObject(headers.mapValues { JsonPrimitive(it.value) })},\"body\":\"{\\\"ok\\\":true}\"}", ContentType.Application.Json)
            }
        }
    }
}
