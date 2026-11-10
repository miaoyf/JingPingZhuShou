package com.fuchu.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.fuchu.assistant.data.Prefs
import com.fuchu.assistant.data.RuleRepository
import com.fuchu.assistant.service.Diag
import com.fuchu.assistant.service.KeepAliveService
import com.fuchu.assistant.service.SkipService
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var switchService: SwitchMaterial
    private lateinit var switchGeneric: SwitchMaterial
    private lateinit var switchDiagText: SwitchMaterial
    private lateinit var tvStatus: TextView
    private lateinit var tvInfo: TextView
    private lateinit var guideCard: MaterialCardView
    private lateinit var tvGuideStep1: TextView
    private lateinit var tvGuideStep2: TextView
    private var syncing = false
    private var guideShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        switchService = findViewById(R.id.switch_service)
        switchGeneric = findViewById(R.id.switch_generic)
        switchDiagText = findViewById(R.id.switch_diag_text)
        tvStatus = findViewById(R.id.tv_status)
        tvInfo = findViewById(R.id.tv_info)
        guideCard = findViewById(R.id.guide_card)
        tvGuideStep1 = findViewById(R.id.tv_guide_step1)
        tvGuideStep2 = findViewById(R.id.tv_guide_step2)
        findViewById<Button>(R.id.btn_guide_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_guide_battery).setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }
        findViewById<Button>(R.id.btn_app_settings).setOnClickListener {
            startActivity(Intent(this, AppSettingActivity::class.java))
        }
        findViewById<Button>(R.id.btn_diag).setOnClickListener {
            showDiag()
        }
        switchService.setOnClickListener {
            if (syncing) return@setOnClickListener
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        switchGeneric.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            Prefs.setGenericSplashEnabled(this, checked)
            refreshInfo()
        }
        switchDiagText.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            Prefs.setDiagTextEnabled(this, checked)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
        if (!isServiceEnabled() && !guideShown) {
            guideShown = true
            showGuide()
        }
    }

    private fun refreshState() {
        val enabled = isServiceEnabled()
        syncing = true
        switchService.isChecked = enabled
        switchGeneric.isChecked = Prefs.isGenericSplashEnabled(this)
        switchDiagText.isChecked = Prefs.isDiagTextEnabled(this)
        syncing = false
        tvStatus.setText(if (enabled) R.string.main_service_on else R.string.main_service_off)
        if (enabled) {
            KeepAliveService.start(this)
        } else {
            KeepAliveService.stop(this)
        }
        refreshInfo()
        refreshGuide(enabled)
    }

    /** 新手引导卡：服务开启 + 电池优化白名单都完成才隐藏 */
    private fun refreshGuide(serviceEnabled: Boolean) {
        val batteryOk = isIgnoringBatteryOptimizations()
        tvGuideStep1.text = getString(
            R.string.guide_step1_format,
            getString(if (serviceEnabled) R.string.guide_state_done else R.string.guide_state_todo)
        )
        tvGuideStep2.text = getString(
            R.string.guide_step2_format,
            getString(if (batteryOk) R.string.guide_state_done else R.string.guide_state_todo)
        )
        guideCard.visibility = if (serviceEnabled && batteryOk) View.GONE else View.VISIBLE
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /** 直达系统电池优化白名单申请对话框；个别 ROM 不支持时退到电池优化列表页 */
    private fun requestIgnoreBatteryOptimizations() {
        val granted = isIgnoringBatteryOptimizations()
        if (granted) {
            Toast.makeText(this, R.string.guide_state_done, Toast.LENGTH_SHORT).show()
            return
        }
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(direct) }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun refreshInfo() {
        val rules = RuleRepository.load(this)
        val count = rules.values.sumOf { it.popupRules.size }
        val genericText = getString(
            if (Prefs.isGenericSplashEnabled(this)) R.string.main_info_generic_on else R.string.main_info_generic_off
        )
        tvInfo.text = getString(
            R.string.main_info_combined,
            getString(R.string.main_info_format, rules.size, count, genericText),
            getString(R.string.main_info_format_offline)
        )
    }

    private fun showDiag() {
        // 打开诊断页时把完整日志导出到外部私有目录，adb 可直接读取用于深度排查
        //（对话框只显示最近 120 行，完整文件 512KB 滚动）
        runCatching {
            val src = java.io.File(filesDir, "fu_diag.log")
            val dstDir = getExternalFilesDir(null)
            if (src.exists() && dstDir != null) {
                src.copyTo(java.io.File(dstDir, "fu_diag_export.log"), overwrite = true)
            }
        }
        val enabledText = getString(
            if (isServiceEnabled()) R.string.main_service_on else R.string.main_service_off
        )
        val debugApp = Settings.Global.getString(contentResolver, "debug_app") ?: ""
        val wait = Settings.Global.getInt(contentResolver, "wait_for_debugger", 0)
        val tail = Diag.tail(this, 120)
        val body = StringBuilder()
        body.append(getString(R.string.diag_service_state, enabledText)).append('\n')
        body.append(getString(R.string.diag_debug_app, if (debugApp.isEmpty()) getString(R.string.diag_none) else debugApp)).append('\n')
        body.append(
            getString(
                if (wait == 1) R.string.diag_wait_yes else R.string.diag_wait_no
            )
        ).append('\n').append('\n')
        body.append(if (tail.isEmpty()) getString(R.string.diag_empty) else tail)
        val text = body.toString()
        AlertDialog.Builder(this)
            .setTitle(R.string.diag_title)
            .setMessage(text)
            .setPositiveButton(R.string.diag_copy) { _, _ -> copyDiag(text) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyDiag(text: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("fu_diag", text))
        Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
    }

    private fun showGuide() {
        AlertDialog.Builder(this)
            .setTitle(R.string.guide_title)
            .setMessage(R.string.guide_message)
            .setPositiveButton(R.string.guide_confirm) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun isServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target = ComponentName(this, SkipService::class.java)
        return enabledServices.split(':').any { entry ->
            ComponentName.unflattenFromString(entry) == target
        }
    }
}
