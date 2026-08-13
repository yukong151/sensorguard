package com.sensorguard.app.shizuku

/**
 * Shizuku UserService (T2 精确归因的执行体)。
 *
 * Shizuku 13.x 的 user service 是一个独立进程里的普通类(继承 AIDL Stub),
 * 由 Shizuku 以 shell(adb, uid 2000) 身份通过构造函数实例化。
 * 因此本类里的 [exec] 以 shell 权限运行 `dumpsys`,能读到传感器客户端归因数据。
 *
 * 无需继承 android.app.Service——Shizuku 按 manifest 中声明带
 * `rikka.shizuku.service.action.TRANSFER` action 的组件拉起本进程,
 * 再通过构造函数取得本 Stub 作为 binder。
 */
class UserService : IUserService.Stub() {
    // 主构造器即无参公共构造(调用 Stub()),Shizuku 经 getConstructor().newInstance() 实例化本类。
    // 注意: 这里不能再写第二个 `constructor() : super()`,否则 Kotlin 报
    // "类已有主构造器,次级构造器必须委托 this()" 的编译错误。

    override fun exec(cmd: String?): String {
        if (cmd.isNullOrEmpty()) return ""
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out = process.inputStream.bufferedReader().use { it.readText() }
            val err = process.errorStream.bufferedReader().use { it.readText() }
            try { process.waitFor() } catch (_: Exception) {}
            out + err
        } catch (e: Exception) {
            "ERR: ${e.message}"
        }
    }

    /** Shizuku 服务端清理 user service 时调用(事务码 16777114)。*/
    override fun destroy() {
        System.exit(0)
    }

    override fun exit() {
        System.exit(0)
    }
}
