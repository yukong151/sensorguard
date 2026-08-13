package com.yuexiao12.sensorguard.logic

import com.yuexiao12.sensorguard.jni.SgEnum

/**
 * 干预路由(文档 §6 干预路由表)的纯 JVM 部分: op → 干预方案。
 *
 * 返回 [Intervention] 描述(深链 action + 引导语),由 UI/服务层将其
 * 转换为真实 Intent 与本地化文案。
 *
 * 能力边界(诚实标注):
 * - 常规探针(Mic/Camera/Baseline)只能提供 12B pkgHash(HMAC 截断,不可逆),
 *   无 Shizuku 时无法还原包名,故「蓝牙扫描高频 → ACTION_UNINSTALL_PACKAGE」
 *   依赖包名构造 data URI 的场景默认不可达(resolve 返回 null,不返回假路由)。
 * - P3:Shizuku 精确归因提供了包名 → 新增 [resolveUninstall],由调用方在拥有
 *   包名时构造卸载深链。
 */
object ActionRouter {

    /** Settings.ACTION_PRIVACY_SETTINGS 常量值(避免依赖 android.jar 的纯 JVM 单测)。*/
    const val ACTION_PRIVACY_SETTINGS = "android.settings.PRIVACY_SETTINGS"

    /** P3 (文档 §5.5):ACTION_UNINSTALL_PACKAGE 常量值(纯 JVM 单测友好)。*/
    const val ACTION_UNINSTALL_PACKAGE = "android.intent.action.DELETE"

    /** 干预方案枚举(UI 层据此映射本地化文案与图标)。*/
    enum class InterventionKind { PRIVACY_MIC, PRIVACY_CAMERA, SENSOR_GUIDE, UNINSTALL }

    data class Intervention(
        val kind: InterventionKind,
        val intentAction: String,
    )

    /**
     * op → 干预方案(文档 §6):
     *  - RECORD_AUDIO 异常 → 隐私设置深链 + 麦克风磁贴引导;
     *  - CAMERA 异常 → 隐私设置深链 + Camera Toggle 引导;
     *  - ACCEL/GYRO/MAG(IMU 高频)→ 隐私设置深链 + Android 13+「传感器已关闭」引导;
     *  - 蓝牙扫描(OP_BT_SCAN)→ 卸载方案(需包名构造,见 [resolveUninstall]);
     *  - 其余 op(定位/气压/光/接近)不在干预路由表内 → null(仅记录)。
     */
    fun resolve(op: Int): Intervention? = when (op) {
        SgEnum.OP_RECORD_AUDIO ->
            Intervention(InterventionKind.PRIVACY_MIC, ACTION_PRIVACY_SETTINGS)
        SgEnum.OP_CAMERA ->
            Intervention(InterventionKind.PRIVACY_CAMERA, ACTION_PRIVACY_SETTINGS)
        SgEnum.OP_ACCEL, SgEnum.OP_GYRO, SgEnum.OP_MAG ->
            Intervention(InterventionKind.SENSOR_GUIDE, ACTION_PRIVACY_SETTINGS)
        SgEnum.OP_BT_SCAN ->
            Intervention(InterventionKind.UNINSTALL, ACTION_UNINSTALL_PACKAGE)
        else -> null
    }

    /**
     * P3 (文档 §5.5):构造卸载深链 —— 需真实包名(Shizuku 精确归因提供)。
     * 返回 `action.DELETE` + `package:${pkg}` data URI;调用方拼 Intent。
     */
    fun resolveUninstall(pkgName: String): Intervention? {
        if (pkgName.isBlank()) return null
        return Intervention(InterventionKind.UNINSTALL, ACTION_UNINSTALL_PACKAGE)
    }
}