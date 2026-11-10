package com.fuchu.assistant.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object RuleRepository {

    private const val TAG = "RuleRepository"
    private const val ASSET_NAME = "rules.json"

    @Volatile
    private var cache: Map<String, AppRules>? = null

    @Volatile
    private var globalRules: List<Rule>? = null

    fun load(context: Context): Map<String, AppRules> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val loaded = try {
                parse(readAsset(context))
            } catch (t: Throwable) {
                Log.e(TAG, "load rules failed", t)
                emptyMap()
            }
            cache = loaded
            return loaded
        }
    }

    fun get(context: Context, packageName: String): AppRules? =
        load(context)[packageName.hashCode().toString()]

    /** SDK 级全局规则：对所有应用生效（穿山甲/CSJ/TopOn 等广告 SDK 的跳过按钮跨应用同 id） */
    fun getGlobal(context: Context): List<Rule> {
        load(context)
        return globalRules ?: emptyList()
    }

    private fun readAsset(context: Context): String =
        context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun parse(text: String): Map<String, AppRules> {
        val root = JSONObject(text)
        globalRules = parseRules(root.optJSONArray("globalRules"))
        val apps = root.optJSONObject("apps") ?: return emptyMap()
        val result = HashMap<String, AppRules>(apps.length())
        val keys = apps.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val obj = apps.optJSONObject(key) ?: continue
            result[key] = AppRules(
                popupRules = parseRules(obj.optJSONArray("popupRules")),
                clickWayPopup = obj.optBoolean("clickWayPopup", false),
                times = obj.optInt("times", 1),
                delayPopup = obj.optLong("delayPopup", 0L),
                unitePopupRules = obj.optBoolean("unitePopupRules", false),
                searchTimesPopup = obj.optInt("searchTimesPopup", 5)
            )
        }
        return result
    }

    private fun parseRules(array: JSONArray?): List<Rule> {
        if (array == null) return emptyList()
        val rules = ArrayList<Rule>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            val action = obj.optString("action", "")
            // 盲坐标规则：id 为空但 action 是 x:..y:.. 坐标时保留，
            // 用于跳过按钮完全不进无障碍树的广告（如腾讯视频部分开屏广告）
            if (id.isEmpty() && !action.trim().matches(COORDINATE_PATTERN)) continue
            rules.add(Rule(id, action, obj.optInt("times", 1)))
        }
        return rules
    }

    private val COORDINATE_PATTERN = Regex("^x:(-?\\d+)y:(-?\\d+)$", RegexOption.IGNORE_CASE)
}
