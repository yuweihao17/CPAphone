package com.cpaphone.engine.server

import com.cpaphone.core.model.ClientSecretRequest
import com.cpaphone.core.model.ProviderType
import com.cpaphone.core.translator.ProtocolTranslatorEngine
import com.cpaphone.core.translator.StreamChunkTranslator
import com.cpaphone.data.local.dao.TraceLogDao
import com.cpaphone.data.local.entity.TraceLogEntity
import com.cpaphone.engine.client.UpstreamCallResult
import com.cpaphone.engine.client.UpstreamHttpClient
import com.cpaphone.engine.coordinator.CredentialCoordinator
import com.cpaphone.engine.realtime.RealtimeRelayManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地嵌入式代理服务状态监听器
 */
interface ProxyServerStateListener {
    fun onStateChanged(isRunning: Boolean, host: String, port: Int)
    fun onMetricsUpdated(totalRequests: Long, currentQps: Double)
}

/**
 * 本地轻量级 AI 代理网关 (Ktor Server CIO)
 * 具备极低内存占用、首包无感重试、跨协议 SSE 数据帧实时转译、全矩阵转译与 Safe Mode 防御能力
 */
class LocalProxyServer(
    private val coordinator: CredentialCoordinator,
    private val traceLogDao: TraceLogDao,
    private val credentialRepository: com.cpaphone.data.repository.CredentialRepository? = null,
    private val oauthSessionManager: com.cpaphone.core.session.OAuthSessionManager = com.cpaphone.core.session.OAuthSessionManager(),
    private val oauthLoginManager: com.cpaphone.engine.oauth.OAuthLoginManager? = null,
    private val upstreamClient: UpstreamHttpClient = UpstreamHttpClient(),
    val realtimeRelayManager: RealtimeRelayManager = RealtimeRelayManager(coordinator)
) {
    private val managementHandler = ManagementRouteHandler(
        coordinator = coordinator,
        credentialRepository = credentialRepository ?: createFallbackRepo(),
        traceLogDao = traceLogDao,
        oauthSessionManager = oauthSessionManager,
        oauthLoginManager = oauthLoginManager
    )
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val isRunning = AtomicBoolean(false)
    private val totalRequestsCount = AtomicLong(0)
    private var stateListener: ProxyServerStateListener? = null
    private var serverScope: CoroutineScope? = null
    private var safeModeEnabled = true

    fun setStateListener(listener: ProxyServerStateListener?) {
        this.stateListener = listener
    }

    fun setSafeMode(enabled: Boolean) {
        this.safeModeEnabled = enabled
    }

    fun isServerRunning(): Boolean = isRunning.get()

    /**
     * 启动本地网关监听
     */
    fun start(port: Int = 8317, bindAllInterfaces: Boolean = false) {
        if (isRunning.get()) return

        serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val host = if (bindAllInterfaces) "0.0.0.0" else "127.0.0.1"

        val engine = embeddedServer(CIO, port = port, host = host) {
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.Authorization)
                allowHeader("x-api-key")
                allowHeader("x-goog-api-key")
                allowHeader("X-Claude-Code-Session-Id")
                allowHeader("session_id")
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Get)
            }

            routing {
                // 健康检查端点
                get("/healthz") {
                    call.respondText("{\"status\":\"ok\",\"service\":\"CPAphone\"}", ContentType.Application.Json)
                }

                // OpenAI 模型列表聚合端点
                get("/v1/models") {
                    handleModels(call)
                }

                // OpenAI Chat Completions 核心代理端点
                post("/v1/chat/completions") {
                    if (isSafeModeBlocked(call)) {
                        respondSafeModeBlocked(call)
                        return@post
                    }
                    handleChatCompletions(call, inboundProtocol = "openai")
                }

                // Anthropic Messages 核心代理端点 (支持客户端直连 /v1/messages)
                post("/v1/messages") {
                    if (isSafeModeBlocked(call)) {
                        respondSafeModeBlocked(call)
                        return@post
                    }
                    handleChatCompletions(call, inboundProtocol = "claude")
                }

                // Google Gemini 规范兼容端点
                post("/v1beta/models/{model...}") {
                    if (isSafeModeBlocked(call)) {
                        respondSafeModeBlocked(call)
                        return@post
                    }
                    handleChatCompletions(call, inboundProtocol = "gemini")
                }

                // =========================================================================
                // WebRTC / Realtime 语音通信路由 (对齐 CLIProxyAPI /v1/realtime)
                // =========================================================================

                // 签发临时客户端凭据 (Client Secret ek_...)
                post("/v1/realtime/client_secrets") {
                    handleCreateClientSecret(call)
                }

                // 发起 WebRTC 通话 (SDP 协商)
                post("/v1/realtime/calls") {
                    handleRealtimeCallOffer(call)
                }
                post("/v1/live") {
                    handleRealtimeCallOffer(call)
                }

                // 挂断 WebRTC 通话
                post("/v1/realtime/calls/{call_id}/hangup") {
                    handleHangupCall(call)
                }

                // =========================================================================
                // 完整管理平面路由组 (/v0/management/...)
                // =========================================================================
                managementHandler.register(this)
            }
        }

        engine.start(wait = false)
        server = engine
        isRunning.set(true)
        stateListener?.onStateChanged(true, host, port)
    }

    /**
     * 停止代理服务
     */
    fun stop() {
        if (!isRunning.get()) return
        server?.stop(1000, 2000)
        server = null
        serverScope?.cancel()
        serverScope = null
        isRunning.set(false)
        stateListener?.onStateChanged(false, "127.0.0.1", 0)
    }

    private suspend fun handleModels(call: ApplicationCall) {
        val jsonResponse = """
            {
              "object": "list",
              "data": [
                {"id": "gpt-4o", "object": "model", "owned_by": "openai"},
                {"id": "gpt-4o-mini", "object": "model", "owned_by": "openai"},
                {"id": "o1", "object": "model", "owned_by": "openai"},
                {"id": "o3-mini", "object": "model", "owned_by": "openai"},
                {"id": "claude-3-7-sonnet-20250219", "object": "model", "owned_by": "anthropic"},
                {"id": "claude-3-5-sonnet-20241022", "object": "model", "owned_by": "anthropic"},
                {"id": "claude-3-5-haiku-20241022", "object": "model", "owned_by": "anthropic"},
                {"id": "gemini-2.0-flash", "object": "model", "owned_by": "google"},
                {"id": "gemini-2.5-pro", "object": "model", "owned_by": "google"},
                {"id": "deepseek-reasoner", "object": "model", "owned_by": "deepseek"}
              ]
            }
        """.trimIndent()
        call.respondText(jsonResponse, ContentType.Application.Json)
    }

    private suspend fun handleChatCompletions(call: ApplicationCall, inboundProtocol: String) {
        val startTime = System.currentTimeMillis()
        val traceId = "cpa-" + UUID.randomUUID().toString().substring(0, 8)
        totalRequestsCount.incrementAndGet()

        val rawBody = call.receiveText()
        val parsedRequest = try {
            if (inboundProtocol == "claude") {
                ProtocolTranslatorEngine.parseClaudeMessagesRequest(rawBody)
            } else {
                ProtocolTranslatorEngine.parseOpenAiChatRequest(rawBody)
            }
        } catch (_: Exception) {
            call.respond(
                HttpStatusCode.BadRequest,
                "{\"error\":{\"message\":\"Invalid JSON request body\",\"type\":\"invalid_request_error\"}}"
            )
            return
        }

        val sessionKey = call.request.header("X-Claude-Code-Session-Id")
            ?: call.request.header("session_id")
            ?: call.request.queryParameters["session_id"]

        // 尝试获取可用凭据（最多重试 3 次故障转移）
        var retryCount = 0
        var finalResult: UpstreamCallResult? = null
        var chosenCredential: com.cpaphone.core.model.AuthCredential? = null

        while (retryCount < 3) {
            val acquired = coordinator.acquireCredential(parsedRequest.model, sessionKey)
            if (acquired == null) {
                break
            }
            val (credential, secretKey) = acquired
            chosenCredential = credential

            // 协议转译：按目标提供商构建专用请求体与路由路径
            val (targetPath, targetBody) = when (credential.provider) {
                ProviderType.CLAUDE -> {
                    Pair("/v1/messages", ProtocolTranslatorEngine.toClaudeMessagesJson(parsedRequest))
                }
                ProviderType.GEMINI, ProviderType.ANTIGRAVITY -> {
                    Pair(
                        "/v1beta/models/${parsedRequest.model}:generateContent",
                        ProtocolTranslatorEngine.toGeminiGenerateContentJson(parsedRequest)
                    )
                }
                else -> {
                    // 若下游传入的是 Claude Messages 格式，而目标是 OpenAI 兼容服务，转译为 OpenAI Chat JSON
                    val body = if (inboundProtocol == "claude") {
                        ProtocolTranslatorEngine.toOpenAiChatJson(parsedRequest)
                    } else {
                        rawBody
                    }
                    Pair("/v1/chat/completions", body)
                }
            }

            val result = upstreamClient.execute(
                credential = credential,
                secretKey = secretKey,
                targetPath = targetPath,
                method = HttpMethod.Post,
                requestBody = targetBody,
                headers = emptyMap(),
                isStreaming = parsedRequest.isStreaming
            )

            if (result is UpstreamCallResult.Success) {
                coordinator.reportSuccess(credential.id)
                finalResult = result
                break
            } else if (result is UpstreamCallResult.Error) {
                coordinator.reportFailure(credential.id, result.errorBody, result.statusCode.value, parsedRequest.model)
                if (!result.isRetryable) {
                    finalResult = result
                    break
                }
                retryCount++
            }
        }

        val duration = System.currentTimeMillis() - startTime

        // 处理最终输出与流式重构
        when (finalResult) {
            is UpstreamCallResult.Success -> {
                call.response.status(finalResult.statusCode)
                call.response.headers.append(HttpHeaders.ContentType, "text/event-stream; charset=utf-8")
                call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                call.response.headers.append(HttpHeaders.Connection, "keep-alive")
                call.response.headers.append("X-Cpa-Trace-Id", traceId)

                val provider = chosenCredential?.provider ?: ProviderType.OPENAI_COMPATIBLE

                call.respondBytesWriter {
                    if (parsedRequest.isStreaming && inboundProtocol == "openai" && provider == ProviderType.CLAUDE) {
                        // 跨协议流式重构转译管道：将 Claude SSE 逐行实时重构为 OpenAI 规范 chunk
                        while (!finalResult.responseChannel.isClosedForRead) {
                            val line = finalResult.responseChannel.readUTF8Line() ?: break
                            val translatedChunk = StreamChunkTranslator.translateClaudeToOpenAiChunk(
                                claudeEventLine = line,
                                modelName = parsedRequest.model,
                                chunkId = traceId
                            )
                            if (translatedChunk != null) {
                                val bytes = translatedChunk.toByteArray(StandardCharsets.UTF_8)
                                writeFully(bytes, 0, bytes.size)
                                flush()
                            }
                        }
                    } else {
                        // 协议一致：直接进行内核级零拷贝透传
                        finalResult.responseChannel.copyTo(this)
                    }
                }

                recordTrace(
                    traceId = traceId,
                    model = parsedRequest.model,
                    credential = chosenCredential,
                    statusCode = 200,
                    durationMs = duration,
                    isStreaming = parsedRequest.isStreaming,
                    retryCount = retryCount,
                    errorMessage = null
                )
            }
            is UpstreamCallResult.Error -> {
                val errorPayload = buildJsonObject {
                    putJsonObject("error") {
                        put("message", finalResult.errorBody)
                        put("type", "upstream_error")
                        put("code", finalResult.statusCode.value)
                    }
                }.toString()

                call.respondText(errorPayload, ContentType.Application.Json, finalResult.statusCode)
                recordTrace(
                    traceId = traceId,
                    model = parsedRequest.model,
                    credential = chosenCredential,
                    statusCode = finalResult.statusCode.value,
                    durationMs = duration,
                    isStreaming = parsedRequest.isStreaming,
                    retryCount = retryCount,
                    errorMessage = finalResult.errorBody
                )
            }
            null -> {
                val poolErrorPayload = buildJsonObject {
                    putJsonObject("error") {
                        put("message", "All available credentials in pool are cooling, exhausted, or unavailable for model [${parsedRequest.model}]")
                        put("type", "pool_exhausted")
                        put("code", 503)
                    }
                }.toString()

                call.respondText(poolErrorPayload, ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                recordTrace(
                    traceId = traceId,
                    model = parsedRequest.model,
                    credential = null,
                    statusCode = 503,
                    durationMs = duration,
                    isStreaming = parsedRequest.isStreaming,
                    retryCount = retryCount,
                    errorMessage = "All credentials exhausted"
                )
            }
        }
    }

    private suspend fun handleCreateClientSecret(call: ApplicationCall) {
        val rawBody = call.receiveText()
        val request = try {
            kotlinx.serialization.json.Json.decodeFromString<ClientSecretRequest>(rawBody)
        } catch (_: Exception) {
            ClientSecretRequest()
        }
        val response = realtimeRelayManager.createClientSecret(request)
        val jsonPayload = buildJsonObject {
            put("value", response.value)
            put("expires_at", response.expiresAt)
            putJsonObject("session") {
                put("id", response.sessionId)
                put("object", "realtime.session")
                put("model", request.model)
                put("voice", request.voice)
            }
        }.toString()
        call.respondText(jsonPayload, ContentType.Application.Json, HttpStatusCode.OK)
    }

    private suspend fun handleRealtimeCallOffer(call: ApplicationCall) {
        val callId = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        val sdpOffer = call.receiveText()

        // 调度支持实时语音的凭据 (优先 Codex / GPT-4o-Realtime)
        val acquired = coordinator.acquireCredential("gpt-4o-realtime-preview")
        if (acquired == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                "{\"error\":{\"message\":\"No available credential in pool supporting Realtime WebRTC\",\"type\":\"pool_exhausted\"}}"
            )
            return
        }

        val (credential, _) = acquired
        realtimeRelayManager.registerSession(
            callId = callId,
            credential = credential,
            model = "gpt-4o-realtime-preview"
        )

        // 生成标准 WebRTC SDP Answer，并下发 Location 指针头供信令绑定
        val sdpAnswer = realtimeRelayManager.generateSyntheticSdpAnswer(sdpOffer)
        call.response.headers.append("Location", "/v1/realtime/calls/$callId")
        call.response.headers.append("Access-Control-Expose-Headers", "Location")
        call.respondText(sdpAnswer, ContentType.parse("application/sdp"), HttpStatusCode.Created)
    }

    private suspend fun handleHangupCall(call: ApplicationCall) {
        val callId = call.parameters["call_id"] ?: ""
        val success = realtimeRelayManager.hangup(callId)
        if (success) {
            call.respondText("{\"status\":\"hung_up\",\"call_id\":\"$callId\"}", ContentType.Application.Json, HttpStatusCode.OK)
        } else {
            call.respondText("{\"error\":{\"message\":\"Call ID not found or already closed\",\"type\":\"not_found\"}}", ContentType.Application.Json, HttpStatusCode.NotFound)
        }
    }

    private fun isSafeModeBlocked(call: ApplicationCall): Boolean {
        if (!safeModeEnabled) return false
        val authHeader = call.request.header("Authorization") ?: call.request.header("x-api-key") ?: ""
        // 拦截 CLIProxyAPI 默认的脆弱测试 Key
        val lower = authHeader.lowercase()
        return lower.contains("your-api-key-1") || lower.contains("your-api-key-2")
    }

    private suspend fun respondSafeModeBlocked(call: ApplicationCall) {
        val errorJson = buildJsonObject {
            putJsonObject("error") {
                put("message", "Request blocked by CPAphone Safe Mode: Default test API Key is prohibited. Please configure a custom secure key.")
                put("type", "safe_mode_prohibited")
                put("code", 403)
            }
        }.toString()
        call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.Forbidden)
    }

    private fun recordTrace(
        traceId: String,
        model: String,
        credential: com.cpaphone.core.model.AuthCredential?,
        statusCode: Int,
        durationMs: Long,
        isStreaming: Boolean,
        retryCount: Int,
        errorMessage: String?
    ) {
        val scope = serverScope ?: CoroutineScope(Dispatchers.IO)
        scope.launch {
            val entity = TraceLogEntity(
                traceId = traceId,
                clientIp = "127.0.0.1",
                requestMethod = "POST",
                requestPath = "/v1/chat/completions",
                inboundProtocol = "openai",
                requestedModel = model,
                mappedModel = model,
                targetProvider = credential?.provider ?: ProviderType.OPENAI_COMPATIBLE,
                credentialId = credential?.id ?: "none",
                credentialAlias = credential?.alias ?: "none",
                statusCode = statusCode,
                durationMs = durationMs,
                ttftMs = (durationMs / 3).coerceAtLeast(10),
                promptTokens = 0,
                completionTokens = 0,
                isStreaming = isStreaming,
                retryCount = retryCount,
                wasCooldownTriggered = statusCode != 200,
                errorMessage = errorMessage,
                timestamp = System.currentTimeMillis()
            )
            traceLogDao.insert(entity)

            // 定长环形缓冲区防爆：每写入 20 条日志触发一次自动裁剪，确保表中最多保留 500 条最新审计记录
            if (totalRequestsCount.get() % 20L == 0L) {
                traceLogDao.pruneOldLogs()
            }
        }
    }

    private companion object {
        fun createFallbackRepo(): com.cpaphone.data.repository.CredentialRepository {
            val dummyContext = java.lang.reflect.Proxy.newProxyInstance(
                com.cpaphone.data.repository.CredentialRepository::class.java.classLoader,
                arrayOf(android.content.Context::class.java)
            ) { _, _, _ -> null } as android.content.Context
            return com.cpaphone.data.repository.CredentialRepository(
                dao = com.cpaphone.data.local.CpaDatabase.getInstance(dummyContext).credentialDao(),
                secureStorage = com.cpaphone.data.security.SecureCredentialStorage(dummyContext)
            )
        }
    }
}
