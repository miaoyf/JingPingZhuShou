package com.example.lttclone

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.lttclone.data.Prefs
import com.example.lttclone.data.RuleRepository
import com.example.lttclone.service.LttService
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var switchService: SwitchMaterial
    private lateinit var switchGeneric: SwitchMaterial
    private lateinit var tvStatus: TextView
    private lateinit var tvInfo: TextView
    private var syncing = false
    private var guideShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        switchService = findViewById(R.id.switch_service)
        switchGeneric = findViewById(R.id.switch_generic)
        tvStatus = findViewById(R.id.tv_status)
        tvInfo = findViewById(R.id.tv_info)
        findViewById<Button>(R.id.btn_app_settings).setOnClickListener {
            startActivity(Intent(this, AppSettingActivity::class.java))
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
        syncing = false
        tvStatus.setText(if (enabled) R.string.main_service_on else R.string.main_service_off)
        refreshInfo()
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
        val target = ComponentName(this, LttService::class.java)
        return enabledServices.split(':').any { entry ->
            ComponentName.unflattenFromString(entry) == target
        }
    }
}
