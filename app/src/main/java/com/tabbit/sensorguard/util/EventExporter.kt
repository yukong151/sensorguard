package com.tabbit.sensorguard.util

import com.tabbit.sensorguard.jni.SgEnum
import com.tabbit.sensorguard.jni.VerdictEntryData
import com.tabbit.sensorguard.probe.ProbeEvent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * W8 (文档 §5/§8): 用户数据导出 —— 把本机采集的传感器事件与告警序列化为本地文件,
 * 明文脱敏、不出端。支持 JSON / CSV 两种格式,以及匿名化开关(用 pkg_hash 替代包名)。
 *
 * 设计依据(文档原文):
 *  - 第 2971–2972 行: 详情页仅保留「查看时间线」与「导出记录」(生成本地 JSON/CSV,
 *    不含厂商名,只含 pkg_hash 或原始包名字符串,由用户自行选择)。
 *  - 第 448 行: 提供「导出最近 7 天原始事件」功能,让高级用户/安全研究者自行复核。
 *  - 第 2986 行: 默认导出显示原始包名(用户自己设备、自己知情);
 *    若选「匿名分享给他人复核」则自动切换为 pkg_hash。
 *
 * 时间字段说明:
 *  - tsNs / windowStartNs / windowEndNs 为墙钟纳秒(= System.currentTimeMillis()*1e6),保留以不丢精度。
 *  - tsIso / windowStartIso / windowEndIso 为可读 ISO 8601(含时区),供人工分析。
 *  - relMs = 距导出首条事件的毫秒; deltaMs = 距上一条事件的间隔;
 *    二者配合可直观还原"谁在什么时刻、以什么节奏调用传感器"的过程。
 *  - sessions: 按 (App + 传感器) 分组,连续间隔 < gapMs 的调用合并为一次会话,
 *    便于直接看出"每个 App 调了几次、每次持续多久"。
 */
object EventExporter {

    private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

    /** 会话合并阈值(毫秒):两次调用间隔超过该值即视为新的会话。可调。*/
    const val DEFAULT_SESSION_GAP_MS: Long = 2000L

    private fun iso(tsNs: Long): String = ISO.format(Date(tsNs / 1_000_000L))

    private fun opName(op: Int): String = when (op) {
        SgEnum.OP_RECORD_AUDIO -> "MIC"
        SgEnum.OP_CAMERA -> "CAMERA"
        SgEnum.OP_FINE_LOCATION -> "LOCATION"
        SgEnum.OP_ACCEL -> "ACCEL"
        SgEnum.OP_GYRO -> "GYRO"
        SgEnum.OP_MAG -> "MAG"
        SgEnum.OP_BARO -> "BARO"
        SgEnum.OP_LIGHT -> "LIGHT"
        SgEnum.OP_PROX -> "PROX"
        else -> "OP($op)"
    }

    private fun phaseName(p: Int): String = when (p) {
        SgEnum.PHASE_START -> "START"
        SgEnum.PHASE_STOP -> "STOP"
        SgEnum.PHASE_TICK -> "TICK"
        else -> "PHASE($p)"
    }

    private fun verdictName(k: Int): String = when (k) {
        SgEnum.VERDICT_LEGIT -> "LEGIT"
        SgEnum.VERDICT_OBSERVE -> "OBSERVE"
        SgEnum.VERDICT_ALERT -> "ALERT"
        else -> "V($k)"
    }

