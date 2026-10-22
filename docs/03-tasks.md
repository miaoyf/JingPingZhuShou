# 03 任务拆分 — 李跳跳复刻（LttClone）

- 版本：v1.0
- 日期：2026-10-20
- 依据：`docs/02-design.md`
- 粒度：9 个批次（每个批次为一次改动后端到端可验证的能力切片）

## 批次总览

- [x] 批次1：工程骨架 + 无障碍服务接入
  - 涉及文件：`settings.gradle.kts`、`build.gradle.kts`、`app/build.gradle.kts`、`gradle.properties`、`gradle/wrapper/gradle-wrapper.properties`、`app/proguard-rules.pro`、`app/src/main/AndroidManifest.xml`、`app/src/main/res/xml/accessibility_service_config.xml`、`app/src/main/res/xml-v31/accessibility_service_config.xml`、`app/src/main/res/values/strings.xml`、`app/src/main/res/values/themes.xml`、`app/src/main/res/layout/activity_main.xml`、`app/src/main/java/com/example/lttclone/App.kt`、`app/src/main/java/com/example/lttclone/MainActivity.kt`、`app/src/main/java/com/example/lttclone/service/LttService.kt`
  - 验收标准：工程可被 Gradle 识别（`settings`/`build` 语法正确）；Service 在 Manifest 声明并能被系统无障碍框架绑定；MainActivity 可跳转无障碍设置页并展示服务开关状态。
  - 依赖：无

- [x] 批次2：规则数据层（转换脚本 + assets + Repository/Prefs）
  - 涉及文件：`tools/convert_rules.py`、`app/src/main/assets/rules.json`（脚本产出）、`app/src/main/java/com/example/lttclone/data/Rule.kt`、`app/src/main/java/com/example/lttclone/data/RuleRepository.kt`、`app/src/main/java/com/example/lttclone/data/Prefs.kt`
  - 验收标准：脚本成功读取三份源 JSON 并修复尾随逗号，输出 `rules.json`，全部 app id 无丢失（含 8 条坏串条目 + Extended 3 条）；Repository 能从 assets 解析出 `apps` 映射；Prefs 能读写总开关与按应用开关。
  - 依赖：批次1

- [x] 批次3：匹配引擎
  - 涉及文件：`app/src/main/java/com/example/lttclone/service/RuleMatcher.kt`、`app/src/main/java/com/example/lttclone/service/LttService.kt`（接入）
  - 验收标准：支持文本/resource-id/contentDescription/bounds 定位；支持点击定位节点 → 可点击祖先 → 手势点击三级策略；支持 `action="back"`；支持 `times` 重试、`delayPopup` 延迟、`clickWayPopup` 手势、倒计时/青少年弹窗；事件 100ms 节流。
  - 依赖：批次2

- [x] 批次4：界面（主界面 + 按应用开关 + 规则查看）
  - 涉及文件：`app/src/main/java/com/example/lttclone/AppSettingActivity.kt`、`app/src/main/res/layout/activity_app_setting.xml`、`app/src/main/res/layout/item_app_info.xml`、`app/src/main/res/values/strings.xml`、`app/src/main/AndroidManifest.xml`
  - 验收标准：应用列表显示已安装应用，每项带开关且状态持久化；切换开关后前台该应用立即生效；规则查看页可列出内置规则。
  - 依赖：批次2、批次3

- [x] 批次5：文档与自检
  - 涉及文件：`README.md`、`tools/convert_rules.py`（自检输出）、`app/src/main/assets/rules.json`（校验）
  - 验收标准：README 含构建说明；转换脚本 + JSON 完整性自检通过并打印统计（app 数 / 规则对 / 修复坏串数）；静态检查（Gradle/Manifest/XML 语法）无阻断问题。
  - 依赖：批次2、批次3、批次4
  - 完成情况：`README.md` 已创建（构建说明 / 使用方式 / 工作原理 / 规则库 / 项目结构 / 权限与合规）；转换脚本新增 `selfcheck` 汇总行，输出 `apps=328 rule_pairs=794 trailing_comma_fixes=9 unparsed=0`；`./gradlew assembleDebug` 构建成功，`./gradlew lintDebug` 结果为 0 errors / 0 warnings。
  - 附加验证（Android 14 arm64 模拟器，API 34）：主界面与按应用开关页渲染正常；无障碍服务成功绑定（label=李跳跳复刻服务，TYPES_ALL_MASK，notificationTimeout=100）；mock 广告页上「跳过」按钮被自动点击（文本由「跳过」变为「已跳过」）；按应用开关关闭后不再点击；`action="back"` 规则可关闭弹窗回到桌面。

- [x] 批次6：真机失效排查与匹配引擎增强（详见下方「批次6」小节）

