package com.tabbit.sensorguard.jni

import android.util.Log
import com.tabbit.sensorguard.logic.SystemHealth

object SgErrors {
    const val E_OK = 0
    const val E_INVALID_ARG = -1
    const val E_BUF_TOO_SMALL = -2
    const val E_STATE = -3
    const val E_INTERNAL = -4
    const val E_RESOURCE = -5
    const val E_PANIC = -6

    /**
     * W8 (文档 §10): Health 状态机接线点(GuardService.onCreate 注入)。
     * 空时为无操作(单测/早期阶段不崩)。
     */
    @Volatile var health: SystemHealth? = null

    fun check(tag: String, rc: Int) {
        if (rc != E_OK && rc >= 0) return
        if (rc == E_OK) return
        Log.w("SG", "$tag failed rc=$rc")
        // W8 (文档 §10): 接入 Health 状态机 —— 同错累计 ≥100 次升级 DEGRADED,
        // panic(E_PANIC) 直接进 SAFE_MODE;DEGRADED 下同错再累计 → SAFE_MODE。
        health?.onError(rc)
    }
}
