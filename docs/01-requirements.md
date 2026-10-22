# 01 需求定义 — 李跳跳复刻（LttClone）

- 版本：v1.0
- 日期：2026-10-20
- 状态：定稿
- 依据：对 `Li_tiaotiao.2.2.apk`（SHA-256 `095b70eb8a186e56b01bc4951c077ff2527e1dc94bb7e54b43671a8f8f33762f`）的静态逆向，及 `wanggaolang/LiTiaotiao`（AGPL-3.0，HEAD `c30d28d7fc997cfe1b7e6e78a9f053276ae2db40`）规则仓库调研。

## 1. 项目背景与目标

“李跳跳”是一款**不联网的单机 Android 应用**，通过无障碍服务监听窗口内容变化，自动点击开屏广告的“跳过”控件。原版已停更，作者声明推荐改用 GKD。原 APK 使用腾讯乐固加壳（`libshell-super.2019.so` 等），真实匹配算法无法静态获取。

本项目目标：**另行编写一套 Kotlin 原生 Android 无障碍 App**，实现与原版同等的核心能力，并内置开源规则库。

目标（可验收）：
1. 监听无障碍事件，自动跳过开屏广告与常见弹窗。
2. 支持按应用单独开关自动跳过。
3. 内置规则库（来自 LiTiaotiao 规则仓库，AGPL-3.0），完全离线可用。
4. 支持自定义规则（定位 + 关闭 + 返回键兜底）。

## 2. 用户故事 / 用例

- US-1：作为用户，打开某 App 出现开屏广告时，广告“跳过”控件在约 1 秒内被自动点击，直接进入主界面。
- US-2：作为用户，某些 App 我不希望被自动操作，可单独关闭其跳过开关。
- US-3：作为用户，遇到规则未覆盖的弹窗，可自行填写“定位文字 / id / bounds”与“关闭文字 / id”新增规则。
- US-4：作为用户，可在设置页一键跳转开启系统无障碍服务，并看到服务运行状态。

## 3. 功能列表（优先级排序）

### P0（必须）
- F1 无障碍服务接入：`typeAllMask`、`notificationTimeout=100`、`canRetrieveWindowContent=true`、`canPerformGestures=true`；API 31+ 追加 `isAccessibilityTool="true"`。
- F2 事件驱动匹配：`TYPE_WINDOW_STATE_CHANGED` / `TYPE_WINDOW_CONTENT_CHANGED` → 遍历节点 → 定位 → 关闭。
- F3 规则内置与加载：打包 `assets/rules.json`，启动时解析入内存。
- F4 按应用开关：读取前台包名，仅当该应用开关为开时处理。
- F5 主界面：无障碍服务开关（`SwitchMaterial`）+ 实时服务状态。
- F6 返回键兜底：`action="back"` → `performGlobalAction(GLOBAL_ACTION_BACK)`。
- F7 手势点击兜底：定位节点及其祖先均不可点击时，用 `dispatchGesture` 点 bounds 中心。
- F8 规则高级字段：`times` 重试、`delay_popup` 延迟、倒计时弹窗、青少年弹窗、`click_way_popup`（手势点击方式）。
- F9 通用开屏广告跳过：规则库未覆盖的应用，在切前台 15s 窗口内识别「跳过 / Skip / 跳过广告 / 关闭广告」类按钮并点击；可在主界面关闭。
- F10 规则语义兼容上游：`id` / `action` 支持 `&` OR 拆分、`action` 支持 `GLOBAL_ACTION_BACK` 与 `x:123y:456` 坐标、定位按打分择优而非首个命中。
- F11 服务未开启时给出明确引导（弹窗 + 一键跳转系统设置），并展示规则库覆盖规模。

### P1（增强）
- F12 规则管理页：查看内置规则、新增自定义规则（对话框：定位 / 关闭 / 返回键）。
- F13 规则导入导出：本地 JSON 文件读写（无网络）。
- F14 规则命中统计：本地记录命中次数（可选展示）。

## 4. 非功能需求