- [x] 批次7：真机可用性加固与真机验证（详见下方「批次7」小节）

- [x] 批次8：逐应用实测与跨窗口/广告 SDK 节点检索增强（详见下方「批次8」小节）
- [x] 批次9：跳过延迟优化与运行稳定性收敛（详见下方「批次9」小节）

## 批次6：真机失效排查与匹配引擎增强

- [x] 批次6：真机失效排查与匹配引擎增强
  - 背景：用户反馈真机安装后「无法跳过广告」。排查确认三处根因：
    1. 规则库来源是「李跳跳自定义规则」集合（应用内弹窗为主），**几乎没有开屏广告规则**，单靠规则无法满足「跳过所有开屏广告」；
    2. 旧 `RuleMatcher` 把上游 `&` 当字面量做 contains 匹配，且取**首个**命中节点（会点到同页说明文字），语义与上游不一致；
    3. 旧实现 `running` 为 true 时直接丢弃新事件，且首次扫描命中即结束，控件渲染晚于事件时必然漏匹配。
  - 涉及文件：`app/src/main/java/com/example/lttclone/service/NodeMatcher.kt`（新增）、`app/src/main/java/com/example/lttclone/service/SplashSkipper.kt`（新增）、`app/src/main/java/com/example/lttclone/service/RuleMatcher.kt`（重写）、`app/src/main/java/com/example/lttclone/service/LttService.kt`（重写）、`app/src/main/java/com/example/lttclone/data/Prefs.kt`、`app/src/main/java/com/example/lttclone/MainActivity.kt`、`app/src/main/res/layout/activity_main.xml`、`app/src/main/res/values/strings.xml`、`README.md`、`docs/01-requirements.md`、`docs/02-design.md`、`docs/03-tasks.md`
  - 验收标准：
    - `&` 按 OR 拆分匹配；候选节点按打分择优（相等 > 前缀 > 包含，控件 id 后缀加权）而非首个 contains 命中；
    - `action` 支持 `GLOBAL_ACTION_BACK` / `back` / `x:123y:456` 坐标；
    - 无规则覆盖的应用能在前台 15s 窗口内识别「跳过 / Skip / 跳过广告 / 关闭广告」按钮并点击，且不误点同页说明文字；
    - 会话进行中的事件不再被丢弃；点击后 700ms 回检未生效则切换点击方式重试；
    - 主界面在服务未开启时给出明确引导，并提供通用跳过开关；
    - `adb logcat -s LttService` 能看到 `service connected` / `acted key=…` 关键日志；
    - 模拟器 + mock 广告应用三阶段回归通过，规则库还原为 pristine（apps=328）。
  - 依赖：批次3、批次4
  - 完成情况：模拟器（Android 14 / API 34 / arm64）+ mock 应用 `com.example.lttmock` 验证通过——
    阶段1 通用跳过：日志 `acted key=__ltt_generic_skip__` 且点击到 `btn_skip`（未误点诱饵 `tv_tip`）；
    阶段2 `&` OR 语义：`id="为何会看到此广告&不存在的备选"` 正确命中，并按 `action="不感兴趣"` 点中 `btn_dislike`；
    阶段3 精确优先（关闭 generic_splash）：`id="跳过"` 点中 `btn_skip` 而非同页含「跳过」的说明文字；
    回归：规则库还原为 pristine（apps=328、mock 项已移除）后通用跳过仍生效。
  - 第二轮加固（真机场景预判 + 实测修复）：
    1. `LttService.resolveRules` 合并「事件来源包」与「前台包」两份规则，开屏广告由独立广告 SDK 包渲染时也能命中目标应用规则；
    2. 通用跳过不再要求事件包必须等于前台包，避免广告 SDK 事件直接禁用通用跳过；
    3. **修复手势点击失效**：`dispatchGesture` 原用只有 `moveTo` 的零长路径，`dispatchGesture` 返回 true 但实际不产生点击；改为半径 1.5px 的圆形笔迹后实测点击命中；
    4. **修复通用跳过无法回检**：原回检按规则 id/action 重新定位，通用规则 id 为内部标识恒定位不到，导致手势未生效时不会切换为 `ACTION_CLICK`；改为按「跳过按钮是否仍存在」回检；
    5. 规则与通用跳过互斥：规则点过的坐标进入排除集，避免重复点击同一按钮。
  - 未完成项：无（真机验证已在批次7 完成）。

## 批次7：真机可用性加固与真机验证

