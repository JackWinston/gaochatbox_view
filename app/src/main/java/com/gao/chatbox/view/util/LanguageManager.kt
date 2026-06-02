package com.gao.chatbox.view.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LanguageManager @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val KEY_LANGUAGE = stringPreferencesKey("ui_language")
        const val LANGUAGE_SYSTEM = "system"
        const val LANGUAGE_CHINESE = "zh"
        const val LANGUAGE_ENGLISH = "en"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val currentLanguage: StateFlow<String> = dataStore.data
        .map { prefs -> prefs[KEY_LANGUAGE] ?: LANGUAGE_SYSTEM }
        .stateIn(scope, SharingStarted.Eagerly, LANGUAGE_SYSTEM)

    suspend fun setLanguage(language: String) {
        dataStore.edit { prefs ->
            prefs[KEY_LANGUAGE] = language
        }
        applyLanguage(language)
    }

    fun applyLanguage(language: String) {
        val localeList = when (language) {
            LANGUAGE_CHINESE -> LocaleListCompat.forLanguageTags("zh")
            LANGUAGE_ENGLISH -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.getEmptyLocaleList()
        }

        AppCompatDelegate.setApplicationLocales(localeList)
    }

    fun getLanguageDisplayName(context: Context, language: String): String {
        return when (language) {
            LANGUAGE_CHINESE -> context.getString(com.gao.chatbox.view.R.string.language_chinese)
            LANGUAGE_ENGLISH -> context.getString(com.gao.chatbox.view.R.string.language_english)
            else -> context.getString(com.gao.chatbox.view.R.string.language_system)
        }
    }

    fun getCurrentLanguageSync(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) {
            LANGUAGE_SYSTEM
        } else {
            when (locales[0]?.language) {
                "zh" -> LANGUAGE_CHINESE
                "en" -> LANGUAGE_ENGLISH
                else -> LANGUAGE_SYSTEM
            }
        }
    }
}
