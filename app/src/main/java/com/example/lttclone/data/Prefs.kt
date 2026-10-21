package com.example.lttclone.data

import android.content.Context

object Prefs {

    private const val NAME = "ltt_prefs"
    private const val KEY_ENABLED = "service_enabled"
    private const val KEY_APP_PREFIX = "app_"
    private const val KEY_GENERIC = "generic_splash"

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
}
