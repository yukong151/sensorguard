package com.sensorguard.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.sensorguard.app.BuildConfig
import com.sensorguard.app.databinding.ActivityMainBinding
import com.sensorguard.app.jni.SgNative
import com.sensorguard.app.logic.HealthLevel
import com.sensorguard.app.service.GuardService
import com.sensorguard.app.ui.TimelineActivity
import com.sensorguard.app.util.EventExporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * W8 (文档 §3): A1 实时仪表盘 —— 系统健康度(§10)+ 加密存储状态(§10)+ 今日计数(§6)+
 * 入口(A2 时间线)。ViewBinding 布局,无 Compose。每 2s 刷新状态;保留 W4 真机压测入口。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())

    /** W8 (文档 §5/§8): 用户导出 —— 经 SAF 让用户自选落点(免存储权限、不出端)。*/
    private var pendingAnonymize = false
    private var pendingIsJson = true
    private val exportLauncher = registerForActivityResult(
        object : ActivityResultContract<Pair<String, String>, Uri?>() {
            override fun createIntent(context: Context, input: Pair<String, String>): Intent =
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = input.first
                    putExtra(Intent.EXTRA_TITLE, input.second)
                }
            override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
                if (resultCode == RESULT_OK) intent?.data else null
        }
    ) { uri -> uri?.let { writeExport(it) } }

    private val refresh = object : Runnable {
        override fun run() {
            refreshDashboard()
            handler.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStart.setOnClickListener {
            startForegroundService(Intent(this, GuardService::class.java))
            // W12/T2: 必须在 Activity 上下文向 Shizuku 发起授权,否则系统授权框弹不出来
            // (后台 Service 调用会被 Shizuku 静默丢弃,导致 T2 探针永远停在未授权)。
            // 延迟一小段确保 GuardService.onCreate 已完成并注册监听器。
            handler.postDelayed({ GuardService.instance?.requestShizukuPermission(this@MainActivity) }, 600)
            Toast.makeText(this, R.string.ui_btn_start, Toast.LENGTH_SHORT).show()
        }

        binding.btnTimeline.setOnClickListener {
            startActivity(Intent(this, TimelineActivity::class.java))
        }

        // W8 (文档 §5/§8): 导出记录(本地) —— 用户主动触发,明文脱敏、不出端。
        // 弹窗选 JSON/CSV + 匿名化(用 pkg_hash 替代包名),再经 SAF 落盘到用户自选位置。
        binding.btnExport.setOnClickListener { onExportClicked() }

        // 真机压测入口:测量 sgPushSensor 纯标量 JNI 往返延迟(mean/P99/max)。
        // 覆盖 ring CAP=4096 前 2200 次均为 E_OK;超限 E_RESOURCE 属预期反压。
        binding.btnPressure.setOnClickListener {
            Thread {
                for (i in 0 until 200) SgNative.sgPushSensor(SystemClock.elapsedRealtimeNanos(), 10, 0f, 0f, 0f)
                val n = 2000
                val times = LongArray(n)
                var ok = 0
                for (i in 0 until n) {
                    val t0 = System.nanoTime()
                    val rc = SgNative.sgPushSensor(SystemClock.elapsedRealtimeNanos(), 10, 0f, 0f, 0f)
                    times[i] = System.nanoTime() - t0
                    if (rc == 0) ok++
                }
                times.sort()
                val p99idx = (n * 0.99).toInt().coerceAtMost(n - 1)
                val mean = times.average() / 1000.0
                Log.i("SG-PTEST", "n=$n ok=$ok mean=${"%.1f".format(mean)}us p99=${times[p99idx]/1000}us max=${times[n-1]/1000}us")
            }.start()
        }

        // W8 (Debug 演示):注入演示告警 → 跳转时间线,人工点击告警行验证 A2→A3 链路。
        // 仅 debug 构建显示(BuildConfig.DEBUG);release 下 no-op + 按钮 GONE。
        if (BuildConfig.DEBUG) {
            binding.btnDemoAlert.visibility = View.VISIBLE
            binding.btnDemoAlert.setOnClickListener {
                val gs = GuardService.instance
                if (gs == null) {
                    Toast.makeText(this, R.string.ui_btn_start_first, Toast.LENGTH_SHORT).show()
                } else {
                    gs.injectDemoAlert()
                    startActivity(Intent(this, TimelineActivity::class.java))
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshDashboard()
        handler.post(refresh)
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        super.onStop()
    }

    /**
     * W8 (文档 §10): 仪表盘状态刷新。
     * 健康度 UI 措辞与日志严格分离: 颜色语义 = 绿(OK)/黄(DEGRADED)/橙(SAFE_MODE)/红(DEAD)。
     */
    private fun refreshDashboard() {
        val gs = GuardService.instance
        val level = gs?.healthLevel() ?: HealthLevel.OK
        binding.tvHealthValue.text = when (level) {
            HealthLevel.OK -> getString(R.string.ui_health_ok)
            HealthLevel.DEGRADED -> getString(R.string.ui_health_degraded)
            HealthLevel.SAFE_MODE -> getString(R.string.ui_health_safe)
            HealthLevel.DEAD -> getString(R.string.ui_health_dead)
        }
        binding.tvHealthValue.setTextColor(
            when (level) {
                HealthLevel.OK -> 0xFF2E7D32.toInt()
                HealthLevel.DEGRADED -> 0xFFF9A825.toInt()
                HealthLevel.SAFE_MODE -> 0xFFEF6C00.toInt()
                HealthLevel.DEAD -> 0xFFC62828.toInt()
            }
        )
        val storageSafe = gs?.inSafeMode() ?: false
        binding.tvStorageValue.text =
            if (storageSafe) getString(R.string.ui_storage_safe) else getString(R.string.ui_storage_ok)
        binding.tvStorageValue.setTextColor(
            if (storageSafe) 0xFFEF6C00.toInt() else 0xFF2E7D32.toInt()
        )

        if (gs == null) {
            binding.tvCounts.visibility = View.GONE
            return
        }
        binding.tvCounts.visibility = View.VISIBLE
        val startOfDayNs = run {
            val c = java.util.Calendar.getInstance()
            c.set(java.util.Calendar.HOUR_OF_DAY, 0)
            c.set(java.util.Calendar.MINUTE, 0)
            c.set(java.util.Calendar.SECOND, 0)
            c.set(java.util.Calendar.MILLISECOND, 0)
            c.timeInMillis * 1_000_000L
        }
        val events = gs.recentEvents().count { it.tsNs >= startOfDayNs }
        val alerts = gs.recentAlerts().count { it.windowEndNs >= startOfDayNs }
        binding.tvCounts.text = getString(R.string.ui_event_counts, events, alerts)
    }

    /** W8 (文档 §5/§8): 导出前置 —— 校验有数据后弹选项(格式 + 匿名化)。*/
    private fun onExportClicked() {
        val gs = GuardService.instance
        if (gs == null || (gs.recentEvents().isEmpty() && gs.recentAlerts().isEmpty())) {
            Toast.makeText(this, R.string.ui_export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val cb = CheckBox(this).apply {
            text = getString(R.string.ui_export_anonymize)
            isChecked = false
            val p = (resources.displayMetrics.density * 20).toInt()
            setPadding(p, p, p, p)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.ui_export_title)
            .setMessage(R.string.ui_export_msg)
            .setView(cb)
            .setPositiveButton(R.string.ui_export_json) { _, _ -> launchExport(json = true, anon = cb.isChecked) }
            .setNeutralButton(R.string.ui_export_csv) { _, _ -> launchExport(json = false, anon = cb.isChecked) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchExport(json: Boolean, anon: Boolean) {
        pendingIsJson = json
        pendingAnonymize = anon
        val ext = if (json) "json" else "csv"
        val mime = if (json) "application/json" else "text/csv"
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        exportLauncher.launch(mime to "sensorguard-export-$ts.$ext")
    }

    /** SAF 回写: 把内存事件/告警序列化为本地文件,全程不出端。*/
    private fun writeExport(uri: Uri) {
        val gs = GuardService.instance ?: run {
            Toast.makeText(this, R.string.ui_export_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val events = gs.recentEvents()
        val alerts = gs.recentAlerts()
        val attribution: (String) -> Pair<String, Int>? = { hex -> gs.attributionFor(hex) }
        val content = if (pendingIsJson) {
            EventExporter.buildJson(events, alerts, pendingAnonymize, attribution)
        } else {
            EventExporter.buildCsv(events, alerts, pendingAnonymize, attribution)
        }
        try {
            contentResolver.openOutputStream(uri)?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, getString(R.string.ui_export_done, uri.lastPathSegment ?: ""), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("SG", "export failed", e)
            Toast.makeText(this, getString(R.string.ui_export_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}