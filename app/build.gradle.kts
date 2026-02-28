plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp") version "1.9.22-1.0.17"
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.soulon.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.soulon.top"
        minSdk = 30
        targetSdk = 34
        versionCode = 120
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        vectorDrawables {
            useSupportLibrary = true
        }
        buildConfigField("String", "IDENTITY_URI", "\"https://soulon.top\"")
    }

    val releaseStoreFile = providers.gradleProperty("RELEASE_STORE_FILE").orNull
        ?: System.getenv("RELEASE_STORE_FILE")
    val releaseStorePassword = providers.gradleProperty("RELEASE_STORE_PASSWORD").orNull
        ?: System.getenv("RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").orNull
        ?: System.getenv("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull
        ?: System.getenv("RELEASE_KEY_PASSWORD")

    if (!releaseStoreFile.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()
    ) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
    }

    flavorDimensions += "env"
    productFlavors {
        create("dev") {
            dimension = "env"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-SNAPSHOT"
            buildConfigField("String", "BACKEND_BASE_URL", "\"https://api-test.soulon.top\"")
            buildConfigField("String", "NEGOTIATION_BASE_URL", "\"https://api-test.soulon.top\"")
        }
        create("staging") {
            dimension = "env"
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-rc.0"
            buildConfigField("String", "BACKEND_BASE_URL", "\"https://api-staging.soulon.top\"")
            buildConfigField("String", "NEGOTIATION_BASE_URL", "\"https://api-staging.soulon.top\"")
        }
        create("prod") {
            dimension = "env"
            buildConfigField("String", "BACKEND_BASE_URL", "\"https://api.soulon.top\"")
            buildConfigField("String", "NEGOTIATION_BASE_URL", "\"https://api.soulon.top\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // 排除重复的 BouncyCastle LICENSE 文件
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
    }
}

dependencies {
    // ============================================================================
    // Phase 3: AI/ML 依赖
    // ============================================================================
    
    // 注意：已改用云端 Qwen API，不再需要本地 ONNX Runtime
    // ONNX Runtime 已移除，解决 16KB 页面对齐问题
    
    // Room (本地数据库 - $MEMO 积分系统)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    
    // ============================================================================
    // Phase 2: Solana Mobile Stack - 官方 SDK (docs.solanamobile.com)
    // ============================================================================
    
    // Mobile Wallet Adapter 2.0 (官方推荐版本)
    // https://docs.solanamobile.com/android-native/setup
    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.0.3")
    
    // Solana Web3 核心库 (仅用于 SolanaPublicKey 类型)
    // 注意：不使用 SystemProgram.transfer，手动构建交易以避免 kborsh 依赖问题
    implementation("com.solanamobile:web3-solana:0.2.5")
    
    // RPC 请求库 (Solana JSON-RPC)
    implementation("com.solanamobile:rpc-core:0.2.7")
    
    // Base58 编解码工具
    implementation("io.github.funkatronics:multimult:0.2.3")
    
    // ============================================================================
    // Firebase Cloud Messaging (推送通知)
    // ============================================================================
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")

    // ML Kit (本地翻译)
    implementation("com.google.mlkit:translate:17.0.2")
    
    // BIP-32 密钥派生 - 排除旧版 BouncyCastle
    implementation("org.bitcoinj:bitcoinj-core:0.16.3") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    }
    
    // Irys SDK (通过 HTTP 客户端实现)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Android 核心
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.biometric:biometric-ktx:1.2.0-alpha05")
    
    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material") // 添加 Material (非 M3) 以支持 PullRefresh
    implementation("androidx.compose.material:material-icons-extended")
    
    // 加密 - 使用新版 BouncyCastle（与 BitcoinJ 兼容）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.73")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.73") // 用于 Ed25519 签名
    
    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // 日志
    implementation("com.jakewharton.timber:timber:5.0.1")
    
    // ============================================================================
    // 跨设备同步：QR 码 & 相机
    // ============================================================================
    
    // QR 码生成与扫描
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    
    // CameraX (用于 QR 扫描)
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")
    
    // ML Kit 条码扫描
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
    
    // WorkManager (定期备份提醒)
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // 测试
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    
    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
