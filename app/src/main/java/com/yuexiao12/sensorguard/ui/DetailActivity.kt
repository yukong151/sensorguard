package com.yuexiao12.sensorguard.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.yuexiao12.sensorguard.BuildConfig
import com.yuexiao12.sensorguard.R
import com.yuexiao12.sensorguard.databinding.ActivityDetailBinding
import com.yuexiao12.sensorguard.enums.SgEnum
import com.yuexiao12.sensorguard.jni.VerdictEntryData
import com.yuexiao12.sensorguard.logic.ActionRouter
import com.yuexiao12.sensorguard.probe.ProbeEvent
import com.yuexiao12.sensorguard.service.GuardService
import com.yuexiao12.sensorguard.ui.AppAttribution
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * W8 (文档 §3): A3 风险详情 / 引导页 —— 展示单条告警全字段,
 * 并按文档 §6 干预路由表提供深链按钮(麦克风/摄像头 → 隐私设置页;IMU → 传感器引导)。
 *
 * 回归修复:同时支持"探针事件详情"模式 —— 点击时间线普通事件行跳转,展示
 * ProbeEvent 全字段(uid/包名/传感器/相位/来源/采样周期),无干预路由按钮。
 * 告警模式(默认)保留原有字段与深链。
 *
 * 数据经 Intent 按字段传递(Primitive/Bool/String),避免把纯 JVM 的
 * VerdictEntryData/ProbeEvent 拉进 Parcelable 序列化(jni 层保持无 android 依赖,
 * 见 VerdictReader.kt 由 JVM 单测覆盖的既有约定)。
 */
