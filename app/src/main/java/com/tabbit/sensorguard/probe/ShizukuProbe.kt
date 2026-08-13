package com.tabbit.sensorguard.probe

import android.util.Log
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import rikka.shizuku.Shizuku
import com.tabbit.sensorguard.shizuku.IUserService
import com.tabbit.sensorguard.BuildConfig

/**
 * Shizuku 精确归因探针 (文档 §4 P4:T2 增强,独立可选插件)。
 *
 * 通过 Shizuku API 以 ADB shell 权限执行 `dumpsys sensorservice`,
 * 解析出每个活跃传感器连接的精确 uid + 包名 + 采样率,
 * 消除 KS 检验只能推断不能定位的盲区。
 *
 * Shizuku 不可用时完全静默(不 crash、不弹窗、不增大 APK 体积)。
 * 用户需安装 Shizuku App 并授权后,本探针自动激活,evidenceTier 升至 T2 增强。
 *
 * 健壮性设计(Step 3 打磨):
 * - [isAvailable] = binder 存活 **且** 已授权,避免"装了未授权"误报 T2。
 * - 反射注册 BinderReceived / BinderDead / RequestPermissionResult 三类监听器:
 *   晚授权(用户在 Shizuku 内手动授予)、Shizuku 重启会自动激活/停用探针。
 * - [execDumpsys] 带 15s 超时,经独立 IO 线程读取,避免 Shizuku 进程挂死卡住调度单线程。
 */
