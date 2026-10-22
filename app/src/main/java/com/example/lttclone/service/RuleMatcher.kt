package com.example.lttclone.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.example.lttclone.data.AppRules
import com.example.lttclone.data.Rule

class RuleMatcher(
    private val service: AccessibilityService,
    private val rulesProvider: (String) -> AppRules?,
    private val genericProvider: () -> Boolean
) {

    var onGenericAction: (() -> Unit)? = null
    var onAction: ((String) -> Unit)? = null
    var onVerified: ((String) -> Unit)? = null

    private class Session(val generation: Int, val packageName: String) {
        val acted = HashSet<String>()
        val methods = HashMap<String, Int>()
        val switches = HashMap<String, Int>()
        val checks = HashMap<String, (List<AccessibilityNodeInfo>) -> Boolean>()
        val ruleBounds = HashSet<String>()
        val genericBounds = HashSet<String>()
        var genericActed = false
        var rounds = 0
        var idleRounds = 0
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var pendingPackage: String? = null
    private var lastTextLogPackage: String? = null
    private var generation = 0

    fun handle(packageName: String) {
        if (running) {
            pendingPackage = packageName
            return
        }
        running = true
        pendingPackage = null
        generation++
        scan(Session(generation, packageName))
    }

    private fun scan(session: Session) {
        if (session.generation != generation) {
            finish()
            return
        }
        session.rounds++
        var acted = false
        var ruleActed = false
        val root = runCatching { service.rootInActiveWindow }.getOrNull()
        if (root != null) {
            val nodes = scopedNodes(root, session.packageName)
            val rules = rulesProvider(session.packageName)
            if (session.rounds == 1) {
                Diag.log("scan pkg=${session.packageName} nodes=${nodes.size} rules=${rules?.popupRules?.size ?: -1}")
                Diag.log("windows=" + runCatching {
                    service.windows.joinToString(",") { w ->
                        val root = runCatching { w.root }.getOrNull()
                        (w.id.toString() + ":" + w.type.toString() + ":" +
                            (root?.packageName?.toString() ?: "null") + ":" + w.isActive)
                    }
                }.getOrDefault("?"))
                if (lastTextLogPackage != session.packageName) {
                    lastTextLogPackage = session.packageName
                    Diag.log("texts=" + nodes.take(150).joinToString("|") {
                        NodeMatcher.textOf(it).replace('\u0000', '|').take(10)
                    })
                }
            }
            if (rules != null) {
                for (rule in rules.popupRules) {
                    val key = ruleKey(rule)
                    if (session.acted.contains(key)) continue
                    val idNode = locate(nodes, rule.id)
                    val actionNode = locate(nodes, rule.action)
                    if (idNode == null && actionNode == null) continue
                    if (dispatch(session, rule, idNode, actionNode, 0, null, session.ruleBounds)) {
                        acted = true
                        ruleActed = true
                    }
                }
            }
            if (!ruleActed && genericProvider() && !session.genericActed && !isSystemPackage(session.packageName)) {
                val metrics = service.resources.displayMetrics
                val candidate = SplashSkipper.findCandidate(
                    nodes,
                    metrics.widthPixels,
                    metrics.heightPixels,
                    session.ruleBounds
                )
                if (candidate != null) {
                    session.genericActed = true
                    val rule = Rule(GENERIC_ID, GENERIC_ID, GENERIC_TIMES)
                    val check: (List<AccessibilityNodeInfo>) -> Boolean = { current ->
                        SplashSkipper.findCandidate(current, metrics.widthPixels, metrics.heightPixels) != null
                    }
                    if (dispatch(session, rule, candidate, candidate, 0, check, session.genericBounds)) {
                        acted = true
                        onGenericAction?.invoke()
                    }
                }
            }
        }
        if (acted) session.idleRounds = 0 else session.idleRounds++
        if (session.rounds >= MAX_ROUNDS || (session.idleRounds >= MAX_IDLE_ROUNDS && session.rounds > 1)) {
            finish()
            return
        }
        handler.postDelayed({ scan(session) }, SCAN_INTERVAL)
    }

    private fun finish() {
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
        val method = session.methods[key] ?: METHOD_TAP
        val done = perform(rule, idNode, actionNode, method)
        if (done) {
            session.acted.add(key)
            listOfNotNull(idNode, actionNode).forEach { node ->
                NodeMatcher.boundsKey(node)?.let { boundsTarget.add(it) }
            }
            if (verifyCheck != null) session.checks[key] = verifyCheck
            Diag.log("acted key=$key method=$method attempt=$attempt")
            onAction?.invoke(key)
            if (verifiable(rule)) {
                handler.postDelayed({ verify(session, rule, key) }, VERIFY_DELAY)
            } else {
                onVerified?.invoke(key)
            }
            return true
        }
        if (attempt + 1 >= rule.times.coerceAtLeast(1)) return false
        handler.postDelayed({
            if (session.generation != generation) return@postDelayed
            val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return@postDelayed
            val nodes = scopedNodes(root, session.packageName)
            dispatch(session, rule, locate(nodes, rule.id), locate(nodes, rule.action), attempt + 1, verifyCheck, boundsTarget)
        }, RETRY_INTERVAL)
        return false
    }

    private fun verify(session: Session, rule: Rule, key: String) {
        if (session.generation != generation) return
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return
        val nodes = scopedNodes(root, session.packageName)
        val check = session.checks[key]
        val stillThere = if (check != null) {
            runCatching { check(nodes) }.getOrDefault(false)
        } else {
            locate(nodes, rule.id) ?: locate(nodes, rule.action) != null
        }
        if (!stillThere) {
            onVerified?.invoke(key)
            return
        }
        val switches = session.switches[key] ?: 0
        if (switches >= MAX_SWITCHES) return
        session.switches[key] = switches + 1
        session.methods[key] = if ((session.methods[key] ?: METHOD_TAP) == METHOD_TAP) METHOD_CLICK else METHOD_TAP
        session.acted.remove(key)
        if (key.startsWith(GENERIC_ID)) session.genericActed = false
        Diag.log("verify retry key=$key method=${session.methods[key]}")
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
        if (self) return true
        val ancestor = NodeMatcher.clickableAncestor(node) ?: return false
        return runCatching {
            ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
    }

    private fun tap(node: AccessibilityNodeInfo): Boolean {
        val bounds = NodeMatcher.boundsOf(node) ?: return false
        val point = Point(bounds.exactCenterX().toInt(), bounds.exactCenterY().toInt())
        Diag.log("tap x=${point.x} y=${point.y} text=${NodeMatcher.textOf(node).replace('\u0000', '|')}")
        return tapPoint(point)
    }

    private fun tapPoint(point: Point): Boolean {
        val path = Path().apply {
            addCircle(point.x.toFloat(), point.y.toFloat(), TAP_RADIUS, Path.Direction.CW)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION))
            .build()
        return runCatching { service.dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    private fun scopedNodes(root: AccessibilityNodeInfo?, targetPackage: String): List<AccessibilityNodeInfo> {
        val own = service.packageName
        if (targetPackage.isBlank() || targetPackage == own) return emptyList()
        val roots = ArrayList<AccessibilityNodeInfo>(8)
        runCatching { service.windows }.getOrNull()?.forEach { window ->
            runCatching { window.root }.getOrNull()?.let { roots.add(it) }
        }
        if (root != null) roots.add(root)
        val collected = ArrayList<AccessibilityNodeInfo>(256)
        for (item in roots) {
            for (node in NodeMatcher.collectNodes(item)) {
                if (!collected.contains(node)) collected.add(node)
            }
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
        if (packageName.endsWith(".systemui")) return true
        if (packageName.contains(".launcher")) return true
        return false
    }

    private fun ruleKey(rule: Rule): String = rule.id + "\u0001" + rule.action

    companion object {
        private const val MAX_ROUNDS = 24
        private const val MAX_IDLE_ROUNDS = 8
        private const val SCAN_INTERVAL = 250L
        private const val RETRY_INTERVAL = 200L
        private const val RESUME_DELAY = 100L
        private const val VERIFY_DELAY = 700L
        private const val TAP_DURATION = 50L
        private const val TAP_RADIUS = 1.5f
        private const val BACK = "back"
        private const val GLOBAL_BACK = "GLOBAL_ACTION_BACK"
        const val GENERIC_ID = "__ltt_generic_skip__"
        private const val GENERIC_TIMES = 3
        private const val METHOD_TAP = 0
        private const val METHOD_CLICK = 1
        private const val MAX_SWITCHES = 2
    }
}
