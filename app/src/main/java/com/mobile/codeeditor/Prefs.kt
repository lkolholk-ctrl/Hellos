package com.mobile.codeeditor

import android.content.Context
import androidx.core.content.edit

/** Thin wrapper over [android.content.SharedPreferences] for editor settings. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("editor_prefs", Context.MODE_PRIVATE)

    var theme: Int
        get() = sp.getInt(KEY_THEME, 0)
        set(v) = sp.edit { putInt(KEY_THEME, v) }

    var fontSize: Int
        get() = sp.getInt(KEY_FONT_SIZE, 14)
        set(v) = sp.edit { putInt(KEY_FONT_SIZE, v) }

    var tabSize: Int
        get() = sp.getInt(KEY_TAB_SIZE, 4)
        set(v) = sp.edit { putInt(KEY_TAB_SIZE, v) }

    var wordWrap: Boolean
        get() = sp.getBoolean(KEY_WORD_WRAP, false)
        set(v) = sp.edit { putBoolean(KEY_WORD_WRAP, v) }

    var lineNumbers: Boolean
        get() = sp.getBoolean(KEY_LINE_NUMBERS, true)
        set(v) = sp.edit { putBoolean(KEY_LINE_NUMBERS, v) }

    var showWhitespace: Boolean
        get() = sp.getBoolean(KEY_WHITESPACE, false)
        set(v) = sp.edit { putBoolean(KEY_WHITESPACE, v) }

    var restoreSession: Boolean
        get() = sp.getBoolean(KEY_RESTORE, true)
        set(v) = sp.edit { putBoolean(KEY_RESTORE, v) }

    var lastFolder: String?
        get() = sp.getString(KEY_FOLDER, null)
        set(v) = sp.edit { putString(KEY_FOLDER, v) }

    var openFiles: List<String>
        get() = sp.getString(KEY_OPEN, "")
            ?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        set(v) = sp.edit { putString(KEY_OPEN, v.joinToString("\n")) }

    var activeTab: Int
        get() = sp.getInt(KEY_ACTIVE, 0)
        set(v) = sp.edit { putInt(KEY_ACTIVE, v) }

    companion object {
        private const val KEY_THEME = "theme"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_TAB_SIZE = "tab_size"
        private const val KEY_WORD_WRAP = "word_wrap"
        private const val KEY_LINE_NUMBERS = "line_numbers"
        private const val KEY_WHITESPACE = "whitespace"
        private const val KEY_RESTORE = "restore_session"
        private const val KEY_FOLDER = "last_folder"
        private const val KEY_OPEN = "open_files"
        private const val KEY_ACTIVE = "active_tab"

        val THEME_NAMES = arrayOf(
            "Darcula (Dark)", "VS 2019 (Dark)", "GitHub (Light)",
            "Eclipse (Light)", "Notepad++"
        )
        val TAB_SIZES = arrayOf(2, 4, 8)
    }
}
