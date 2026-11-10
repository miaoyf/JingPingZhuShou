package com.fuchu.assistant.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自学习坐标缓存 v3：多候选投票记忆。
 * 每个应用最多保留 3 条候选（归一化指纹 + 坐标 120px 聚类归并），确认成功加分、
 * 误确认/回放失败扣分（连续失败 3 次出局），回放时选得分最高者。
 * 单槽覆盖的痛点：一次运气好的误点顶掉好坐标、A/B 版式在两坐标间震荡——
 * 多候选投票让"越用越准"真正成立：好记录必须被多次确认才坐稳，不会被单次失误顶掉。
 * 附带应用级负样本黑名单：通用跳过点过但没点掉的非严格候选文本持久化排除。
 * 全部本地存储，不联网。
 */
object LearnedStore {

    private const val NAME = "ltt_learned"
    private const val BLOCK_NAME = "ltt_blocked"
    private const val MAX_AGE_MS = 30L * 24 * 3600 * 1000
    private const val MAX_CONSECUTIVE_FAIL = 3
    private const val MAX_CANDIDATES = 3
    private const val MAX_BLOCKED = 8
    // 同指纹且坐标距离在此值内视为同一条候选（归并加分而非新建）
    private const val MERGE_DISTANCE_PX = 120

    class Entry(
        val xRatio: Float,
        val yRatio: Float,
        val fingerprint: String,
        val success: Int,
        val failStreak: Int,
        val source: String,
        val screenW: Int,
        val screenH: Int
    )

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private fun blockSp(context: Context) =
        context.applicationContext.getSharedPreferences(BLOCK_NAME, Context.MODE_PRIVATE)

    // 缓存格式/策略版本：升级后一次性清空全部旧条目，给新闸门一个干净起点
    //（v3：10-25 多候选投票记忆 + 指纹归一化 + 黑名单上线；
    //  v4：10-25 清掉 OCR 误学前台闸门缺失期间产生的脏条目；
    //  v5：10-25 坐标口径从含 insets 的 displayMetrics 改为整屏 displayBounds，
    //      旧比例坐标的 y 会偏，一次性重学；
    //  v6：10-27 清掉桌面窗口化模式期间学进的越界坐标（如腾讯视频 x=1303>1080），
    //      越界拦截已上线，重学只会收合法坐标）
    private const val SCHEMA = 6

    private fun ensureSchema(context: Context) {
        val p = sp(context)
        if (p.getInt("__schema__", 0) != SCHEMA) {
            p.edit().clear().putInt("__schema__", SCHEMA).apply()
            blockSp(context).edit().clear().apply()
        }
    }

    /**
     * 指纹归一化：剥掉倒计时数字与其单位（"跳过 3s"→"跳过"）、去空白。
     * 倒计时会随广告剩余时长变化，原文入库会导致下次树闸门误判"无广告"而弃用好记录。
     * 注意只剥"数字+可选单位"整体，不动字母 s 本身（避免把 "Skip" 剥成 "kip"）。
     */
    fun normalizeFp(raw: String): String =
        raw.replace(Regex("\\d+\\s*[sS秒]?"), "")
            .replace(Regex("\\s+"), "")
            .take(20)

    /** 取本应用当前得分最高的有效候选；全部出局/过期返回 null */
    @Synchronized
    fun get(context: Context, pkg: String): Entry? {
        ensureSchema(context)
        val cands = candidates(context, pkg) ?: return null
        var best: JSONObject? = null
        for (i in 0 until cands.length()) {
            val o = cands.optJSONObject(i) ?: continue
            if (System.currentTimeMillis() - o.optLong("ts") > MAX_AGE_MS) continue
            if (o.optInt("failStreak") >= MAX_CONSECUTIVE_FAIL) continue
            val cur = best
            if (cur == null || o.optInt("success") > cur.optInt("success") ||
                (o.optInt("success") == cur.optInt("success") && o.optLong("ts") > cur.optLong("ts"))
            ) {
                best = o
            }
        }
        return best?.let {
            Entry(
                it.optDouble("xr").toFloat(), it.optDouble("yr").toFloat(),
                it.optString("fp"), it.optInt("success"), it.optInt("failStreak"),
                it.optString("src", "tree"), it.optInt("sw"), it.optInt("sh")
            )
        }
    }