- [x] 批次7：真机可用性加固与真机验证
  - 背景：真机（荣耀 PNM-AN10 / Android 16 / API 36）连通后，逐项定位到 5 个真实阻断点。
  - 涉及文件：`app/src/main/java/com/example/lttclone/service/Diag.kt`（新增）、`app/src/main/java/com/example/lttclone/service/KeepAliveService.kt`（新增）、`app/src/main/java/com/example/lttclone/service/LttService.kt`、`app/src/main/java/com/example/lttclone/service/RuleMatcher.kt`、`app/src/main/java/com/example/lttclone/MainActivity.kt`、`app/src/main/res/drawable/ic_stat_keepalive.xml`（新增）、`app/src/main/res/layout/activity_main.xml`、`app/src/main/res/values/strings.xml`、`app/src/main/AndroidManifest.xml`、`app/build.gradle.kts`（release 签名）、`keystore.properties`、`.gitignore`、`README.md`、`docs/01-requirements.md`、`docs/02-design.md`、`docs/03-tasks.md`
  - 根因与修复：
    1. **服务从未绑定**：设备把应用设为「调试应用 + 等待调试器」，进程启动即挂起；需清除 `debug_app` / `wait_for_debugger`。
    2. **自身界面被误点**：通用跳过扫描到了本应用自己的「通用开屏广告跳过」开关文字并点中，把开关持久化成 `generic_splash=false`，导致之后通用跳过全程失效；修复为节点按目标应用包名过滤（`scopedNodes`），并把 `genericFired` 改为「回检确认成功后才置位」。
    3. **事件通道断流**：系统反复 unbind/rebind 无障碍服务导致事件不再投送；缓解手段为保活前台服务 + 看门狗定期重开会话。
    4. **扫描时机错位**：开屏广告按钮常在首个事件之后数秒才渲染，而会话空闲 2 秒即结束；新增看门狗每 2 秒复查重开会话（窗口期 15 秒内）。
    5. **日志不可读**：该 ROM 屏蔽第三方 logcat；改为 logcat + `files/ltt_diag.log` 双通道，并在主界面提供「诊断信息」页。
  - 验收标准：
    - 真机（荣耀 PNM-AN10 / Android 16）上对真实开屏广告应用完成一次「点击跳过」闭环；
    - 通用跳过与库内规则均可触发，且有回检确认日志；
    - 后台保活前台服务处于 `isForeground=true`；
    - release 包签名校验通过、无 `debuggable`；
    - `clean assembleDebug assembleRelease lintDebug` 通过且 lint 零告警。
  - 完成情况：真机拼多多（`com.xunmeng.pinduoduo`）登录/开屏页实测通过——
    日志 `tap x=987 y=213 text=跳过` → `acted key=__ltt_generic_skip__ method=0` → `action confirmed key=__ltt_generic_skip__`；
    随后 `uiautomator dump` 中「跳过」节点数由 1 变为 0，焦点落在 `com.xunmeng.pinduoduo/.ui.activity.HomeActivity`。
    隔离掉库内 `GLOBAL_ACTION_BACK` 规则后重测，**纯手势点击**同样完成跳过，未依赖返回键兜底。
    库内规则同样验证命中（拼多多命中 7 条规则）。
    release 包 4,760,792 字节，自签名证书 SHA-256 `1f943f5c969b53b23697606918fba8eace1db73d835359de8fe38c78b4f02a3c`，`aapt2 dump badging` 确认无 `debuggable` 标记。
  - 遗留风险：SurfaceView / 全自绘且无文本、无 contentDescription、无控件 id 的开屏广告（部分短视频类应用）任何无障碍方案都无法识别，属原理性限制。

## 批次8：逐应用实测与跨窗口 / 广告 SDK 节点检索增强

