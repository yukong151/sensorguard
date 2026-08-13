package com.yuexiao12.sensorguard.probe

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * P3 (文档 §7):decl_purpose 离线抽取 —— App 用途类别静态先验。
 *
 * 文档 §7 要求:App 首次被观测到时,离线抽取一次其 `<uses-feature>` 与
 * `<uses-permission>` 映射到用途标签(相机类/健身类/导航类/输入法类/未知),
 * 供上下文一致性 S_ctx 判定(健身类 App 高频采 accel 合法,手电筒类则可疑)。
 *
 * 分类规则(文档 §7 语义 + Android 常识约束):
 *  - CAMERA: 声明 android.hardware.camera + android.permission.CAMERA
 *  - FITNESS: android.hardware.sensor.accelerometer/gyroscope + BODY_SENSORS 或
 *             HEALTH_PERMISSION 或无敏感权限
 *  - NAVIGATION: android.hardware.location.gps + ACCESS_FINE_LOCATION
 *  - IME: BIND_INPUT_METHOD 服务(系统输入法)
 *  - OTHER/UNKNOWN 兜底
 *
 * 结果仅本地缓存(每次启动重扫,不落库,体积约 0)。
 */
object DeclPurposeClassifier {

    /** decl_purpose 枚举值(与 CtxTag.decl_purpose ubyte 对应)。*/
    const val UNKNOWN = 0
    const val CAMERA = 1
    const val FITNESS = 2
    const val NAVIGATION = 3
    const val IME = 4

    private val cache = HashMap<String, Int>() // pkgName -> decl_purpose

    /**
     * 离线抽取某 App 的用途类别。失败/未知返回 UNKNOWN(0)。
     * callPackage 为空(如本地囤积的 uid=-1 T0 事件)直接返回 UNKNOWN。
     */
    fun classify(context: Context, packageName: String?): Int {
        if (packageName.isNullOrBlank()) return UNKNOWN
        cache[packageName]?.let { return it }
        val result = try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            classifyInfo(pm, appInfo)
        } catch (_: PackageManager.NameNotFoundException) {
            UNKNOWN
        }
        cache[packageName] = result
        if (result != UNKNOWN) Log.d(TAG, "decl_purpose[$packageName]=$result")
        return result
    }

    private fun classifyInfo(pm: PackageManager, ai: android.content.pm.ApplicationInfo): Int {
        val info = pm.getPackageInfo(ai.packageName, PackageManager.GET_PERMISSIONS)
        val declared = info.requestedPermissions?.toSet().orEmpty()

        // 输入法:声明 BIND_INPUT_METHOD(系统输入法核心权限)
        if (declared.contains("android.permission.BIND_INPUT_METHOD")) return IME
        // 相机类:硬件 + 权限双声明
        if (declared.contains("android.permission.CAMERA")) return CAMERA
        // 导航:GPS 定位权限
        if (declared.contains("android.permission.ACCESS_FINE_LOCATION") ||
            declared.contains("android.permission.ACCESS_COARSE_LOCATION")) return NAVIGATION
        // 健身/健康:IMU 传感器相关权限(健身/运动 App 核心)
        if (declared.contains("android.permission.BODY_SENSORS") ||
            declared.contains("android.permission.ACTIVITY_RECOGNITION")) return FITNESS
        return UNKNOWN
    }

    /** 用途名(调试/UI 展示)。*/
    fun name(purpose: Int): String = when (purpose) {
        CAMERA -> "camera"
        FITNESS -> "fitness"
        NAVIGATION -> "navigation"
        IME -> "ime"
        else -> "unknown"
    }

    private const val TAG = "SgDeclPurpose"
}