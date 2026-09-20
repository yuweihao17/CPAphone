package com.cpaphone.engine.remote

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RemoteServerHealth(
    val status: String,
    val uptimeSeconds: Long = 0L,
    val version: String = "unknown"
)

/**
 * 远程 CLIProxyAPI 管理控制中心客户端
 * 对接远端运行中的 CLIProxyAPI (`/v0/management/...`)
 */
class RemoteManagementClient(
    private var baseUrl: String,
    private var secretKey: String
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000L
            connectTimeoutMillis = 10_000L
        }
    }

    fun updateConnection(newBaseUrl: String, newSecretKey: String) {
        this.baseUrl = newBaseUrl
        this.secretKey = newSecretKey
    }

    /**
     * 探活远程节点状态
     */
    suspend fun checkHealth(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = client.get("${baseUrl.trimEnd('/')}/healthz")
            if (response.status.isSuccess()) {
                Result.success(true)
            } else {
                Result.failure(Exception("HTTP ${response.status.value}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取远程实例的所有凭据池状态
     */
    suspend fun fetchRemoteAuthFiles(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val response = client.get("${baseUrl.trimEnd('/')}/v0/management/auth-files") {
                header("Authorization", "Bearer $secretKey")
            }
            if (response.status.isSuccess()) {
                Result.success(response.bodyAsText())
            } else {
                Result.failure(Exception("HTTP ${response.status.value}: ${response.bodyAsText()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取远程实例的实时配置
     */
    suspend fun fetchRemoteConfig(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val response = client.get("${baseUrl.trimEnd('/')}/v0/management/config") {
                header("Authorization", "Bearer $secretKey")
            }
            if (response.status.isSuccess()) {
                Result.success(response.bodyAsText())
            } else {
                Result.failure(Exception("HTTP ${response.status.value}: ${response.bodyAsText()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 触发远程实例重新加载配置与凭据池
     */
    suspend fun reloadRemoteConfig(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = client.post("${baseUrl.trimEnd('/')}/v0/management/reload") {
                header("Authorization", "Bearer $secretKey")
            }
            if (response.status.isSuccess()) {
                Result.success(true)
            } else {
                Result.failure(Exception("HTTP ${response.status.value}: ${response.bodyAsText()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取远程实例的实时环形审计日志
     */
    suspend fun fetchRemoteLogs(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val response = client.get("${baseUrl.trimEnd('/')}/v0/management/logs") {
                header("Authorization", "Bearer $secretKey")
            }
            if (response.status.isSuccess()) {
                Result.success(response.bodyAsText())
            } else {
                Result.failure(Exception("HTTP ${response.status.value}: ${response.bodyAsText()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun close() {
        client.close()
    }
}
