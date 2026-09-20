package com.cpaphone.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 硬件级加密凭据保险箱 (Secure Vault)
 * 利用 Android Keystore 硬件 TEE/StrongBox 生成主密钥，
 * 使用 AES-256-GCM 保护 OAuth Token、Refresh Token、GCP 服务账号 JSON 与原生 API Key。
 */
class SecureCredentialStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "cpa_secure_vault",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * 安全保存敏感 API Key 或 Token
     */
    fun saveSecret(credentialId: String, secretKeyOrToken: String) {
        securePrefs.edit().putString("secret_$credentialId", secretKeyOrToken).apply()
    }

    /**
     * 读取解密后的凭据密钥
     */
    fun getSecret(credentialId: String): String? {
        return securePrefs.getString("secret_$credentialId", null)
    }

    /**
     * 检查是否存在敏感密钥
     */
    fun hasSecret(credentialId: String): Boolean {
        return securePrefs.contains("secret_$credentialId")
    }

    /**
     * 保存 OAuth 刷新令牌 (Refresh Token)
     */
    fun saveRefreshToken(credentialId: String, refreshToken: String) {
        securePrefs.edit().putString("refresh_$credentialId", refreshToken).apply()
    }

    /**
     * 读取 OAuth 刷新令牌
     */
    fun getRefreshToken(credentialId: String): String? {
        return securePrefs.getString("refresh_$credentialId", null)
    }

    /**
     * 保存 GCP Vertex AI 或云服务账号 JSON 凭据字符串
     */
    fun saveServiceAccountJson(credentialId: String, serviceAccountJson: String) {
        securePrefs.edit().putString("sa_$credentialId", serviceAccountJson).apply()
    }

    /**
     * 读取 GCP Vertex AI 服务账号 JSON
     */
    fun getServiceAccountJson(credentialId: String): String? {
        return securePrefs.getString("sa_$credentialId", null)
    }

    /**
     * 清理凭据的所有敏感信息
     */
    fun deleteSecrets(credentialId: String) {
        securePrefs.edit()
            .remove("secret_$credentialId")
            .remove("refresh_$credentialId")
            .remove("sa_$credentialId")
            .apply()
    }

    /**
     * 清空全部敏感信息
     */
    fun clearAll() {
        securePrefs.edit().clear().apply()
    }
}
