package com.tabbit.sensorguard.probe

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.tabbit.sensorguard.jni.SgEnum
import com.tabbit.sensorguard.jni.SgNative

/**
 * W5 (文档 §5.2):传感器基线探针 —— 自采基线 HAL 竞争推断。
 *
 * 我方以 ~50 Hz 注册 accel/gyro(以及 mag/light/prox,对应规则表 R113~R116),
 * 把每个 `SensorEvent` 的 `timestamp`(纳秒,bootclock)连同轴值经 [SgNative.sgPushSensor]
 * 注入 Rust 核心的环形缓冲 RING。第三方以更高频率激活同一物理传感器时,HAL 切档,
 * 我方看到的 event.timestamp 抖动分布会系统性偏离自采基线,Rust 侧([sg_sensor_health])
 * 用两样本 KS 检验检出"存在第三方高频采样"(T0:未知来源)。
 *
 * 生命周期:start() 注册监听器(前台服务 onCreate),stop() 反注册(onDestroy)。
 * HIGH_SAMPLING_RATE_SENSORS 为 normal 权限(API31+ 安装即授予),仍 try/catch 防御
 * 无权限/无传感器场景,优雅跳过单个传感器,不阻断整体服务。
 *
 * 注意:本探针只喂原始样本,RING 由 Batch Tick(sg_tick)批量消费,不在热路径加锁,
 * 与文档 §5.3 性能预算(传感器热路径 ≤3µs)一致。
 */
class SensorBaselineProbe(private val context: Context) : Probe {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val listeners = LinkedHashMap<Int, SensorEventListener>()

    /** ~50 Hz 自采(文档 §5.2)。*/
    private val delayUs = SensorManager.SENSOR_DELAY_GAME

    override fun start(sink: ProbeSink) = start()
    fun start() {
        Log.i(TAG, "start() called, sensorManager=$sensorManager")
        if (sensorManager == null) {
            Log.w(TAG, "SensorManager 不可用,跳过传感器基线探针")
            return
        }
        register(Sensor.TYPE_ACCELEROMETER, SgEnum.OP_ACCEL)
        register(Sensor.TYPE_GYROSCOPE, SgEnum.OP_GYRO)
        register(Sensor.TYPE_MAGNETIC_FIELD, SgEnum.OP_MAG)
        register(Sensor.TYPE_LIGHT, SgEnum.OP_LIGHT)
        register(Sensor.TYPE_PROXIMITY, SgEnum.OP_PROX)
        Log.i(TAG, "传感器基线探针已启动:注册 ${listeners.size} 个传感器")
    }

    private fun register(type: Int, kind: Int) {
        val sensor = sensorManager?.getDefaultSensor(type) ?: run {
            Log.w(TAG, "传感器类型 $type 不可用,跳过")
            return
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                // event.timestamp 为纳秒(bootclock),与 Rust 侧时间基一致;
                // 轴值按 schema SensorSample {x,y,z} 注入(light/prox 仅 1 维,其余补 0)。
                val rc = runCatching {
                    SgNative.sgPushSensor(
                        e.timestamp,
                        kind.toByte(),
                        e.values.firstOrNull() ?: 0f,
                        e.values.getOrElse(1) { 0f },
                        e.values.getOrElse(2) { 0f },
                    )
                }.getOrDefault(SgErrors_E_PANIC)
                if (rc < 0) Log.w(TAG, "sgPushSensor kind=$kind rc=$rc")
            }

            override fun onAccuracyChanged(s: Sensor?, acc: Int) = Unit
        }
        try {
            sensorManager?.registerListener(listener, sensor, delayUs)
            listeners[type] = listener
        } catch (ex: Exception) {
            Log.w(TAG, "注册传感器 type=$type kind=$kind 失败", ex)
        }
    }

    override fun stop() {
        listeners.values.forEach { sensorManager?.unregisterListener(it) }
        listeners.clear()
        Log.i(TAG, "传感器基线探针已停止")
    }

    companion object {
        private const val TAG = "SG-Baseline"
        // SgErrors.E_PANIC 的本地镜像,避免 Probe 层反向依赖 SgErrors 的 check 路径。
        private const val SgErrors_E_PANIC = -6
    }
}
