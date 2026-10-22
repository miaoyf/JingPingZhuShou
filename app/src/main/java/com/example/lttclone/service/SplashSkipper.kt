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

    private val FAST_KEYWORDS = listOf("跳过", "skip", "关闭广告")

    private const val MAX_STRICT_LENGTH = 8

    private const val MAX_NODES = 3000

    class ScanResult(
        val candidate: AccessibilityNodeInfo?,
        val texts: List<String>,
        val visited: Int,
        val strict: Boolean = false
    )

    fun scanRootsFast(
        roots: List<AccessibilityNodeInfo>,
        screenWidth: Int,
        screenHeight: Int,
        excluded: Set<String> = emptySet()
    ): ScanResult {
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = 0
        var bestStrict = false
        var visited = 0
        for (item in roots) {
            for (keyword in FAST_KEYWORDS) {
                val found = runCatching { item.findAccessibilityNodeInfosByText(keyword) }.getOrNull() ?: continue
                for (node in found) {
                    visited++
                    val match = score(node) ?: continue
                    val bounds = NodeMatcher.boundsOf(node) ?: continue
                    if (excluded.contains(NodeMatcher.boundsKey(bounds))) continue
                    if (!acceptable(bounds, screenWidth, screenHeight, match.clickable, match.strict)) continue
                    val total = match.score + if (match.clickable) 25 else 0
                    if (total > bestScore) {
                        bestScore = total
                        bestNode = node
                        bestStrict = match.strict
                    }
                    if (match.strict) return ScanResult(bestNode, emptyList(), visited, true)
                }
            }
        }
        return ScanResult(bestNode, emptyList(), visited, bestStrict)
    }

    fun scanRoots(
        roots: List<AccessibilityNodeInfo>,
        screenWidth: Int,
        screenHeight: Int,
        excluded: Set<String> = emptySet(),
        collectTexts: Boolean = false
    ): ScanResult {
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = 0
        var bestStrict = false
        var visited = 0
        val texts = ArrayList<String>(160)
        for (item in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(item)
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.removeFirst()
                visited++
                if (collectTexts && texts.size < 150) {
                    val text = runCatching { node.text?.toString() }.getOrNull().orEmpty()
                    val description = runCatching { node.contentDescription?.toString() }.getOrNull().orEmpty()
                    val id = runCatching { node.viewIdResourceName }.getOrNull().orEmpty()
                    val merged = listOf(text, description, id).filter { it.isNotEmpty() }.joinToString("|")
                    if (merged.isNotEmpty()) texts.add(merged.take(40))
                }
                val match = score(node)
                if (match != null) {
                    val bounds = NodeMatcher.boundsOf(node)
                    if (bounds != null &&
                        !excluded.contains(NodeMatcher.boundsKey(bounds)) &&
                        acceptable(bounds, screenWidth, screenHeight, match.clickable, match.strict)
                    ) {
                        val total = match.score + if (match.clickable) 25 else 0
                        if (total > bestScore) {
                            bestScore = total
                            bestNode = node
                            bestStrict = match.strict
                        }
                        if (match.strict) return ScanResult(bestNode, texts, visited, true)
                    }
                }
                val childCount = runCatching { node.childCount }.getOrNull() ?: 0
                for (i in 0 until childCount) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    queue.add(child)
                }
            }
        }
        return ScanResult(bestNode, texts, visited, bestStrict)
    }

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
            if (!acceptable(bounds, screenWidth, screenHeight, match.clickable, match.strict)) continue
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
        clickable: Boolean,
        strict: Boolean
    ): Boolean {
        val area = bounds.width().toLong() * bounds.height().toLong()
        val screenArea = screenWidth.toLong() * screenHeight.toLong()
        if (screenArea > 0 && area * 4 > screenArea) return false
        if (clickable) return true
        if (strict) return true
        return bounds.bottom * 2 <= screenHeight
    }
}