- [x] 批次8：逐应用实测与跨窗口 / 广告 SDK 节点检索增强
  - 背景：用户反馈「微博无法跳过」，并要求把手机上安装的应用逐个打开验证。逐应用实测暴露出「控件树里明明有跳过按钮，但服务点不到」的共性问题。
  - 涉及文件：`app/src/main/java/com/example/lttclone/service/RuleMatcher.kt`、`app/src/main/java/com/example/lttclone/service/SplashSkipper.kt`、`app/src/main/res/xml/accessibility_service_config.xml`、`app/src/main/res/xml-v31/accessibility_service_config.xml`、`README.md`、`docs/02-design.md`、`docs/03-tasks.md`
  - 根因与修复：
    1. **只取活动窗口**：开屏广告常被渲染在独立窗口（广告 SDK 悬浮窗 / 独立 Activity 窗口），`rootInActiveWindow` 取不到；改为遍历 `AccessibilityService.getWindows()` 的全部可见窗口合并节点。
    2. **未声明交互窗口标志**：`getWindows()` 在未开启 `flagRetrieveInteractiveWindows` 时返回空列表，导致上一条修复不生效；在 `res/xml` 与 `res/xml-v31` 的 `accessibilityFlags` 中补上该标志后，窗口列表正常返回。
    3. **包名过滤过严**：节点过滤原本要求包名等于目标应用，广告由独立广告 SDK 包渲染时节点被整体丢弃；改为只排除自身包与系统包。
    4. **位置限制挡住底部跳过条**：严格命中的短文案（如微博国际版屏幕底部的「跳过」）被「必须位于屏幕上半部」规则拒绝；改为严格命中不再受位置限制，仅保留面积上限。
  - 验收标准：
    - 逐应用实测中出现的跳过控件均能触发 `acted key=__ltt_generic_skip__`；
    - 库内规则与通用跳过互不干扰；
    - `clean assembleDebug assembleRelease lintDebug` 通过且 lint 零告警。
  - 完成情况：真机逐个启动 65 个第三方应用并抓取控件树与诊断日志，实测通过的应用包括：拼多多、个人所得税、高德地图、网易云音乐、央广网、QQ 音乐、腾讯会议、闲鱼、腾讯视频、喜马拉雅、微博国际版、央视影音、哔哩哔哩。抖音的开屏为 SurfaceView 自绘，控件树中不存在跳过控件，属原理性限制。
  - 遗留风险：部分厂商 ROM（如荣耀）会对后台应用做进程冻结并反复 unbind 无障碍服务，需用户手动放行「应用启动管理」；SurfaceView 全自绘开屏无法跳过。

## 依赖关系

```
批次1 → 批次2 → 批次3 → 批次4
                   ↘      ↓
                     批次5
批次3 + 批次4 ──→ 批次6 ──→ 批次7 ──→ 批次8 ──→ 批次9
```

### 批次9：跳过延迟优化与运行稳定性收敛

- 背景：用户反馈「广告关闭太慢，还有 1-3 秒的显示」「一会儿能跳过，一会儿跳不过」，并要求把「应用启动管理」作为使用前提（用户已关闭该应用的「自动管理」）。
- 涉及文件：`app/src/main/java/com/example/lttclone/service/RuleMatcher.kt`、`app/src/main/java/com/example/lttclone/service/SplashSkipper.kt`、`app/src/main/java/com/example/lttclone/service/LttService.kt`、`README.md`、`docs/02-design.md`、`docs/03-tasks.md`
- 根因与修复：
  1. **会话过长阻塞新应用**：扫描会话上限被放大到约 96 秒，切换应用时只记录待处理包名，必须等旧会话自然结束才开新会话，导致换应用后长时间不扫描。改为切换包名立即废弃旧会话并开新会话，并给单会话加 15 秒上限。
  2. **会话早退留出空窗**：原会话空闲 15 轮（约 750ms）即结束，之后要等看门狗重开，存在约 1 秒空窗。改为单会话覆盖整个开屏窗口期，轮询自适应 50ms / 150ms / 300ms。
  3. **单轮遍历过慢**：每轮遍历全部窗口整棵树。改为遍历时命中严格候选即提前结束。
  4. **通用跳过只在首轮扫描**：当 `genericAllowed()` 暂为 false 时后续轮次不再扫描，会漏掉稍后出现的广告。改为通用跳过每轮都扫、规则扫描每 4 轮一次。
  5. **权限弹窗抢占会话**：首启权限请求窗口（`com.android.permissioncontroller`）不在系统包排除名单内，会把广告窗口挤出扫描范围。已加入排除名单（同时排除 `com.android.packageinstaller`、`com.android.shell`）。
  6. **看门狗过慢**：复查间隔由 1 秒降为 300 毫秒。
- 验收标准：微信/微博/腾讯视频等真实开屏页面从启动到点击跳过按钮的延迟显著低于 2 秒；连续多次验证结果稳定；`clean assembleDebug assembleRelease lintDebug` 通过且 lint 零告警。
- 完成情况：微博国际版 16ms、腾讯视频 452ms、央视频 893ms、拼多多 336ms（旧实现普遍约 2 秒）；`clean assembleDebug assembleRelease lintDebug` 通过、lint 零告警；release 包已在真机安装并绑定服务。
- 验证前提（写进 README）：测开屏跳过前必须先结束目标应用进程（`am force-stop` 并确认 `pidof` 为空），否则是热启动、不走开屏广告流程；且冷启动不一定下发广告，控件树里没有「跳过」节点即表示本轮未下发广告，不能据此判定跳不过。
- 遗留风险：荣耀 / 华为「应用启动管理 → 自动管理」开启时会冻结进程并反复重绑无障碍服务，必须由用户关闭「自动管理」并放行自启动 / 后台活动；SurfaceView 全自绘开屏属原理性限制。
