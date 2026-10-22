package com.example.lttclone.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private var actedFlag = false

    private val watchdog = object : Runnable {
        override fun run() {
            handler.postDelayed(this, WATCHDOG_MS)
            try {
                sweep()
            } catch (t: Throwable) {
                Diag.log("watchdog error=${t.javaClass.simpleName}")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        matcher = RuleMatcher(
            service = this,
            rulesProvider = { pkg -> resolveRules(pkg) },
            genericProvider = { genericAllowed() }
        ).also {
            it.onGenericAction = { Diag.log("generic action attempted") }
            it.onAction = { key -> Diag.log("action attempt key=$key") }
            it.onVerified = { key ->
                actedFlag = true
                if (key.startsWith(RuleMatcher.GENERIC_ID)) genericFired = true
                Diag.log("action confirmed key=$key")
            }
        }
        Diag.init(this)
        Diag.log("service connected")
        KeepAliveService.start(this)
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Diag.log("window changed pkg=$pkg")
        }
        if (pkg == packageName) return
        if (!Prefs.isEnabled(this)) {
            Diag.log("skip: global switch off")
            return
        }
        if (!Prefs.isAppEnabled(this, pkg)) {
            Diag.log("skip: app switch off pkg=$pkg")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (isSystemPackage(pkg)) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            updateForeground(pkg, now)
            latestPackage = pkg
            matcher?.handle(pkg)
            return
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
        Diag.log("service interrupted")
        clearPending()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Diag.log("service unbound")
        clearPending()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearPending()
        handler.removeCallbacks(watchdog)
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
            actedFlag = false
            Diag.log("foreground package=$pkg")
            return
        }
        if (now - foregroundSince > SPLASH_WINDOW_MS) {
            foregroundSince = now
            genericFired = false
            actedFlag = false
        }
    }

    private fun sweep() {
        val fg = foregroundPackage ?: return
        if (actedFlag) return
        val elapsed = SystemClock.elapsedRealtime() - foregroundSince
        if (elapsed > SPLASH_WINDOW_MS) return
        matcher?.handle(fg)
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

    private fun isSystemPackage(pkg: String): Boolean {
        if (pkg == packageName) return true
        if (pkg == "android") return true
        if (pkg == "com.android.systemui") return true
        if (pkg == "com.android.permissioncontroller") return true
        if (pkg == "com.android.packageinstaller") return true
        if (pkg == "com.android.shell") return true
        if (pkg.endsWith(".systemui")) return true
        if (pkg.endsWith(".permissioncontroller")) return true
        if (pkg.contains(".launcher")) return true
        return false
    }

    companion object {
        private const val THROTTLE_MS = 40L
        private const val SPLASH_WINDOW_MS = 15000L
        private const val WATCHDOG_MS = 300L
    }
}
