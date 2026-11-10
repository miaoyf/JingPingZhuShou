package com.fuchu.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.fuchu.assistant.data.AppRules
import com.fuchu.assistant.data.LearnedStore
import com.fuchu.assistant.data.Prefs
import com.fuchu.assistant.data.Rule

class RuleMatcher(
    private val service: AccessibilityService,
    private val rulesProvider: (String) -> AppRules?,
    private val genericProvider: () -> Boolean,
    private val strictOnlyProvider: () -> Boolean = { false }
) {

    var onGenericAction: (() -> Unit)? = null
    var onAction: ((String) -> Unit)? = null
    var onVerified: ((String) -> Unit)? = null

    private class Session(
        val generation: Int,
        val packageName: String,
        val blockedTexts: Set<String>
    ) {
        val startAt = SystemClock.elapsedRealtime()
        val acted = HashSet<String>()
        val ruleBounds = HashSet<String>()
        val genericBounds = HashSet<String>()
        var genericActed = false
        var rounds = 0
        var idleRounds = 0
        var replayed = false
        var learnPoint: Point? = null
        var learnFp = ""
        // 通用跳过点中的候选文本与严格度：verify 最终放弃时把非严格候选文本记入黑名单
        var genericCandText = ""
        var genericCandStrict = false
        var cashierLogged = false
        // 每次 dispatch 的学习信息按 key 独立存放：session 共享的 learnFp/learnPoint
        // 会被后续 dispatch（如盲坐标规则）清空，导致先点中的通用跳过确认时学不进去
        //（10-25 实测 14:40 腾讯视频首轮：通用 548ms 点中，盲规则 758ms 补枪把
        //  learnFp 冲成空串，confirm 时丢失学习）
        val learnInfo = HashMap<String, Pair<Point?, String>>()
    }

    // 服务重连（ROM 静默解绑后自愈）会新建 RuleMatcher，但旧实例 handler 里滞留的
    // 回放/验证任务仍会继续发手势——实测微信被新旧两实例连打 12 发。重连时调用
    // dispose 作废旧实例的全部滞留任务
    fun dispose() {
        generation++
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    // 点击方法切换与校验函数挂在“包级”而不是“会话级”：
    // 开屏→主页的窗口切换会开新会话（generation++），会话级状态会让 verify/重试链断裂
    private val methodByKey = HashMap<String, Int>()
    private val switchesByKey = HashMap<String, Int>()
    private val checksByKey = HashMap<String, (List<AccessibilityNodeInfo>) -> Boolean>()
    private val blindFired = HashSet<String>()
    private var statePackage: String? = null

    private fun stateKey(packageName: String, key: String) = "$packageName|$key"

    private fun ensureStatePackage(packageName: String) {
        if (statePackage != packageName) {
            statePackage = packageName
            methodByKey.clear()
            switchesByKey.clear()
            checksByKey.clear()
            blindFired.clear()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var activePackage: String? = null
    private var pendingPackage: String? = null
    private var lastTextLogPackage: String? = null
    private var generation = 0
    // 每个前台周期只允许回放一次学习坐标，防止开屏→主页的窗口切换会话误点主界面
    private var replayUsedInPeriod = false
    // 回放点射最近开火时刻：OCR 兜底据此避让，不与点射撞车
    private var lastReplayFiredAt = 0L
    // 前台周期起点：学习窗口按周期算，防止开屏→主页会话重开把信息流广告学进去
    private var periodStartedAt = 0L

    // 输入法包集合：IME 会话永远不会有开屏广告，且其屏上内容是用户打字，
    // OCR 截屏与文本采集都必须排除（10-26 实测日志记到了拼音候选内容）
    private val imePackages: Set<String> by lazy {
        runCatching {
            service.packageManager.queryIntentServices(
                android.content.Intent("android.view.InputMethod"), 0
            ).mapNotNull { it.serviceInfo?.packageName }.toSet()
        }.getOrDefault(emptySet())
    }

    fun handle(packageName: String) {
        ensureStatePackage(packageName)
        if (activePackage != packageName) {
            replayUsedInPeriod = false
            periodStartedAt = SystemClock.elapsedRealtime()
        } else if (SystemClock.elapsedRealtime() - periodStartedAt > CONTENT_CUTOFF_MS) {
            // 内容期的事件不再拉起扫描会话（内容 changed 事件每几百毫秒一波，
            // 不挡这里会把诊断环形日志刷成 cutoff 刷屏，也白费 CPU）
            return
        }
        if (running) {
            if (activePackage == packageName) {
                pendingPackage = packageName
                return
            }
            generation++
            running = false
            pendingPackage = null
        }
        running = true
        activePackage = packageName
        pendingPackage = null
        generation++
        // 回放调度前置：窗口切换事件到达即排程，不等第一轮扫描（实测首轮扫描
        // 本身要 100~200ms，后置排程会把首发从 250ms 拖到 400ms+）
        scheduleLearnedReplay(packageName)
        scan(Session(generation, packageName, LearnedStore.blocked(service, packageName)))
    }

    // 同包新前台周期（15 秒内杀进程重开）：旧会话还在扫描循环里时，handle 会被
    // 当成 pending 吞掉，首轮扫描拖到旧会话过期（实测延迟 10.7 秒，广告放完）。
    // 前台包变化说明是新的冷启动周期，立刻作废旧会话、重置回放与学习窗口
    fun onNewForegroundPeriod(pkg: String) {
        if (activePackage == pkg && running) {
            generation++
            running = false
            pendingPackage = null
        }
        if (activePackage == pkg) {
            // 同周期内的重复窗口事件不允许重新武装回放（10-25 实测腾讯视频开屏两次窗口
            // 事件 14ms 内连到，回放被排了两次、同坐标多打一倍）。杀进程重开的真实新周期
            // 与上一周期相隔必然超过 1 秒，用这个阈值区分
            if (SystemClock.elapsedRealtime() - periodStartedAt > REPLAY_REARM_MIN_MS) {
                replayUsedInPeriod = false
                periodStartedAt = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun scan(session: Session) {
        if (session.generation != generation) {
            return
        }
        // 内容页截止：前台满 CONTENT_CUTOFF_MS 后通用扫描/规则/OCR 全部停止。
        // 10-25 实测：用户在微博内容页浏览 12.7 秒时，通用扫描的宽松匹配把"关闭广告"
        // 文案节点点了，导致内容页被关。开屏广告生命周期都在前几秒，超期扫描只会误伤
        if (SystemClock.elapsedRealtime() - periodStartedAt > CONTENT_CUTOFF_MS) {
            Diag.log("content cutoff pkg=${session.packageName} elapsed=${SystemClock.elapsedRealtime() - periodStartedAt}ms")
            finish(session)
            return
        }
        session.rounds++
        val roundStart = SystemClock.elapsedRealtime()
        var acted = false
        val roots = windowRoots(session.packageName)
        val rules = rulesProvider(session.packageName)
        val db = displayBounds()
        val metrics = android.util.DisplayMetrics().apply {
            widthPixels = db.width()
            heightPixels = db.height()
        }
        // 确认后宽限期重新武装：开屏盲渲期可能先点了首页横幅（非 strict 候选）并被
        // 误确认，真跳过按钮晚几百毫秒才进树。宽限期内发现 strict 候选允许再打一次
        val rearm = session.genericActed &&
            SystemClock.elapsedRealtime() - session.startAt <= GENERIC_REARM_MS
        val genericOn = (!session.genericActed || rearm) &&
            genericProvider() && !isSystemPackage(session.packageName)
        var genericResult: SplashSkipper.ScanResult? = null
        val needFull = session.rounds % RULE_SCAN_EVERY == 1
        // 界面文本采集（texts=）默认关闭：只在用户打开"详细诊断"开关后采集。
        // 该文本可能含拼音、号码等敏感内容，自用排查才需要（10-27 定）
        val collectTexts = session.rounds == 1 && Prefs.isDiagTextEnabled(service)
        if (roots.isNotEmpty() && genericOn) {
            val fast = SplashSkipper.scanRootsFast(
                roots,
                metrics.widthPixels,
                metrics.heightPixels,
                session.ruleBounds,
                session.blockedTexts
            )
            genericResult = fast
            if (fast.candidate == null || (!fast.strict && needFull)) {
                val full = SplashSkipper.scanRoots(
                    roots,
                    metrics.widthPixels,
                    metrics.heightPixels,
                    session.ruleBounds,
                    collectTexts,
                    session.blockedTexts
                )
                if (full.candidate != null || fast.candidate == null) genericResult = full
                else if (full.texts.isNotEmpty()) genericResult = full
            }
        } else if (roots.isNotEmpty() && needFull) {
            genericResult = SplashSkipper.scanRoots(
                roots,
                metrics.widthPixels,
                metrics.heightPixels,
                session.ruleBounds,
                collectTexts,
                session.blockedTexts
            )
        }
        if (session.rounds == 1) {
            Diag.log("scan pkg=${session.packageName} nodes=${genericResult?.visited ?: 0} rules=${rules?.popupRules?.size ?: -1}")
            Diag.log("windows=" + runCatching {
                service.windows.joinToString(",") { w ->
                    val windowRoot = runCatching { w.root }.getOrNull()
                    (w.id.toString() + ":" + w.type.toString() + ":" +
                        (windowRoot?.packageName?.toString() ?: "null") + ":" + w.isActive)
                }
            }.getOrDefault("?"))
            val collected = genericResult?.texts ?: emptyList()
            if (collected.isNotEmpty() && lastTextLogPackage != session.packageName &&
                session.packageName !in imePackages
            ) {
                lastTextLogPackage = session.packageName
                Diag.log("texts=" + collected.joinToString("|"))
            }
        }
        if (genericOn && genericResult != null) {
            val candidate = genericResult.candidate
            // 宽限期重新武装或热启动严格模式（锁屏/最近任务路径回来）时只认 strict：
            // 防止对已点过的非严格候选（横幅等）重复开火，防内容页宽松匹配误点。
            // 宽松候选再加时间窗：前台超过 LOOSE_WINDOW_MS 后"关闭广告"这类模糊文案
            // 一律视为内容页不再开火（10-27 微博前台 4.9s 点"关闭广告"无法证实是否
            // 误点，与 10-25 微博 12.7s 确认误点同类，按保守路线收紧）；精确"跳过"
            // 严格候选不受影响，覆盖完整内容截止期
            val strictRequired = session.genericActed || strictOnlyProvider() ||
                SystemClock.elapsedRealtime() - periodStartedAt > LOOSE_WINDOW_MS
            // acted 防抖：点过一发后等 verify 裁决（250ms 后没点掉会自动从 acted 移除
            // 再放行）。不防抖的话扫描 30ms 一轮会在广告消失动画期间连点同坐标 4-5 次，
            // 多出来的点击落到首页同位置把商城/活动页点开（10-25 腾讯视频 GOODS 实证）
            val alreadyActed = session.acted.contains(GENERIC_ID + "" + GENERIC_ID)
            if (candidate != null && (!strictRequired || genericResult.strict) && !alreadyActed) {
                val rule = Rule(GENERIC_ID, GENERIC_ID, GENERIC_TIMES)
                val check: (List<AccessibilityNodeInfo>) -> Boolean = { current ->
                    SplashSkipper.findCandidate(
                        current, metrics.widthPixels, metrics.heightPixels,
                        emptySet(), session.blockedTexts
                    ) != null
                }
                if (dispatch(session, rule, candidate, candidate, 0, check, session.genericBounds)) {
                    session.genericActed = true
                    session.genericCandText = session.learnFp
                    session.genericCandStrict = genericResult?.strict == true
                    acted = true
                    onGenericAction?.invoke()
                }
            }
        }
        if (!acted && rules != null && roots.isNotEmpty() && session.rounds % RULE_SCAN_EVERY == 1) {
            val nodes = collectAll(roots)
            // 收银台保护：付款/输密页面禁止一切规则点击（10-25 实测支付宝付款确认页
            // 的"关闭"被"推荐广告→关闭"规则连点 5 发，页面被关、支付被打断）
            val cashier = nodes.any { n ->
                runCatching { n.text?.toString() }.getOrNull()
                    ?.let { CASHIER_PATTERN.containsMatchIn(it) } == true
            }
            if (cashier && !session.cashierLogged) {
                session.cashierLogged = true
                Diag.log("cashier guard pkg=${session.packageName} rules paused")
            }
            for (rule in rules.popupRules) {
                if (cashier) break
                val key = ruleKey(rule)
                if (session.acted.contains(key)) continue
                // 盲坐标规则：跳过按钮不进无障碍树时按坐标点按，每个前台周期只发一次。
                // 延迟 600ms 发：第 1 轮（开屏后约 30ms）广告视图往往还没渲染，点了白点。
                // 通用跳过已得手或本会话已有动作时让位不发：广告消失动画期间补枪会落到
                // 首页同坐标点开别的页面（10-25 腾讯视频 GOODS 商城误进实证）
                if (rule.id.isBlank() && NodeMatcher.parseCoordinate(rule.action) != null) {
                    if (session.genericActed || session.acted.isNotEmpty()) continue
                    val skey = stateKey(session.packageName, key)
                    if (blindFired.contains(skey)) continue
                    blindFired.add(skey)
                    handler.postDelayed({
                        if (session.packageName != activePackage) return@postDelayed
                        // 学习回放刚出过手就让位：广告已被回放点掉时这发补枪会落在
                        // 首页同坐标（10-25 实测 14:25 回放 54.138 跳掉广告，盲规则
                        // 54.597 仍补打在首页 (944,203)）
                        if (SystemClock.elapsedRealtime() - lastReplayFiredAt <= BLIND_REPLAY_GAP_MS) {
                            Diag.log("blind rule yield (replay fired) pkg=${session.packageName}")
                            return@postDelayed
                        }
                        // 发射时再判让位（调度时通用跳过往往还没得手，600ms 后形势已变）：
                        // 通用跳过已得手或本会话已有动作，这发补枪会落在广告消失后的
                        // 首页同坐标（10-25 实测 14:40 腾讯视频通用 548ms 得手，
                        // 盲规则 758ms 仍补打 (944,203)）
                        if (session.genericActed || session.acted.isNotEmpty()) {
                            Diag.log("blind rule yield (acted) pkg=${session.packageName}")
                            return@postDelayed
                        }
                        dispatch(session, rule, null, null, 0, null, session.ruleBounds)
                    }, BLIND_FIRE_MS)
                    continue
                }
                val idNode = locate(nodes, rule.id)
                // 锚点必须在场：id 是定位锚（如"推荐广告"），仅动作词（如"关闭"）
                // 命中就开火，会在任何带"关闭"按钮的页面误点（10-25 支付宝实证）
                if (idNode == null && idIsMatcher(rule.id)) continue
                val actionNode = locate(nodes, rule.action)
                if (idNode == null && actionNode == null) continue
                if (dispatch(session, rule, idNode, actionNode, 0, null, session.ruleBounds)) {
                    acted = true
                }
            }
        }
        if (session.rounds <= 3) {
            Diag.log("round=${session.rounds} dur=${SystemClock.elapsedRealtime() - roundStart}ms")
        }
        if (acted) session.idleRounds = 0 else session.idleRounds++
        val expired = SystemClock.elapsedRealtime() - session.startAt > SESSION_MAX_MS
        if (expired || session.rounds >= MAX_ROUNDS ||
            (session.idleRounds >= MAX_IDLE_ROUNDS && session.rounds > 1)
        ) {
            finish(session)
            return
        }
        handler.postDelayed({ scan(session) }, scanInterval(session.rounds))
    }

    private fun finish(session: Session) {
        if (session.generation != generation) return
        running = false
        val next = pendingPackage
        pendingPackage = null
        if (next != null) {
            handler.postDelayed({ handle(next) }, RESUME_DELAY)
        }
    }

    private fun dispatch(
        session: Session,
        rule: Rule,
        idNode: AccessibilityNodeInfo?,
        actionNode: AccessibilityNodeInfo?,
        attempt: Int,
        verifyCheck: ((List<AccessibilityNodeInfo>) -> Boolean)?,
        boundsTarget: MutableSet<String>
    ): Boolean {
        if (session.generation != generation) return false
        val key = ruleKey(rule)
        val skey = stateKey(session.packageName, key)
        val method = methodByKey[skey] ?: METHOD_CLICK
        val done = perform(rule, idNode, actionNode, method)
        if (done) {
            session.acted.add(key)
            // 自点免疫标记：这发点击产生的 TYPE_VIEW_CLICKED 不应被手动学习回收
            (NodeMatcher.parseCoordinate(rule.action)
                ?: listOfNotNull(idNode, actionNode).firstOrNull()?.let { n ->
                    NodeMatcher.boundsOf(n)?.let { Point(it.centerX(), it.centerY()) }
                })?.let { AutoTapGuard.mark(it.x, it.y) }
            listOfNotNull(idNode, actionNode).forEach { node ->
                NodeMatcher.boundsKey(node)?.let { boundsTarget.add(it) }
            }
            if (verifyCheck != null) checksByKey[skey] = verifyCheck
            // 捕获学习信息：坐标规则直接用规则坐标，否则用节点中心；指纹取节点文本。
            // 面积闸门：匹配到的若是大容器（可点祖先常是整个开屏页），节点中心会落到
            // 屏幕中部/底部污染学习缓存（10-24 实测微博轻享版好坐标被覆盖成 y=2282 底部），
            // 超过 1/4 屏的节点不学坐标
            session.learnPoint = NodeMatcher.parseCoordinate(rule.action)
                ?: listOfNotNull(idNode, actionNode).firstOrNull()?.let { n ->
                    NodeMatcher.boundsOf(n)?.let { b ->
                        val db2 = displayBounds()
                        val tooBig = b.width().toLong() * b.height() * 4 >
                            db2.width().toLong() * db2.height()
                        if (tooBig) null else Point(b.centerX(), b.centerY())
                    }
                }
            session.learnFp = listOfNotNull(idNode, actionNode).firstOrNull()?.let { n ->
                runCatching { n.text?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: runCatching { n.contentDescription?.toString() }.getOrNull().orEmpty()
            }?.take(40) ?: ""
            session.learnInfo[key] = session.learnPoint to session.learnFp
            Diag.log("acted key=$key method=$method attempt=$attempt after=${SystemClock.elapsedRealtime() - session.startAt}ms")
            onAction?.invoke(key)
            if (verifiable(rule)) {
                handler.postDelayed({ verify(session, rule, key) }, VERIFY_DELAY)
            } else {
                confirmLearn(session, key)
            }
            return true
        }
        if (attempt + 1 >= rule.times.coerceAtLeast(1)) {
            // 彻底失败（如越界被拦）：把候选 bounds 排除出后续扫描，否则扫描 30ms 一轮
            // 会反复重试同一个永远点不成的目标（10-25 微博国际版 offscreen 连拒 40+ 发实证）
            listOfNotNull(idNode, actionNode).forEach { node ->
                NodeMatcher.boundsKey(node)?.let { boundsTarget.add(it) }
            }
            return false
        }
        handler.postDelayed({
            if (session.generation != generation) return@postDelayed
            val nodes = collectAll(windowRoots(session.packageName))
            dispatch(session, rule, locate(nodes, rule.id), locate(nodes, rule.action), attempt + 1, verifyCheck, boundsTarget)
        }, RETRY_INTERVAL)
        return false
    }

    // 跳过确认后把落点写入自学习缓存（通用规则 GENERIC_ID 也学习，这是泛化的核心来源）
    // 只学开屏窗口期内的确认：超时还在点的大概率是信息流广告，学了会污染回放坐标
    private fun confirmLearn(session: Session, key: String) {
        val inSplashWindow = SystemClock.elapsedRealtime() - periodStartedAt <= LEARN_WINDOW_MS
        // 指纹必须是跳过类才学：弹窗规则（如"开启通知权限→取消"）确认成功后会把
        // 取消按钮坐标覆盖掉跳过按钮的好记录（10-24 实测喜马拉雅被污染）。
        // 回放端虽有指纹闸门不点，但原记录已被顶掉，只能在源头堵住。
        // 例外：通用跳过 key 的空指纹候选允许学（纯图标跳过按钮），见下方 genericBlankOk。
        // 学习信息按 key 取：会话共享字段会被后续 dispatch 冲掉（10-25 实测 14:40
        // 盲规则补枪把通用跳过的 learnFp 冲成空串导致学不进去）
        val info = session.learnInfo[key]
        val learnFp = info?.second ?: session.learnFp
        val learnPoint = info?.first ?: session.learnPoint
        val learnable = SKIP_LIKE_PATTERN.containsMatchIn(learnFp)
        // 通用跳过的空指纹候选允许入库：有些广告跳过按钮是纯图标无文本（10-27 微博国际版
        // 实测 click ancestor text= 空串，学了空指纹才能进入回放通道，否则每次都走
        // 通用扫描全程慢在 1 秒以上）。安全性由三层兜底：通用候选本身已经过了跳过系
        // 匹配才会被点；信任门槛要求确认成功满 2 次才允许盲射；导航禁区/越界/开屏窗口
        // 三重拦截不变。规则类 key 的空指纹仍不学（防弹窗规则确认污染坐标，10-24 实证）
        val genericBlankOk = key.startsWith(GENERIC_ID) && learnFp.isBlank()
        if (inSplashWindow && ((learnFp.isNotBlank() && learnable) || genericBlankOk)) learnPoint?.let { pt ->
            val db3 = displayBounds()
            // 左上角导航禁区（x<30% 且 y<15%）：返回/关闭按钮区不是跳过按钮，
            // 学了会在每次回应用时盲打返回区（10-25 微信污染实证）
            if (pt.x * 10 < db3.width() * 3 && pt.y * 20 < db3.height() * 3) {
                Diag.log("learn rejected nav-corner pkg=${session.packageName} x=${pt.x} y=${pt.y}")
            } else runCatching {
                LearnedStore.learn(
                    service, session.packageName, pt.x, pt.y,
                    db3.width(), db3.height(), learnFp
                )
                Diag.log("learned save pkg=${session.packageName} x=${pt.x} y=${pt.y} fp=$learnFp")
            }
        }
        onVerified?.invoke(key)
    }

    // 冷启动首轮：若本应用有学习记录，按缓存比例坐标预点，不等规则/文本匹配。
    // 点发（250/450/650/850ms）：盲渲染开屏（腾讯系等）按钮可点时机不固定，
    // 单发 700ms 命中晚则用户看到近 1 秒广告；早发+多发把落点压到按钮刚可点的瞬间。
    // 有树场景在指纹可见的第一发即点，后续自动作废；盲场景 4 发全打。
    private fun scheduleLearnedReplay(packageName: String) {
        if (replayUsedInPeriod) return
        // 回放全局限速：10-25 实测微博国际版热启动 4 秒内 4 个前台事件各触发一轮
        // 回放（热重新武装门槛 1s 完全拦不住），trusted 候选在小树上被连环盲射。
        // 冷启动两次间隔必然超过 8 秒，限速不影响正常场景
        if (SystemClock.elapsedRealtime() - lastReplayFiredAt < REPLAY_MIN_INTERVAL_MS) {
            Diag.log("learned replay skip (rate limited) pkg=$packageName")
            return
        }
        replayUsedInPeriod = true
        if (isSystemPackage(packageName)) return
        val aborted = java.util.concurrent.atomic.AtomicBoolean(false)
        REPLAY_BURST_MS.forEachIndexed { idx, delay ->
            handler.postDelayed({
                if (aborted.get()) return@postDelayed
                if (packageName != activePackage) return@postDelayed
                // 前台闸门：事件包名≠可见应用时（其他应用的悬浮窗/后台窗口事件也会
                // 驱动回放调度），回放 tap 会打到别人屏幕上。10-24 实测同类工具的窗口
                // 事件在腾讯视频前台时触发了回放误点，此处按真实前台窗口再校验一次
                val visiblePkg = runCatching {
                    service.rootInActiveWindow?.packageName?.toString()
                }.getOrNull()
                if (visiblePkg != null && visiblePkg != packageName) {
                    aborted.set(true)
                    Diag.log("learned replay skip (not foreground) pkg=$packageName visible=$visiblePkg")
                    return@postDelayed
                }
                val entry = LearnedStore.get(service, packageName) ?: return@postDelayed
                // 指纹闸门：非跳过类指纹（如信息流"广告"标）说明是误学记录，不点且计失败加速作废
                if (entry.fingerprint.isNotBlank() &&
                    !SKIP_LIKE_PATTERN.containsMatchIn(entry.fingerprint)
                ) {
                    aborted.set(true)
                    Diag.log("learned replay skip (fp not skip-like) pkg=$packageName fp=${entry.fingerprint}")
                    LearnedStore.outcome(service, packageName, false, entry.fingerprint)
                    return@postDelayed
                }
                val dbR = displayBounds()
                // 宽高比校验：折叠屏展开/折叠态开屏版式会重新排版，比例坐标平移后必然点偏，
                // 形态变了就放弃回放，交给通用扫描按当前树重新找（找到后会学进新比例）
                if (entry.screenW > 0 && entry.screenH > 0) {
                    val oldRatio = entry.screenW.toFloat() / entry.screenH
                    val curRatio = dbR.width().toFloat() / dbR.height()
                    if (kotlin.math.abs(curRatio - oldRatio) / oldRatio > 0.05f) {
                        aborted.set(true)
                        Diag.log("learned replay skip (aspect changed) pkg=$packageName old=${entry.screenW}x${entry.screenH} cur=${dbR.width()}x${dbR.height()}")
                        return@postDelayed
                    }
                }
                // 信任门槛：确认成功满 2 次的候选才允许盲树点射。新候选第一轮回放只在
                // 树填充且指纹可见时单发（等于复验一次），防止单次误确认的坐标被盲打出去
                val trusted = entry.success >= TRUST_SUCCESS
                val roots = windowRoots(packageName)
                val nodeCount = collectAll(roots).size
                if (nodeCount >= BLIND_TREE_THRESHOLD) {
                    if (entry.fingerprint.isNotBlank()) {
                        // 树已填充：指纹可见 = 广告在屏，点；不可见 = 无广告或已跳掉，整组作废
                        val fpVisible = roots.any { r ->
                            runCatching {
                                r.findAccessibilityNodeInfosByText(entry.fingerprint)?.isNotEmpty()
                            }.getOrNull() == true
                        }
                        if (!fpVisible) {
                            aborted.set(true)
                            Diag.log("learned replay skip (fp absent) pkg=$packageName nodes=$nodeCount round=$idx")
                            return@postDelayed
                        }
                        // 有树场景点一发就够，后续作废
                        aborted.set(true)
                    } else if (!trusted) {
                        Diag.log("learned replay hold (untrusted, no fp) pkg=$packageName s=${entry.success}")
                        return@postDelayed
                    }
                } else {
                    if (!trusted) {
                        // 盲树+不信任：本发不打也不作废，等下一发 tick 树填充后再判
                        Diag.log("learned replay hold (untrusted blind) pkg=$packageName nodes=$nodeCount s=${entry.success}")
                        return@postDelayed
                    }
                    if (idx == 0) {
                        Diag.log("learned replay blind-tree pkg=$packageName nodes=$nodeCount")
                    }
                }
                val pt = Point(
                    (entry.xRatio * dbR.width()).toInt(),
                    (entry.yRatio * dbR.height()).toInt()
                )
                Diag.log("learned replay pkg=$packageName x=${pt.x} y=${pt.y} fp=${entry.fingerprint} burst=$idx")
                lastReplayFiredAt = SystemClock.elapsedRealtime()
                tapPoint(pt)
                if (idx == REPLAY_BURST_MS.size - 1 || aborted.get()) {
                    handler.postDelayed({ verifyReplay(packageName, entry) }, REPLAY_VERIFY_MS)
                }
            }, delay)
        }
    }

    private fun verifyReplay(packageName: String, entry: LearnedStore.Entry) {
        if (packageName != activePackage) return
        val roots = windowRoots(packageName)
        // 指纹仍在 = 预点没跳掉，记一次失败；指纹消失无法区分"跳掉了/本次无广告"，保持中性
        // 只查目标应用的窗口，避免本应用诊断页等无关文本造成误判
        val stillThere = if (entry.fingerprint.isBlank()) false else roots.any { r ->
            runCatching {
                r.findAccessibilityNodeInfosByText(entry.fingerprint)?.isNotEmpty()
            }.getOrNull() == true
        }
        if (stillThere) {
            LearnedStore.outcome(service, packageName, false, entry.fingerprint)
            Diag.log("learned replay MISS pkg=$packageName")
            return
        }
        // 盲回放后验：树已填充且屏上另有跳过候选（我们没点中的真按钮），说明坐标
        // 是污染的（实测微信被学进左上角 x=206 y=179，每次回来盲打返回键区）。
        // 中性化的旧逻辑永远不会作废这类条目，此处补刀计失败，3 次自动清除
        if (collectAll(roots).size >= BLIND_TREE_THRESHOLD) {
            val dbV = displayBounds()
            val other = SplashSkipper.findCandidate(
                collectAll(roots), dbV.width(), dbV.height(),
                emptySet(),
                LearnedStore.blocked(service, packageName)
            )
            if (other != null) {
                LearnedStore.outcome(service, packageName, false, entry.fingerprint)
                Diag.log("learned replay MISS (other candidate visible) pkg=$packageName")
                return
            }
        }
        Diag.log("learned replay ok/neutral pkg=$packageName")
    }

    private fun verify(session: Session, rule: Rule, key: String) {
        // 不按 generation 丢弃：会话可能已被“开屏→主页”窗口切换替换，
        // 只要仍在同一应用内，验证与方法切换就必须生效，否则点了没点掉时永远不会重试
        if (session.packageName != activePackage) return
        val skey = stateKey(session.packageName, key)
        val nodes = collectAll(windowRoots(session.packageName))
        val check = checksByKey[skey]
        val stillThere = if (check != null) {
            runCatching { check(nodes) }.getOrDefault(false)
        } else {
            (locate(nodes, rule.id) ?: locate(nodes, rule.action)) != null
        }
        if (!stillThere) {
            confirmLearn(session, key)
            return
        }
        val switches = switchesByKey[skey] ?: 0
        if (switches >= MAX_SWITCHES) {
            // 点了 4 发还没点掉的非严格候选：基本是点不动的信息流横幅，文本记入该应用
            // 黑名单永久排除（严格候选是精确匹配的"跳过"按钮，倒计时期间点不掉属正常，不伤）
            if (key.startsWith(GENERIC_ID) && !session.genericCandStrict &&
                session.genericCandText.isNotBlank()
            ) {
                LearnedStore.block(service, session.packageName, session.genericCandText)
                Diag.log("blocked add pkg=${session.packageName} text=${session.genericCandText}")
            }
            return
        }
        switchesByKey[skey] = switches + 1
        methodByKey[skey] = if ((methodByKey[skey] ?: METHOD_CLICK) == METHOD_CLICK) METHOD_TAP else METHOD_CLICK
        session.acted.remove(key)
        if (key.startsWith(GENERIC_ID)) session.genericActed = false
        Diag.log("verify retry key=$key method=${methodByKey[skey]}")
    }

    private fun perform(
        rule: Rule,
        idNode: AccessibilityNodeInfo?,
        actionNode: AccessibilityNodeInfo?,
        method: Int
    ): Boolean {
        val action = rule.action.trim()
        if (action.isEmpty()) {
            val target = idNode ?: actionNode
            return target != null && click(target, method)
        }
        if (action.equals(BACK, ignoreCase = true) || action == GLOBAL_BACK) {
            return runCatching {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }.getOrDefault(false)
        }
        val coordinate = NodeMatcher.parseCoordinate(action)
        if (coordinate != null) {
            return tapPoint(coordinate)
        }
        if (action == rule.id.trim()) {
            val target = idNode ?: actionNode
            return target != null && click(target, method)
        }
        if (actionNode != null) {
            return click(actionNode, method)
        }
        if (idNode != null && matches(idNode, action)) {
            return click(idNode, method)
        }
        return false
    }

    private fun verifiable(rule: Rule): Boolean {
        val action = rule.action.trim()
        if (action.isEmpty()) return true
        if (action.equals(BACK, ignoreCase = true) || action == GLOBAL_BACK) return false
        return NodeMatcher.parseCoordinate(action) == null
    }

    private fun locate(nodes: List<AccessibilityNodeInfo>, key: String): AccessibilityNodeInfo? {
        if (key.isBlank()) return null
        val trimmed = key.trim()
        if (trimmed.equals(BACK, ignoreCase = true) || trimmed == GLOBAL_BACK) return null
        if (NodeMatcher.parseCoordinate(trimmed) != null) return null
        return NodeMatcher.bestMatch(nodes, trimmed)
    }

    // id 是否为真实定位锚（文本/id 匹配器）：BACK/坐标/空白不是锚，不参与锚点必须在场判定
    private fun idIsMatcher(id: String): Boolean {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.equals(BACK, ignoreCase = true) || trimmed == GLOBAL_BACK) return false
        return NodeMatcher.parseCoordinate(trimmed) == null
    }

    private fun matches(node: AccessibilityNodeInfo, key: String): Boolean {
        val alternatives = NodeMatcher.splitAlternatives(key)
        if (alternatives.isEmpty()) return false
        return alternatives.any { alternative ->
            NodeMatcher.bestMatch(listOf(node), alternative) != null
        }
    }

    private fun click(node: AccessibilityNodeInfo, method: Int): Boolean {
        if (method == METHOD_CLICK) {
            if (clickByAction(node)) return true
            return tap(node)
        }
        if (tap(node)) return true
        return clickByAction(node)
    }

    private fun clickByAction(node: AccessibilityNodeInfo): Boolean {
        val self = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
        if (self) {
            Diag.log("click node text=${NodeMatcher.textOf(node).replace('\u0000', '|')}")
            return true
        }
        val ancestor = NodeMatcher.clickableAncestor(node) ?: return false
        val hit = runCatching {
            ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
        if (hit) Diag.log("click ancestor text=${NodeMatcher.textOf(ancestor).replace('\u0000', '|')}")
        return hit
    }

    private fun tap(node: AccessibilityNodeInfo): Boolean {
        val bounds = NodeMatcher.boundsOf(node) ?: return false
        val point = Point(bounds.exactCenterX().toInt(), bounds.exactCenterY().toInt())
        Diag.log("tap x=${point.x} y=${point.y} text=${NodeMatcher.textOf(node).replace('\u0000', '|')}")
        return tapPoint(point)
    }

    // 真实显示屏边界：无障碍节点 bounds 用的是整屏坐标系，而 service.resources
    // .displayMetrics 会减去状态栏/导航栏 insets（10-25 实测微博国际版跳过按钮
    // y=2282 被误判越界连拒 40+ 发，displayMetrics 高度比整屏 2420 小）。
    // 一切与节点坐标/手势坐标互算的口径统一走这里
    private fun displayBounds(): android.graphics.Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = service.getSystemService(android.view.WindowManager::class.java)
            val b = runCatching { wm?.currentWindowMetrics?.bounds }.getOrNull()
            if (b != null && b.width() > 0 && b.height() > 0) return b
        }
        val m = service.resources.displayMetrics
        return android.graphics.Rect(0, 0, m.widthPixels, m.heightPixels)
    }

    private fun tapPoint(point: Point): Boolean {
        // 越界拦截：折叠屏/多窗口场景节点坐标可能超出当前显示屏（实测 y=4685 打在
        // 2420 高的屏上），这种手势必落空且会被误判成功，直接拒绝交给重试链
        val b = displayBounds()
        if (point.x < 0 || point.y < 0 || point.x >= b.width() || point.y >= b.height()) {
            Diag.log("tap rejected offscreen x=${point.x} y=${point.y}")
            return false
        }
        AutoTapGuard.mark(point.x, point.y)
        val path = Path().apply {
            addCircle(point.x.toFloat(), point.y.toFloat(), TAP_RADIUS, Path.Direction.CW)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION))
            .build()
        return runCatching { service.dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    private fun windowRoots(targetPackage: String): List<AccessibilityNodeInfo> {
        val own = service.packageName
        if (targetPackage.isBlank() || targetPackage == own) return emptyList()
        val roots = ArrayList<AccessibilityNodeInfo>(4)
        runCatching { service.windows }.getOrNull()?.forEach { window ->
            val windowRoot = runCatching { window.root }.getOrNull() ?: return@forEach
            val windowPackage = runCatching { windowRoot.packageName?.toString() }.getOrNull()
            if (windowPackage.isNullOrEmpty()) return@forEach
            if (windowPackage == own || isSystemPackage(windowPackage)) return@forEach
            roots.add(windowRoot)
        }
        val active = runCatching { service.rootInActiveWindow }.getOrNull()
        if (active != null) {
            val activePackage = runCatching { active.packageName?.toString() }.getOrNull()
            if (!activePackage.isNullOrEmpty() && activePackage != own && !isSystemPackage(activePackage)) {
                roots.add(active)
            }
        }
        return roots
    }

    private fun collectAll(roots: List<AccessibilityNodeInfo>): List<AccessibilityNodeInfo> {
        val own = service.packageName
        val collected = LinkedHashSet<AccessibilityNodeInfo>(512)
        for (item in roots) {
            for (node in NodeMatcher.collectNodes(item)) collected.add(node)
        }
        return collected.filter { node ->
            val pkg = runCatching { node.packageName?.toString() }.getOrNull() ?: return@filter false
            pkg != own && !isSystemPackage(pkg)
        }
    }

    private fun isSystemPackage(packageName: String): Boolean {
        if (packageName == service.packageName) return true
        if (packageName == "android") return true
        if (packageName == "com.android.systemui") return true
        if (packageName == "com.android.settings") return true
        if (packageName == "com.android.permissioncontroller") return true
        if (packageName == "com.android.packageinstaller") return true
        if (packageName == "com.android.shell") return true
        if (packageName.endsWith(".systemui")) return true
        if (packageName.endsWith(".permissioncontroller")) return true
        if (packageName.contains(".launcher")) return true
        if (packageName == "com.hihonor.hiboard") return true
        return false
    }

    private fun ruleKey(rule: Rule): String = rule.id + "\u0001" + rule.action

    private fun scanInterval(rounds: Int): Long = when {
        rounds <= FAST_ROUNDS -> FAST_INTERVAL
        rounds <= MID_ROUNDS -> MID_INTERVAL
        else -> SLOW_INTERVAL
    }

    companion object {
        private const val MAX_ROUNDS = 400
        private const val MAX_IDLE_ROUNDS = 200
        private const val SESSION_MAX_MS = 15000L
        private const val FAST_ROUNDS = 60
        private const val MID_ROUNDS = 120
        private const val FAST_INTERVAL = 30L
        private const val MID_INTERVAL = 150L
        private const val SLOW_INTERVAL = 300L
        private const val RULE_SCAN_EVERY = 4
        private const val RETRY_INTERVAL = 120L
        private const val RESUME_DELAY = 60L
        private const val VERIFY_DELAY = 250L
        private const val TAP_DURATION = 50L
        private const val TAP_RADIUS = 1.5f
        private const val BACK = "back"
        private const val GLOBAL_BACK = "GLOBAL_ACTION_BACK"
        const val GENERIC_ID = "__ltt_generic_skip__"
        private const val GENERIC_TIMES = 3
        private const val METHOD_TAP = 0
        private const val METHOD_CLICK = 1
        private const val MAX_SWITCHES = 3
        // 点发（150~900ms 六发）：盲渲染开屏（腾讯系等）按钮可点时机不固定，
        // 间隔越密越容易压住按钮"刚可点"的瞬间；树填充且无指纹时立即整组作废，
        // 无广告场景最多盲打前两三发，落点都是已学的小按钮区域，风险可控
        private val REPLAY_BURST_MS = longArrayOf(150L, 300L, 450L, 600L, 750L, 900L)
        private const val REPLAY_VERIFY_MS = 600L
        private const val BLIND_TREE_THRESHOLD = 10
        private const val LEARN_WINDOW_MS = 2500L
        private const val BLIND_FIRE_MS = 600L
        private const val GENERIC_REARM_MS = 2500L
        // 盲树点射信任门槛：确认成功满此次数的候选才允许无树盲打；新候选首轮回放
        // 只在树填充且指纹可见时单发复验
        private const val TRUST_SUCCESS = 2
        // 同周期重复窗口事件的回放重新武装最小间隔：短于此值视为同周期噪声事件
        private const val REPLAY_REARM_MIN_MS = 1000L
        // 回放全局最小间隔：两次回放点射组之间至少隔这么久，防止反复前台事件连环盲射
        private const val REPLAY_MIN_INTERVAL_MS = 8000L
        // 内容页截止：前台超过此时长后通用扫描/规则/OCR 全部停止，防内容页误点
        private const val CONTENT_CUTOFF_MS = 8000L
        // 宽松候选时间窗：前台超过此时长后非严格候选（"关闭广告"等模糊文案）不再开火
        private const val LOOSE_WINDOW_MS = 3000L
        // 回放点射开火后该时长内盲坐标规则让位，防止对已跳掉的广告补枪
        private const val BLIND_REPLAY_GAP_MS = 1500L
        private val SKIP_LIKE_PATTERN = Regex("跳|(?i)skip")
        // 收银台页面特征：出现即暂停全部规则点击
        private val CASHIER_PATTERN = Regex("确认付款|支付金额|输入支付密码|支付密码|确认支付|付款密码")
    }
}