    private fun catName(c: Int): String = when (c) {
        SgEnum.CAT_NONE -> "NONE"
        SgEnum.CAT_OUT_OF_SCOPE -> "OUT_OF_SCOPE"
        SgEnum.CAT_STEALTH_HOURS -> "STEALTH_HOURS"
        SgEnum.CAT_SIDE_CHANNEL -> "SIDE_CHANNEL"
        SgEnum.CAT_FINGERPRINT -> "FINGERPRINT"
        else -> "C($c)"
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

    /**
     * @param anonymize true 时用 pkg_hash 替代包名(分享复核场景),false 时显示原始包名(本人设备)。
     * @param attribution 由调用方传入,hex -> (pkgName, uid),用于告警(仅有 pkgHash)反查包名。
     * @param sessionGapMs 会话合并阈值(ms),默认 2s;两次调用间隔超过则拆为新会话。
     */
    fun buildJson(
        events: List<ProbeEvent>,
        alerts: List<VerdictEntryData>,
        anonymize: Boolean,
        attribution: (String) -> Pair<String, Int>?,
        sessionGapMs: Long = DEFAULT_SESSION_GAP_MS,
    ): String {
        val root = JSONObject().apply {
            put("app", "SensorGuard")
            put("schema", "export-v3")
            put("exportedAtMs", System.currentTimeMillis())
            put("exportedAtIso", iso(System.currentTimeMillis() * 1_000_000L))
            put("anonymized", anonymize)
            put("sessionGapMs", sessionGapMs)
            put(
                "timeNote",
                "tsNs/windowStart(Ns)为墙钟纳秒; *_Iso为可读ISO8601;relMs/deltaMs基于按时间升序序列;sessions按(App+传感器)合并"
            )
        }
        val evSorted = events.sortedBy { it.tsNs }
        val t0 = evSorted.firstOrNull()?.tsNs ?: 0L
        var prev = 0L
        val evArr = JSONArray()
        for (e in evSorted) {
            val deltaMs = if (prev == 0L) 0L else (e.tsNs - prev) / 1_000_000L
            val o = JSONObject().apply {
                put("type", "EVENT")
                put("tsNs", e.tsNs)
                put("tsIso", iso(e.tsNs))
                put("relMs", (e.tsNs - t0) / 1_000_000L)
                put("deltaMs", deltaMs)
                put("uid", e.uid)
                put("op", opName(e.op))
                put("opCode", e.op)
                put("phase", phaseName(e.phase))
                put("sensor", e.sensorName.ifBlank { opName(e.op) })
                put("tier", e.tier)
                put("source", e.source)
                put("samplingPeriodUs", e.samplingPeriodUs)
                put("pkgHash", hex(e.pkgHash))
                put("pkg", if (anonymize) "" else (e.pkgName ?: ""))
            }
            evArr.put(o)
            prev = e.tsNs
        }
        root.put("events", evArr)
        val alSorted = alerts.sortedBy { it.windowStartNs }
        val alArr = JSONArray()
        for (a in alSorted) {
            val info = if (anonymize) null else attribution(hex(a.pkgHash))
            val o = JSONObject().apply {
                put("type", "ALERT")
                put("kind", verdictName(a.kind))
                put("category", catName(a.category))
                put("severity", a.severity)
                put("ruleId", a.ruleId)
                put("op", opName(a.op))
                put("opCode", a.op)
                put("windowStartNs", a.windowStartNs)
                put("windowEndNs", a.windowEndNs)
                put("windowStartIso", iso(a.windowStartNs))
                put("windowEndIso", iso(a.windowEndNs))
                put("durMs", (a.windowEndNs - a.windowStartNs) / 1_000_000L)
                put("degraded", a.degraded)
                put("pkgHash", hex(a.pkgHash))
                put("pkg", info?.first ?: "")
            }
            alArr.put(o)
        }
        root.put("alerts", alArr)
        // 会话汇总
        val sessArr = JSONArray()
        for ((i, s) in buildSessions(events, anonymize, sessionGapMs).withIndex()) {
            sessArr.put(
                JSONObject().apply {
                    put("sessionId", i + 1)
                    put("app", s.appLabel)
                    put("pkgHash", s.pkgHashHex)
                    put("op", opName(s.op))
                    put("opCode", s.op)
                    put("startIso", iso(s.firstTsNs))
                    put("endIso", iso(s.lastTsNs))
                    put("count", s.count)
                    put("durMs", (s.lastTsNs - s.firstTsNs) / 1_000_000L)
                }
            )
        }
        root.put("sessions", sessArr)
        return root.toString(2)
    }

    fun buildCsv(
        events: List<ProbeEvent>,
        alerts: List<VerdictEntryData>,
        anonymize: Boolean,
        attribution: (String) -> Pair<String, Int>?,
        sessionGapMs: Long = DEFAULT_SESSION_GAP_MS,
    ): String {
        val sb = StringBuilder()
        // 事件段(按时间升序,relMs/deltaMs 才有意义)
        sb.appendLine("type,tsNs,tsIso,relMs,deltaMs,uid,op,phase,sensor,source,samplingPeriodUs,pkg,pkgHash")
        val evSorted = events.sortedBy { it.tsNs }
        val t0 = evSorted.firstOrNull()?.tsNs ?: 0L
        var prev = 0L
        for (e in evSorted) {
            val deltaMs = if (prev == 0L) 0L else (e.tsNs - prev) / 1_000_000L
            val sensor = e.sensorName.ifBlank { opName(e.op) }
            val pkg = if (anonymize) "" else (e.pkgName ?: "")
            sb.appendLine(
                "EVENT,${e.tsNs},${iso(e.tsNs)},${(e.tsNs - t0) / 1_000_000L},$deltaMs," +
                    "${e.uid},${opName(e.op)},${phaseName(e.phase)}," +
                    "${csvCell(sensor)},${csvCell(e.source)},${e.samplingPeriodUs}," +
                    "${csvCell(pkg)},${hex(e.pkgHash)}"
            )
            prev = e.tsNs
        }
        // 告警段(字段集不同,单独成表,避免列错位)
        sb.appendLine("")
        sb.appendLine("type,windowStartNs,windowStartIso,windowEndNs,windowEndIso,durMs,op,kind,category,severity,ruleId,degraded,pkg,pkgHash")
        for (a in alerts.sortedBy { it.windowStartNs }) {
            val info = if (anonymize) null else attribution(hex(a.pkgHash))
            val pkg = info?.first ?: ""
            sb.appendLine(
                "ALERT,${a.windowStartNs},${iso(a.windowStartNs)},${a.windowEndNs},${iso(a.windowEndNs)}," +
                    "${(a.windowEndNs - a.windowStartNs) / 1_000_000L}," +
                    "${opName(a.op)},${verdictName(a.kind)},${catName(a.category)}," +
                    "${a.severity},${a.ruleId},${a.degraded}," +
                    "${csvCell(pkg)},${hex(a.pkgHash)}"
            )
        }
        // 会话汇总段
        sb.appendLine("")
        sb.appendLine("type,sessionId,app,pkgHash,op,startIso,endIso,count,durMs")
        for ((i, s) in buildSessions(events, anonymize, sessionGapMs).withIndex()) {
            sb.appendLine(
                "SESSION,${i + 1},${csvCell(s.appLabel)},${s.pkgHashHex},${opName(s.op)}," +
                    "${iso(s.firstTsNs)},${iso(s.lastTsNs)},${s.count}," +
                    "${(s.lastTsNs - s.firstTsNs) / 1_000_000L}"
            )
        }
        return sb.toString()
    }

    /** 按 (App + 传感器) 分组的连续调用会话;间隔超过 gapMs 拆为新会话。*/
    private fun buildSessions(
        events: List<ProbeEvent>,
        anonymize: Boolean,
        gapMs: Long,
    ): List<Session> {
        val sorted = events.sortedBy { it.tsNs }
        val out = mutableListOf<Session>()
        var cur: Session? = null
        for (e in sorted) {
            val key = "${hex(e.pkgHash)}|${e.op}"
            val gap = if (cur == null) Long.MAX_VALUE else (e.tsNs - cur.lastTsNs) / 1_000_000L
            if (cur == null || cur.key != key || gap > gapMs) {
                cur = Session(
                    key = key,
                    pkgHashHex = hex(e.pkgHash),
                    pkgName = e.pkgName,
                    op = e.op,
                    firstTsNs = e.tsNs,
                    lastTsNs = e.tsNs,
                    count = 1,
                ).also { out.add(it) }
            } else {
                cur.lastTsNs = e.tsNs
                cur.count += 1
            }
        }
        // 计算 app 显示名(匿名化 / 无包名时回退 pkgHash)
        for (s in out) {
            s.appLabel = if (anonymize || s.pkgName.isNullOrBlank()) s.pkgHashHex else s.pkgName
        }
        return out
    }

    private data class Session(
        val key: String,
        val pkgHashHex: String,
        val pkgName: String?,
        val op: Int,
        var firstTsNs: Long,
        var lastTsNs: Long,
        var count: Int,
        var appLabel: String = "",
    )

    private fun csvCell(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
}
