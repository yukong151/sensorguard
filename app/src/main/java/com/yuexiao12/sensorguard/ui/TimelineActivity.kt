package com.yuexiao12.sensorguard.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.yuexiao12.sensorguard.BuildConfig
import com.yuexiao12.sensorguard.R
import com.yuexiao12.sensorguard.databinding.ActivityTimelineBinding
import com.yuexiao12.sensorguard.enums.SgEnum
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
 * 滚动加载增强(512 停更修复):
 *  - 内存缓冲仅保留最新 512 条(eventLog);滚动到底自动从 Room 加密历史分页解密回读更早事件,
 *    与内存段合并展示(按 tsNs 去重),历史不再被 512 上限截断。
 *  - 适配器尾部附加"加载更早记录…"占位行,滚动到底触发下一页;无更多历史时移除。
 *
 * 事件时间线增强(W12/T2):
 *  - 标题行展示 [操作] + 具体传感器名 + 相位(开始/停止);
 *  - 副标题展示归属:内测版(internal)完整显示包名/宿主 App(见 AppAttribution),
 *    商店版(store flavor)仅显示"某应用"(合规要求);告警行同样带归因。
 */
class TimelineActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimelineBinding
    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** 当前展示项(与 adapter 顺序一致),点击告警行据此跳转 A3。*/
    private var items: List<TimelineItem> = emptyList()
    private var adapter: TimelineAdapter? = null

    /** 筛选开关:true=显示系统调用(默认),false=仅第三方 App。*/
    private var showSystem = true

    /** Room 历史分页状态。historyCursor=0 表示未初始化,∞ 表示从最新探底;加载后取最旧 tsNs 推进。*/
    private val historyItems = ArrayList<TimelineItem>()
    private var historyCursor = 0L
    private var historyLoading = false
    private var historyExhausted = false

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
                "${getString(R.string.ui_title_timeline)}（社区版·含 App 归属）"
        }

        // W12/P0-1 增强:筛选开关。默认开启(显示系统调用),关闭则仅展示第三方 App。
        binding.swSystem.setOnCheckedChangeListener { _, isChecked ->
            showSystem = isChecked
            refreshTimeline()
        }

        binding.listTimeline.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val item = items.getOrNull(position) ?: return@OnItemClickListener
            when {
                item.alert != null -> startActivity(DetailActivity.intent(this, item.alert))
                item.event != null -> startActivity(DetailActivity.eventIntent(this, item.event))
                // footer 占位行等无数据 → 忽略
                else -> Unit
            }
        }

        // 滚动到底 → 加载更早 Room 历史(分页)
        binding.listTimeline.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
            override fun onScroll(view: AbsListView?, firstVisible: Int, visibleCount: Int, total: Int) {
                if (total == 0) return
                if (firstVisible + visibleCount >= total - 1) maybeLoadMore()
            }
        })
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
        // 追加已加载的 Room 历史(更早记录,按时间倒序)——
        // 回归修复:历史同样按当前 showSystem 状态过滤,切换开关即时生效
        for (h in historyItems) {
            if (showSystem || !h.isSystem) next.add(h)
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
        adapter?.showFooter(!historyExhausted)
    }

    /** 滚动到底触发:从 Room 加载早于当前最早事件的一页历史,追加到列表尾部。*/
    private fun maybeLoadMore() {
        val gs = GuardService.instance ?: return
        if (historyLoading || historyExhausted) return
        val cursor = if (historyCursor == 0L) Long.MAX_VALUE else historyCursor
        historyLoading = true
        adapter?.showFooter(true)
        gs.loadMoreEvents(cursor) { events ->
            historyLoading = false
            val gs2 = GuardService.instance ?: return@loadMoreEvents
            // 去重:内存段与历史段可能重叠(同一事件既在内存缓冲也在 Room 加密库)
            val seen = HashSet<String>()
            for (i in items) if (i.tsNs != 0L) seen.add("${i.tsNs}:${i.title}")
            val appended = ArrayList<TimelineItem>()
            for (e in events) {
                val item = eventItem(gs2, e)
                // 始终缓存全部历史(不过滤),展示阶段由 refreshTimeline 按当前开关过滤,
                // 避免"加载时开关为关 → 系统事件被丢弃 → 打开开关后也无法找回"。
                val key = "${item.tsNs}:${item.title}"
                if (seen.add(key)) appended.add(item)
            }
            historyItems.addAll(appended)
            // 游标推进:取本页最旧事件 tsNs;无更多(空页)则置为耗尽
            historyCursor = appended.lastOrNull()?.tsNs ?: 0L
            if (appended.isEmpty()) historyExhausted = true
            adapter?.showFooter(!historyExhausted)
            refreshTimeline()
        }
    }

    private fun eventItem(gs: GuardService, e: ProbeEvent): TimelineItem {
        val opTag = opName(e.op)
        val sensor = if (e.sensorName.isNotBlank()) e.sensorName else opTag
        val phase = if (e.isStart) "开始" else "停止"
        val title = "[$opTag] $sensor · $phase"
        // 内测版(internal)显示归属;商店版(store)仅中性措辞
        val who = if (BuildConfig.IS_INTERNAL) {
            AppAttribution.resolve(this, e.pkgName, e.uid)
                ?: if (e.pkgName.isNullOrBlank()) "uid=${e.uid}" else e.pkgName
        } else {
            "某应用"
        }
        val sub = "$who · ${fmtTs(e.tsNs)}"
        // 回归修复:uid<0(T0 未知来源)不算"系统调用"——它可能是恶意第三方,
        // 关闭"显示系统调用"开关时不应被隐藏(仅隐藏可归因的系统进程/系统包)。
        val isSys = e.uid >= 0 && CtxProbe.isSystemComponent(e.uid, e.pkgName)
        return TimelineItem(title, sub, null, isSys, e.tsNs, e)
    }

    private fun alertItem(gs: GuardService, a: VerdictEntryData): TimelineItem {
        val title = "⚠ ${kindName(a.kind)} sev=${a.severity} rule=#${a.ruleId} · ${opName(a.op)}"
        val hex = a.pkgHash.joinToString("") { "%02X".format(it) }
        val info = gs.attributionFor(hex)
        // 内测版(internal):完整显示应用归属;商店版(store)一律中性措辞
        val who = if (BuildConfig.IS_INTERNAL) {
            info?.let { AppAttribution.resolve(this, it.first, it.second) }
                ?: if (hex == "000000000000000000000000") "未知来源" else "某应用"
        } else {
            "某应用"
        }
        val idPart = if (BuildConfig.IS_INTERNAL && info != null) " · 包指纹 ${hex.take(8)}" else ""
        val sub = "$who$idPart · ${fmtTs(a.windowStartNs)}"
        val isSys = info?.let { CtxProbe.isSystemComponent(it.second, it.first) } ?: false
        return TimelineItem(title, sub, a, isSys, a.windowStartNs)
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

    /** 时间线单行模型:标题 + 副标题 + (告警时)归属的 VerdictEntryData + 是否系统调用(供筛选)+ tsNs(分页游标/去重)。*/
    private data class TimelineItem(
        val title: String,
        val sub: String,
        val alert: VerdictEntryData?,
        val isSystem: Boolean = false,
        val tsNs: Long = 0L,
        /** 回归修复:普通事件引用 —— 点击事件行跳转事件详情模式。告警行为 null。*/
        val event: ProbeEvent? = null,
    )

    private class TimelineAdapter(
        context: Context,
        items: List<TimelineItem>,
    ) : ArrayAdapter<TimelineItem>(context, 0, items) {

        private var footerVisible = false

        /** 显示/隐藏"加载更早"页脚(adapter 尾部附加一项,不参与数据 items)。*/
        fun showFooter(show: Boolean) {
            if (footerVisible == show) return
            footerVisible = show
            notifyDataSetChanged()
        }

        override fun getCount(): Int = super.getCount() + (if (footerVisible) 1 else 0)

        override fun getItem(position: Int): TimelineItem? {
            if (position >= super.getCount()) return null // footer 占位
            return super.getItem(position)
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            if (position >= super.getCount()) {
                val v = convertView
                    ?: LayoutInflater.from(context).inflate(R.layout.item_timeline, parent, false)
                v.findViewById<TextView>(R.id.tvTitle).text = "加载更早记录…"
                v.findViewById<TextView>(R.id.tvSub).text = "正在从本地加密历史读取"
                return v
            }
            val item = super.getItem(position)!!
            val v = convertView
                ?: LayoutInflater.from(context).inflate(R.layout.item_timeline, parent, false)
            v.findViewById<TextView>(R.id.tvTitle).text = item.title
            v.findViewById<TextView>(R.id.tvSub).text = item.sub
            return v
        }
    }
}