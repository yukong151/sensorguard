plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    // P4-5 (文档 §15): Kotlin KDoc
    id("org.jetbrains.dokka")
}
android {
    namespace = "com.yuexiao12.sensorguard"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.yuexiao12.sensorguard"
        minSdk = 29; targetSdk = 34
        versionCode = 1; versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // W7 (文档 §8.1): Room exportSchema=true, schema JSON 提交入库;
    // PR 变表必须提供 migration(app/schemas 下 diff 校验,CI 门禁 §12-5)。
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }
    // P1-5: Release 签名配置 —— 从环境变量读取 keystore 路径与密码,
    // 缺失时回退 debug 签名(开发构建可正常 assembleRelease)。
    // CI 通过 secrets 注入 SG_KEYSTORE_FILE / SG_KEYSTORE_PASSWORD /
    // SG_KEY_ALIAS / SG_KEY_PASSWORD 四个环境变量。
    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("SG_KEYSTORE_FILE")
            if (keystoreFile != null && file(keystoreFile).exists()) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("SG_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("SG_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("SG_KEY_PASSWORD") ?: ""
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // P1-5: 环境变量提供 keystore 时用 release 签名;否则回退 debug(开发构建)。
            // 原 W1 偏差(始终用 debug)已修复:CI 设置 SG_KEYSTORE_* 即启用正式签名。
            val keystoreFile = System.getenv("SG_KEYSTORE_FILE")
            signingConfig = if (keystoreFile != null && file(keystoreFile).exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    // W8/Final(文档 §3051/§3162)→ 社区版:双 flavor 已合并为单一 internal 变体。
    // 社区版(面向安全研究者)显示 App 归属/包名——这是核心功能,不复用商店版隐藏身份逻辑。
    // BuildConfig.IS_INTERNAL=true 恒真,控制归属展示;release 构建不含 DEBUG 门控调试入口。
    flavorDimensions += listOf("mode")
    productFlavors {
        create("internal") {
            dimension = "mode"
            buildConfigField("boolean", "IS_INTERNAL", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // buildConfig 供 W8 Debug 演示入口(BuildConfig.DEBUG 门控注入按钮,Release 不可见)
    // aidl=true 确保 src/main/aidl 下的 IUserService.aidl 被编译(否则生成的 IUserService
    // 类不存在,UserService.kt / ShizukuProbe.kt 会因 unresolved reference 编译失败)。
    buildFeatures { viewBinding = true; buildConfig = true; aidl = true }
    // Rust .so 由 cargo-ndk 产出后放到 src/main/jniLibs/arm64-v8a/
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}
dependencies {
    // P4-1 (文档 §8 多模块结构):纯 JVM 模块依赖
    implementation(project(":core:enums"))
    implementation(project(":core:logic"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    // W7 (文档 §8.2): Room 加密存储 + counter(AndroidKeyStore+AES-GCM,原子递增 + fsync)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    // P2-7:移除 security-crypto:1.1.0-alpha06(Google 已标记 deprecated)。
    // 改用 AndroidKeyStore + AES-256-GCM 直接加密 counter 值,无外部依赖。
    // Shizuku 精确归因(文档 §4 P4:T2 增强):运行时可选的独立插件。
    // P1-4 分析:compileOnly 不可行 —— ShizukuProbe 通过 Class.forName("rikka.shizuku.Shizuku")
    // 反射调用 API,类必须随 APK 分发。改为保留 implementation + ProGuard 混淆缩减体积 +
    // 运行时 PackageManager 检测 Shizuku App 安装状态,未安装时零开销静默降级。
    // Shizuku API + Provider 合计 ~30KB,经 ProGuard 混淆后 <15KB,对 APK 体积影响可忽略。
    implementation("dev.rikka.shizuku:api:13.1.5")
    // W12/T2 (文档 §4 P4): Shizuku 客户端必须额外依赖 provider 模块,Manifest 中声明的
    // rikka.shizuku.ShizukuProvider 即来自此模块。缺失会导致 Shizuku 报
    // 'provider is null <pkg>.shizuku'、binder 永远到不了本 App、T2 探针无法激活。
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}