    /**
     * 确认跳过后归并写入：同指纹且 120px 内 → 老候选加分并刷新坐标（最新落点反映当前版式）；
     * 否则新建候选；超出 3 条时淘汰得分最低（success-failStreak，并列取最旧）者。
     */
    @Synchronized
    fun learn(
        context: Context, pkg: String, x: Int, y: Int,
        screenW: Int, screenH: Int, fingerprint: String, source: String = "tree"
    ) {
        if (screenW <= 0 || screenH <= 0) return
        // 越界坐标拒绝入库：桌面窗口化模式下应用窗口可落在默认显示屏之外
        //（10-26 实测腾讯视频窗口化节点 x=1303 超出 1080 屏宽被学成 manual 记录），
        // 手势无法到达的坐标学进去只会盲打转圈
        if (x < 0 || x >= screenW || y < 0 || y >= screenH) return
        ensureSchema(context)
        val fp = normalizeFp(fingerprint)
        val xr = x.toFloat() / screenW
        val yr = y.toFloat() / screenH
        val cands = candidates(context, pkg) ?: JSONArray()
        var merged = false
        for (i in 0 until cands.length()) {
            val o = cands.optJSONObject(i) ?: continue
            if (o.optString("fp") != fp) continue
            val dx = kotlin.math.abs(o.optDouble("xr").toFloat() - xr) * screenW
            val dy = kotlin.math.abs(o.optDouble("yr").toFloat() - yr) * screenH
            if (dx > MERGE_DISTANCE_PX || dy > MERGE_DISTANCE_PX) continue
            o.put("xr", xr.toDouble())
            o.put("yr", yr.toDouble())
            o.put("src", source)
            o.put("success", o.optInt("success") + 1)
            o.put("failStreak", 0)
            o.put("ts", System.currentTimeMillis())
            o.put("sw", screenW)
            o.put("sh", screenH)
            merged = true
            break
        }
        if (!merged) {
            val o = JSONObject()
            o.put("xr", xr.toDouble())
            o.put("yr", yr.toDouble())
            o.put("fp", fp)
            o.put("src", source)
            o.put("success", 1)
            o.put("failStreak", 0)
            o.put("ts", System.currentTimeMillis())
            o.put("sw", screenW)
            o.put("sh", screenH)
            cands.put(o)
        }
        while (cands.length() > MAX_CANDIDATES) {
            var worst = 0
            var worstScore = Int.MAX_VALUE
            var worstTs = Long.MAX_VALUE
            for (i in 0 until cands.length()) {
                val o = cands.optJSONObject(i) ?: continue
                val score = o.optInt("success") - o.optInt("failStreak")
                val ts = o.optLong("ts")
                if (score < worstScore || (score == worstScore && ts < worstTs)) {
                    worst = i
                    worstScore = score
                    worstTs = ts
                }
            }
            cands.remove(worst)
        }
        sp(context).edit().putString(pkg, JSONObject().put("cands", cands).toString()).apply()
    }

    /**
     * 回放复验结果回流：fp 指定惩罚/奖励哪条候选（回放用的是哪条就记到哪条）；
     * fp 为空时记到当前最高分候选上。连续失败达阈值后 get 不再返回该候选。
     */
    @Synchronized
    fun outcome(context: Context, pkg: String, ok: Boolean, fp: String = "") {
        ensureSchema(context)
        val cands = candidates(context, pkg) ?: return
        var target: JSONObject? = null
        val nfp = normalizeFp(fp)
        if (nfp.isNotEmpty()) {
            for (i in 0 until cands.length()) {
                val o = cands.optJSONObject(i) ?: continue
                if (o.optString("fp") == nfp) { target = o; break }
            }
        }
        if (target == null) {
            var bestScore = Int.MIN_VALUE
            for (i in 0 until cands.length()) {
                val o = cands.optJSONObject(i) ?: continue
                val score = o.optInt("success") - o.optInt("failStreak")
                if (score > bestScore) { bestScore = score; target = o }
            }
        }
        val o = target ?: return
        o.put("ts", System.currentTimeMillis())
        if (ok) {
            o.put("success", o.optInt("success") + 1)
            o.put("failStreak", 0)
        } else {
            o.put("failStreak", o.optInt("failStreak") + 1)
        }
        sp(context).edit().putString(pkg, JSONObject().put("cands", cands).toString()).apply()
    }

    // ---------- 应用级负样本黑名单 ----------

    /**
     * 把通用跳过"点了但点不掉"的非严格候选文本永久排除（信息流横幅等）。
     * 严格候选（精确匹配"跳过"系正则）永不进黑名单，倒计时开屏按钮不被误伤。
     */
    @Synchronized
    fun block(context: Context, pkg: String, text: String) {
        val t = text.take(40)
        if (t.isBlank()) return
        val arr = blockSp(context).getString(pkg, null)?.let {
            runCatching { JSONArray(it) }.getOrNull()
        } ?: JSONArray()
        for (i in 0 until arr.length()) {
            if (arr.optString(i) == t) return
        }
        arr.put(t)
        while (arr.length() > MAX_BLOCKED) arr.remove(0)
        blockSp(context).edit().putString(pkg, arr.toString()).apply()
    }

    @Synchronized
    fun blocked(context: Context, pkg: String): Set<String> {
        val arr = blockSp(context).getString(pkg, null)?.let {
            runCatching { JSONArray(it) }.getOrNull()
        } ?: return emptySet()
        val out = HashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optString(i)?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        return out
    }

    private fun candidates(context: Context, pkg: String): JSONArray? {
        val raw = sp(context).getString(pkg, null) ?: return null
        return try {
            JSONObject(raw).optJSONArray("cands")
        } catch (t: Throwable) {
            null
        }
    }
}
