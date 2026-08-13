package com.sensorguard.app.logic

import java.util.concurrent.atomic.AtomicReference

/**
 * 文档 §10 系统健康度(面向研发/日志),与威胁严重度(面向用户)严格分离。
 * OK / DEGRADED / SAFE_MODE / DEAD —— 描述本 App 自身异常。
 */
enum class HealthLevel { OK, DEGRADED, SAFE_MODE, DEAD }

/**
 * 文档 §10 系统健康度(面向研发/日志),与威胁严重度(面向用户)严格分离。
 * OK / DEGRADED / SAFE_MODE / DEAD —— 描述本 App 自身异常。
 *
 * 三级回退 + 指数退避自愈(全部为文档硬规格):
 *  1. 一级(单次丢弃): 连续 [SAME_ERROR_THRESHOLD] 次同错升级二级;
 *  2. 二级(DEGRADED): 调用方关闭对应模块,Verdict 标记 degraded;
 *     同错继续累积达到阈值 → 升级三级;
 *  3. 三级(SAFE_MODE): 只透传落库不判定;自愈间隔 [HEAL_DELAYS_MS]
 *     (10min → 30min → 2h → 停),累计 [MAX_HEAL_FAILURES] 次自愈失败
 *     进入 DEAD 终态,等待用户重启。
 *
 * 纯 JVM 逻辑,时钟注入 [nowMs] 以便单测;并发安全(单写线程 sg-tick 调度,
 * level 用 AtomicReference 兜底)。
 */
class SystemHealth(private val nowMs: () -> Long = System::currentTimeMillis) {

    companion object {
        const val SAME_ERROR_THRESHOLD = 100
        const val MAX_HEAL_FAILURES = 3

        /** 指数退避自愈间隔: 10min → 30min → 2h → 停(用尽后不再自动自愈)。*/
        val HEAL_DELAYS_MS = longArrayOf(
            10 * 60_000L,
            30 * 60_000L,
            2 * 3_600_000L,
        )

        /** 与 SgErrors.E_PANIC 对齐(避免指向 jni 包的循环依赖)。*/
        const val E_PANIC = -6
    }

    private val levelRef = AtomicReference(HealthLevel.OK)

    @Volatile private var lastErrorCode: Int = Int.MIN_VALUE
    @Volatile private var sameErrorCount = 0

    // SAFE_MODE 自愈状态
    @Volatile private var healFailures = 0
    @Volatile private var healIndex = 0
    @Volatile private var nextHealAtMs = 0L

    fun level(): HealthLevel = levelRef.get()

    /** SAFE_MODE 或 DEAD 时调用方应停止判定(只透传落库)。*/
    fun isDegradedOrWorse(): Boolean =
        levelRef.get() == HealthLevel.DEGRADED ||
            levelRef.get() == HealthLevel.SAFE_MODE ||
            levelRef.get() == HealthLevel.DEAD

    /**
     * 一级/二级回退入口。同错(相同 rc)连续累积;panic 直接进 SAFE_MODE。
     * DEAD 与 SAFE_MODE 下不改变状态(自愈完全由 tick 驱动,见 [shouldAttemptHeal])。
     */
    fun onError(rc: Int): HealthLevel {
        val cur = levelRef.get()
        if (cur == HealthLevel.DEAD || cur == HealthLevel.SAFE_MODE) return cur
        if (rc == E_PANIC) return enterSafeMode()
        if (rc != lastErrorCode) {
            lastErrorCode = rc
            sameErrorCount = 0
        }
        sameErrorCount++
        if (sameErrorCount < SAME_ERROR_THRESHOLD) return cur
        return when (cur) {
            HealthLevel.OK -> escalate(HealthLevel.DEGRADED)
            HealthLevel.DEGRADED -> enterSafeMode()
            else -> cur
        }
    }

    /** 成功上报: 清零同错计数(不改变当前等级)。*/
    fun onSuccess(): HealthLevel {
        lastErrorCode = Int.MIN_VALUE
        sameErrorCount = 0
        return levelRef.get()
    }

    /** 立即进入 SAFE_MODE(三级),初始化自愈计时。*/
    fun enterSafeMode(): HealthLevel {
        healFailures = 0
        healIndex = 0
        nextHealAtMs = nowMs() + HEAL_DELAYS_MS[0]
        // DEGRADED → SAFE_MODE 覆盖;OK → SAFE_MODE 由 panic 触发
        levelRef.set(HealthLevel.SAFE_MODE)
        return HealthLevel.SAFE_MODE
    }

    /**
     * 自愈判定(由外部 tick 周期性调用,如 batchTick)。
     * 到自愈时间且未停(healIndex 未用尽)时返回 true,调用方应执行一次
     * 判定恢复探测,并通过 [reportHealResult] 上报结果。
     */
    fun shouldAttemptHeal(now: Long = nowMs()): Boolean {
        if (levelRef.get() != HealthLevel.SAFE_MODE) return false
        if (healIndex >= HEAL_DELAYS_MS.size) return false // 已停
        return now >= nextHealAtMs
    }

    /**
     * 自愈探测结果上报。
     * success=true → 恢复 OK;false → 失败计数 +1,累计 [MAX_HEAL_FAILURES]
     * 次进入 DEAD 终态;否则退避到下一间隔(用尽后停在 SAFE_MODE)。
     */
    fun reportHealResult(success: Boolean) {
        if (levelRef.get() != HealthLevel.SAFE_MODE) return
        if (success) {
            levelRef.set(HealthLevel.OK)
            lastErrorCode = Int.MIN_VALUE
            sameErrorCount = 0
            healFailures = 0
            healIndex = 0
            return
        }
        healFailures++
        if (healFailures >= MAX_HEAL_FAILURES) {
            levelRef.set(HealthLevel.DEAD)
            return
        }
        healIndex++
        nextHealAtMs =
            if (healIndex < HEAL_DELAYS_MS.size) nowMs() + HEAL_DELAYS_MS[healIndex]
            else Long.MAX_VALUE // 停: 不再自动自愈
    }

    /** 手动复位(用户重启 / 应用重启),回到 OK。*/
    fun reset(): HealthLevel {
        levelRef.set(HealthLevel.OK)
        lastErrorCode = Int.MIN_VALUE
        sameErrorCount = 0
        healFailures = 0
        healIndex = 0
        nextHealAtMs = 0L
        return HealthLevel.OK
    }

    private fun escalate(level: HealthLevel): HealthLevel {
        levelRef.set(level)
        return level
    }
}