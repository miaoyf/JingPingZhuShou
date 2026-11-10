package com.fuchu.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.fuchu.assistant.R

class KeepAliveService : Service() {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val a11yWatchdog = object : Runnable {
        override fun run() {
            ensureAccessibilityEnabled()
            handler.postDelayed(this, A11Y_CHECK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        handler.postDelayed(a11yWatchdog, A11Y_CHECK_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(a11yWatchdog)
        super.onDestroy()
    }

    /**
     * 荣耀 MagicOS 会静默解绑无障碍服务（日志实测 10 分钟内 2 次），
     * 解绑后内存会话清零、跳过中断。这里周期自检：系统设置里丢了本服务时，
     * 重写 enabled_accessibility_services 拉回。
     * 依赖 adb 一次性授权 WRITE_SECURE_SETTINGS；未授权则静默跳过。
     * 若解绑发生在系统设置页前台（用户手动关闭），尊重意愿：不拉回并自行退出。
     */
    private fun ensureAccessibilityEnabled() {
        if (!com.fuchu.assistant.data.Prefs.isEnabled(this)) return
        if (com.fuchu.assistant.data.Prefs.isUnboundByUser(this)) {
            Diag.log("a11y watchdog: user disabled, keepalive exit")
            handler.removeCallbacks(a11yWatchdog)
            stopSelf()
            return
        }
        runCatching {
            val cr = contentResolver
            val component = android.content.ComponentName(this, SkipService::class.java)
                .flattenToString()
            val current = android.provider.Settings.Secure.getString(
                cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            if (current.split(':').any { it.equals(component, ignoreCase = true) }) return
            val ok = android.provider.Settings.Secure.putString(
                cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                if (current.isBlank()) component else "$current:$component"
            )
            android.provider.Settings.Secure.putInt(
                cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1
            )
            Diag.log("a11y self-repair re-enabled=$ok")
        }
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keepalive_channel),
                NotificationManager.IMPORTANCE_MIN
            )
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.keepalive_title))
            .setContentText(getString(R.string.keepalive_text))
            .setSmallIcon(R.drawable.ic_stat_keepalive)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "fu_keepalive"
        private const val NOTIFICATION_ID = 1001
        private const val A11Y_CHECK_MS = 60_000L

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
