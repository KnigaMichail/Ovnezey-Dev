package ru.uralgamebuilding.ovnezey

import android.content.Context
import android.content.res.Resources
import ru.uralgamebuilding.ovnezey.engine.Language

enum class LanguageChoice(val value: String) {
    AUTO("auto"), RU("ru"), EN("en");

    companion object {
        fun fromValue(value: String?): LanguageChoice = entries.firstOrNull { it.value == value } ?: AUTO
    }
}

/** Настройки приложения. Язык применяется и к интерфейсу, и к тексту в игре. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var languageChoice: LanguageChoice
        get() = LanguageChoice.fromValue(prefs.getString(KEY_LANGUAGE, null))
        set(value) { prefs.edit().putString(KEY_LANGUAGE, value.value).apply() }

    /** Итоговый язык: выбранный вручную или, при «Авто», русский для русской системы, иначе английский. */
    val language: Language
        get() = when (languageChoice) {
            LanguageChoice.RU -> Language.RU
            LanguageChoice.EN -> Language.EN
            LanguageChoice.AUTO ->
                if (Resources.getSystem().configuration.locales.get(0).language == "ru") Language.RU else Language.EN
        }

    private companion object {
        const val KEY_LANGUAGE = "language"
    }
}
