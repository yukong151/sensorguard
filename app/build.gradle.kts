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
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Deviation(doc-frozen): W1 使用 debug 签名,使文档 §4 的 `adb install app-release.apk` 可安装;
            // 生产签名(独立 keystore)留待发布里程碑,文档未冻结具体签名方案。
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // W8/Final(文档 §3051/§3162): Product Flavor 分离「内测版 internal」与「商店版 store」。
    // 归属显隐由 flavor 维度 BuildConfig.IS_INTERNAL 控制(配置化而非删除式):
    //  - internal: 显示宿主 App 归属 / 包指纹(内测"怎么玩都可以");
    //  - store:    永不显示任何身份标识(上架合规)。
    // 开发期调试入口(演示告警 / 压测 / UserService debuggable)仍门控于 BuildConfig.DEBUG,
    // 与 flavor 解耦,确保任何 release 构建都不含调试入口。
    flavorDimensions += listOf("mode")
    productFlavors {
        create("internal") {
            dimension = "mode"
            buildConfigField("boolean", "IS_INTERNAL", "true")
        }
        create("store") {
            dimension = "mode"
            buildConfigField("boolean", "IS_INTERNAL", "false")
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
    // W7 (文档 §8.2): Room 加密存储 + EncryptedSharedPreferences(counter 原子递增 + fsync)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    // 文档 §8.2 指定 EncryptedSharedPreferences(counter 原子递增 + fsync)。
    // Google 已标记 deprecated 但 API 稳定;供应链锁版本(文档 §14)。
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Shizuku 精确归因(文档 §4 P4:T2 增强):运行时可选的独立插件。
    // 注: 当前以 implementation 硬依赖打入主 APK —— Shizuku 客户端类(rikka.shizuku.Shizuku)
    // 必须随 APK 分发,运行时方能经反射调用;纯 compileOnly 会导致 Class.forName 失败、探针永不激活。
    // 这与文档"可选插件 / <4MB"目标存在张力;彻底解法是拆为 dynamic-feature 模块(按需安装),
    // 该架构拆分留待 Step 4 决策,此处保持硬依赖以保证 T2 链路可用。
    implementation("dev.rikka.shizuku:api:13.1.5")
    // W12/T2 (文档 §4 P4): Shizuku 客户端必须额外依赖 provider 模块,Manifest 中声明的
    // rikka.shizuku.ShizukuProvider 即来自此模块。缺失会导致 Shizuku 报
    // 'provider is null <pkg>.shizuku'、binder 永远到不了本 App、T2 探针无法激活。
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}
