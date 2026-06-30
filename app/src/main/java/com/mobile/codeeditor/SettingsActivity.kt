package com.mobile.codeeditor

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.mobile.codeeditor.databinding.ActivitySettingsBinding

/** A simple, persisted settings screen for editor appearance and behavior. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.settingsToolbar.setNavigationOnClickListener { finish() }

        bindThemeRow()
        bindFontSizeRow()
        bindTabSizeRow()
        bindSwitches()
    }

    private fun bindThemeRow() {
        updateValue(binding.themeValue, Prefs.THEME_NAMES[prefs.theme])
        binding.rowTheme.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_theme)
                .setSingleChoiceItems(Prefs.THEME_NAMES, prefs.theme) { d, which ->
                    prefs.theme = which
                    updateValue(binding.themeValue, Prefs.THEME_NAMES[which])
                    d.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun bindFontSizeRow() {
        updateValue(binding.fontSizeValue, "${prefs.fontSize} sp")
        binding.rowFontSize.setOnClickListener {
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(prefs.fontSize.toString())
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_font_size)
                .setView(input)
                .setPositiveButton(R.string.ok) { _, _ ->
                    val size = input.text.toString().toIntOrNull()?.coerceIn(8, 40) ?: prefs.fontSize
                    prefs.fontSize = size
                    updateValue(binding.fontSizeValue, "$size sp")
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun bindTabSizeRow() {
        updateValue(binding.tabSizeValue, prefs.tabSize.toString())
        binding.rowTabSize.setOnClickListener {
            val labels = Prefs.TAB_SIZES.map { it.toString() }.toTypedArray()
            val checked = Prefs.TAB_SIZES.indexOf(prefs.tabSize).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_tab_size)
                .setSingleChoiceItems(labels, checked) { d, which ->
                    prefs.tabSize = Prefs.TAB_SIZES[which]
                    updateValue(binding.tabSizeValue, prefs.tabSize.toString())
                    d.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun bindSwitches() {
        bindSwitch(binding.switchWordWrap, prefs.wordWrap) { prefs.wordWrap = it }
        bindSwitch(binding.switchLineNumbers, prefs.lineNumbers) { prefs.lineNumbers = it }
        bindSwitch(binding.switchWhitespace, prefs.showWhitespace) { prefs.showWhitespace = it }
        bindSwitch(binding.switchRestore, prefs.restoreSession) { prefs.restoreSession = it }
    }

    private fun bindSwitch(sw: MaterialSwitch, value: Boolean, setter: (Boolean) -> Unit) {
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _, checked -> setter(checked) }
        (sw.parent as? android.view.View)?.setOnClickListener { sw.toggle() }
    }

    private fun updateValue(view: TextView, value: String) {
        view.text = value
    }
}
