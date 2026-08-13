package com.yuexiao12.sensorguard.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.yuexiao12.sensorguard.BuildConfig
import com.yuexiao12.sensorguard.R
import com.yuexiao12.sensorguard.databinding.ActivityTimelineBinding
import com.yuexiao12.sensorguard.jni.SgEnum
import com.yuexiao12.sensorguard.jni.VerdictEntryData
import com.yuexiao12.sensorguard.probe.CtxProbe
import com.yuexiao12.sensorguard.probe.ProbeEvent
import com.yuexiao12.sensorguard.service.GuardService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * W8 (文档 §3): A2 事件时间线 —— 探针事件 + 告警(新→旧),每 2s 轮询;
 * 点击告警行 → A3 风险详情/引导页。ViewBinding 布局,无 Compose。
 *
 * 事件时间线增强(W12/T2):
 *  - 标题行展示 [操作] + 具体传感器名(如 "lsm6dso Accelerometer") + 相位(开始/停止);
 *  - 副标题展示归属:DEBUG 构建显示"宿主 App + 包名 + SDK 内部类"(见 AppAttribution),
 *    商店版(store flavor)仅显示"某应用"(不向终端用户明示包名,合规要求);内测版(internal)显示完整归属;
 *  - 告警行同样带归因(包指纹 → 内层包名/uid → 宿主 App)。
 */
class TimelineActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimelineBinding
    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** 当前展示项(与 adapter 顺序一致),点击告警行据此跳转 A3。*/
    private var items: List<TimelineItem> = emptyList()
    private var adapter: TimelineAdapter? = null

    /** 筛选开关:true=显示系统调用(默认,证明 App 真实有效),false=仅第三方 App。*/
    private var showSystem = true

    private val refresh = object : Runnable {
        override fun run() {
            refreshTimeline()
            handler.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimelineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (BuildConfig.IS_INTERNAL) {
            binding.tvTimelineTitle.text =
                "${getString(R.string.ui_title_timeline)}（内测版·含 App 归属）"
        }

        // W12/P0-1 增强:筛选开关。默认开启(显示系统调用),关闭则仅展示第三方 App。
        binding.swSystem.setOnCheckedChangeListener { _, isChecked ->
            showSystem = isChecked
            refreshTimeline()
        }

        binding.listTimeline.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val alert = items.getOrNull(position)?.alert
            if (alert != null) startActivity(DetailActivity.intent(this, alert))
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(refresh)
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        super.onStop()
    }

    /** 事件(新→旧)在前,告警以 ⚠ 前缀区分。showSystem=false 时隐藏系统调用。*/
    private fun refreshTimeline() {
        val gs = GuardService.instance ?: return
        val next = ArrayList<TimelineItem>()
        for (e in gs.recentEvents()) {
            val item = eventItem(gs, e)
            if (showSystem || !item.isSystem) next.add(item)
        }
        for (a in gs.recentAlerts()) {
            val item = alertItem(gs, a)
            if (showSystem || !item.isSystem) next.add(item)
        }
        items = next
        if (adapter == null) {
            adapter = TimelineAdapter(this, next)
            binding.listTimeline.adapter = adapter
        } else {
            adapter!!.clear()
            adapter!!.addAll(next)
            adapter!!.notifyDataSetChanged()
        }
    }

    private fun eventItem(gs: GuardService, e: ProbeEvent): TimelineItem {
        val opTag = opName(e.op)
        val sensor = if (e.sensorName.isNotBlank()) e.sensorName else opTag
        val phase = if (e.isStart) "开始" else "停止"
        val title = "[$opTag] $sensor · $phase"
        // 内测版(internal)显示归属;商店版(store)仅中性措辞
        val who = AppAttribution.resolve(this, e.pkgName, e.uid) ?: "某应用"
        val sub = "$who · ${fmtTs(e.tsNs)}"
        val isSys = CtxProbe.isSystemComponent(e.uid, e.pkgName)
        return TimelineItem(title, sub, null, isSys)
    }

    private fun alertItem(gs: GuardService, a: VerdictEntryData): TimelineItem {
        val title = "⚠ ${kindName(a.kind)} sev=${a.severity} rule=#${a.ruleId} · ${opName(a.op)}"
        val hex = a.pkgHash.joinToString("") { "%02X".format(it) }
        val info = gs.attributionFor(hex)
        val who = info?.let { AppAttribution.resolve(this, it.first, it.second) } ?: "某应用"
        // 内测版(internal)附带包指纹前 8 位,便于核对;商店版(store)隐藏一切标识
        val idPart = if (BuildConfig.IS_INTERNAL && info != null) " · 包指纹 ${hex.take(8)}" else ""
        val sub = "$who$idPart · ${fmtTs(a.windowStartNs)}"
        val isSys = info?.let { CtxProbe.isSystemComponent(it.second, it.first) } ?: false
        return TimelineItem(title, sub, a, isSys)
    }

    private fun kindName(k: Int): String = when (k) {
        SgEnum.VERDICT_LEGIT -> "LEGIT"
        SgEnum.VERDICT_OBSERVE -> "OBSERVE"
        SgEnum.VERDICT_ALERT -> "ALERT"
        else -> "kind$k"
    }

    private fun opName(op: Int): String = when (op) {
        SgEnum.OP_RECORD_AUDIO -> "MIC"
        SgEnum.OP_CAMERA -> "CAM"
        SgEnum.OP_FINE_LOCATION -> "LOC"
        SgEnum.OP_ACCEL -> "ACCEL"
        SgEnum.OP_GYRO -> "GYRO"
        SgEnum.OP_MAG -> "MAG"
        SgEnum.OP_BARO -> "BARO"
        SgEnum.OP_LIGHT -> "LIGHT"
        SgEnum.OP_PROX -> "PROX"
        else -> "op$op"
    }

    private fun fmtTs(tsNs: Long): String = timeFmt.format(Date(tsNs / 1_000_000L))

    /** 时间线单行模型:标题 + 副标题 + (告警时)归属的 VerdictEntryData + 是否系统调用(供筛选)。*/
    private data class TimelineItem(
        val title: String,
        val sub: String,
        val alert: VerdictEntryData?,
        val isSystem: Boolean = false,
    )

    private class TimelineAdapter(
        context: Context,
        items: List<TimelineItem>,
    ) : ArrayAdapter<TimelineItem>(context, 0, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = getItem(position)!!
            val v = convertView
                ?: LayoutInflater.from(context).inflate(R.layout.item_timeline, parent, false)
            v.findViewById<TextView>(R.id.tvTitle).text = item.title
            v.findViewById<TextView>(R.id.tvSub).text = item.sub
            return v
        }
    }
}
