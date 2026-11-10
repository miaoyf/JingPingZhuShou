package com.fuchu.assistant.data

import android.content.Context

object Prefs {

    private const val NAME = "ltt_prefs"
    private const val KEY_ENABLED = "service_enabled"
    private const val KEY_APP_PREFIX = "app_"
    private const val KEY_GENERIC = "generic_splash"
    private const val KEY_UNBOUND_BY_USER = "unbound_by_user"
    private const val KEY_DIAG_TEXT = "diag_text_enabled"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isAppEnabled(context: Context, packageName: String): Boolean =
        sp(context).getBoolean(KEY_APP_PREFIX + packageName, true)

    fun setAppEnabled(context: Context, packageName: String, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_APP_PREFIX + packageName, enabled).apply()
    }

    fun isGenericSplashEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_GENERIC, true)

    fun setGenericSplashEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_GENERIC, enabled).apply()
    }

    /** 无障碍服务解绑时若前台是系统设置页，判定为用户手动关闭（尊重意愿，不再自动拉回）；
     *  其他场景视为 ROM 静默杀服务，允许 KeepAlive 看门狗自愈。重连成功时清除标记。 */
    fun isUnboundByUser(context: Context): Boolean =
        sp(context).getBoolean(KEY_UNBOUND_BY_USER, false)

    fun setUnboundByUser(context: Context, byUser: Boolean) {
        sp(context).edit().putBoolean(KEY_UNBOUND_BY_USER, byUser).apply()
    }

    /** 详细诊断（采集界面文本）：默认关闭。开启后 texts= 全量界面文本才会进诊断
     *  日志（可能包含拼音、号码等敏感内容），只用于排查问题；事件类日志
     * （点击/闸门/回放）不含界面文本，不受影响。 */
    fun isDiagTextEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_DIAG_TEXT, false)

    fun setDiagTextEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_DIAG_TEXT, enabled).apply()
    }
}
