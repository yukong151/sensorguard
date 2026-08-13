package com.yuexiao12.sensorguard.ui

import android.content.Context
import com.yuexiao12.sensorguard.BuildConfig

/**
 * 事件/告警归因解析 —— 将"内层传感器客户端包名 + uid"解析为可读的宿主 App 归属。
 *
 * 文档约束(用户明确):开发文档规定"只显示包名"是为规避大厂法务纠纷;
 * 当前开发阶段不需要考虑该限制,故 **内测版(internal flavor)** 下完整展示包名;
 * **商店版(store flavor)** 一律返回 null,UI 据此以"某应用"等中性措辞替代,绝不向终端用户明示包名/身份
 * (上架市场合规要求)。门控用 BuildConfig.IS_INTERNAL 而非 BuildConfig.DEBUG,
 * 以便"内测发布版(internal+release)"仍可向内部人员展示归因,而 store 任何构建都隐藏。
 *
 * 解析策略(内测版):
 *  - 优先尝试宿主解析: uid -> getPackagesForUid -> 同前缀宿主包 -> 显示 "宿主名 (宿主包) › SDK:内层包";
 *  - Android 包可见性限制下 getPackagesForUid 可能查不到其他 App(无 QUERY_ALL_PACKAGES,
 *    文档 §7 明确不申请) → **降级直接显示内层完整包名**(Shizuku dumpsys 已拿到,内测版明确要看到具体 App);
 *  - 任何异常/空值均降级,不抛异常、不泄露更多信息。
 */
object AppAttribution {

    /**
     * @param innerPkg 传感器客户端内层包名(可能为空,如系统占位)
     * @param uid      客户端 uid
     * @return 内测版(internal)返回归属串;商店版(store)或无法解析返回 null。
     */
    fun resolve(context: Context, innerPkg: String?, uid: Int): String? {
        if (!BuildConfig.IS_INTERNAL) return null
        if (innerPkg.isNullOrBlank()) return null
        // 优先：宿主解析(嵌套 SDK 归属于宿主 App)
        val hostResolved = try {
            val pm = context.packageManager
            val pkgs = pm.getPackagesForUid(uid).orEmpty()
            val host = pkgs.firstOrNull { innerPkg.startsWith(it) } ?: pkgs.firstOrNull()
            val label = host?.let {
                try { pm.getApplicationInfo(it, 0).loadLabel(pm).toString() } catch (_: Exception) { it }
            }
            if (host != null) {
                buildString {
                    if (label != null) append(label)
                    append(" (").append(host).append(")")
                    if (innerPkg != host) append(" › SDK:").append(innerPkg)
                }
            } else null
        } catch (_: Exception) {
            null
        }
        if (!hostResolved.isNullOrBlank()) return hostResolved
        // 降级：内测版直接显示 Shizuku 拿到的完整包名(用户明确需要看到具体 App)
        return innerPkg
    }
}