- 网络：**不声明 INTERNET 权限**，全部离线。
- 权限最小化：`QUERY_ALL_PACKAGES`、`BIND_ACCESSIBILITY_SERVICE`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS`（后三者仅用于后台保活与常驻通知；不声明 `INTERNET`，不声明其它无关权限）。
- 兼容：minSdk 24、targetSdk 34、compileSdk 34、仅 `arm64-v8a`。
- 性能：事件回调 100ms 节流；单次遍历节点数设上限，避免主线程卡顿。
- 稳定性：规则解析失败不崩溃，坏规则跳过并在日志中计数。
- 隐私：不采集、不存储、不上传任何用户数据。
- 可维护：规则库与代码解耦，规则可通过替换 `assets/rules.json` 更新。

## 5. 明确的不包含范围

- 不做在线规则订阅 / 云同步 / 账号体系（原版亦无网络能力）。
- 不破解、不修改、不二次打包原 APK；不复用其加壳 dex。
- 不实现微信 / QQ 特定增强（原版 `activity_we_chat.xml` 仅为扫码引导页）。
- 不采用 root / Xposed / LSPosed 方案。
- 不做签名发布与上架。

## 6. 假定与默认值

以下为本项目自行决定的细节（非关键项），记录供事后一次性纠正：

1. 包名 `com.example.lttclone`，应用名“李跳跳复刻”。
2. 规则来源：本地规则仓库的 `BasicRules.json` / `AllRules.json` / `ExtendedRules.json`，由 `tools/convert_rules.py` 转换为单一 `assets/rules.json`。
3. 规则合并策略：以 `AllRules` 为基线，`ExtendedRules` 补充，`BasicRules` 仅补入尚不存在的 `(id, action)` 对；同一 app 内按对去重。
4. 事件节流 100ms，对齐原版 `notificationTimeout`。
5. 自定义规则持久化于 `SharedPreferences`（JSON 字符串）。
6. 手势点击坐标 = 目标节点 bounds 中心点。
7. 无障碍配置：`values` 与 `xml-v31` 两份，后者仅追加 `isAccessibilityTool="true"`。
8. 默认所有应用为“开启跳过”，用户可单独关闭（沿用原版语义）。
9. 不在源码中写注释（遵循全局执行规范）。
10. 主题使用 `Theme.MaterialComponents.DayNight.NoActionBar` 系。

## 7. 风险与限制

- 原 APK 加壳，真实选择器与匹配算法不可得 → 复刻行为可能与原版存在差异，以规则库语义为准。
- 上游规则库（Snoopy1866/LiTiaotiao-Custom-Rules 镜像）以**应用内弹窗**为主，开屏广告规则极少 → 必须依赖通用启发式补齐，存在误点风险（已用严格文案 + 可点击优先 + 面积/位置三重约束收敛）。
- 部分广告按钮由无标识的自绘控件构成，节点树中无任何可匹配文本 → 无法跳过（上游同样无法适配）。
- 规则 JSON 内层存在尾随逗号（`BasicRules` 4 条、`AllRules` 4 条、`ExtendedRules` 3 条）→ 转换器必须容错修复，不得丢弃规则。
- 规则文件许可为 AGPL-3.0 → 本项目若分发须遵循 AGPL-3.0 并保留来源声明。

## 8. 验收标准

- 工程结构完整，可用标准 Android 工具链编译通过（本机已验证 `assembleDebug` + `lintDebug` 0 error / 0 warning）。
- `tools/convert_rules.py` 成功产出 `assets/rules.json`，且全部 app id 无丢失（含尾随逗号条目）；自检行显示 `apps=328 rule_pairs=794 unparsed=0`。
- 无障碍服务可开启，前台应用命中规则时能自动点击关闭。
- 按应用开关生效：关闭的应用不被处理。
- 通用开屏跳过生效：规则库未覆盖的应用，命中「跳过」类按钮并点击，且不误点同页说明文字。
- 上游语义生效：`id` 含 `&` 的规则按 OR 匹配；`action` 为 `GLOBAL_ACTION_BACK` 时执行返回键。
- 主界面在服务未开启时给出明确引导，避免「装了就以为生效」。
