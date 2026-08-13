package com.yuexiao12.sensorguard.benchmark

import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import android.content.Intent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P4-4 (文档 §13):冷启动性能回归基准。
 *
 * 门禁: MainActivity 冷启动 P95 ≤ 500 ms(骁龙 6 Gen1,文档 §13 表)。
 * 运行: ./gradlew :benchmark:connectedAndroidTest
 * 结果输出: 报告中的 macrobenchmark 结果(需 adb + profileable app)。
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class ColdStartBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /** 冷启动: pressHome 清栈后启动 MainActivity,测启动时间(P95 ≤ 500ms)。 */
    @Test
    fun startup() = benchmarkRule.measureRepeated(
        packageName = "com.yuexiao12.sensorguard",
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        setupBlock = {
            // 冷启动要求: pressHome 清前台
            pressHome()
        },
        measureBlock = {
            startActivityAndWait(
                Intent().setClassName(
                    "com.yuexiao12.sensorguard",
                    "com.yuexiao12.sensorguard.MainActivity"
                )
            )
        }
    )
}