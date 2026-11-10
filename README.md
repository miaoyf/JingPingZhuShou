# 净屏助手

用 Kotlin 原生无障碍服务（AccessibilityService）自动识别并跳过开屏广告。

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
| `FOREGROUND_SERVICE` | 后台保活服务，保证无障碍事件通道不被系统冻结 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | 保活服务的类型声明（Android 14+） |
| `POST_NOTIFICATIONS` | 展示保活服务的常驻通知（Android 13+） |

不声明 `INTERNET`，不做任何联网行为。

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
cd FuChu
./gradlew clean assembleDebug       # 调试包（可查看运行日志）
./gradlew clean assembleRelease     # 发布包（使用 keystore.properties 中的签名）
./gradlew lintDebug                 # 静态检查
```

产物路径：

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

发布包签名配置在工程根目录 `keystore.properties`（**该文件与 `.jks` 密钥不入库**）：

```
storeFile=jingping-release.jks
storePassword=<你的密码>
keyAlias=jingping
keyPassword=<你的密码>
```

生成签名密钥：

```bash
keytool -genkeypair -keystore jingping-release.jks -alias jingping \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=JingPingZhuShou, O=JingPingZhuShou, C=CN"
```

工程根目录另附两份可直接安装的副本：

- `FuChu-debug.apk`（Android Debug 签名，可读取运行日志）
- `FuChu-release.apk`（自签名，不可调试、体积更小，正式使用推荐）

安装到设备：

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
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

1. 安装并打开应用「净屏助手」。
2. 主界面顶部显示无障碍服务状态。**必须**点「无障碍服务」开关跳转到系统设置页，找到「净屏助手服务」并真正开启（首次开启会有系统确认弹窗，要点「允许」）。只安装 APK 不开服务不会生效。
3. **开启服务后，请再回到本应用打开一次主界面**。荣耀 / 华为 / 小米等系统会冻结长时间未在后台运行的进程，未先打开一次时服务可能收不到事件。
4. **在系统「应用启动管理」中放行本应用（荣耀 / 华为 / 小米等必做，属于本应用的运行前提）**：
   > 设置 → 应用 → 应用启动管理 → 净屏助手 → **关闭「自动管理」**，并允许「自启动」「后台活动」「关联启动」

   系统若接管「自动管理」，会在应用退到后台约 7 秒后冻结进程，并让系统每 4 秒左右重���次绑定无障碍服务，绑定空窗期内开屏广告按钮会漏掉，表现为「时好时坏」。关闭「自动管理」后系统不再接管本进程，事件通道保持畅通。
5. 返回应用，状态应变为「服务已开启」。若仍显示未开启，说明系统设置里没有真正打开该服务。
6. 主界面「通用开屏广告跳过」开关默认开启，用于跳过规则库未覆盖的应用的开屏广告；如担心误触可关闭。
7. 进入应用内「应用设置」页，可对每个已安装应用单独开关：
   - 开启 = 对该应用执行自动跳过
   - 关闭 = 忽略该应用
   - 点击应用名称可查看该应用命中的内置规则明细
8. 回到桌面打开任意带开屏广告的应用，服务会自动匹配规则并点击「跳过」。

开关状态保存在 `SharedPreferences`（`ltt_prefs`），服务运行期间即时生效。

### 后台保活（荣耀 / 华为必做）

本应用在后台运行一个低优先级常驻通知（前台服务，`specialUse` 类型），用于避免系统冻结导致无障碍事件通道中断。

若使用荣耀 / 华为 / 小米等深度管控系统，还需手动放行：

> 设置 → 应用 → 应用启动管理 → 净屏助手 → 允许「自启动」「后台活动」「关联启动」

### 诊断信息

主界面「诊断信息」按钮会展示：

- 无障碍服务是否已开启
- 设备是否把本应用设为「调试应用 + 等待调试器」（这会导致服务永远无法绑定）
- 最近 120 行运行日志（服务事件、前台包名、扫描、点击、确认）

也可在已连接 adb 的电脑上直接读取（debug 包）：

```bash
adb shell run-as com.netscreen.assistant cat files/jp_diag.log
```

排查提示：部分国产 ROM 会屏蔽第三方应用的 logcat 输出，因此本应用把关键日志同时写入应用私有目录 `files/jp_diag.log`，用上面的命令查看。日志中的关键行：`service connected` / `event type=… pkg=…` / `foreground package=…` / `scan pkg=… nodes=… rules=…` / `tap x=… y=… text=…` / `acted key=… method=…` / `action confirmed key=…`。

## 工作原理

```
AccessibilityEvent
   └─ SkipService（无障碍服务）
        ├─ 前台包名开关判断（Prefs.isAppEnabled）
         ├─ 记录前台应用与「开屏窗口期」（切前台后 15 秒内）
         ├─ 看门狗：每 300 毫秒复查一次，前台应用未变且窗口期未过则重新开启扫描会话
         │    （应对广告按钮晚于首个事件才渲染的情况）
          └─ RuleMatcher 会话式扫描（单次会话覆盖整个开屏窗口期，自适应轮询
               50ms / 150ms / 300ms，切换应用立即换会话，单会话上限 15 秒）
               ├─ 跨窗口取节点：遍历 AccessibilityService.getWindows() 返回的全部
               │    可见窗口（需 accessibilityFlags 开启 flagRetrieveInteractiveWindows，
               │    否则开屏广告所在的悬浮/广告窗口取不到），
               │    只排除自身包与系统包节点，避免误点自身界面
               │    （另排除 com.android.permissioncontroller 等权限/安装弹窗窗口）
               ├─ 遍历时命中严格候选即提前结束本轮，避免整棵树遍历拖慢响应
               ├─ 规则定位：id / action 各自按「关键字 & 关键字」OR 拆分
               │    └─ 候选节点打分择优：完全相等 100 > 前缀 80 > 包含 60，
               │       resource-id 后缀匹配额外加权，取最高分而非首个命中
               ├─ 通用开屏跳过：规则未命中时，识别「跳过 / Skip / 跳过广告 / 关闭广告」
               │    按钮（严格短文案 + 可点击优先 + 面积约束；严格命中的短文案
               │    不再受「必须位于屏幕上半部」限制，可覆盖底部跳过条），
               │    且排除本会话已被规则点过的坐标，避免重复点击
              ├─ 点击策略：手势点击（圆形笔迹）→ 节点 ACTION_CLICK → 可点击祖先 ACTION_CLICK
              ├─ action = "back" / "GLOBAL_ACTION_BACK" 时执行返回键
              ├─ action = "x:123y:456" 时按坐标手势点击
              └─ 效果验证：点击 700ms 后回检目标是否还在，未生效则切换点击方式重试
                   （规则按 id/action 定位回检，通用跳过按「跳过按钮是否仍存在」回检）
                   回检通过后才标记「已成功」，避免一次手势失效就永久关闭本次跳过
