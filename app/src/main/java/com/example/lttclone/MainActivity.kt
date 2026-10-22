package com.example.lttclone

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.lttclone.data.Prefs
import com.example.lttclone.data.RuleRepository
import com.example.lttclone.service.Diag
import com.example.lttclone.service.KeepAliveService
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
        requestNotificationPermission()
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
        if (enabled) {
            KeepAliveService.start(this)
        } else {
            KeepAliveService.stop(this)
        }
        refreshInfo()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
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
        manager.setPrimaryClip(ClipData.newPlainText("ltt_diag", text))
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
        val target = ComponentName(this, LttService::class.java)
        return enabledServices.split(':').any { entry ->
            ComponentName.unflattenFromString(entry) == target
        }
    }
}
