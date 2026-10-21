# 李跳跳复刻（LttClone）

用 Kotlin 原生无障碍服务（AccessibilityService）自动识别并跳过开屏广告，复刻「李跳跳」的核心能力。

本项目为独立实现，内置 AGPL-3.0 开源规则库（[wanggaolang/LiTiaotiao](https://github.com/wanggaolang/LiTiaotiao)）。

- 完全离线运行，**不声明 `INTERNET` 权限**，不收集、不上传任何数据
- 规则全部内置在应用 assets 中，无网络请求、无数据库、无远程配置
- **通用开屏广告跳过**：即使目标应用不在规则库内，也会识别「跳过 / Skip / 跳过广告 / 关闭广告」类按钮并点击（可在主界面关闭）
- 规则引擎兼容上游语义：`&` 关键字 OR、`GLOBAL_ACTION_BACK` 返回键、`x:123y:456` 坐标点击

## 权限

| 权限 | 用途 |
| --- | --- |
| `QUERY_ALL_PACKAGES` | 列出已安装应用，供用户逐个开关 |
| `BIND_ACCESSIBILITY_SERVICE` | 无障碍服务绑定（由系统授予） |

不声明 `INTERNET`，不做任何联网行为；不声明 `POST_NOTIFICATIONS` 等无关权限。

---

## 环境要求

| 项目 | 版本 |
| --- | --- |
| JDK | 17+（已在 JDK 20 上验证） |
| Gradle | 8.2（wrapper 已内置，无需本地安装） |
| Android Gradle Plugin | 8.2.2 |
| Kotlin | 1.9.22 |
| compileSdk / targetSdk / minSdk | 34 / 34 / 24 |
| Android SDK | Platform 34 + Build-Tools 34.0.0 |

## 构建

```bash
cd LiTiaotiaoClone
./gradlew assembleDebug        # 调试包
./gradlew assembleRelease      # 发布包（需自行配置签名）
./gradlew lintDebug            # 静态检查
```

产物路径：

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

工程根目录另附一份可直接安装的副本 `LiTiaotiaoClone-debug.apk`（Android Debug 签名，需允许「安装未知来源应用」）。

安装到设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

若本机尚未安装 Android SDK，可用 Homebrew 一次装齐：

```bash
brew install --cask android-commandlinetools
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

并在工程根目录写 `local.properties`：

```
sdk.dir=/opt/homebrew/share/android-commandlinetools
```

## 使用方式

1. 安装并打开应用「李跳跳复刻」。
2. 主界面顶部显示无障碍服务状态。**必须**点「无障碍服务」开关跳转到系统设置页，找到「李跳跳复刻服务」并真正开启（首次开启会有系统确认弹窗，要点「允许」）。只安装 APK 不开服务不会生效。
3. 返回应用，状态应变为「服务已开启」。若仍显示未开启，说明系统设置里没有真正打开该服务。
4. 主界面「通用开屏广告跳过」开关默认开启，用于跳过规则库未覆盖的应用的开屏广告；如担心误触可关闭。
5. 进入应用内「应用设置」页，可对每个已安装应用单独开关：
   - 开启 = 对该应用执行自动跳过
   - 关闭 = 忽略该应用
   - 点击应用名称可查看该应用命中的内置规则明细
6. 回到桌面打开任意带开屏广告的应用，服务会自动匹配规则并点击「跳过」。

开关状态保存在 `SharedPreferences`（`ltt_prefs`），服务运行期间即时生效。

排查提示：服务运行日志为 `adb logcat -s LttService`，可看到 `service connected` / `acted key=… method=…` / `generic skip` 等记录，用来确认服务是否真的在工作。

## 工作原理

```
AccessibilityEvent
   └─ LttService（无障碍服务）
        ├─ 前台包名开关判断（Prefs.isAppEnabled）
        ├─ 记录前台应用与「开屏窗口期」（切前台后 15 秒内）
        └─ RuleMatcher 会话式扫描（每 250ms 一轮，最长 6 秒）
             ├─ 规则定位：id / action 各自按「关键字 & 关键字」OR 拆分
             │    └─ 候选节点打分择优：完全相等 100 > 前缀 80 > 包含 60，
             │       resource-id 后缀匹配额外加权，取最高分而非首个命中
             ├─ 通用开屏跳过：无规则命中时，识别「跳过 / Skip / 跳过广告 / 关闭广告」
             │    按钮（严格短文案 + 可点击优先 + 面积/位置约束）
             ├─ 点击策略：节点 ACTION_CLICK → 可点击祖先 ACTION_CLICK → 手势点击坐标
             ├─ action = "back" / "GLOBAL_ACTION_BACK" 时执行返回键
             ├─ action = "x:123y:456" 时按坐标手势点击
             └─ 效果验证：点击 700ms 后回检节点是否还在，未生效则切换点击方式重试
```

规则查找键为包名 `String.hashCode()` 的十进制字符串（与上游规则库一致）。

## 规则库

规则来源：[wanggaolang/LiTiaotiao](https://github.com/wanggaolang/LiTiaotiao)（镜像自 [Snoopy1866/LiTiaotiao-Custom-Rules](https://github.com/Snoopy1866/LiTiaotiao-Custom-Rules)），许可证 **AGPL-3.0**。

- 仓库提交：`c30d28d7fc997cfe1b7e6e78a9f053276ae2db40`
- 源文件：`AllRules.json`、`ExtendedRules.json`、`BasicRules.json`
- 合并顺序：`All → Extended → Basic`（Extended 覆盖标量，Basic 只补缺失）
- 规则去重键：`(id, action)`
- 规模：328 个应用、794 条规则（其中 56 条规则的 `id` 含 `&`，需按 OR 拆分匹配）

> **重要说明**：上游是「李跳跳自定义规则」集合，主要覆盖**应用内广告、更新弹窗、青少年模式、权限提示**，开屏广告规则极少（仅 `tt_splash_skip_btn` 等个别几条）。因此本项目在规则引擎之外**额外实现了通用开屏广告启发式**，用于跳过规则库未覆盖的应用的开屏广告。

规则字段语义（与上游一致）：

| 字段 | 含义 |
| --- | --- |
| `id` | 要**定位**的元素（关键字 / 控件 ID），多个候选用 `&` 分隔，任一命中即可 |
| `action` | 要**点击**的元素；可为空（表示点 id 本身），可为 `GLOBAL_ACTION_BACK`（返回键），可为 `x:123y:456`（坐标） |
| `times` | 单条规则最大尝试次数 |

重新生成内置规则：

```bash
# 默认从上游仓库检出目录读取；可用 LTT_RULES_SRC 指定源目录
export LTT_RULES_SRC=/path/to/LiTiaotiao
python3 tools/convert_rules.py
```

脚本输出（自检统计）：

```
AllRules.json: apps=328 rules=794 trailing_comma_fixes=4 unparsed=0 duplicates=0
ExtendedRules.json: apps=3 rules=10 trailing_comma_fixes=1 unparsed=0 duplicates=0
BasicRules.json: apps=326 rules=784 trailing_comma_fixes=4 unparsed=0 duplicates=0
merged: apps=328 rules=794 bytes=92561 output=.../app/src/main/assets/rules.json
selfcheck: apps=328 rule_pairs=794 trailing_comma_fixes=9 unparsed=0 duplicates=0 bad_entries_repaired=9
```

`trailing_comma_fixes` 为自动修复的 JSON 尾随逗号数量，`unparsed` 为无法解析的条目数（应为 0），`rule_pairs` 为去重后的规则条数。

## 内置规则 JSON 结构

```json
{
  "version": 1,
  "generatedAt": "2026-10-21T07:20:00Z",
  "apps": {
    "<appId>": {
      "popupRules": [
        { "id": "为何会看到此广告&不感兴趣", "action": "不感兴趣", "times": 1 }
      ],
      "clickWayPopup": false,
      "times": 1,
      "delayPopup": 0,
      "unitePopupRules": false,
      "searchTimesPopup": 5
    }
  }
}
```

## 项目结构

```
LiTiaotiaoClone/
├── app/
│   ├── build.gradle.kts                  # 模块配置：SDK、依赖、Java 17
│   ├── proguard-rules.pro                # 混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml           # 权限、Activity、无障碍 Service 声明
│       ├── assets/rules.json             # 内置规则（脚本产出）
│       ├── java/com/example/lttclone/
│       │   ├── App.kt                    # Application 入口
│       │   ├── MainActivity.kt           # 主界面：服务状态 / 通用跳过开关 / 引导
│       │   ├── AppSettingActivity.kt     # 按应用开关列表 + 规则查看
│       │   ├── data/
│       │   │   ├── Rule.kt               # 规则数据类
│       │   │   ├── RuleRepository.kt     # assets 解析与缓存
│       │   │   └── Prefs.kt              # 总开关 / 按应用开关 / 通用跳过开关
│       │   └── service/
│       │       ├── LttService.kt         # 无障碍服务接入与前台窗口期管理
│       │       ├── RuleMatcher.kt        # 会话式匹配与点击引擎
│       │       ├── NodeMatcher.kt        # 节点定位：OR 拆分、打分择优、bounds/坐标解析
│       │       └── SplashSkipper.kt      # 通用开屏广告按钮启发式识别
│       └── res/
│           ├── layout/                   # activity_main / activity_app_setting / item_app_info
│           ├── values/                   # strings.xml、themes.xml
│           ├── xml/accessibility_service_config.xml
│           └── xml-v31/accessibility_service_config.xml
├── tools/convert_rules.py                # 规则转换 + 自检脚本
├── docs/
│   ├── 01-requirements.md                # 需求说明
│   ├── 02-design.md                      # 技术设计
│   └── 03-tasks.md                       # 任务拆分与验收
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat                 # Gradle wrapper
└── gradle/wrapper/                       # wrapper JAR 与发行版配置
```

## 文档

- `docs/01-requirements.md` — 功能需求、用户故事、P0/P1 划分、权限与合规边界
- `docs/02-design.md` — 技术栈、匹配算法、组件设计、错误处理
- `docs/03-tasks.md` — 5 个批次的任务拆分与验收标准

## 隐私与合规

- 无网络权限，不做任何联网行为
- 所有规则与开关状态仅保存在本机（assets + SharedPreferences）
- 无障碍服务仅在本机读取界面节点树用于匹配「跳过」类文本，不上传、不记录
- 上游规则库以 AGPL-3.0 协议分发，本项目同样以 AGPL-3.0 授权

## 当前状态

已在本机完成实际构建、静态检查与模拟器端到端验证：

- `./gradlew assembleDebug` 构建成功，产出 `app/build/outputs/apk/debug/app-debug.apk`（约 5.8 MB，debug 签名已校验）
- `./gradlew lintDebug` 结果：**0 errors, 0 warnings**
- `aapt2 dump badging` 校验通过：包名 `com.example.lttclone`、minSdk 24 / targetSdk 34 / compileSdk 34、launcher 为 `MainActivity`、仅声明 `QUERY_ALL_PACKAGES`
- 打包产物内含 `assets/rules.json`（92561 字节），无障碍服务在合并后 Manifest 中正确声明 `android.accessibilityservice.AccessibilityService` 与 `accessibilityservice` meta-data

模拟器（Android 14 / API 34 / arm64）端到端验证（mock 广告应用 `com.example.lttmock`）：

| 验证项 | 结果 |
| --- | --- |
| 主界面渲染 | 通过（标题 / 副标题 / 服务开关 / 通用跳过开关 / 状态 / 应用设置按钮均正常） |
| 按应用开关页 | 通过（列出已安装应用，每项带开关） |
| 无障碍服务绑定 | 通过（label=李跳跳复刻服务，TYPES_ALL_MASK，notificationTimeout=100） |
| 通用开屏跳过 | 通过（规则库无该应用时仍点击「跳过」按钮，日志 `acted key=__ltt_generic_skip__`） |
| 规则 `&` OR 语义 | 通过（`id="为何会看到此广告&不存在的备选"` 正确命中第一段并点击 `tv_dislike_hint`） |
| 精确匹配优先 | 通过（存在「跳过」文案时点中按钮而非包含「跳过」的说明文字） |
| `action="back"` | 通过（弹窗被关闭并回到桌面） |
| 按应用开关生效 | 通过（关闭后不再点击） |
| 崩溃检查 | 无 FATAL / AndroidRuntime 异常 |

> 验证过程中的两个环境坑（真机/模拟器通用）：
> 重装 APK 会清掉无障碍服务绑定，需要重新执行
> `adb shell settings put secure enabled_accessibility_services com.example.lttclone/.service.LttService` 与
> `adb shell settings put secure accessibility_enabled 1`；
> 对无障碍服务所在包执行 `am force-stop` 会**禁用**该服务，测试时请改为对目标应用执行。