```

后台保活：`KeepAliveService` 以 `specialUse` 类型前台服务常驻，通知渠道最低优先级不可点击。

规则查找键为包名 `String.hashCode()` 的十进制字符串（与上游规则库一致）。

## 规则库

规则来源：[wanggaolang/LiTiaotiao](https://github.com/wanggaolang/LiTiaotiao)（镜像自 [Snoopy1866/LiTiaotiao-Custom-Rules](https://github.com/Snoopy1866/LiTiaotiao-Custom-Rules)），许可证 **AGPL-3.0**。

- 仓库提交：`c30d28d7fc997cfe1b7e6e78a9f053276ae2db40`
- 源文件：`AllRules.json`、`ExtendedRules.json`、`BasicRules.json`
- 合并顺序：`All → Extended → Basic`（Extended 覆盖标量，Basic 只补缺失）
- 规则去重键：`(id, action)`
- 规模：328 个应用、794 条规则（其中 56 条规则的 `id` 含 `&`，需按 OR 拆分匹配）

> **重要说明**：上游是开源的「自定义规则」集合，主要覆盖**应用内广告、更新弹窗、青少年模式、权限提示**，开屏广告规则极少（仅 `tt_splash_skip_btn` 等个别几条）。因此本项目在规则引擎之外**额外实现了通用开屏广告启发式**，用于跳过规则库未覆盖的应用的开屏广告。

规则字段语义（与上游一致）：

| 字段 | 含义 |
| --- | --- |
| `id` | 要**定位**的元素（关键字 / 控件 ID），多个候选用 `&` 分隔，任一命中即可 |
| `action` | 要**点击**的元素；可为空（表示点 id 本身），可为 `GLOBAL_ACTION_BACK`（返回键），可为 `x:123y:456`（坐标） |
| `times` | 单条规则最大尝试次数 |

重新生成内置规则：

```bash
# 默认从上游仓库检出目录读取；可用 JP_RULES_SRC 指定源目录
export JP_RULES_SRC=/path/to/LiTiaotiao
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
FuChu/
├── app/
│   ├── build.gradle.kts                  # 模块配置：SDK、依赖、Java 17
│   ├── proguard-rules.pro                # 混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml           # 权限、Activity、无障碍 Service 声明
│       ├── assets/rules.json             # 内置规则（脚本产出）
│       ├── java/com/jingping/zhushou/
│       │   ├── App.kt                    # Application 入口
│       │   ├── MainActivity.kt           # 主界面：服务状态 / 通用跳过开关 / 引导
│       │   ├── AppSettingActivity.kt     # 按应用开关列表 + 规则查看
│       │   ├── data/
│       │   │   ├── Rule.kt               # 规则数据类
│       │   │   ├── RuleRepository.kt     # assets 解析与缓存
│       │   │   └── Prefs.kt              # 总开关 / 按应用开关 / 通用跳过开关
│       │   └── service/
│       │       ├── SkipService.kt         # 无障碍服务接入、前台窗口期管理、看门狗、保活拉起
│       │       ├── RuleMatcher.kt        # 会话式匹配与点击引擎
│       │       ├── NodeMatcher.kt        # 节点定位：OR 拆分、打分择优、bounds/坐标解析
│       │       ├── SplashSkipper.kt      # 通用开屏广告按钮启发式识别
│       │       ├── KeepAliveService.kt   # 后台保活前台服务（specialUse）
│       │       └── Diag.kt               # 运行日志：logcat + files/jp_diag.log 双通道
│       └── res/
│           ├── drawable/ic_stat_keepalive.xml  # 保活通知小图标
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
├── keystore.properties                   # 发布签名配置（不入库）
└── gradle/wrapper/                       # wrapper JAR 与发行版配置
```

## 文档

- `docs/01-requirements.md` — 功能需求、用户故事、P0/P1 划分、权限与合规边界
- `docs/02-design.md` — 技术栈、匹配算法、组件设计、错误处理
- `docs/03-tasks.md` — 10 个批次的任务拆分与验收标准

## 隐私与合规

- 无网络权限，不做任何联网行为
- 所有规则与开关状态仅保存在本机（assets + SharedPreferences）
- 无障碍服务仅在本机读取界面节点树用于匹配「跳过」类文本，不上传、不记录
- 上游规则库以 AGPL-3.0 协议分发，本项目同样以 AGPL-3.0 授权

## 当前状态

已在本机完成实际构建、静态检查与模拟器端到端验证：

- `./gradlew assembleDebug` 构建成功，产出 `app/build/outputs/apk/debug/app-debug.apk`（约 5.8 MB，debug 签名已校验）
- `./gradlew lintDebug` 结果：**0 errors, 0 warnings**
- `aapt2 dump badging` 校验通过：包名 `com.netscreen.assistant`（applicationId，代码包 `com.jingping.zhushou`）、minSdk 24 / targetSdk 34 / compileSdk 34、launcher 为 `MainActivity`、仅声明 `QUERY_ALL_PACKAGES`
- 打包产物内含 `assets/rules.json`（92561 字节），无障碍服务在合并后 Manifest 中正确声明 `android.accessibilityservice.AccessibilityService` 与 `accessibilityservice` meta-data

模拟器（Android 14 / API 34 / arm64）端到端验证（mock 广告应用 `com.example.lttmock`）：

| 验证项 | 结果 |
| --- | --- |
| 主界面渲染 | 通过（标题 / 副标题 / 服务开关 / 通用跳过开关 / 状态 / 应用设置按钮均正常） |
| 按应用开关页 | 通过（列出已安装应用，每项带开关） |
| 无障碍服务绑定 | 通过（label=净屏助手服务，TYPES_ALL_MASK，notificationTimeout=100） |
| 通用开屏跳过 | 通过（规则库无该应用时仍点击「跳过」按钮，日志 `acted key=__ltt_generic_skip__`） |
| 规则 `&` OR 语义 | 通过（`id="为何会看到此广告&不存在的备选"` 正确命中第一段，并按 `action="不感兴趣"` 点中 `btn_dislike`） |
| 精确匹配优先 | 通过（存在「跳过」文案时点中按钮而非包含「跳过」的说明文字） |
| 手势点击有效性 | 通过（手势笔迹改为半径 1.5px 圆点，实测点击命中；未生效时 700ms 回检自动切换为 `ACTION_CLICK`） |
| 规则与通用跳过不重复点击 | 通过（规则点过的坐标会从通用跳过候选中排除） |
| `action="back"` | 通过（弹窗被关闭并回到桌面） |
| 按应用开关生效 | 通过（关闭后不再点击） |
| 崩溃检查 | 无 FATAL / AndroidRuntime 异常 |

真机验证（荣耀 PNM-AN10 / Android 16 / API 36，真实开屏广告应用）：

| 验证项 | 结果 |
| --- | --- |
| 无障碍服务绑定 | 通过（`Bound services` 含净屏助手服务，`TYPES_ALL_MASK`） |
| 通用开屏跳过（拼多多真实广告页） | 通过（日志 `tap x=987 y=213 text=跳过` → `acted key=__ltt_generic_skip__` → `action confirmed`，随后页面不再存在「跳过」节点，焦点落在 `HomeActivity`） |
| 纯手势点击有效性（隔离掉库内 `GLOBAL_ACTION_BACK` 规则） | 通过（仅手势即完成跳过，未依赖返回键兜底） |
| 规则命中（库内规则） | 通过（拼多多命中 7 条规则） |
| 诊断日志通道 | 通过（`files/jp_diag.log` 可读；该 ROM 屏蔽第三方 logcat） |
| 后台保活前台服务 | 通过（`ServiceRecord … KeepAliveService isForeground=true types=0x40000000`） |
| release 包签名与安装 | 通过（自签名证书校验通过，无 `debuggable` 标记） |

真机全量冷启动实测（荣耀 PNM-AN10 / Android 16 / API 36）。**验证协议：测前必须先结束目标应用进程并确认已退出（`am force-stop` + `pidof` 为空），再做冷启动**——热启动不会走开屏广告流程，测不出效果。对机上安装的 65 个第三方应用逐个冷启动验证，全部跑完：

| 结果 | 数量 | 说明 |
| --- | --- | --- |
| 广告下发且成功跳过 | 10 | 全部在 1 秒内完成点击 |
| 本轮未下发开屏广告 | 55 | 控件树 8 秒内无任何「跳过」节点，非匹配失败 |

成功跳过的 10 个应用（`after` = 本服务「会话开始 → 完成点击」的自身耗时；`TOTAL` = 从应用被拉起到完成点击，含应用自身冷启动与广告渲染）：

| 应用 | 跳过控件 | after（自身动作） | TOTAL（端到端） |
| --- | --- | --- | --- |
| 微博国际版 | `跳过`（屏幕底部） | 174ms | 369ms |
| 腾讯视频 | `跳过` | 188ms | 599ms |
| 央视频 | `5s \| 跳过` | 306ms | 1043ms |
| 喜马拉雅 | `跳过广告` | 335ms | 926ms |
| 小宇宙播客 | `跳过 (4)` | 71ms | 639ms |
| 央广网 | `跳过 2` | 281ms | 592ms |
| 车机助手 | `跳过` | 351ms | 927ms |
| QQ 音乐 | `跳过` | 805ms | 895ms |
| 腾讯会议 | 应用内弹窗规则 | 2194ms | 2415ms |
| 中国移动 | 应用内弹窗规则 | 11749ms | 1606ms |

> 开屏广告场景下本服务自身的「发现 → 点击」耗时为 **71~351ms**；端到端 TOTAL 还包含应用冷启动与广告渲染时间，这部分不受本服务控制。
> 腾讯会议与中国移动命中的是库内「应用内弹窗」规则（弹窗数秒后才出现），不属于开屏跳过。

未下发的应用（含拼多多、闲鱼、网易云音乐、高德地图、哔哩哔哩、微信、QQ、抖音）本轮冷启动均未下发开屏广告——广告下发由应用自身决定（网络、频控、是否首启），「控件树里没有跳过节点」只能说明本轮广告没下发，不能据此判断跳不过；要复现需满足广告下发条件。

> 抖音等自绘（SurfaceView）开屏广告，控件树中不存在跳过控件，属于原理性限制。


真机排障要点（已全部验证并写入诊断信息页）：

1. 手机必须开启「开发者选项 → USB 调试 / 无线调试」，否则无法抓日志。
2. **必须先打开一次本应用主界面**，否则进程会被系统冻结，无障碍服务收不到事件。
3. 设备若把应用设为「调试应用 + 等待调试器」，服务永远无法绑定（需在开发者选项取消）。
4. 荣耀 / 华为需在「设置 → 应用 → 应用启动管理」中**关闭「自动管理」**，并允许自启动、后台活动、关联启动。系统接管「自动管理」时会冻结进程并反复重绑无障碍服务，导致时好时坏。
5. 屏幕必须点亮，未点亮时开屏广告不会渲染，也就无从跳过。

跳过延迟优化（本轮新增，实测从应用启动到点击跳过按钮）：

- 扫描会话不再早退：单次会话覆盖整个开屏窗口期（15 秒），自适应轮询 30ms / 150ms / 300ms，切换应用立即换会话。
- 快速定位：优先用 `findAccessibilityNodeInfosByText` 按「跳过 / skip / 关闭广告」直接取候选节点，命中即提前结束本轮，不再逐节点 `getChild` 遍历整棵控件树。
- 默认点击方式改为先走节点 `ACTION_CLICK`（其次可点击祖先、最后才手势），回检间隔缩短到 250 毫秒。
- 诊断日志改为常驻缓冲写入，且只记录窗口变化事件——原先每个无障碍事件都同步落盘 4 次文件 I/O，会拖慢事件处理。
- 看门狗复查间隔由 1 秒降为 300 毫秒；规则扫描每 4 轮一次，通用跳过每轮都扫。
- 排除了权限请求弹窗（`com.android.permissioncontroller`）等系统窗口，避免它们抢占会话。
- 实测（本服务自身「发现 → 点击」）：微博国际版 174ms、腾讯视频 188ms、央视频 306ms、喜马拉雅 335ms、央广网 281ms、小宇宙 71ms（旧实现普遍约 2 秒）。

验证开屏跳过的前提条件（否则测不出效果）：

1. **目标应用的进程必须先结束**，否则是热启动、应用不会走开屏广告流程。测前需 `am force-stop <包名>` 并确认 `pidof <包名>` 为空。
2. 开屏广告是否下发由应用自身决定（网络、频控、是否首启），**冷启动不一定出广告**；控件树里没有「跳过」节点即表示本轮广告未下发，不能据此判定「跳不过」。

> 验证过程中的两个环境坑（真机/模拟器通用）：
> 重装 APK 会清掉无障碍服务绑定，需要重新执行
> `adb shell settings put secure enabled_accessibility_services com.netscreen.assistant/com.jingping.zhushou.service.SkipService` 与
> `adb shell settings put secure accessibility_enabled 1`；
> 对无障碍服务所在包执行 `am force-stop` 会**禁用**该服务，测试时请改为对目标应用执行。
