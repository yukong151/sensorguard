plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

// P4-4 (文档 §13):Macrobenchmark 冷启动回归基准。
// 独立 test 模块,运行: ./gradlew :benchmark:connectedAndroidTest
// 衡量 MainActivity 冷启动 P95,门禁 §13 "≤ 500 ms (骁龙6Gen1)"。
android {
    namespace = "com.yuexiao12.sensorguard.benchmark"
    compileSdk = 34
    defaultConfig {
        minSdk = 29
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    buildTypes {
        // 基准测试需要 profileable/debuggable 的 app
        debug {}
    }
}

dependencies {
    implementation("androidx.test.ext:junit:1.1.5")
    implementation("androidx.test.espresso:espresso-core:3.5.1")
    implementation("androidx.test.uiautomator:uiautomator:2.2.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.2.4")
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enable = true
    }
}