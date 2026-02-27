# ============================================================================
# Memory AI - ProGuard Rules
# Phase 3 Week 4: Security & Obfuscation
# ============================================================================

# ===== 保留基础类 =====
-keep class com.soulon.app.MainActivity { *; }
-keep class com.soulon.app.SoulonApplication { *; }

# ===== 保留 Room 数据库 =====
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * {
    <fields>;
}
-keep @androidx.room.Dao class * {
    <methods>;
}

# 保留 Room 数据库相关类
-keep class com.soulon.app.rewards.RewardsDatabase { *; }
-keep class com.soulon.app.rewards.UserProfile { *; }
-keep class com.soulon.app.rewards.MemoTransaction { *; }
-keep class com.soulon.app.rewards.PersonaProfileV2 { *; }
-keep class com.soulon.app.rag.MemoryVector { *; }

# ===== 保留 Jetpack Compose =====
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }

# ===== 混淆但保留关键类的结构 =====
# Rewards 系统
-keep,allowobfuscation class com.soulon.app.rewards.** { *; }

# RAG 系统
-keep,allowobfuscation class com.soulon.app.rag.** { *; }

# Persona 分析
-keep,allowobfuscation class com.soulon.app.persona.** { *; }

# Sovereign / SBT
-keep,allowobfuscation class com.soulon.app.sovereign.** { *; }

# AI 管理
-keep,allowobfuscation class com.soulon.app.ai.** { *; }

# ===== 移除日志（Release 构建） =====
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}

-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}

-assumenosideeffects class timber.log.Timber$Tree {
    public *** d(...);
    public *** v(...);
    public *** i(...);
    public *** w(...);
}

# ===== Solana Mobile SDK =====
-keep class com.solanamobile.** { *; }
-keep interface com.solanamobile.** { *; }
-dontwarn com.solanamobile.**

# 保留 Solana 相关类
-keep class com.solana.** { *; }
-dontwarn com.solana.**

# ===== OkHttp & Networking =====
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

-dontwarn okio.**
-keep class okio.** { *; }

# OkHttp 平台特定类
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ===== BouncyCastle 加密库 =====
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

-keep class org.bitcoinj.** { *; }
-dontwarn org.bitcoinj.**

# ===== ONNX Runtime =====
-keep class ai.onnxruntime.** { *; }
-keep interface ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ===== Kotlin Coroutines =====
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ===== Kotlin Serialization =====
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ===== Gson =====
-keepattributes Signature
-keepattributes *Annotation*

-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keep class com.soulon.app.irys.IrysClient$* { *; }

# ===== JSON (org.json) =====
-keep class org.json.** { *; }
-dontwarn org.json.**

# ===== AndroidX Security Crypto =====
-keep class androidx.security.crypto.** { *; }
-keep interface androidx.security.crypto.** { *; }

# ===== Lifecycle 相关 =====
-keep class * implements androidx.lifecycle.LifecycleObserver {
    <init>(...);
}

-keep class androidx.lifecycle.** { *; }
-keep interface androidx.lifecycle.** { *; }

# ===== 保留 BuildConfig =====
-keep class com.soulon.app.BuildConfig { *; }

# ===== 保留枚举 =====
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ===== 保留 Parcelable =====
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ===== 保留 Serializable =====
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ===== 保留 Native 方法 =====
-keepclasseswithmembernames class * {
    native <methods>;
}

# ===== 保留 View 构造函数 =====
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ===== 保留 R 类 =====
-keep class **.R$* {
    <fields>;
}

# ===== 优化设置 =====
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose

# ===== 保留行号信息（便于调试崩溃） =====
-keepattributes SourceFile,LineNumberTable

-dontwarn com.ditchoom.buffer.BufferFactoryJvm
-dontwarn org.slf4j.impl.StaticLoggerBinder

# ===== 重命名源文件名（增加混淆） =====
-renamesourcefileattribute SourceFile

# ===== 保留泛型签名 =====
-keepattributes Signature

# ===== 保留异常信息 =====
-keepattributes Exceptions

# ===== 警告抑制 =====
-dontwarn javax.annotation.**
-dontwarn javax.inject.**
-dontwarn sun.misc.**
-dontwarn java.lang.invoke.**

# ============================================================================
# 自定义混淆策略
# ============================================================================

# 关键算法类 - 强混淆
-keep,allowobfuscation,allowshrinking class com.soulon.app.rewards.RewardsRepository
-keep,allowobfuscation,allowshrinking class com.soulon.app.rewards.UserLevelManager
-keep,allowobfuscation,allowshrinking class com.soulon.app.rag.SemanticSearchEngine
-keep,allowobfuscation,allowshrinking class com.soulon.app.sovereign.SkrCalculator

# ============================================================================
# 结束
# ============================================================================
