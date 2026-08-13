package com.tabbit.sensorguard.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemHealthTest {

    private class FakeClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
        fun advance(ms: Long) { now += ms }
    }

    // ---------- 一级/二级: 同错累计(§10) ----------

    @Test
    fun `ok stays ok below same-error threshold`() {
        val clock = FakeClock()
        val h = SystemHealth(clock)
        assertEquals(HealthLevel.OK, h.level())
        // 99 次同错仍是 OK
        repeat(SystemHealth.SAME_ERROR_THRESHOLD - 1) { h.onError(-1) }
        assertEquals(HealthLevel.OK, h.level())
    }

    @Test
    fun `same error at threshold escalates to degraded`() {
        val h = SystemHealth(FakeClock())
        val rc = (0 until SystemHealth.SAME_ERROR_THRESHOLD)
            .fold(HealthLevel.OK) { _, _ -> h.onError(-1) }
        assertEquals(HealthLevel.DEGRADED, rc)
        assertEquals(HealthLevel.DEGRADED, h.level())
    }

    @Test
    fun `different errors reset same-error counter`() {
        val h = SystemHealth(FakeClock())
        repeat(SystemHealth.SAME_ERROR_THRESHOLD - 1) { h.onError(-1) }
        h.onError(-2) // 换错 → 计数重置
        h.onError(-1)
        assertEquals(HealthLevel.OK, h.level())
    }

    @Test
    fun `success resets same-error counter`() {
        val h = SystemHealth(FakeClock())
        repeat(SystemHealth.SAME_ERROR_THRESHOLD - 1) { h.onError(-1) }
        h.onSuccess()
        repeat(SystemHealth.SAME_ERROR_THRESHOLD - 1) { h.onError(-1) }
        assertEquals(HealthLevel.OK, h.level())
    }

    @Test
    fun `degraded re-accumulates to safe mode`() {
        val h = SystemHealth(FakeClock())
        // 一级: 100 次同错 → DEGRADED
        repeat(SystemHealth.SAME_ERROR_THRESHOLD) { h.onError(-1) }
        assertEquals(HealthLevel.DEGRADED, h.level())
        // 二级: 继续同错再 100 次 → SAFE_MODE(三级)
        repeat(SystemHealth.SAME_ERROR_THRESHOLD) { h.onError(-1) }
        assertEquals(HealthLevel.SAFE_MODE, h.level())
    }

    @Test
    fun `panic escalates directly to safe mode`() {
        val h = SystemHealth(FakeClock())
        assertEquals(HealthLevel.SAFE_MODE, h.onError(SystemHealth.E_PANIC))
        assertEquals(HealthLevel.SAFE_MODE, h.level())
    }

    @Test
    fun `safe mode ignores further onError`() {
        val h = SystemHealth(FakeClock())
        h.onError(SystemHealth.E_PANIC)
        repeat(10) { h.onError(-1) }
        assertEquals(HealthLevel.SAFE_MODE, h.level())
    }

    // ---------- 三级: 指数退避自愈(§10) ----------

    @Test
    fun `no heal attempt before first interval`() {
        val clock = FakeClock(1_000_000L)
        val h = SystemHealth(clock)
        h.onError(SystemHealth.E_PANIC) // → SAFE_MODE, nextHealAt = now + 10min
        assertEquals(false, h.shouldAttemptHeal())
        clock.advance(9 * 60_000L)
        assertEquals(false, h.shouldAttemptHeal())
    }

    @Test
    fun `heal attempt allowed at 10min then success recovers`() {
        val clock = FakeClock(0L)
        val h = SystemHealth(clock)
        h.onError(SystemHealth.E_PANIC)
        clock.advance(11 * 60_000L)
        assertEquals(true, h.shouldAttemptHeal())
        h.reportHealResult(true)
        assertEquals(HealthLevel.OK, h.level())
        // 恢复后不应再自愈
        assertEquals(false, h.shouldAttemptHeal())
    }

    @Test
    fun `heal failures back off 10m 30m 2h then stop`() {
        val clock = FakeClock(0L)
        val h = SystemHealth(clock)
        h.onError(SystemHealth.E_PANIC) // nextHealAt = +10min

        clock.advance(10 * 60_000L)
        h.reportHealResult(false) // 失败 #1 → next = +30min
        assertEquals(HealthLevel.SAFE_MODE, h.level())
        assertEquals(false, h.shouldAttemptHeal()) // 30min 未到

        clock.advance(30 * 60_000L)
        assertEquals(true, h.shouldAttemptHeal())
        h.reportHealResult(false) // 失败 #2 → next = +2h
        assertEquals(false, h.shouldAttemptHeal())

        clock.advance(2 * 3_600_000L)
        assertEquals(true, h.shouldAttemptHeal())
        h.reportHealResult(false) // 失败 #3 → 累计 3 次,进入 DEAD 终态(文档 §10)
        assertEquals(false, h.shouldAttemptHeal())

        // DEAD 终态: 自愈间隔用尽,永不自动自愈
        clock.advance(24 * 3_600_000L)
        assertEquals(false, h.shouldAttemptHeal())
        assertEquals(HealthLevel.DEAD, h.level())
    }

    @Test
    fun `three heal failures enter dead terminal state`() {
        val clock = FakeClock(0L)
        val h = SystemHealth(clock)
        h.onError(SystemHealth.E_PANIC)
        // 10min → 30min → 2h 三次失败(第 3 次即 DEAD,无需等后一次)
        clock.advance(10 * 60_000L); h.reportHealResult(false)
        clock.advance(30 * 60_000L); h.reportHealResult(false)
        assertEquals(HealthLevel.SAFE_MODE, h.level())
        clock.advance(2 * 3_600_000L)
        assertEquals(true, h.shouldAttemptHeal())
        h.reportHealResult(false)
        assertEquals(HealthLevel.DEAD, h.level())
        // DEAD 终态: 不再自愈、错误不改状态
        assertEquals(false, h.shouldAttemptHeal())
        h.onError(-1)
        assertEquals(HealthLevel.DEAD, h.level())
    }

    @Test
    fun `reset returns to ok`() {
        val h = SystemHealth(FakeClock())
        h.onError(SystemHealth.E_PANIC)
        h.reportHealResult(false)
        assertEquals(HealthLevel.SAFE_MODE, h.level())
        assertEquals(HealthLevel.OK, h.reset())
        assertEquals(HealthLevel.OK, h.level())
        // 复位后可重新累计
        repeat(SystemHealth.SAME_ERROR_THRESHOLD) { h.onError(-1) }
        assertEquals(HealthLevel.DEGRADED, h.level())
    }

    @Test
    fun `isDegradedOrWorse reflects states`() {
        val clock = FakeClock(0L)
        val h = SystemHealth(clock)
        assertEquals(false, h.isDegradedOrWorse())
        repeat(SystemHealth.SAME_ERROR_THRESHOLD) { h.onError(-1) }
        assertEquals(true, h.isDegradedOrWorse()) // DEGRADED
        h.onError(SystemHealth.E_PANIC)
        assertEquals(true, h.isDegradedOrWorse()) // SAFE_MODE
        h.reset()
        assertEquals(false, h.isDegradedOrWorse())
    }
}