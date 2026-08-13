package com.yuexiao12.sensorguard.enums

/** 与 schemas/sensorguard.fbs 中 OpKind/Phase 枚举值保持一致,勿单独修改。*/
object SgEnum {
    const val OP_RECORD_AUDIO = 0
    const val OP_CAMERA = 1
    const val OP_FINE_LOCATION = 2
    const val OP_ACCEL = 10
    const val OP_GYRO = 11
    const val OP_MAG = 12
    const val OP_BARO = 13
    const val OP_LIGHT = 14
    const val OP_PROX = 15
    // P3 (文档 §2 威胁面):蓝牙扫描为独立威胁面;fbs OpKind 冻结(16+保留给未来),故取 20
    // 仅用于 Kotlin 侧时间线/告警展示与干预路由,不进入 Rust 规则引擎。
    const val OP_BT_SCAN = 20

    const val PHASE_START = 0
    const val PHASE_STOP = 1
    const val PHASE_TICK = 2

    const val VERDICT_LEGIT = 0
    const val VERDICT_OBSERVE = 1
    const val VERDICT_ALERT = 2

    const val CAT_NONE = 0
    const val CAT_OUT_OF_SCOPE = 1
    const val CAT_STEALTH_HOURS = 2
    const val CAT_SIDE_CHANNEL = 3
    const val CAT_FINGERPRINT = 4

    const val TIER_T0_BASIC = 0
    const val TIER_T1_STANDARD = 1
    const val TIER_T2_ENHANCED = 2
}