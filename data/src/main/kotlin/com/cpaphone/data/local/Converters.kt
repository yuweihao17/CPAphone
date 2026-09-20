package com.cpaphone.data.local

import androidx.room.TypeConverter
import com.cpaphone.core.model.AuthType
import com.cpaphone.core.model.CredentialStatus
import com.cpaphone.core.model.ProviderType

/**
 * Room 枚举与数据类型转换器
 */
class Converters {
    @TypeConverter
    fun fromProviderType(value: ProviderType): String = value.identifier

    @TypeConverter
    fun toProviderType(value: String): ProviderType = ProviderType.fromIdentifier(value)

    @TypeConverter
    fun fromAuthType(value: AuthType): String = value.name

    @TypeConverter
    fun toAuthType(value: String): AuthType = try {
        AuthType.valueOf(value)
    } catch (_: Exception) {
        AuthType.API_KEY
    }

    @TypeConverter
    fun fromCredentialStatus(value: CredentialStatus): String = value.name

    @TypeConverter
    fun toCredentialStatus(value: String): CredentialStatus = try {
        CredentialStatus.valueOf(value)
    } catch (_: Exception) {
        CredentialStatus.ACTIVE
    }
}
