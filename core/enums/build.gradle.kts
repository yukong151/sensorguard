plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.dokka")
}

// P4-1 (文档 §8 多模块结构):纯 JVM 枚举常量模块。
// SgEnum 为 FlatBuffers schema 的 Kotlin 镜像常量,无 Android 依赖,
// 独立成模块供 :core:logic / :app 复用。
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}