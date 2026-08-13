package com.tabbit.sensorguard.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CounterStoreTest {

    // ---------- MemoryCounterStore(与 ESP 相同单调语义,见各探针注释) ----------

    @Test
    fun `memory counter monotonic from 1`() {
        val store = MemoryCounterStore()
        assertEquals(1, store.next("a"))
        assertEquals(2, store.next("a"))
        assertEquals(3, store.next("a"))
    }

    @Test
    fun `memory counter names independent`() {
        val store = MemoryCounterStore()
        store.next("a"); store.next("b")
        assertEquals(2, store.next("a"))
        assertEquals(2, store.next("b"))
    }

    // ---------- CounterGuard:(key_id, counter) 唯一断言(§8.2) ----------

    @Test
    fun `guard allows strictly monotonic counters per key`() {
        val g = CounterGuard()
        g.check(1, 1)
        g.check(1, 2)
        g.check(2, 1) // 不同 key_id 独立
        g.check(2, 5)
    }

    @Test
    fun `guard rejects replay of counter`() {
        val g = CounterGuard()
        g.check(1, 100)
        // 重放同一个 counter -> 违反唯一性断言,立即 Safe Mode
        assertThrows(SafeModeException::class.java) { g.check(1, 100) }
        assertThrows(SafeModeException::class.java) { g.check(1, 50) }
    }

    @Test
    fun `guard reset clears sequences`() {
        val g = CounterGuard()
        g.check(1, 100)
        g.reset()
        g.check(1, 1) // 重置后从 1 重新开始(遗忘权 wipeAll 场景)
    }

    @Test
    fun `guard rejects counter decreasing across reset boundary`() {
        val g = CounterGuard()
        g.check(1, 10)
        g.reset()
        g.check(1, 5) // 允许(重置后独立会话)
        assertThrows(SafeModeException::class.java) { g.check(1, 5) }
    }
}