package com.easybc.planner.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Persisted theme preference (System default / Light / Dark), the Android
 * counterpart of the web's `localStorage["easybc.themeMode"]`. Device-scoped
 * (plain SharedPreferences), not part of any profile or backup payload.
 */
object ThemeModeStore {
    private const val PREFS_NAME = "easybc_appearance"
    private const val KEY = "theme_mode"

    private var loaded = false
    private val _mode = MutableStateFlow(ThemeMode.SYSTEM)
    val mode: StateFlow<ThemeMode> = _mode

    fun load(context: Context): ThemeMode {
        if (!loaded) {
            loaded = true
            val raw = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY, null)
            _mode.value = runCatching { ThemeMode.valueOf(raw ?: "") }
                .getOrDefault(ThemeMode.SYSTEM)
        }
        return _mode.value
    }

    fun set(context: Context, mode: ThemeMode) {
        _mode.value = mode
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (mode == ThemeMode.SYSTEM) remove(KEY) else putString(KEY, mode.name)
            }
            .apply()
    }
}
