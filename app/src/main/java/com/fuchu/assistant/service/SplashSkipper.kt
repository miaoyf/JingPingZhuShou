package com.fuchu.assistant.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object SplashSkipper {

    // "关闭广告"刻意不进 STRICT：开屏广告前几百毫秒常不进无障碍树，而应用首页
    // 底部信息流横幅的关闭按钮（desc=关闭广告）已加载——strict 即中即点会抢到
    // 横幅上，确认时开屏仍盲渲被判成功，真跳过按钮出现后已解除武装（10-24 喜马拉雅实测）
    private val STRICT = listOf(
        Regex("^\\s*(点击)?\\s*跳过\\s*(广告)?\\s*[>》»›]?\\s*\\d{0,2}\\s*[sS秒]?\\s*$"),
        Regex("^\\s*skip\\s*(ad)?\\s*[>»›]?\\s*\\d{0,2}\\s*[sS]?\\s*$", RegexOption.IGNORE_CASE),
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
        excluded: Set<String> = emptySet(),
        blocked: Set<String> = emptySet()
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
                    val match = score(node, blocked) ?: continue
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
        collectTexts: Boolean = false,
        blocked: Set<String> = emptySet()
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
                val match = score(node, blocked)
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
        excluded: Set<String> = emptySet(),
        blocked: Set<String> = emptySet()
    ): AccessibilityNodeInfo? {
        if (nodes.isEmpty()) return null
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = 0
        for (node in nodes) {
            val match = score(node, blocked) ?: continue
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

    private fun score(node: AccessibilityNodeInfo, blocked: Set<String> = emptySet()): Match? {
        val text = runCatching { node.text?.toString()?.trim() }.getOrNull().orEmpty()
        val description = runCatching { node.contentDescription?.toString()?.trim() }.getOrNull().orEmpty()
        val id = runCatching { node.viewIdResourceName }.getOrNull().orEmpty()
        val clickable = NodeMatcher.isClickable(node) || NodeMatcher.clickableAncestor(node) != null
        var best = 0
        var strict = false
        var matchedValue = ""
        for (value in listOf(text, description)) {
            if (value.isEmpty()) continue
            if (value.length <= MAX_STRICT_LENGTH && STRICT.any { it.matches(value) }) {
                best = maxOf(best, 100)
                strict = true
                matchedValue = value
            } else if (LOOSE.any { value.contains(it, ignoreCase = true) }) {
                if (best < 45) matchedValue = value
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
        // 负样本黑名单：该应用里"点了但点不掉"的非严格候选文本永久排除（信息流横幅等）。
        // 严格候选（精确匹配"跳过"系正则）不受黑名单影响，倒计时开屏按钮不会被误伤
        if (!strict && matchedValue.isNotEmpty() && blocked.contains(matchedValue.take(40))) return null
        // 不再因“非 strict 且不可点”丢弃候选：广告 SDK 常用自定义 View + OnTouchListener，
        // 无障碍树不标 clickable；这类节点交给点击阶段的手势 tap 兜底（acceptable 仍限制其在屏幕上半部）
        return Match(best, strict, clickable)
    }

    private fun acceptable(
        bounds: Rect,
        screenWidth: Int,
        screenHeight: Int,
        clickable: Boolean,
        strict: Boolean
    ): Boolean {
        // 越界候选拒绝：桌面窗口化模式下应用窗口可整体落在默认显示屏之外
        //（10-26 腾讯视频窗口化实测：跳过按钮 x=1303 超出 1080 屏宽，点击必然失败，
        // verify 还会连切方法补枪 4 发），扫描期直接排除，不点不重试不污染学习
        if (bounds.centerX() < 0 || bounds.centerX() >= screenWidth ||
            bounds.centerY() < 0 || bounds.centerY() >= screenHeight
        ) return false
        val area = bounds.width().toLong() * bounds.height().toLong()
        val screenArea = screenWidth.toLong() * screenHeight.toLong()
        if (screenArea > 0 && area * 4 > screenArea) return false
        // 非严格候选（典型：信息流横幅的"关闭广告"）只认屏幕顶部 60%：
        // 开屏跳过按钮极少在底部，底部匹配基本是内容页广告，点了误确认还污染学习缓存
        if (!strict && bounds.top * 5 > screenHeight * 3) return false
        if (clickable) return true
        if (strict) return true
        return bounds.bottom * 2 <= screenHeight
    }
}