class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding
    private var intervention: ActionRouter.Intervention? = null
    private var pkgHashHex: String = "" // P3: 卸载深链反查包名用

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val ev = readEventExtra()
        if (ev != null) {
            binding.tvDetailTitle.setText(R.string.ui_title_event_detail)
            binding.tvVerdict.text = formatEvent(ev)
            // 事件模式无干预路由,隐藏按钮与"无干预"提示
            binding.btnIntervention.visibility = View.GONE
            binding.tvInterventionNone.visibility = View.GONE
            return
        }

        val v = readVerdictExtra()
        if (v == null) {
            binding.tvVerdict.text = "无告警数据"
            return
        }
        binding.tvVerdict.text = formatVerdict(v)
        pkgHashHex = v.pkgHash.joinToString("") { "%02X".format(it) }

        // W8 (文档 §6): 干预路由深链 —— GuardService.interventionFor 即 ActionRouter.resolve
        intervention = GuardService.instance?.interventionFor(v) ?: ActionRouter.resolve(v.op)
        val i = intervention
        if (i == null) {
            binding.tvInterventionNone.visibility = View.VISIBLE
        } else {
            binding.btnIntervention.visibility = View.VISIBLE
            binding.btnIntervention.text = when (i.kind) {
                ActionRouter.InterventionKind.PRIVACY_MIC ->
                    getString(R.string.ui_intervention_mic)
                ActionRouter.InterventionKind.PRIVACY_CAMERA ->
                    getString(R.string.ui_intervention_camera)
                ActionRouter.InterventionKind.SENSOR_GUIDE ->
                    getString(R.string.ui_intervention_sensor)
                // P3 (文档 §5.5):蓝牙扫描高频 → 一键卸载入口
                ActionRouter.InterventionKind.UNINSTALL ->
                    getString(R.string.ui_intervention_uninstall)
            }
            binding.btnIntervention.setOnClickListener { launchIntervention(i) }
        }
    }

    /** 文档 §6 / P3: 深链 Intent → 系统隐私页(无额外权限);UNINSTALL 需拼包名 data URI。*/
    private fun launchIntervention(i: ActionRouter.Intervention) {
        val intent: Intent = if (i.kind == ActionRouter.InterventionKind.UNINSTALL) {
            // P3: 卸载入口需真实包名 —— 从告警归因反查;无包名则退化为设置页
            val pkg = (GuardService.instance?.attributionFor(pkgHashHex))?.first ?: ""
            if (pkg.isNotBlank()) {
                Intent(i.intentAction, android.net.Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                Intent(ActionRouter.ACTION_PRIVACY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(i.intentAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "未找到可处理该引导的系统组件", Toast.LENGTH_LONG).show()
        }
    }

    private fun readVerdictExtra(): VerdictEntryData? {
        val windowStartNs = intent.getLongExtra(EXTRA_WINDOW_START, 0L)
        if (windowStartNs == 0L) return null
        val pkgHashHex = intent.getStringExtra(EXTRA_PKG_HASH) ?: ""
        val pkgHash = ByteArray(12)
        if (pkgHashHex.length == 24) {
            for (i in 0 until 12) {
                pkgHash[i] = pkgHashHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
        return VerdictEntryData(
            kind = intent.getIntExtra(EXTRA_KIND, SgEnum.VERDICT_LEGIT),
            category = intent.getIntExtra(EXTRA_CATEGORY, SgEnum.CAT_NONE),
            severity = intent.getIntExtra(EXTRA_SEVERITY, 0),
            sCtx = intent.getFloatExtra(EXTRA_S_CTX, 0f),
            ruleId = intent.getIntExtra(EXTRA_RULE_ID, 0),
            top3 = emptyList(),
            windowStartNs = windowStartNs,
            windowEndNs = intent.getLongExtra(EXTRA_WINDOW_END, 0L),
            evidenceTier = intent.getIntExtra(EXTRA_TIER, SgEnum.TIER_T0_BASIC),
            pkgHash = pkgHash,
            op = intent.getIntExtra(EXTRA_OP, SgEnum.OP_RECORD_AUDIO),
            degraded = intent.getBooleanExtra(EXTRA_DEGRADED, false),
        )
    }

    /** 事件模式:从 Intent extra 重建 ProbeEvent。事件详情为辅助审计,无 Intervention 路由。*/
    private fun readEventExtra(): ProbeEvent? {
        val tsNs = intent.getLongExtra(EXTRA_EV_TS, 0L)
        if (tsNs == 0L) return null
        val pkgHash = ByteArray(12)
        val hex = intent.getStringExtra(EXTRA_EV_PKG_HASH) ?: ""
        if (hex.length == 24) {
            for (i in 0 until 12) {
                pkgHash[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
        return ProbeEvent(
            tsNs = tsNs,
            uid = intent.getIntExtra(EXTRA_EV_UID, -1),
            pkgName = intent.getStringExtra(EXTRA_EV_PKG),
            pkgHash = pkgHash,
            op = intent.getIntExtra(EXTRA_EV_OP, SgEnum.OP_RECORD_AUDIO),
            phase = intent.getIntExtra(EXTRA_EV_PHASE, SgEnum.PHASE_START),
            tier = intent.getIntExtra(EXTRA_EV_TIER, SgEnum.TIER_T0_BASIC),
            source = intent.getStringExtra(EXTRA_EV_SOURCE) ?: "",
            samplingPeriodUs = intent.getLongExtra(EXTRA_EV_PERIOD, 0L),
            sensorName = intent.getStringExtra(EXTRA_EV_SENSOR) ?: "",
        )
    }

    /** 事件模式全字段展示(无干预路由)。*/
    private fun formatEvent(ev: ProbeEvent): String {
        val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        val hex = ev.pkgHash.joinToString("") { "%02X".format(it) }
        val who = if (BuildConfig.IS_INTERNAL) {
            AppAttribution.resolve(this, ev.pkgName, ev.uid)
                ?: if (ev.pkgName.isNullOrBlank()) "uid=${ev.uid}" else ev.pkgName
        } else {
            "某应用"
        }
        val sb = StringBuilder()
        sb.append("时间    ").append(timeFmt.format(Date(ev.tsNs / 1_000_000L))).append('\n')
        sb.append("操作    ").append(opName(ev.op)).append('\n')
        sb.append("传感器  ").append(if (ev.sensorName.isNotBlank()) ev.sensorName else opName(ev.op)).append('\n')
        sb.append("相位    ").append(if (ev.phase == SgEnum.PHASE_START) "开始" else if (ev.phase == SgEnum.PHASE_STOP) "停止" else "持续").append('\n')
        sb.append("来源    ").append(if (ev.source.isBlank()) "探针" else ev.source).append('\n')
        sb.append("等级    ").append(tierName(ev.tier)).append('\n')
        sb.append("UID     ").append(ev.uid).append('\n')
        sb.append("归属    ").append(who).append('\n')
        sb.append("采样周期 ").append(if (ev.samplingPeriodUs > 0) "${ev.samplingPeriodUs}us" else "未知").append('\n')
        sb.append("包指纹  ").append(hex)
        return sb.toString()
    }

    private fun formatVerdict(v: VerdictEntryData): String {        val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        val pkgHashHex = v.pkgHash.joinToString("") { "%02X".format(it) }
        val sb = StringBuilder()
        sb.append("时间    ").append(timeFmt.format(Date(v.windowStartNs / 1_000_000L))).append('\n')
        sb.append("种类    ").append(kindName(v.kind)).append('\n')
        sb.append("类别    ").append(catName(v.category)).append('\n')
        sb.append("严重度  ").append(v.severity).append('\n')
        sb.append("规则    ").append("rule-").append(v.ruleId).append('\n')
        sb.append("操作    ").append(opName(v.op)).append('\n')
        sb.append("等级    ").append(tierName(v.evidenceTier)).append('\n')
        sb.append("降级    ").append(if (v.degraded) "是" else "否").append('\n')
        sb.append("包指纹  ").append(pkgHashHex).append('\n')
        // W12/T2 (内测版 internal):包指纹 → 宿主 App 归属;商店版(store)不展示任何身份标识
        val info = GuardService.instance?.attributionFor(pkgHashHex)
        val attr = info?.let { AppAttribution.resolve(this, it.first, it.second) }
        if (attr != null) sb.append("归属    ").append(attr)
        return sb.toString()
    }

    private fun kindName(k: Int): String = when (k) {
        SgEnum.VERDICT_LEGIT -> "LEGIT"
        SgEnum.VERDICT_OBSERVE -> "OBSERVE"
        SgEnum.VERDICT_ALERT -> "ALERT"
        else -> "kind$k"
    }

    private fun catName(c: Int): String = when (c) {
        SgEnum.CAT_OUT_OF_SCOPE -> "OUT_OF_SCOPE"
        SgEnum.CAT_STEALTH_HOURS -> "STEALTH_HOURS"
        SgEnum.CAT_SIDE_CHANNEL -> "SIDE_CHANNEL"
        SgEnum.CAT_FINGERPRINT -> "FINGERPRINT"
        else -> "NONE"
    }

    private fun tierName(t: Int): String = when (t) {
        SgEnum.TIER_T2_ENHANCED -> "T2(Shizuku)"
        SgEnum.TIER_T1_STANDARD -> "T1"
        else -> "T0"
    }

    private fun opName(op: Int): String = when (op) {
        SgEnum.OP_RECORD_AUDIO -> "MIC"
        SgEnum.OP_CAMERA -> "CAM"
        SgEnum.OP_FINE_LOCATION -> "LOC"
        SgEnum.OP_ACCEL -> "ACCEL"
        SgEnum.OP_GYRO -> "GYRO"
        SgEnum.OP_MAG -> "MAG"
        else -> "op$op"
    }

    companion object {
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_CATEGORY = "category"
        private const val EXTRA_SEVERITY = "severity"
        private const val EXTRA_S_CTX = "sCtx"
        private const val EXTRA_RULE_ID = "ruleId"
        private const val EXTRA_WINDOW_START = "windowStartNs"
        private const val EXTRA_WINDOW_END = "windowEndNs"
        private const val EXTRA_TIER = "evidenceTier"
        private const val EXTRA_PKG_HASH = "pkgHash"
        private const val EXTRA_OP = "op"
        private const val EXTRA_DEGRADED = "degraded"
        // 事件详情模式字段
        private const val EXTRA_EV_TS = "ev_tsNs"
        private const val EXTRA_EV_UID = "ev_uid"
        private const val EXTRA_EV_PKG = "ev_pkg"
        private const val EXTRA_EV_PKG_HASH = "ev_pkgHash"
        private const val EXTRA_EV_OP = "ev_op"
        private const val EXTRA_EV_PHASE = "ev_phase"
        private const val EXTRA_EV_TIER = "ev_tier"
        private const val EXTRA_EV_SOURCE = "ev_source"
        private const val EXTRA_EV_PERIOD = "ev_period"
        private const val EXTRA_EV_SENSOR = "ev_sensor"

        /** A2 时间线点击告警行跳转入口(按字段携带,避免 VerdictEntryData Parcelable 化)。*/
        fun intent(context: Context, v: VerdictEntryData): Intent =
            Intent(context, DetailActivity::class.java)
                .putExtra(EXTRA_KIND, v.kind)
                .putExtra(EXTRA_CATEGORY, v.category)
                .putExtra(EXTRA_SEVERITY, v.severity)
                .putExtra(EXTRA_S_CTX, v.sCtx)
                .putExtra(EXTRA_RULE_ID, v.ruleId)
                .putExtra(EXTRA_WINDOW_START, v.windowStartNs)
                .putExtra(EXTRA_WINDOW_END, v.windowEndNs)
                .putExtra(EXTRA_TIER, v.evidenceTier)
                .putExtra(EXTRA_PKG_HASH, v.pkgHash.joinToString("") { "%02X".format(it) })
                .putExtra(EXTRA_OP, v.op)
                .putExtra(EXTRA_DEGRADED, v.degraded)

        /** A2 时间线点击普通事件行跳转入口(事件详情模式,无干预路由)。*/
        fun eventIntent(context: Context, ev: ProbeEvent): Intent =
            Intent(context, DetailActivity::class.java)
                .putExtra(EXTRA_EV_TS, ev.tsNs)
                .putExtra(EXTRA_EV_UID, ev.uid)
                .putExtra(EXTRA_EV_PKG, ev.pkgName)
                .putExtra(EXTRA_EV_PKG_HASH, ev.pkgHash.joinToString("") { "%02X".format(it) })
                .putExtra(EXTRA_EV_OP, ev.op)
                .putExtra(EXTRA_EV_PHASE, ev.phase)
                .putExtra(EXTRA_EV_TIER, ev.tier)
                .putExtra(EXTRA_EV_SOURCE, ev.source)
                .putExtra(EXTRA_EV_PERIOD, ev.samplingPeriodUs)
                .putExtra(EXTRA_EV_SENSOR, ev.sensorName)
    }
}