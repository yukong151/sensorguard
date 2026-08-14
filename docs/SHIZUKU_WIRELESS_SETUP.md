# Shizuku 无线激活步骤(每次开机后需重新执行)

SensorGuard 内测版的 T2 精确归因(传感器/相机 → 真实 App 包名)依赖 Shizuku。
Shizuku 以 **ADB shell 身份**运行服务,需在 Shizuku App 内完成激活与授权。
**每次设备重启后 Shizuku 服务会停止,需重新激活。**

## 前提
- 手机已安装 Shizuku App(`moe.shizuku.privileged.api`)
- 手机已开启「开发者选项」→「USB 调试」
- (无线方式)「开发者选项」→「无线调试」可被开启

## 方法 A:无线调试激活(推荐,手机可脱离电脑)

1. 打开 Shizuku App,主界面显示「Shizuku 未运行」
2. 点击 **「通过无线调试启动」**
3. 系统弹出「要开启无线调试吗?」→ 点击 **允许/确定**
4. 进入「无线调试」设置页 → 打开顶部 **无线调试开关**
5. 回到 Shizuku App → 点击 **「启动」**
   - Shizuku 会自动通过无线 adb 端口启动服务,界面显示:
     `Starting with wireless adb in port XXXX... Service started`
   - 出现「Service started, this window will be automatically closed in 3 seconds」即成功
6. 打开 SensorGuard → 点「启动隐私监测」
   - 若弹出 Shizuku 授权请求 → 点 **允许**
   - GuardService 检测到授权后自动激活 T2 探针

### 验证激活成功
SensorGuard 时间线应显示带真实包名的事件:
- `[ACCEL] lsm6dso Accelerometer · Google 定位服务 (com.google.android.gsf)`
- `[CAM] CAM · 开始 — com.motorola.camera3`

## 方法 B:通过 USB adb 激活(手机连接电脑)

1. 打开 Shizuku App → 点击 **「通过连接电脑启动(使用 adb)」**
2. 手机 USB 连接电脑,电脑执行(Windows PowerShell):
   ```powershell
   adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
   ```
   - 若提示 start.sh 不存在,先在 Shizuku App 内点一次「通过连接电脑启动」生成脚本
3. 看到 `Service started` 即成功,拔线不影响服务

## 授权 SensorGuard(若未自动弹出)
1. 打开 Shizuku App → 进入「已授权应用」
2. 若列表中没有 SensorGuard → 打开 SensorGuard → 点「启动隐私监测」
   - GuardService 会主动发起 Shizuku 授权请求(弹框)→ 点 **允许**
3. 授权后 GuardService 的 ShizukuProbe 自动激活(binder 监听器回调),无需重启

## 诊断
- 日志关键字(`adb logcat`):
  - `ShizukuProbe wait: binder not alive` → Shizuku 服务未运行,重新激活
  - `ShizukuProbe wait: permission not granted` → 服务在但未授权,授权 SensorGuard
  - `ShizukuProbe started (T2 enhanced)` → T2 探针激活成功
  - `Shizuku UserService bound (T2 exec ready)` → 命令执行通道就绪
  - `Shizuku: N sensor clients` / `Shizuku camera: N clients` → 轮询工作正常
- Shizuku 未激活时 SensorGuard **静默降级**:传感器/相机事件显示「未知来源」(T0),不影响其他功能
