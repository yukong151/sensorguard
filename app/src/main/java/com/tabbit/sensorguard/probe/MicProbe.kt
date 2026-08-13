package com.tabbit.sensorguard.probe

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tabbit.sensorguard.jni.SgEnum

/**
 * 麦克风探针(T0)。
 *
 * 文档 §11 前提两次偏差,均已按公共 API 事实落地:
 * 1. AppOpsManager.OnOpNotedCallback 仅监听本 App 自身访问,第三方 App 无法监听他应用 op;
 * 2. AudioRecordingConfiguration 在 android-34 公共 stub 中无 getClientUid()
 *    (AOSP 中为 @SystemApi/@hide,非系统应用编译不可见,反射亦命中 hidden-API 黑名单),
 *    即麦克风占用**无法归因 uid**。
 *
 * 等效替代:AudioManager.getActiveRecordingConfigurations() + registerAudioRecordingCallback
 * 为公开 API,无需 RECORD_AUDIO 权限即可感知设备当前录音会话 start/stop。
 * 事件按 T0 语义上报(uid=-1、pkgHash 全 0),时间线展示"麦克风占用/释放";
 * T1 归因留待 W8 可选项:用户授权的 UsageStats(前景 App 与录音会话时间相关,非权限强制)。
 *
 * 通过活跃录音会话集合 diff 推断 START/STOP;无 uid 归因,会话集合非空即"占用"。
 */
class MicProbe(private val context: Context) : Probe {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            sync(configs)
        }
    }

    @Volatile private var sink: ProbeSink? = null
    private var active = false                       // 当前是否有录音会话
    private var started = false

    override fun start(sink: ProbeSink) {
        this.sink = sink
        if (started) return
        started = true
        runCatching { audioManager.registerAudioRecordingCallback(callback, handler) }
            .onFailure { Log.w(TAG, "mic probe 注册失败", it) }
        // 显式初始同步,捕获注册前已进行的录音(去重交给 active)
        sync(audioManager.activeRecordingConfigurations)
    }

    override fun stop() {
        started = false
        sink = null
        runCatching { audioManager.unregisterAudioRecordingCallback(callback) }
        active = false
    }

    private fun sync(configs: List<AudioRecordingConfiguration>) {
        val sinkNow = sink ?: return
        val nowActive = configs.isNotEmpty()
        if (nowActive && !active) emit(sinkNow, SgEnum.PHASE_START)
        if (!nowActive && active) emit(sinkNow, SgEnum.PHASE_STOP)
        active = nowActive
    }

    /** 无 uid 归因:T0 语义,uid=-1、pkgHash 全 0。*/
    private fun emit(sink: ProbeSink, phase: Int) {
        sink.onProbeEvent(
            ProbeEvent(
                tsNs = wallClockNs(),
                uid = -1,
                pkgName = null,
                pkgHash = ByteArray(12),
                op = SgEnum.OP_RECORD_AUDIO,
                phase = phase,
                tier = SgEnum.TIER_T0_BASIC,
                source = "MIC",
            )
        )
    }

    private companion object {
        const val TAG = "SgMicProbe"
    }
}