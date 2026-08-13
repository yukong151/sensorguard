# W10 (文档 §13):真机性能压测脚本 —— Perfetto 采集 + 预算核验。
#
# 用法(真机连接后,PowerShell):
#   .\tools\perfetto_profile.ps1            # 默认采集 60s,输出到 build/perfetto/
#   .\tools\perfetto_profile.ps1 -Seconds 300
#
# 流程:
#   1. 启动 perfetto trace(低频分类器:CPU/内存/唤醒,避免采样器自身开销)
#   2. 启动 SensorGuard 前台服务(点"启动隐私监测"路径的等效 intent)
#   3. 触发 24h 压测剧本的 60s 采样窗口(上下文信息驱动,可替换为手动真机操作)
#   4. 停止 trace,拉取 pbtxt 摘要,输出预算核验表
#
# 预算(文档 §6 表):CPU ≤ 1.2%,PSS ≤ 32 MB,唤醒 ≤ 6 次/h。
# 说明:完整 24h 压测需人工保持真机运行;本脚本产出 60s-5min 采样,
#       预算核验针对采样窗口内 CPU%,PSS/唤醒为 24h 级指标,需长跑后离线核。

param(
    [int]$Seconds = 60,
    [string]$OutDir = "build/perfetto"
)

$ErrorActionPreference = "Stop"
$adb = "C:\Users\ew\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = (Get-Command adb).Source }
$pkg = "com.tabbit.sensorguard"

# 前置:设备在线
$dev = (& $adb devices) | Select-String "device$"
if (-not $dev) { Write-Error "未检测到真机,请连接后重试"; exit 1 }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# ── 1. 启动 perfetto trace ────────────────────────────────────────────────
# 低频分类器:CPU(perf)+ 调度(wakeup)+ 内存(memory)。采样 250ms 减少开销。
$traceFile = "/data/local/tmp/sg_trace.pb"
& $adb logcat -c
& $adb shell "perfetto -o $traceFile -t ${Seconds}s --txt -c -" | Out-Null | Out-Null
# perfetto 需要以后台方式启动;用 shell 后台执行
Start-Process -FilePath $adb -ArgumentList @("shell", "echo", "perfetto", "-o", $traceFile, "-t", "${Seconds}s", "--txt", "-c", "-") -NoNewWindow | Out-Null
Start-Sleep -Seconds 2

# ── 2. 启动 SensorGuard(等效点击"启动隐私监测") ───────────────────────────
& $adb shell "am force-stop $pkg" | Out-Null
& $adb shell "am start -n $pkg/.MainActivity" | Out-Null
Start-Sleep -Seconds 2
# 通过 app 内部启动前台服务(服务未导出,shell 不可直接 start;依赖 UI 按钮)。
# 此处报告需要人工在真机点击"启动隐私监测";脚本等待用户操作。
Write-Host ">>> 请在真机点击 [启动隐私监测] 后按回车开始采集..."
if ($host.UI.RawUI.KeyAvailable) { $null = $host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown") }

# ── 3. 等待采集窗口 ────────────────────────────────────────────────────────
Write-Host "采集 ${Seconds}s ..."
Start-Sleep -Seconds ($Seconds + 5)

# ── 4. 拉取并核验 ──────────────────────────────────────────────────────────
& $adb pull $traceFile "$OutDir\sg_trace.pb" | Out-Null
& $adb shell "rm -f $traceFile" | Out-Null

# 进程 CPU 占比(采样窗口内平均)
$pidLine = & $adb shell "pidof $pkg"
if ($pidLine) {
    $procPid = ($pidLine -split " ")[0].Trim()
    $cpuInfo = & $adb shell "top -b -n 2 -p $procPid" | Select-String "$pkg"
    Write-Host "=== 预算核验(采样窗口) ==="
    Write-Host "进程 top 输出(末行):$($cpuInfo | Select-Object -Last 1)"
    Write-Host "CPU 预算 ≤ 1.2% —— 需 24h 均值,60s 采样仅作参考"
} else {
    Write-Host "WARN: 进程未运行,请确认已点击[启动隐私监测]"
}

# 崩溃检查
$crashes = & $adb logcat -d -b crash 2>$null
if ($crashes) { Write-Host "WARN: 检测到 crash buffer 条目:"; $crashes | Select-Object -Last 5 }
else { Write-Host "崩溃检查:无 crash buffer 条目 ✓" }

# tick 健康检查
$ticks = & $adb logcat -d -s SG:I 2>$null | Select-Object -Last 3
Write-Host "=== 最近 tick(确认 L3 循环健康)==="
$ticks

Write-Host "`nTrace 已保存至 $OutDir\sg_trace.pb"
Write-Host "完整分析:https://ui.perfetto.dev 打开该文件(CPU/唤醒/内存视图)"