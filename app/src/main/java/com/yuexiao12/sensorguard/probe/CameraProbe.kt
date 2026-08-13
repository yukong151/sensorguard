package com.yuexiao12.sensorguard.probe

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.yuexiao12.sensorguard.jni.SgEnum

/**
 * 摄像头探针(T0)。
 *
 * 文档 §11 偏差:CameraManager.registerAvailabilityCallback 无需 CAMERA 权限;
 * onCameraUnavailable 在任意进程占用摄像头时触发,onCameraAvailable 在释放时触发。
 * 该 API 不暴露占用者 uid,无法归因 -> uid=-1、pkgHash=全 0、tier=T0_BASIC(文档 T0 语义)。
 *
 * 注册时系统会立即回调各摄像头当前状态,因此可捕获注册前已进行的占用。
 */
class CameraProbe(private val context: Context) : Probe {

    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val callback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) = emit(cameraId, SgEnum.PHASE_STOP)
        override fun onCameraUnavailable(cameraId: String) = emit(cameraId, SgEnum.PHASE_START)
    }

    @Volatile private var sink: ProbeSink? = null
    private val unavailableCams = HashSet<String>()   // 当前被占用的摄像头 id
    private var started = false

    override fun start(sink: ProbeSink) {
        this.sink = sink
        if (started) return
        started = true
        runCatching { cameraManager.registerAvailabilityCallback(callback, handler) }
            .onFailure { Log.w(TAG, "camera probe 注册失败", it) }
    }

    override fun stop() {
        started = false
        sink = null
        runCatching { cameraManager.unregisterAvailabilityCallback(callback) }
        unavailableCams.clear()
    }

    private fun emit(cameraId: String, phase: Int) {
        val sinkNow = sink ?: return
        if (phase == SgEnum.PHASE_START) {
            if (!unavailableCams.add(cameraId)) return   // 已占用,去重
        } else {
            if (!unavailableCams.remove(cameraId)) return // 已释放,去重
        }
        sinkNow.onProbeEvent(
            ProbeEvent(
                tsNs = wallClockNs(),
                uid = -1,
                pkgName = null,
                pkgHash = ByteArray(12),
                op = SgEnum.OP_CAMERA,
                phase = phase,
                tier = SgEnum.TIER_T0_BASIC,
                source = "CAMERA",
            )
        )
    }

    private companion object {
        const val TAG = "SgCamProbe"
    }
}