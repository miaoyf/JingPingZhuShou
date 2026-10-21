package com.example.lttclone.service

import android.graphics.Point
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

object NodeMatcher {

    const val MAX_NODES = 3000
    const val MAX_ANCESTOR_DEPTH = 10
    const val TOLERANCE = 12

    private const val SEPARATOR = "\u0000"

    fun splitAlternatives(key: String): List<String> {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parts = trimmed.split('&').map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.isEmpty()) listOf(trimmed) else parts
    }

    fun parseBounds(key: String): Rect? {
        val parts = key.split(',')
        if (parts.size != 4) return null
        val values = parts.map { it.trim().toIntOrNull() ?: return null }
        if (values[1] <= values[0] || values[3] <= values[2]) return null
        return Rect(values[0], values[1], values[2], values[3])
    }

    fun parseCoordinate(action: String): Point? {
        val matcher = Regex("^x:(-?\\d+)y:(-?\\d+)$", RegexOption.IGNORE_CASE)
            .find(action.trim()) ?: return null
        val x = matcher.groupValues[1].toIntOrNull() ?: return null
        val y = matcher.groupValues[2].toIntOrNull() ?: return null
        return Point(x, y)
    }

    fun bestMatch(nodes: List<AccessibilityNodeInfo>, key: String): AccessibilityNodeInfo? {
        val alternatives = splitAlternatives(key)
        if (alternatives.isEmpty() || nodes.isEmpty()) return null
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = 0
        for (alternative in alternatives) {
            val bounds = parseBounds(alternative)
            for (node in nodes) {
                val score = if (bounds != null) boundsScore(node, bounds) else textScore(node, alternative)
                if (score <= 0) continue
                if (score > bestScore) {
                    bestScore = score
                    bestNode = node
                }
            }
        }
        return bestNode
    }

    fun textOf(node: AccessibilityNodeInfo): String {
        val parts = ArrayList<String>(3)
        runCatching { node.text?.toString() }.getOrNull()?.let { parts.add(it) }
        runCatching { node.contentDescription?.toString() }.getOrNull()?.let { parts.add(it) }
        runCatching { node.viewIdResourceName }.getOrNull()?.let { parts.add(it) }
        return parts.joinToString(SEPARATOR)
    }

    fun isClickable(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.isClickable }.getOrDefault(false)

    fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < MAX_ANCESTOR_DEPTH) {
            val candidate = current
            if (isClickable(candidate)) return candidate
            current = runCatching { candidate.parent }.getOrNull()
            depth++
        }
        return null
    }

    fun collectNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>(128)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && result.size < MAX_NODES) {
            val node = queue.removeFirst()
            result.add(node)
            val count = runCatching { node.childCount }.getOrDefault(0)
            for (index in 0 until count) {
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                queue.add(child)
            }
        }
        return result
    }

    fun boundsOf(node: AccessibilityNodeInfo): Rect? {
        val rect = Rect()
        val ok = runCatching { node.getBoundsInScreen(rect) }.isSuccess
        if (!ok || rect.isEmpty) return null
        return rect
    }

    fun boundsKey(bounds: Rect): String =
        bounds.left.toString() + "," + bounds.top + "," + bounds.right + "," + bounds.bottom

    fun boundsKey(node: AccessibilityNodeInfo): String? = boundsOf(node)?.let { boundsKey(it) }

    private fun boundsScore(node: AccessibilityNodeInfo, bounds: Rect): Int {
        val rect = boundsOf(node) ?: return 0
        if (!near(rect.left, bounds.left)) return 0
        if (!near(rect.top, bounds.top)) return 0
        if (!near(rect.right, bounds.right)) return 0
        if (!near(rect.bottom, bounds.bottom)) return 0
        return 100
    }

    private fun near(a: Int, b: Int): Boolean = abs(a - b) <= TOLERANCE

    private fun textScore(node: AccessibilityNodeInfo, alternative: String): Int {
        val key = alternative.trim()
        if (key.isEmpty()) return 0
        var best = 0
        best = maxOf(best, scoreValue(runCatching { node.text?.toString() }.getOrNull(), key))
        best = maxOf(best, scoreValue(runCatching { node.contentDescription?.toString() }.getOrNull(), key))
        val id = runCatching { node.viewIdResourceName }.getOrNull()
        if (!id.isNullOrEmpty()) {
            best = maxOf(best, scoreValue(id, key))
            if (id.endsWith("/$key", ignoreCase = true)) best = maxOf(best, 95)
            else if (id.contains("/$key", ignoreCase = true)) best = maxOf(best, 85)
        }
        return best
    }

    private fun scoreValue(value: String?, key: String): Int {
        if (value.isNullOrEmpty()) return 0
        val trimmed = value.trim()
        if (trimmed.equals(key, ignoreCase = true)) return 100
        if (trimmed.startsWith(key, ignoreCase = true)) return 80
        if (trimmed.contains(key, ignoreCase = true)) return 60
        return 0
    }
}
