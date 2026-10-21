package com.example.lttclone

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lttclone.data.Prefs
import com.example.lttclone.data.RuleRepository
import com.google.android.material.switchmaterial.SwitchMaterial

class AppSettingActivity : AppCompatActivity() {

    private data class AppEntry(val label: String, val packageName: String)

    private lateinit var adapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_setting)

        val recycler = findViewById<RecyclerView>(R.id.recycler_apps)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = AppAdapter(loadApps())
        recycler.adapter = adapter
    }

    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        return pm.getInstalledPackages(0)
            .map { it.packageName }
            .distinct()
            .map { pkg ->
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                    .getOrDefault(pkg)
                AppEntry(label, pkg)
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun showRules(pkg: String, label: String) {
        val rules = RuleRepository.get(this, pkg)
        val popupRules = rules?.popupRules.orEmpty()
        val message = if (popupRules.isEmpty()) {
            getString(R.string.app_setting_rules_empty)
        } else {
            popupRules.joinToString("\n") { rule ->
                getString(R.string.app_setting_rule_line, rule.id, rule.action, rule.times)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_setting_rules_title, label))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private inner class AppAdapter(private val items: List<AppEntry>) :
        RecyclerView.Adapter<AppAdapter.Holder>() {

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val tvLabel: TextView = view.findViewById(R.id.tv_app_label)
            val tvPackage: TextView = view.findViewById(R.id.tv_app_package)
            val switch: SwitchMaterial = view.findViewById(R.id.switch_app)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_info, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            holder.tvLabel.text = entry.label
            holder.tvPackage.text = entry.packageName
            holder.switch.setOnCheckedChangeListener(null)
            holder.switch.isChecked = Prefs.isAppEnabled(this@AppSettingActivity, entry.packageName)
            holder.switch.setOnCheckedChangeListener { _, checked ->
                Prefs.setAppEnabled(this@AppSettingActivity, entry.packageName, checked)
            }
            holder.itemView.setOnClickListener {
                showRules(entry.packageName, entry.label)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
