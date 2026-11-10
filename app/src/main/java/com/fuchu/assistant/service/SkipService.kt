package com.fuchu.assistant.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.fuchu.assistant.data.AppRules
import com.fuchu.assistant.data.Prefs
import com.fuchu.assistant.data.RuleRepository

class SkipService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var matcher: RuleMatcher? = null
    private var pending: Runnable? = null
    private var latestPackage: String? = null
    private var foregroundPackage: String? = null

    /** 敏感应用：完全不监控（不扫描/不点击/不回放）。
     *  银行类：10-27 实测招行把截屏识别为"正在录屏"并弹窗拦截（ScreenShareWaringActivity），
     *  金融页面误点风险高，整体排除最安全。
     *  微信：11-09 用户反馈打开微信后聊天列表被自动定位到顶部，日志证实学习库中
     *  存在 mm 的坐标记录（learned replay skip pkg=com.tencent.mm 多次被前台闸门
     *  拦下），盲树回放一旦放出就会在聊天列表盲点。微信从无开屏广告，零收益纯风险。 */
    private val sensitivePackages = setOf(
        "com.tencent.mm",            // 微信（11-09 加入）
        "cmb.pb",                    // 招商银行
        "com.icbc",                  // 工商银行
        "com.chinamworld.bocmbci",   // 中国银行
        "com.ccb.mobilebank",        // 建设银行
        "com.abchina.mobilebank",    // 农业银行
        "com.bankcomm.Bankcomm",     // 交通银行
        "com.psbc.mobile",           // 邮储银行
        "com.ecitic.bank.mobile",    // 中信银行
        "com.cebbank.mobile.cemb",   // 光大银行
        "com.cmbc.cc.mbank",         // 民生银行
        "com.cib.cibmba",            // 兴业银行
        "com.spdb.mobilebank.per",   // 浦发银行
        "com.hxb.mobile.client",     // 华夏银行
        "com.pingan.paces.ccms"      // 平安口袋银行
    )

    private var foregroundSince = 0L
    private var genericFired = false
    private var actedFlag = false
    // 热启动重武装：经锁屏/最近任务（systemui）离开应用不算经过桌面，回来时前台包名
    // 不变、不会触发新前台周期，跳过武装保持解除——这是"热启动有概率跳不了"的根因
    // （10-24 14:06 日志实证：systemui→微信直回，无 foreground 行）。这类路径重置
    // 周期后只认 strict 候选+回放，防内容页宽松匹配误点
    private var hotStrictPending = false
    private var strictOnlyUntil = 0L

    private val screenOffReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            foregroundPackage = null
            hotStrictPending = true
            Diag.log("screen off, period reset")
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            handler.postDelayed(this, WATCHDOG_MS)
            try {
                sweep()
            } catch (t: Throwable) {
                Diag.log("watchdog error=${t.javaClass.simpleName}")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 作废旧匹配器的滞留任务（回放连发/延迟验证），防止新旧实例同时发手势连击
        matcher?.dispose()
        matcher = RuleMatcher(
            service = this,
            rulesProvider = { pkg -> resolveRules(pkg) },
            genericProvider = { genericAllowed() },
            strictOnlyProvider = { SystemClock.elapsedRealtime() < strictOnlyUntil }
        ).also {
            it.onGenericAction = { Diag.log("generic action attempted") }
            it.onAction = { key -> Diag.log("action attempt key=$key") }
            it.onVerified = { key ->
                actedFlag = true
                if (key.startsWith(RuleMatcher.GENERIC_ID)) {
                    // 延迟解除武装，给 RuleMatcher 的宽限期重新武装留窗口：
                    // 盲渲开屏误点首页横幅会假确认，真跳过按钮晚进树时需还能打。
                    // 以前台包身份做守卫，防止延迟任务落到下一个前台周期上
                    val fgAtConfirm = foregroundPackage
                    handler.postDelayed({
                        if (foregroundPackage == fgAtConfirm) genericFired = true
                    }, GENERIC_DISARM_DELAY_MS)
                }
                Diag.log("action confirmed key=$key")
            }
        }
        Diag.init(this)
        Diag.log("service connected")
        com.fuchu.assistant.data.Prefs.setUnboundByUser(this, false)
        // 重连后重建前台状态：恢复规则匹配与看门狗，但通用跳过保持解除武装，
        // 避免把“已使用很久的应用”误判为刚冷启动而误点内容里的“跳过”节点
        runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
            ?.takeIf { it.isNotEmpty() && it != packageName && !isSystemPackage(it) }
            ?.let { pkg ->
                foregroundPackage = pkg
                foregroundSince = SystemClock.elapsedRealtime()
                // 重连时若屏上有“跳过”文本，说明开屏广告很可能还在展示，恢复通用跳过武装；
                // 否则维持解除武装，避免把使用已久的应用误判为冷启动误点内容
                val splashLike = runCatching {
                    rootInActiveWindow?.findAccessibilityNodeInfosByText("跳过")?.isNotEmpty()
                }.getOrNull() == true
                genericFired = !splashLike
                Diag.log("foreground restored pkg=$pkg (generic ${if (splashLike) "armed:splash-like" else "disarmed"})")
            }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                this, screenOffReceiver,
                android.content.IntentFilter(Intent.ACTION_SCREEN_OFF),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        KeepAliveService.start(this)
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Diag.log("window changed pkg=$pkg")
        }
        if (pkg == packageName) return
        if (pkg in sensitivePackages) {
            // 银行类应用：视为离开受监控应用，不跟踪、不扫描、不点击、不 OCR
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                foregroundPackage = null
                hotStrictPending = false
                Diag.log("sensitive app, monitoring skipped pkg=$pkg")
            }
            return
        }
        if (!Prefs.isEnabled(this)) {
            Diag.log("skip: global switch off")
            return
        }
        if (!Prefs.isAppEnabled(this, pkg)) {
            Diag.log("skip: app switch off pkg=$pkg")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (isSystemPackage(pkg)) {
            // 回到桌面/负一屏等系统界面：标记已离开应用，下次进入任何应用都视为新的前台周期
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && isHomeSurface(pkg)) {
                foregroundPackage = null
                hotStrictPending = false
                Diag.log("left app (home surface)")
            } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                foregroundPackage != null
            ) {
                // 锁屏界面/最近任务等 systemui 界面：同样是离开应用，但回来时不经过
                // 桌面，前台包名不变。挂起"严格模式重武装"，回来时只认 strict 候选
                foregroundPackage = null
                hotStrictPending = true
                Diag.log("left app (systemui surface)")
            }
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            updateForeground(pkg, now)
            latestPackage = pkg
            matcher?.handle(pkg)
            return
        }
        // 手动跳过学习：用户自己点了"跳过"，说明规则/回放这轮没覆盖到。
        // 落点+指纹写进学习缓存，下次冷启动回放补上。仅限冷启动窗口内，
        // 防止把内容页的"跳过片头"学进去；节点须在无障碍树内（盲渲染广告拿不到
        // 点击事件坐标，这类仍靠回放连发兜底）
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            learnFromManualClick(event, pkg)
            return
        }
        latestPackage = pkg
        // 非前台应用的内容事件不驱动扫描：防止把动作错挂到别的包名下（实测同类跳广告工具的
        // 内容事件在腾讯视频前台时触发了一个包名为 hello.litiaotiao.app 的会话并点了跳过）
        val fg = foregroundPackage
        if (fg != null && pkg != fg) return
        if (pending != null) return
        val task = Runnable {
            pending = null
            val target = latestPackage ?: return@Runnable
            matcher?.handle(target)
        }
        pending = task
        handler.postDelayed(task, THROTTLE_MS)
    }

    override fun onInterrupt() {
        Diag.log("service interrupted")
        clearPending()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Diag.log("service unbound")
        // 判定解绑来源：前台是系统设置页 = 用户手动关的，尊重意愿不自动拉回；
        // 其他场景 = ROM 静默杀服务（荣耀实测频发），交给 KeepAlive 看门狗自愈
        val byUser = latestPackage == "com.android.settings" ||
            foregroundPackage == "com.android.settings"
        com.fuchu.assistant.data.Prefs.setUnboundByUser(this, byUser)
        Diag.log("unbound byUser=$byUser fg=$foregroundPackage latest=$latestPackage")
        clearPending()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearPending()
        handler.removeCallbacks(watchdog)
        runCatching { unregisterReceiver(screenOffReceiver) }
        matcher = null
        // 不再停 KeepAliveService：ROM 静默解绑也会走到这里，若把保活一起停掉，
        // 自检看门狗恰好死在最需要它的时刻。用户手动关闭时由看门狗自己识别并退出
        super.onDestroy()
    }

    private fun resolveRules(pkg: String): AppRules? {
        val primary = RuleRepository.get(this, pkg)
        val fg = foregroundPackage
        val secondary = if (fg != null && fg != pkg) RuleRepository.get(this, fg) else null
        val global = RuleRepository.getGlobal(this)
        if (primary == null && secondary == null && global.isEmpty()) return null
        val rules = global + (primary?.popupRules ?: emptyList()) + (secondary?.popupRules ?: emptyList())
        return AppRules(
            popupRules = rules,
            clickWayPopup = primary?.clickWayPopup ?: secondary?.clickWayPopup ?: false,
            times = primary?.times ?: secondary?.times ?: 1,
            delayPopup = primary?.delayPopup ?: secondary?.delayPopup ?: 0L,
            unitePopupRules = primary?.unitePopupRules ?: secondary?.unitePopupRules ?: false,
            searchTimesPopup = primary?.searchTimesPopup ?: secondary?.searchTimesPopup ?: 5
        )
    }

    private fun updateForeground(pkg: String, now: Long) {
        if (pkg != foregroundPackage) {
            foregroundPackage = pkg
            foregroundSince = now
            genericFired = false
            actedFlag = false
            if (hotStrictPending) {
                strictOnlyUntil = now + HOT_STRICT_ONLY_MS
                hotStrictPending = false
                Diag.log("hot rearm strict-only pkg=$pkg")
            } else {
                strictOnlyUntil = 0L
            }
            matcher?.onNewForegroundPeriod(pkg)
            Diag.log("foreground package=$pkg")
        }
        // 同包不再按 15 秒续窗重置：避免使用中被“跳过片头”类节点误触；
        // 重进应用的重置由 onAccessibilityEvent 的桌面标记（foregroundPackage=null）负责
    }

    private fun sweep() {
        val fg = foregroundPackage ?: return
        if (actedFlag) return
        val elapsed = SystemClock.elapsedRealtime() - foregroundSince
        if (elapsed > SPLASH_WINDOW_MS) return
        matcher?.handle(fg)
    }

    private fun genericAllowed(): Boolean {
        if (!Prefs.isGenericSplashEnabled(this)) return false
        if (genericFired) return false
        if (foregroundPackage == null) return false
        val elapsed = SystemClock.elapsedRealtime() - foregroundSince
        return elapsed in 0..SPLASH_WINDOW_MS
    }

    private fun clearPending() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    /**
     * 手动跳过学习（P1）：冷启动 8 秒窗口内，用户亲手点了带"跳过/skip"类文本的
     * 节点，把落点中心+文本指纹写入学习缓存（source=manual）。下次冷启动回放
     * 连发会直接命中。闸门：窗口期、前台包一致、指纹必须跳过类、面积不超过
     * 屏幕 1/4——四道闸门防把内容页点击学进去。
     */
    private fun learnFromManualClick(event: AccessibilityEvent, pkg: String) {
        if (pkg != foregroundPackage) return
        val elapsed = SystemClock.elapsedRealtime() - foregroundSince
        if (elapsed > MANUAL_LEARN_WINDOW_MS) return
        val source = event.source ?: return
        val text = runCatching { source.text?.toString()?.trim() }.getOrNull().orEmpty()
        val desc = runCatching { source.contentDescription?.toString()?.trim() }.getOrNull().orEmpty()
        val fp = listOf(text, desc).firstOrNull {
            it.isNotBlank() && MANUAL_SKIP_LIKE.containsMatchIn(it)
        }?.take(40) ?: return
        val rect = android.graphics.Rect()
        runCatching { source.getBoundsInScreen(rect) }.getOrElse { return }
        if (rect.isEmpty) return
        // 自点免疫：服务自己的点击也会触发 TYPE_VIEW_CLICKED，不拦截会被当成
        // 用户手动点又学一遍（10-25 腾讯视频 learned manual×3 自回收实证）
        if (AutoTapGuard.isOurs(rect.centerX(), rect.centerY())) return
        // 与 RuleMatcher 同口径：节点 bounds 是整屏坐标系，必须用真实显示屏边界，
        // resources.displayMetrics 会减 insets（10-25 微博国际版 y=2282 误判实证）
        val m = android.util.DisplayMetrics()
        runCatching {
            val b = getSystemService(android.view.WindowManager::class.java)
                .currentWindowMetrics.bounds
            m.widthPixels = b.width()
            m.heightPixels = b.height()
        }.onFailure {
            m.widthPixels = resources.displayMetrics.widthPixels
            m.heightPixels = resources.displayMetrics.heightPixels
        }
        if (rect.width().toLong() * rect.height() * 4 > m.widthPixels.toLong() * m.heightPixels) return
        // 越界拒绝：桌面窗口化模式下节点可落在默认显示屏之外（10-26 x=1303>1080 实证），
        // 与 LearnedStore.learn 的越界拦截同口径，这里先拦并记日志
        if (rect.centerX() < 0 || rect.centerX() >= m.widthPixels ||
            rect.centerY() < 0 || rect.centerY() >= m.heightPixels
        ) {
            Diag.log("learn rejected offscreen pkg=$pkg x=${rect.centerX()} y=${rect.centerY()}")
            return
        }
        // 左上角导航禁区：x<30% 且 y<15% 是返回/关闭按钮区，跳过按钮不会在那，
        // 学了会把用户带回上一页（10-25 实测微信被学进 x=206 y=179 盲打返回区）
        if (rect.centerX() * 10 < m.widthPixels * 3 &&
            rect.centerY() * 20 < m.heightPixels * 3
        ) return
        runCatching {
            com.fuchu.assistant.data.LearnedStore.learn(
                this, pkg, rect.centerX(), rect.centerY(),
                m.widthPixels, m.heightPixels, fp, source = "manual"
            )
            Diag.log("learned manual pkg=$pkg x=${rect.centerX()} y=${rect.centerY()} fp=$fp")
        }
    }

    private fun isSystemPackage(pkg: String): Boolean {
        if (pkg == packageName) return true
        if (pkg == "android") return true
        if (pkg == "com.android.systemui") return true
        if (pkg == "com.android.permissioncontroller") return true
        if (pkg == "com.android.packageinstaller") return true
        if (pkg == "com.android.shell") return true
        if (pkg.endsWith(".systemui")) return true
        if (pkg.endsWith(".permissioncontroller")) return true
        if (pkg.contains(".launcher")) return true
        if (pkg == "com.hihonor.hiboard") return true
        return false
    }

    private fun isHomeSurface(pkg: String): Boolean =
        pkg.contains(".launcher") || pkg == "com.hihonor.hiboard"

    companion object {
        private const val THROTTLE_MS = 40L
        private const val SPLASH_WINDOW_MS = 15000L
        private const val WATCHDOG_MS = 300L
        private const val MANUAL_LEARN_WINDOW_MS = 8000L
        private const val GENERIC_DISARM_DELAY_MS = 2500L
        private const val HOT_STRICT_ONLY_MS = 4000L
        private val MANUAL_SKIP_LIKE = Regex("跳\\s*[过過]|(?i)s\\s*k\\s*i\\s*p|关闭广告")
    }
}