class ShizukuProbe(
    private val callback: (List<SensorServiceParser.SensorClient>) -> Unit,
) {
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sg-shizuku").apply { isDaemon = true } }
    // 独立 IO 线程,带超时读取 dumpsys 输出,避免阻塞 scheduler 单线程
    private val ioExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "sg-shizuku-io").apply { isDaemon = true } }

    @Volatile private var latestSnapshot = emptyList<SensorServiceParser.SensorClient>()
    @Volatile private var permissionRequested = false
    @Volatile private var running = false
    @Volatile private var listenersRegistered = false
    // 反射持有的监听器代理(注册后保存以便反注册)
    private var binderRecvListener: Any? = null
    private var binderDeadListener: Any? = null
    private var permResultListener: Any? = null

    // T2 精确归因(UserService): 以 shell 身份执行 dumpsys 的远程 binder
    @Volatile private var userService: IUserService? = null
    @Volatile private var bindInFlight = false
    private val bindLock = Any()
    private var userSvcArgs: Shizuku.UserServiceArgs? = null
    private var userSvcConn: ServiceConnection? = null

    /** 是否已启动轮询(供调用方去重,避免重复 schedule)。*/
    val isRunning: Boolean get() = running

    /** 启动前的引导重试任务(权限就绪后由 maybeStart 取消),避免永久休眠。*/
    @Volatile private var bootstrapTask: java.util.concurrent.ScheduledFuture<*>? = null

    /**
     * Shizuku 是否真正可用: binder 存活 且 已授权本应用。
     * 仅 pingBinder 为 true 不代表已授权(可能弹过授权但未同意),
     * 此时若误判可用会让 evidenceTier 错误升到 T2。
     */
    val isAvailable: Boolean get() = isBinderAlive && hasPermission()

    private val isBinderAlive: Boolean
        get() {
            val cls = SHIZUKU_CLASS ?: return false
            return try { cls.getMethod("pingBinder").invoke(null) as Boolean } catch (_: Exception) { false }
        }

    private fun hasPermission(): Boolean {
        val cls = SHIZUKU_CLASS ?: return false
        return try {
            val granted = cls.getMethod("checkSelfPermission").invoke(null) as Int
            granted == 0 // PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
    }

    /** 启动: 注册监听器(仅一次),随后尝试启动轮询。若授权在 Service 创建后才就绪
     *  (用户在 Shizuku 设置里预先授权 / 晚授权但监听器因 request code 不匹配而忽略),
     *  maybeStart 不会被触发 → 改为周期性重试,一旦权限可用立即启动,避免探针永久休眠。*/
    fun start() {
        registerListenersOnce()
        maybeStart()
        if (!running) {
            bootstrapTask = scheduler.scheduleWithFixedDelay({ maybeStart() }, 5, 5, TimeUnit.SECONDS)
        }
    }

    /** 停止调度并反注册监听器。*/
    fun stop() {
        running = false
        bootstrapTask?.cancel(false); bootstrapTask = null
        unregisterListeners()
        try {
            userSvcArgs?.let { a -> userSvcConn?.let { c -> Shizuku.unbindUserService(a, c, true) } }
        } catch (_: Exception) {}
        userService = null
        scheduler.shutdownNow()
        ioExecutor.shutdownNow()
    }

    /** binder 存活 + 已授权时启动轮询; 否则等待(授权框统一由 MainActivity 在 Activity 上下文中发起,
     *  此处也会在 binder 已活但本 App 未授权时主动拉起授权框)。*/
    private fun maybeStart() {
        if (running) return
        val alive = isBinderAlive
        val perm = if (alive) hasPermission() else false
        // 一次性诊断: 仅在首次发现"未就绪"时打印原因,避免重试空转刷屏
        if (!alive && !binderDiag) { binderDiag = true; Log.w("SG", "ShizukuProbe wait: binder not alive") }
        if (alive && !perm && !permDiag) { permDiag = true; Log.w("SG", "ShizukuProbe wait: permission not granted") }
        if (!alive || !perm) {
            // 服务端可达但本 App 尚未授权: 主动拉起授权框(单参版由 Shizuku 以 NEW_TASK 弹出,
            // 不依赖 Activity 上下文)。permissionRequested 防止 5s 重试刷屏; 被拒/未决定时
            // 由 onRequestPermissionResult 复位,允许用户后续重试。
            if (alive && !perm && !permissionRequested) requestPermission(null)
            return
        }
        running = true
        // 引导重试已完成使命,取消以免空转
        bootstrapTask?.cancel(false); bootstrapTask = null
        kickBind() // 拉起 UserService(独立 shell 进程),供后续 execDumpsys 使用
        scheduler.scheduleWithFixedDelay(::refresh, 0, 60, TimeUnit.SECONDS)
        Log.i("SG", "ShizukuProbe started (T2 enhanced)")
    }

    @Volatile private var binderDiag = false
    @Volatile private var permDiag = false

    /**
     * 请求 Shizuku 授权。
     * 经 javap 核实 Shizuku 13.1.5 仅暴露单参 [requestPermission(int)] 这一签名
     * (双参 Activity 版本在更高版本才提供)。单参版本由 Shizuku 自身以 NEW_TASK 拉起授权
     * Activity,因此不论前台后台都能弹框;为让授权框稳定出现在用户眼前,统一由 MainActivity
     * (Activity 上下文)触发本方法。若当前 Shizuku 版本确实存在双参签名(更高版本),则改传
     * Activity 以遵循其契约。
     */
    fun requestPermission(activity: android.app.Activity? = null) {
        if (permissionRequested) return
        val cls = SHIZUKU_CLASS ?: return
        // binder 不通时静默 return 且**不**置 permissionRequested,留待下次重试;
        // 否则 Shizuku.requestPermission(int) 在 binder 不 alive 时会静默 return(无异常),
        // 会被误判为"已弹框"而永久锁死标志,导致授权框再也不出现。
        if (!isBinderAlive) return
        val invoked = try {
            // 优先单参(13.1.5 真实签名);仅当单参不存在时回退双参(更新版本)。
            val one = cls.methods.firstOrNull { it.name == "requestPermission" && it.parameterCount == 1 }
            if (one != null) {
                one.invoke(null, SHIZUKU_REQUEST_CODE); true
            } else {
                val two = cls.methods.firstOrNull { it.name == "requestPermission" && it.parameterCount == 2 }
                two?.let { m ->
                    if (activity != null) { m.invoke(null, activity, SHIZUKU_REQUEST_CODE); true }
                    else false
                } ?: false
            }
        } catch (_: Exception) { false }
        if (invoked) permissionRequested = true
    }

    private fun refresh() {
        // 防御性包裹: scheduleWithFixedDelay 一旦任务抛未捕获异常会取消后续所有周期执行,
        // 因此这里吞掉一切异常,保证探针循环长生命周期内不静默停摆。
        try {
            val output = try {
                execDumpsys()
            } catch (e: Exception) {
                Log.w("SG", "Shizuku dumpsys failed", e)
                return
            }
            val clients = try {
                SensorServiceParser.parse(output)
            } catch (e: Exception) {
                Log.w("SG", "Shizuku parse failed", e)
                return
            }
            latestSnapshot = clients
            if (clients.isNotEmpty()) {
                Log.i("SG", "Shizuku: ${clients.size} sensor clients, " +
                    "uid=${clients[0].uid} pkg=${clients[0].packageName}")
            }
            try {
                callback(clients)
            } catch (e: Exception) {
                Log.w("SG", "Shizuku callback error (non-fatal, loop continues)", e)
            }
        } catch (t: Throwable) {
            Log.w("SG", "Shizuku refresh unexpected error (non-fatal)", t)
        }
    }

    /** 带超时的 dumpsys 读取,避免 Shizuku 进程挂死阻塞 scheduler 单线程。
     *  经 Shizuku UserService(独立 shell 进程)以 shell 身份执行 dumpsys sensorservice,
     *  替代 13.x 已移除的 Shizuku.newProcess。 */
    private fun execDumpsys(): String {
        val svc = ensureUserSvc() ?: throw IOException("Shizuku UserService not bound")
        val future = ioExecutor.submit(Callable { svc.exec("dumpsys sensorservice 2>/dev/null") })
        return try {
            future.get(DUMPSYS_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: ""
        } catch (e: Exception) {
            // 超时 / 中断 / 读取异常: 取消读取任务
            future.cancel(true)
            throw IOException("dumpsys read failed: ${e.message}", e)
        }
    }

    /** 触发绑定(不阻塞);若已绑定/绑定中则跳过。 */
    private fun kickBind() {
        if (userService != null) return
        var needBind = false
        synchronized(bindLock) {
            if (userService == null && !bindInFlight) { bindInFlight = true; needBind = true }
        }
        if (needBind) bindUserSvcInternal()
    }

    /** 阻塞等待 UserService binder(最多 8s);超时则放开 bindInFlight 允许下次重试。 */
    private fun ensureUserSvc(): IUserService? {
        if (userService != null) return userService
        kickBind()
        if (userService != null) return userService
        val deadline = System.currentTimeMillis() + 8000
        while (userService == null && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(150) } catch (_: InterruptedException) { break }
        }
        if (userService == null) bindInFlight = false
        return userService
    }

    /** 经 Shizuku.bindUserService 拉起独立 shell 进程中的 UserService,获取 IUserService binder。 */
    private fun bindUserSvcInternal() {
        if (!isBinderAlive || !hasPermission()) { bindInFlight = false; return }
        try {
            val cn = ComponentName("com.tabbit.sensorguard", "com.tabbit.sensorguard.shizuku.UserService")
            val args = Shizuku.UserServiceArgs(cn)
                .processNameSuffix("shizuku")
                .debuggable(BuildConfig.DEBUG)
                .version(1)
                .tag("SgUserService")
            userSvcArgs = args
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    userService = IUserService.Stub.asInterface(binder)
                    Log.i("SG", "Shizuku UserService bound (T2 exec ready)")
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    userService = null
                    bindInFlight = false
                }
            }
            userSvcConn = conn
            Shizuku.bindUserService(args, conn)
        } catch (e: Exception) {
            Log.w("SG", "Shizuku bindUserService failed (non-fatal)", e)
            bindInFlight = false
        }
    }

    // ---- 反射监听器(晚授权 / Shizuku 重启时自动激活或停用) ----

    private fun registerListenersOnce() {
        if (listenersRegistered) return
        listenersRegistered = true
        val cls = SHIZUKU_CLASS ?: return
        try {
            // Binder 收到: Shizuku 启动/授权后自动尝试启动
            binderRecvListener = register(cls, "rikka.shizuku.Shizuku\$BinderReceivedListener",
                "addBinderReceivedListener", "removeBinderReceivedListener") { name, _ ->
                if (name == "onBinderReceived") maybeStart()
                null
            }
            // Binder 死亡: Shizuku 停止,停用探针
            binderDeadListener = register(cls, "rikka.shizuku.Shizuku\$BinderDeadListener",
                "addBinderDeadListener", "removeBinderDeadListener") { name, _ ->
                if (name == "onBinderDead") {
                    running = false
                    Log.i("SG", "Shizuku binder dead, T2 probe paused")
                }
                null
            }
            // 授权结果: 用户同意后自动启动; 被拒/未决定则复位标志允许后续重试
            permResultListener = register(cls, "rikka.shizuku.Shizuku\$RequestPermissionResultListener",
                "addRequestPermissionResultListener", "removeRequestPermissionResultListener") { name, args ->
                if (name == "onRequestPermissionResult") {
                    val req = args?.getOrNull(0) as? Int
                    val grant = args?.getOrNull(1) as? Int
                    if (req == SHIZUKU_REQUEST_CODE) {
                        if (grant == 0) maybeStart() else permissionRequested = false
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w("SG", "Shizuku listener registration failed (non-fatal)", e)
        }
    }

    private fun unregisterListeners() {
        val cls = SHIZUKU_CLASS ?: return
        binderRecvListener?.let { safeRemove(cls, "removeBinderReceivedListener", it) }
        binderDeadListener?.let { safeRemove(cls, "removeBinderDeadListener", it) }
        permResultListener?.let { safeRemove(cls, "removeRequestPermissionResultListener", it) }
        binderRecvListener = null
        binderDeadListener = null
        permResultListener = null
    }

    /** 用动态代理实现 Shizuku 监听器接口,注册并返回代理(失败返回 null)。*/
    private fun register(
        cls: Class<*>, ifaceName: String, addName: String, removeName: String,
        handler: (String, Array<out Any>?) -> Any?,
    ): Any? = try {
        val iface = Class.forName(ifaceName, false, cls.classLoader)
        val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
            handler(method.name, args)
        }
        cls.getMethod(addName, iface).invoke(null, proxy)
        proxy
    } catch (_: Exception) { null }

    private fun safeRemove(cls: Class<*>, removeName: String, listener: Any) {
        try {
            val iface = listener.javaClass.interfaces.firstOrNull() ?: return
            cls.getMethod(removeName, iface).invoke(null, listener)
        } catch (_: Exception) {}
    }

    /** 获取最新快照。*/
    fun snapshot(): List<SensorServiceParser.SensorClient> = latestSnapshot

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 10001
        private const val DUMPSYS_TIMEOUT_MS = 15_000L
        private val SHIZUKU_CLASS by lazy {
            try { Class.forName("rikka.shizuku.Shizuku") } catch (_: Exception) { null }
        }
    }
}
