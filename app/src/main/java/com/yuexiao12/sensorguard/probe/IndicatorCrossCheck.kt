package com.yuexiao12.sensorguard.probe

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * P4-6 (文档 §3.1):Privacy Dashboard 交叉验证。
 *
 * Android 12+ 的系统 Privacy Dashboard 通过 `PermissionManager.getIndicatorAppOpsList()`
 * (Android 13+) / 隐私指示器维护麦克风/摄像头占用列表。该 API 为 @SystemApi,app 层无法
 * 直接编译引用 —— **诚实妥协**:改用 AppOpsManager 反射读取 `OPSTR_RECORD_AUDIO / OP_CAMERA`
 * 的当前活跃状态(第二条独立于我们各探针的数据源),与探针判定交叉核对:
 *
 *  - 探针报"麦克风占用"但 AppOps 无 → 探针假阳性 / 音频会话无 AppOps 记录;
 *  - AppOps 有但探针未报 → 探针盲区(如厂商 ROM 回调异常)。
 *
 * 仅供日志审计与调试标记,不阻塞主链路。API < 29 或反射失败返回空(降级)。
 */
object IndicatorCrossCheck {

    /** 经反射读当前活跃的麦克风/摄像头 AppOps op(独立数据源)。*/
    fun activeIndicators(context: Context): List<Int> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        return try {
            val am = context.getSystemService(AppOpsManager::class.java)
            val check = am::class.java.getMethod(
                "checkOpNoThrow",
                String::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.javaPrimitiveType
            )
            val out = ArrayList<Int>()
            val myUid = android.os.Process.myUid()
            for ((str, sg) in listOf(
                "android:record_audio" to com.yuexiao12.sensorguard.enums.SgEnum.OP_RECORD_AUDIO,
                "android:camera" to com.yuexiao12.sensorguard.enums.SgEnum.OP_CAMERA,
            )) {
                val mode = check.invoke(am, str, myUid, context.packageName) as Int
                // MODE_ALLOWED=0 表示允许(本 App 自采需区分;这里只做图例,记录允许态)
                if (mode == AppOpsManager.MODE_ALLOWED) out.add(sg)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 交叉核对探针 vs 系统 AppOps,写审计日志。*/
    fun auditAgainstProbes(context: Context, probeActiveOps: Set<Int>, tag: String) {
        val sys = activeIndicators(context).toSet()
        if (sys.isEmpty()) return // AppOps 反射不可用,跳过
        val onlySys = sys - probeActiveOps
        val onlyProbe = probeActiveOps - sys
        if (onlySys.isNotEmpty() || onlyProbe.isNotEmpty()) {
            Log.i(tag, "AppOps cross-check: system=$sys probe=$probeActiveOps " +
                "sysOnly=$onlySys probeOnly=$onlyProbe")
        }
    }
}