package com.example.lttclone.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.lttclone.data.AppRules
import com.example.lttclone.data.Rule

class RuleMatcher(
    private val service: AccessibilityService,
    private val rulesProvider: (String) -> AppRules?,
    private val genericProvider: () -> Boolean
) {

    var onGenericAction: (() -> Unit)? = null

    private class Session(val generation: Int, val packageName: String) {
        val acted = HashSet<String>()
        val methods = HashMap<String, Int>()
        val switches = HashMap<String, Int>()
        var genericActed = false
        var rounds = 0
        var idleRounds = 0
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var pendingPackage: String? = null
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
        val root = runCatching { service.rootInActiveWindow }.getOrNull()
        if (root != null) {
            val nodes = NodeMatcher.collectNodes(root)
            val rules = rulesProvider(session.packageName)
            if (rules != null) {
                for (rule in rules.popupRules) {
                    val key = ruleKey(rule)
                    if (session.acted.contains(key)) continue
                    val idNode = locate(nodes, rule.id)
                    val actionNode = locate(nodes, rule.action)
                    if (idNode == null && actionNode == null) continue
                    if (dispatch(session, rule, idNode, actionNode, 0)) acted = true
                }
            }
            if (genericProvider() && !session.genericActed) {
                val metrics = service.resources.displayMetrics
                val candidate = SplashSkipper.findCandidate(nodes, metrics.widthPixels, metrics.heightPixels)
                if (candidate != null) {
                    session.genericActed = true
                    val rule = Rule(GENERIC_ID, GENERIC_ID, GENERIC_TIMES)
                    if (dispatch(session, rule, candidate, candidate, 0)) {
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
        attempt: Int
    ): Boolean {
        if (session.generation != generation) return false
        val key = ruleKey(rule)
        val method = session.methods[key] ?: METHOD_TAP
        val done = perform(rule, idNode, actionNode, method)
        if (done) {
            session.acted.add(key)
            Log.i(TAG, "acted key=$key method=$method attempt=$attempt")
            if (verifiable(rule)) {
                handler.postDelayed({ verify(session, rule, key) }, VERIFY_DELAY)
            }
            return true
        }
        if (attempt + 1 >= rule.times.coerceAtLeast(1)) return false
        handler.postDelayed({
            if (session.generation != generation) return@postDelayed
            val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return@postDelayed
            val nodes = NodeMatcher.collectNodes(root)
            dispatch(session, rule, locate(nodes, rule.id), locate(nodes, rule.action), attempt + 1)
        }, RETRY_INTERVAL)
        return false
    }

    private fun verify(session: Session, rule: Rule, key: String) {
        if (session.generation != generation) return
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return
        val nodes = NodeMatcher.collectNodes(root)
        val current = locate(nodes, rule.id) ?: locate(nodes, rule.action)
        if (current == null) return
        val switches = session.switches[key] ?: 0
        if (switches >= MAX_SWITCHES) return
        session.switches[key] = switches + 1
        session.methods[key] = if ((session.methods[key] ?: METHOD_TAP) == METHOD_TAP) METHOD_CLICK else METHOD_TAP
        session.acted.remove(key)
        Log.i(TAG, "verify retry key=$key method=${session.methods[key]}")
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
        return tapPoint(Point(bounds.exactCenterX().toInt(), bounds.exactCenterY().toInt()))
    }

    private fun tapPoint(point: Point): Boolean {
        val path = Path().apply { moveTo(point.x.toFloat(), point.y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION))
            .build()
        return runCatching { service.dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    private fun ruleKey(rule: Rule): String = rule.id + "\u0001" + rule.action

    companion object {
        private const val TAG = "LttService"
        private const val MAX_ROUNDS = 24
        private const val MAX_IDLE_ROUNDS = 8
        private const val SCAN_INTERVAL = 250L
        private const val RETRY_INTERVAL = 200L
        private const val RESUME_DELAY = 100L
        private const val VERIFY_DELAY = 700L
        private const val TAP_DURATION = 50L
        private const val BACK = "back"
        private const val GLOBAL_BACK = "GLOBAL_ACTION_BACK"
        private const val GENERIC_ID = "__ltt_generic_skip__"
        private const val GENERIC_TIMES = 3
        private const val METHOD_TAP = 0
        private const val METHOD_CLICK = 1
        private const val MAX_SWITCHES = 2
    }
}
