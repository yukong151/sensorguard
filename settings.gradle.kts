pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral(); maven { url = uri("https://maven.rikko.dev/") } }
}
rootProject.name = "sensorguard"
include(":app")
// P4-1 (文档 §8 多模块结构):纯 JVM 模块 —— 枚举常量 / 逻辑层
include(":core:enums")
include(":core:logic")
// P4-4 (文档 §13):Macrobenchmark 冷启动回归模块
include(":benchmark")
