plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
    // P4-5 (文档 §15): Kotlin KDoc 生成(dokka)
    id("org.jetbrains.dokka") version "1.9.20" apply false
}
