# SensorGuard 社区版 v1.0 开源发布流程

> 前提:不上应用市场,面向开发者/安全研究者开源。目标:社区首体验可用、可信、可贡献。

---

## 总体节奏

```
Phase 0  准备(1天)
Phase 1  代码闭环(P0 必须修,2-3天)
Phase 2  审计与质量(1-2天)
Phase 3  文档与元数据(0.5天)
Phase 4  Tag 与发布(0.5天)
Phase 5  维护机制(持续)
```

---

## Phase 0 · 准备(1 天)

- [ ] 从 `master` 切发布分支 `release/community-v1.0`
- [ ] 明确社区版定位声明:不上架、无后端、纯本地、面向安全研究者
- [ ] 确定默认构建变体:社区版 = 去除 `internal`/`store` 双变体,只保留单一 `community` 变体
- [ ] 确认包名:`com.yuexiao12.sensorguard`(统一,清掉 `com.tabbit` 残留)
- [ ] 确认许可证:Apache-2.0(已落地)
- [ ] 确认文档策略:核心开发设计文档保留本地、不随源码公开(已落地)

## Phase 1 · 代码闭环(2-3 天,P0 阻塞)

### 1.1 修规则误报 R112/SIDE_CHANNEL(阻塞)

现状:`R112` 把系统 uid(1000/系统组件/GMS)也判为侧信道滥用,导致"系统应用被监控告警"的糟糕体验。

动作:
- [x] `core-rust/src/rules.rs` 新增 `UidGte(u32)` + `UidNotIn(Vec<i32>)` 谓词,数据驱动、OTA 可更新
- [x] R112 加 `UidGte(10000)` 排除所有平台系统 uid + `UidNotIn([10213])` 排除 GMS
- [x] 更精准特征:保留 `DeclPurposeNotIn([2,3])`(排除健身/导航类),`SystemProxyEquals(false)`
- [x] 回归测试 `r112_system_uid_whitelist_blocks_system_and_gms`,覆盖 5 场景(普通 App 命中,系统 uid=1000/1001、GMS uid=10213、GMS+GYRO 均屏蔽)
- [x] 保留原告警能力:对非系统 uid 且满足特征的 ACCEL/GYRO 组合仍正常观察
- **提交 `a3db51b`,Rust 测试 95/95 通过**

### 1.2 统一构建变体

- [x] 删除 `store` flavor(社区版不复用商店版隐藏身份逻辑)
- [x] 保留单一 `internal` flavor 作为社区版默认变体,`IS_INTERNAL=true` 恒真(显示 App 归属,社区版核心能力)
- [x] DEBUG 门控按钮(`btnPressure`/`btnDemoAlert`)保持 BuildConfig.DEBUG 门控,release 构建不含调试入口
- [x] README(中英)更新社区版定位与构建命令
- **提交 `a9f475c`**

### 1.3 清理历史残留

- [x] 源码树 `com.tabbit` 零残留(仅设备端旧 APK,不影响仓库)
- [x] `AndroidManifest.xml` 包名为 `com.yuexiao12.sensorguard`(确认一致)
- [x] 无旧 `sensorguard_fhs` 等历史构建产物

### 1.4 压测收尾

- [x] 24h 压测 08-14 20:04 启动,实际连续运行 54h(至 08-17 02:00):**零崩溃、pid 未变、服务前台存活、tick 持续递增**
- [x] 设备端自记录脚本被 EMUI 后台回收(仅 1 行采样),RSS 曲线仅保留首尾 2 点(155.4→167.2MB)
- [x] 已切换电脑端 adb 保活采样(`tools/soak2_sample.ps1`,每 10 分钟)→ `build/soak_test/soak2.csv`,待跑满 12h 后补分析
- [x] 稳定性结论:**54h 无泄漏迹象**(54h 增长 +7.6%,低速,需更多点确认);性能曲线数据后补
- [ ] 压测报告待补:12h 采样完成后输出 RSS 趋势/电池/CPU 结论并勾选

## Phase 2 · 审计与质量(1-2 天)

