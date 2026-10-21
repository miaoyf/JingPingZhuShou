# 03 任务拆分 — 李跳跳复刻（LttClone）

- 版本：v1.0
- 日期：2026-10-20
- 依据：`docs/02-design.md`
- 粒度：6 个批次（每个批次为一次改动后端到端可验证的能力切片）

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
    阶段2 `&` OR 语义：`id="为何会看到此广告&不存在的备选"` 正确命中并点击 `tv_dislike_hint`；
    阶段3 精确优先（关闭 generic_splash）：`id="跳过"` 点中 `btn_skip` 而非同页含「跳过」的说明文字；
    回归：规则库还原为 pristine（apps=328、mock 项已移除）后通用跳过仍生效。
  - 未完成项：真机验证。设备为 HONOR PNM-AN10，macOS 侧只枚举到 HonorSuite 虚拟光驱、**无 adb 接口**（手机未开启 USB 调试），无法抓取真机 logcat。待用户在「开发者选项 → USB 调试」开启并重新插拔授权后补做。

## 依赖关系

```
批次1 → 批次2 → 批次3 → 批次4
                   ↘      ↓
                     批次5
批次3 + 批次4 ──→ 批次6
```
