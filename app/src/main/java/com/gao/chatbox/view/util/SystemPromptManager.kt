package com.gao.chatbox.view.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.gao.chatbox.view.R
import com.gao.chatbox.view.data.model.SystemPrompt
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemPromptManager @Inject constructor(
    private val context: Context,
    private val dataStore: DataStore<Preferences>
) {

    private data class MedicalTerms(
        val inPersonCare: String,
        val emergencyCare: String,
        val emergencyContact: String
    )

    private val gson = Gson()
    private val listType = object : TypeToken<List<SystemPrompt>>() {}.type

    suspend fun init() {
        val initialized = dataStore.data.map { prefs ->
            prefs[KEY_INITIALIZED] ?: false
        }.first()
        val currentPrompts = getAll()
        if (!initialized || currentPrompts.isEmpty()) {
            initDefault()
        } else {
            syncDefaultPrompts(currentPrompts)
        }
    }

    private suspend fun initDefault() {
        val defaultPrompts = buildDefaultPrompts()
        dataStore.edit { prefs ->
            prefs[KEY_PROMPTS] = gson.toJson(defaultPrompts)
            prefs[KEY_INITIALIZED] = true
        }
    }

    private suspend fun syncDefaultPrompts(currentPrompts: List<SystemPrompt>) {
        val hiddenPresetKeys = getHiddenPresetKeys()
        val builtPrompts = buildDefaultPrompts()
        val defaultPrompt = builtPrompts.firstOrNull { it.isDefault }
        val activePresetMap = builtPrompts
            .filterNot { it.isDefault }
            .filterNot { it.presetKey in hiddenPresetKeys }
            .associateBy { it.presetKey }

        val syncedPrompts = mutableListOf<SystemPrompt>()
        if (defaultPrompt != null) {
            syncedPrompts.add(defaultPrompt)
        }

        val addedPresetKeys = mutableSetOf<String>()
        currentPrompts.filterNot { it.isDefault }.forEach { prompt ->
            if (prompt.isPreset) {
                val presetKey = prompt.presetKey ?: return@forEach
                val rebuiltPrompt = activePresetMap[presetKey] ?: return@forEach
                if (addedPresetKeys.add(presetKey)) {
                    syncedPrompts.add(rebuiltPrompt)
                }
            } else {
                syncedPrompts.add(prompt)
            }
        }

        activePresetMap.values.forEach { prompt ->
            val presetKey = prompt.presetKey ?: return@forEach
            if (addedPresetKeys.add(presetKey)) {
                syncedPrompts.add(prompt)
            }
        }

        saveList(syncedPrompts)
    }

    suspend fun getAll(): List<SystemPrompt> {
        val json = dataStore.data.map { prefs ->
            prefs[KEY_PROMPTS]
        }.first() ?: return emptyList()
        return try {
            gson.fromJson(json, listType)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getById(id: String): SystemPrompt? = getAll().find { it.id == id }

    suspend fun add(prompt: SystemPrompt) {
        val list = getAll().toMutableList()
        list.add(prompt)
        saveList(list)
    }

    suspend fun update(prompt: SystemPrompt) {
        val list = getAll().toMutableList()
        val index = list.indexOfFirst { it.id == prompt.id }
        if (index != -1) {
            list[index] = prompt
            saveList(list)
        }
    }

    suspend fun delete(id: String) {
        val list = getAll().toMutableList()
        val prompt = list.find { it.id == id } ?: return
        if (prompt.isDefault) return
        if (prompt.isPreset) {
            addHiddenPresetKey(prompt.presetKey)
        }
        list.removeAll { it.id == id }
        saveList(list)
    }

    suspend fun reorderPrompts(orderedPrompts: List<SystemPrompt>) {
        val currentPrompts = getAll()
        val defaultPrompt = currentPrompts.find { it.isDefault }
        val movablePromptMap = currentPrompts
            .filterNot { it.isDefault }
            .associateBy { it.id }
        val reorderedPrompts = orderedPrompts
            .filterNot { it.isDefault }
            .mapNotNull { movablePromptMap[it.id] }
        if (reorderedPrompts.size != movablePromptMap.size) {
            return
        }
        saveList(listOfNotNull(defaultPrompt) + reorderedPrompts)
    }

    private suspend fun saveList(list: List<SystemPrompt>) {
        dataStore.edit { prefs ->
            prefs[KEY_PROMPTS] = gson.toJson(list)
        }
    }

    private suspend fun getHiddenPresetKeys(): Set<String> {
        return dataStore.data.map { prefs ->
            prefs[KEY_HIDDEN_PRESET_KEYS] ?: emptySet()
        }.first()
    }

    private suspend fun addHiddenPresetKey(presetKey: String?) {
        if (presetKey.isNullOrBlank()) return
        dataStore.edit { prefs ->
            val hiddenKeys = (prefs[KEY_HIDDEN_PRESET_KEYS] ?: emptySet()).toMutableSet()
            hiddenKeys.add(presetKey)
            prefs[KEY_HIDDEN_PRESET_KEYS] = hiddenKeys
        }
    }

    private fun buildDefaultPrompts(): List<SystemPrompt> {
        val legalJurisdiction = resolveLegalJurisdiction()
        val medicalTerms = resolveMedicalTerms()
        return listOf(
            SystemPrompt(
                tag = context.getString(R.string.default_system_prompt_tag),
                content = context.getString(R.string.default_system_prompt_content),
                isDefault = true,
                isPreset = true,
                presetKey = PRESET_KEY_DEFAULT
            ),
            SystemPrompt(
                tag = context.getString(R.string.preset_tag_family_doctor),
                content = context.getString(
                    R.string.preset_content_family_doctor,
                    medicalTerms.inPersonCare,
                    medicalTerms.emergencyCare,
                    medicalTerms.emergencyContact
                ),
                isPreset = true,
                presetKey = PRESET_KEY_FAMILY_DOCTOR
            ),
            SystemPrompt(
                tag = context.getString(R.string.preset_tag_lawyer),
                content = context.getString(R.string.preset_content_lawyer, legalJurisdiction),
                isPreset = true,
                presetKey = PRESET_KEY_LAWYER
            ),
            SystemPrompt(
                tag = context.getString(R.string.preset_tag_translator),
                content = context.getString(R.string.preset_content_translator),
                isPreset = true,
                presetKey = PRESET_KEY_TRANSLATOR
            ),
            SystemPrompt(
                tag = context.getString(R.string.preset_tag_writer),
                content = context.getString(R.string.preset_content_writer),
                isPreset = true,
                presetKey = PRESET_KEY_WRITER
            ),
            SystemPrompt(
                tag = context.getString(R.string.preset_tag_programmer),
                content = context.getString(R.string.preset_content_programmer),
                isPreset = true,
                presetKey = PRESET_KEY_PROGRAMMER
            ),
            SystemPrompt(
                tag = context.getString(R.string.preset_tag_interview_coach),
                content = context.getString(R.string.preset_content_interview_coach),
                isPreset = true,
                presetKey = PRESET_KEY_INTERVIEW_COACH
            ),
            SystemPrompt(
                tag = context.getString(R.string.preset_tag_study_tutor),
                content = context.getString(R.string.preset_content_study_tutor),
                isPreset = true,
                presetKey = PRESET_KEY_STUDY_TUTOR
            )
        )
    }

    private fun resolveLegalJurisdiction(): String {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val countryCode = locale.country
        if (countryCode.isNotBlank()) {
            val countryLocale = Locale.Builder().setRegion(countryCode).build()
            return countryLocale.getDisplayCountry(locale).ifBlank {
                defaultLegalJurisdictionFor(locale)
            }
        }
        return defaultLegalJurisdictionFor(locale)
    }

    private fun defaultLegalJurisdictionFor(locale: Locale): String {
        return if (locale.language.equals("zh", ignoreCase = true)) {
            context.getString(R.string.preset_country_china)
        } else {
            context.getString(R.string.preset_country_united_states)
        }
    }

    private fun resolveMedicalTerms(): MedicalTerms {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        return when {
            locale.language.equals("zh", ignoreCase = true) -> MedicalTerms(
                inPersonCare = context.getString(R.string.preset_medical_in_person_care),
                emergencyCare = context.getString(R.string.preset_medical_emergency_care),
                emergencyContact = context.getString(R.string.preset_medical_emergency_contact)
            )
            locale.country.equals("US", ignoreCase = true) -> MedicalTerms(
                inPersonCare = context.getString(R.string.preset_medical_in_person_care),
                emergencyCare = context.getString(R.string.preset_medical_emergency_care_us),
                emergencyContact = context.getString(R.string.preset_medical_emergency_contact_us)
            )
            locale.country.equals("GB", ignoreCase = true) -> MedicalTerms(
                inPersonCare = context.getString(R.string.preset_medical_in_person_care),
                emergencyCare = context.getString(R.string.preset_medical_emergency_care_uk),
                emergencyContact = context.getString(R.string.preset_medical_emergency_contact_uk)
            )
            else -> MedicalTerms(
                inPersonCare = context.getString(R.string.preset_medical_in_person_care),
                emergencyCare = context.getString(R.string.preset_medical_emergency_care_generic),
                emergencyContact = context.getString(R.string.preset_medical_emergency_contact_generic)
            )
        }
    }

    companion object {
        private val KEY_PROMPTS = stringPreferencesKey("system_prompts")
        private val KEY_INITIALIZED = booleanPreferencesKey("initialized")
        private val KEY_HIDDEN_PRESET_KEYS = stringSetPreferencesKey("hidden_system_prompt_preset_keys")

        private const val PRESET_KEY_DEFAULT = "default"
        private const val PRESET_KEY_FAMILY_DOCTOR = "family_doctor"
        private const val PRESET_KEY_LAWYER = "lawyer"
        private const val PRESET_KEY_TRANSLATOR = "translator"
        private const val PRESET_KEY_WRITER = "writer"
        private const val PRESET_KEY_PROGRAMMER = "programmer"
        private const val PRESET_KEY_INTERVIEW_COACH = "interview_coach"
        private const val PRESET_KEY_STUDY_TUTOR = "study_tutor"
    }
}
