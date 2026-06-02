package com.gao.chatbox.view.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemeManager @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val KEY_THEME = stringPreferencesKey("ui_theme")
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val currentTheme: StateFlow<String> = dataStore.data
        .map { prefs -> prefs[KEY_THEME] ?: THEME_SYSTEM }
        .stateIn(scope, SharingStarted.Eagerly, THEME_SYSTEM)

    init {
        scope.launch {
            currentTheme.collect { theme ->
                withContext(Dispatchers.Main) {
                    applyTheme(theme)
                }
            }
        }
    }

    suspend fun setTheme(theme: String) {
        dataStore.edit { prefs ->
            prefs[KEY_THEME] = theme
        }
        withContext(Dispatchers.Main) {
            applyTheme(theme)
        }
    }

    fun applyTheme(theme: String) {
        val mode = when (theme) {
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun getThemeDisplayName(context: Context, theme: String): String {
        return when (theme) {
            THEME_LIGHT -> context.getString(com.gao.chatbox.view.R.string.theme_light)
            THEME_DARK -> context.getString(com.gao.chatbox.view.R.string.theme_dark)
            else -> context.getString(com.gao.chatbox.view.R.string.theme_system)
        }
    }
}