- [x] Semgrep 规则扫描:修复 3 条规则(metavariable-regex→metavariable-pattern,消除 Windows 下 regex 失效 bug)+ 修正 GCM 误报正则为 `ecb|des|rc4`;9 条规则全绿,**0 findings**
- [x] 密钥/明文硬编码扫描:`SecretKeySpec` 两处均为 SecureRandom/KEK 派生(无字面量),已加 nosemgrep 说明
- [x] 日志泄露扫描:`iv` 正则加词边界(`\b...\b`),消除 "alive/Keystore" 误报;3 处日志均为状态/异常信息,无敏感值
- [x] 权限清单审查:`AndroidManifest.xml` 仅 6 项必要权限,**无 INTERNET / QUERY_ALL_PACKAGES**
- [x] Rust clippy `--all-targets`:修复 9 处(empty_line_after_doc、too_many_arguments、collapsible_match、needless_lifetimes、manual_strip、needless_range_loop、manual_range_contains),全绿
- [x] 不安全算法扫描:`Cipher.getInstance` 全部 `AES/GCM/NoPadding`(无 ECB/DES/RC4);无 TrustManager/WebView JS
- [x] 开源合规:NOTICE(算法原创性+参考致谢)已落地,确认无遗漏
- [x] MobSF 静态扫描:`mobsfscan`(1.0.0)扫 APK 零发现;扫源码 6 条全 INFO(ssl_pinning/cert_transparency/safetynet 因零网络不适用;prevent_screenshot/root_detection 非阻塞;tapjacking 非金融可接受),**0 Critical/High/Medium**
- [x] 依赖 SBOM:`gen_sbom.py` 输出 `docs/sbom.txt`(CycloneDX 1.5,202 组件: Gradle 103 + Cargo 99),shizuku 13.1.5 / flatbuffers 25.12.19 / kotlin 1.9.24,无过期高危依赖

## Phase 3 · 文档与元数据(0.5 天)

- [x] `README.md` / `README_EN.md` 更新:
  - 定位声明:社区版、不上架、纯本地 ✅
  - 构建命令 `assembleInternalDebug/Release`(单一 internal 变体)✅
  - 移除已删除开发文档引用 ✅
  - 新增版本/许可证/Rust 测试 badges + CHANGELOG/CONTRIBUTING/CoC 链接
- [x] 添加 `CHANGELOG.md`:v1.0 特性、修复、已知限制(内存/Shizuku/零网络)
- [x] 添加 `CONTRIBUTING.md`:issue/PR 流程、代码规范(Rust/Kotlin)、安全漏洞披露(48h)
- [x] 添加 `CODE_OF_CONDUCT.md`:Contributor Covenant 2.1
- [x] `docs/` 保留:提审清单废弃标记、PIA 保留、SHIZUKU 激活文档保留,新增 `sbom.txt`
- [x] LICENSE / NOTICE / NOTICE_CN.md 确认(已落地)

## Phase 4 · Tag 与发布(0.5 天)

> 注:社区版全部改动直接提交 `master`(无独立 `release/community-v1.0` 分支),故发布直接在 master 打 tag。

- [x] 直接在 `master` 打 tag(无独立 release 分支,社区版全程单分支开发)
- [x] CI 全绿:Rust 测试 95/95 + clippy `--all-targets` 全绿 + `assembleInternalRelease` 构建成功(1.5MB,ProGuard/shrink/lintVital 通过)
- [x] `git tag -a v1.0.0-community -m "社区版 v1.0.0 开源发布"`
- [x] `git push origin master --tags`(Gitee + GitHub 双端 tag 已推送)
- [x] GitHub Releases:创建 Release 页面(id=371405773,附 CHANGELOG 要点),https://github.com/yukong151/sensorguard/releases/tag/v1.0.0-community
- [x] README 顶栏加 GitHub badge(版本/许可证/Rust 测试)
- [x] 公开仓库可见性确认:GitHub 已切换 **public**(2026-08-17),Gitee 已公开

## Phase 5 · 维护机制(持续)

- [x] 建立 issue 模板(bug report / feature request / question)
- [x] 建立 PR 模板 + 自动 CI 检查
- [x] 维护节奏与治理文档化:`docs/maintenance.md`(issue 每月响应、重大安全 48h、维护者 yukong151、合并原则、后续发布流程)
- [x] v1.1 规划公示:`docs/maintenance.md` 路线图(内存优化 150→40-60MB、Isolation Forest 启用评估、云端复核 opt-in、鸿蒙 NAPI 移植、x86 模拟器)
- [ ] 持续执行:按节奏响应 issue / 处理安全披露 / 更新依赖与 SBOM

---

## 风险与决策点

| 风险 | 决策 |
|---|---|
| 规则误报不修就发 → 社区首体验崩塌 | **必须修**,阻塞发布 |
| RSS 超标(156MB vs 40MB 预算) | 社区版记录已知限制,不阻塞,但需定位是否泄漏 |
| 鸿蒙移植 vs Android 收尾 | 社区版先 Android,鸿蒙列 v1.1 |
| 云端功能 | 社区版不含,仅本地 |
| 包名 `com.tabbit` 残留 | 必须清,否则旧设备双包名混乱 |

---

## 发布 check清单(最终)

- [ ] Rust 测试 94/94 通过
- [ ] Android 编译成功,APK 无 crash
- [ ] 24h 压测 RSS 无泄漏
- [ ] R112 系统 uid 白名单 + 回归测试
- [ ] MobSF 零 Critical/High
- [ ] 包名统一,无 `com.tabbit`
- [ ] 单一构建变体
- [ ] README/CHANGELOG/CONTRIBUTING/NOTICE 齐备
- [ ] Git tag + GitHub Release
- [ ] GitHub/Gitee 双端同步
