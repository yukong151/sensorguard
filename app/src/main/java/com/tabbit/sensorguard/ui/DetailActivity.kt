package com.tabbit.sensorguard.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tabbit.sensorguard.R
import com.tabbit.sensorguard.databinding.ActivityDetailBinding
import com.tabbit.sensorguard.jni.SgEnum
import com.tabbit.sensorguard.jni.VerdictEntryData
import com.tabbit.sensorguard.logic.ActionRouter
import com.tabbit.sensorguard.service.GuardService
import com.tabbit.sensorguard.ui.AppAttribution
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * W8 (文档 §3): A3 风险详情 / 引导页 —— 展示单条告警全字段,
 * 并按文档 §6 干预路由表提供深链按钮(麦克风/摄像头 → 隐私设置页;IMU → 传感器引导)。
 *
 * 数据经 Intent 按字段传递(Primitive/Bool/String),避免把纯 JVM 的
 * VerdictEntryData 拉进 Parcelable 序列化(jni 层保持无 android 依赖,
 * 见 VerdictReader.kt 由 JVM 单测覆盖的既有约定)。
 */
class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding
    private var intervention: ActionRouter.Intervention? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val v = readVerdictExtra()
        if (v == null) {
            binding.tvVerdict.text = "无告警数据"
            return
        }
        binding.tvVerdict.text = formatVerdict(v)

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
            }
            binding.btnIntervention.setOnClickListener { launchIntervention(i) }
        }
    }

    /** 文档 §6: 深链 Intent → 系统设置隐私页(无额外权限需求)。*/
    private fun launchIntervention(i: ActionRouter.Intervention) {
        val intent = Intent(i.intentAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

    private fun formatVerdict(v: VerdictEntryData): String {
        val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
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
    }
}