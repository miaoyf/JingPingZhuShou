package com.example.lttclone.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object SplashSkipper {

    private val STRICT = listOf(
        Regex("^\\s*(点击)?\\s*跳过\\s*(广告)?\\s*[>》»›]?\\s*\\d{0,2}\\s*[sS秒]?\\s*$"),
        Regex("^\\s*skip\\s*(ad)?\\s*[>»›]?\\s*\\d{0,2}\\s*[sS]?\\s*$", RegexOption.IGNORE_CASE),
        Regex("^\\s*(关闭|关掉|关)\\s*广告\\s*$"),
        Regex("^\\s*\\d{0,2}\\s*[sS秒]\\s*跳过\\s*$")
    )

    private val LOOSE = listOf("跳过", "skip", "关闭广告")

    private const val MAX_STRICT_LENGTH = 8

    fun findCandidate(
        nodes: List<AccessibilityNodeInfo>,
        screenWidth: Int,
        screenHeight: Int,
        excluded: Set<String> = emptySet()
    ): AccessibilityNodeInfo? {
        if (nodes.isEmpty()) return null
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = 0
        for (node in nodes) {
            val match = score(node) ?: continue
            val bounds = NodeMatcher.boundsOf(node) ?: continue
            if (excluded.contains(NodeMatcher.boundsKey(bounds))) continue
            if (!acceptable(bounds, screenWidth, screenHeight, match.clickable)) continue
            val total = match.score + if (match.clickable) 25 else 0
            if (total > bestScore) {
                bestScore = total
                bestNode = node
            }
        }
        return bestNode
    }

    private data class Match(val score: Int, val strict: Boolean, val clickable: Boolean)

    private fun score(node: AccessibilityNodeInfo): Match? {
        val text = runCatching { node.text?.toString()?.trim() }.getOrNull().orEmpty()
        val description = runCatching { node.contentDescription?.toString()?.trim() }.getOrNull().orEmpty()
        val id = runCatching { node.viewIdResourceName }.getOrNull().orEmpty()
        val clickable = NodeMatcher.isClickable(node) || NodeMatcher.clickableAncestor(node) != null
        var best = 0
        var strict = false
        for (value in listOf(text, description)) {
            if (value.isEmpty()) continue
            if (value.length <= MAX_STRICT_LENGTH && STRICT.any { it.matches(value) }) {
                best = maxOf(best, 100)
                strict = true
            } else if (LOOSE.any { value.contains(it, ignoreCase = true) }) {
                best = maxOf(best, 45)
            }
        }
        if (id.isNotEmpty()) {
            val lower = id.lowercase()
            if (lower.contains("skip")) best = maxOf(best, 90)
            else if (lower.contains("splash") && (lower.contains("ad") || lower.contains("btn"))) {
                best = maxOf(best, 70)
            }
        }
        if (best <= 0) return null
        if (!strict && !clickable) return null
        return Match(best, strict, clickable)
    }

    private fun acceptable(
        bounds: Rect,
        screenWidth: Int,
        screenHeight: Int,
        clickable: Boolean
    ): Boolean {
        val area = bounds.width().toLong() * bounds.height().toLong()
        val screenArea = screenWidth.toLong() * screenHeight.toLong()
        if (screenArea > 0 && area * 4 > screenArea) return false
        if (clickable) return true
        return bounds.bottom * 2 <= screenHeight
    }
}
