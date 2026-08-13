package com.yuexiao12.sensorguard.probe

import com.yuexiao12.sensorguard.jni.SgEnum

/**
 * Shizuku 精确归因 (文档 §4 P4:T2 增强,独立插件)。
 *
 * 解析 `dumpsys sensorservice` 输出,提取每个活跃传感器连接的
 * 精确 uid + 包名 + 采样率,并映射到 SgEnum 的 OpKind。
 *
 * 从 T0 的"存在未知采样方"升级为 T2 的"精确到 uid 的应用归因",
 * 消除 KS 检验只能推断、不能定位来源的盲区。
 *
 * 输入格式(真机实测,SensorService 标准 dumpsys):
 *   `> 14 active connections`
 *   `  Connection Number: 0`
 *   `    Operating Mode: NORMAL`
 *   `> 21:49:26 + 0x010000bf pid=29050 uid=10346 package=com.x.y samplingPeriod=20000us batchingPeriod=0us`
 *   `> 21:49:26 - 0x010000bf pid=29050 uid=10346 package=com.x.y`   (停止行,忽略)
 *   ...
 *   `0x0100000b) lsm6dso Accelerometer | STMicro | type: android.sensor.accelerometer(1) | ...`
 */
object SensorServiceParser {

    /** 单条活跃传感器连接(精确归因结果)。*/
    data class SensorClient(
        val uid: Int,
        val packageName: String,
        val op: Int,          // SgEnum.OP_* (ACCEL/GYRO/MAG/BARO/LIGHT/PROX)
        val samplingPeriodUs: Long,
        /** 具体传感器显示名(如 "lsm6dso Accelerometer"),来自 Sensor List 段;用于时间线展示。*/
        val sensorName: String = "",
    )

    /** Android sensor 类型值 → SgEnum op。仅映射敏感传感器(§2 威胁面)。*/
    private fun sensorTypeToOp(type: Int): Int? = when (type) {
        1 -> SgEnum.OP_ACCEL   // TYPE_ACCELEROMETER
        2 -> SgEnum.OP_MAG     // TYPE_MAGNETIC_FIELD
        4 -> SgEnum.OP_GYRO    // TYPE_GYROSCOPE
        5 -> SgEnum.OP_LIGHT   // TYPE_LIGHT
        6 -> SgEnum.OP_BARO    // TYPE_PRESSURE
        8 -> SgEnum.OP_PROX    // TYPE_PROXIMITY
        else -> null
    }

    /**
     * 解析完整 dumpsys sensorservice 输出。
     * @return 活跃的精确归因连接(仅敏感传感器,uid > 0)]
     */
    fun parse(output: String): List<SensorClient> {
        // 第一遍:建立 sensor handle (0x...) → (type, 显示名) 映射
        // 行形如: `0x0100000b) lsm6dso Accelerometer Non-wakeup | STMicro | ... type: android.sensor.accelerometer(1) | ...`
        val handleToType = HashMap<String, Int>()
        val handleToName = HashMap<String, String>()
        for (line in output.lineSequence()) {
            val handle = handleOf(line) ?: continue
            val type = sensorTypeOf(line) ?: continue
            handleToType[handle] = type
            // 显示名 = `)` 之后、`|` 之前的部分(如 "lsm6dso Accelerometer Non-wakeup")
            val name = nameOf(line)
            if (name.isNotEmpty()) handleToName[handle] = name
        }

        // 第二遍:解析活跃连接行(带 samplingPeriod= 的 + 行)
        val result = ArrayList<SensorClient>()
        for (line in output.lineSequence()) {
            if (!line.contains("samplingPeriod=")) continue
            val m = CONNECTION_RE.find(line) ?: continue
            val handle = m.groupValues[1]
            val uid = m.groupValues[2].trim().toIntOrNull() ?: continue
            val pkg = m.groupValues[3]
            val periodUs = m.groupValues[4].trim().toLongOrNull() ?: continue
            val type = handleToType[handle] ?: continue
            val op = sensorTypeToOp(type) ?: continue
            result.add(SensorClient(uid, pkg, op, periodUs, handleToName[handle] ?: ""))
        }
        return result.distinct()
    }

    private fun handleOf(line: String): String? {
        val h = HANDLE_RE.find(line) ?: return null
        return h.groupValues[1].lowercase()
    }

    private fun sensorTypeOf(line: String): Int? {
        val t = TYPE_RE.find(line) ?: return null
        return t.groupValues[1].toIntOrNull()
    }

    /** 从 Sensor List 行提取传感器显示名:`)` 之后、`|` 之前的部分。无 `|`(如 Sensor Device 段)返回空串。*/
    private fun nameOf(line: String): String {
        val m = NAME_RE.find(line) ?: return ""
        return m.groupValues[1].trim()
    }

    // 连接行: handle、uid、package、samplingPeriod(pid/uid 数值前可能有空格)
    private val CONNECTION_RE = Regex(
        "0x([0-9a-fA-F]+)\\s+pid=\\s*\\d+\\s+uid=\\s*(\\d+)\\s+package=(\\S+)\\s+samplingPeriod=(\\d+)us"
    )
    // sensor 列表行: 0x0100000b) ... type: android.sensor.accelerometer(1)
    private val HANDLE_RE = Regex("^\\s*0x([0-9a-fA-F]+)\\)")
    private val TYPE_RE = Regex("type:\\s+android\\.sensor\\.[a-z_]+?\\((\\d+)\\)")
    // 传感器显示名: handle `)` 之后到首个 `|` 之前(如 "lsm6dso Accelerometer Non-wakeup")
    private val NAME_RE = Regex("^\\s*0x[0-9a-fA-F]+\\)\\s+(.+?)\\s*\\|")
}