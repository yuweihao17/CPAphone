# CPAphone Proguard & R8 Optimize Rules

# 1. 保持注解与序列化反射安全
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# 2. 保持 kotlinx.serialization 序列化生成类与 Companion
-keepclassmembers class * {
    companion object *;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,allowobfuscation,allowshrinking class * extends kotlinx.serialization.internal.GeneratedSerializer { *; }

# 3. 保持 Room 实体与 DAO 反射
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

# 4. 保持 Ktor CIO 异步网络引擎
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# 5. 保持 WebRTC 与 NDK 原生 C-ABI 入口符号
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.cpaphone.core.model.** { *; }
-keep class com.cpaphone.engine.plugin.** { *; }

# 6. 忽略特定第三方警告
-dontwarn okio.**
-dontwarn java.lang.invoke.**
