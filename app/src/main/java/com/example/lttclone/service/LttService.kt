package com.example.lttclone.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.lttclone.data.AppRules
import com.example.lttclone.data.Prefs
import com.example.lttclone.data.RuleRepository

class LttService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var matcher: RuleMatcher? = null
    private var pending: Runnable? = null
    private var latestPackage: String? = null
    private var foregroundPackage: String? = null
    private var foregroundSince = 0L
    private var genericFired = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        matcher = RuleMatcher(
            service = this,
            rulesProvider = { pkg -> resolveRules(pkg) },
            genericProvider = { genericAllowed() }
        ).also { it.onGenericAction = { genericFired = true } }
        Log.i(TAG, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (!Prefs.isEnabled(this)) return
        if (!Prefs.isAppEnabled(this, pkg)) return
        val now = SystemClock.elapsedRealtime()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            updateForeground(pkg, now)
        }
        latestPackage = pkg
        if (pending != null) return
        val task = Runnable {
            pending = null
            val target = latestPackage ?: return@Runnable
            matcher?.handle(target)
        }
        pending = task
        handler.postDelayed(task, THROTTLE_MS)
    }

    override fun onInterrupt() {
        clearPending()
    }

    override fun onDestroy() {
        clearPending()
        matcher = null
        super.onDestroy()
    }

    private fun resolveRules(pkg: String): AppRules? {
        val primary = RuleRepository.get(this, pkg)
        val fg = foregroundPackage
        val secondary = if (fg != null && fg != pkg) RuleRepository.get(this, fg) else null
        if (primary == null && secondary == null) return null
        val rules = (primary?.popupRules ?: emptyList()) + (secondary?.popupRules ?: emptyList())
        return AppRules(
            popupRules = rules,
            clickWayPopup = primary?.clickWayPopup ?: secondary?.clickWayPopup ?: false,
            times = primary?.times ?: secondary?.times ?: 1,
            delayPopup = primary?.delayPopup ?: secondary?.delayPopup ?: 0L,
            unitePopupRules = primary?.unitePopupRules ?: secondary?.unitePopupRules ?: false,
            searchTimesPopup = primary?.searchTimesPopup ?: secondary?.searchTimesPopup ?: 5
        )
    }

    private fun updateForeground(pkg: String, now: Long) {
        if (pkg != foregroundPackage) {
            foregroundPackage = pkg
            foregroundSince = now
            genericFired = false
            Log.i(TAG, "foreground package=$pkg")
            return
        }
        if (now - foregroundSince > SPLASH_WINDOW_MS) {
            foregroundSince = now
            genericFired = false
        }
    }

    private fun genericAllowed(): Boolean {
        if (!Prefs.isGenericSplashEnabled(this)) return false
        if (genericFired) return false
        if (foregroundPackage == null) return false
        val elapsed = SystemClock.elapsedRealtime() - foregroundSince
        return elapsed in 0..SPLASH_WINDOW_MS
    }

    private fun clearPending() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    companion object {
        private const val TAG = "LttService"
        private const val THROTTLE_MS = 120L
        private const val SPLASH_WINDOW_MS = 15000L
    }
}
