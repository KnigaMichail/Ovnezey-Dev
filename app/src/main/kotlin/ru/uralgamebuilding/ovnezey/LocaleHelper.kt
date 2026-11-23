package ru.uralgamebuilding.ovnezey

import android.content.Context
import android.content.res.Configuration
import ru.uralgamebuilding.ovnezey.engine.Language
import java.util.Locale

object LocaleHelper {
    fun localeOf(language: Language): Locale = Locale.forLanguageTag(language.code)

    /** Возвращает контекст, строки которого берутся на выбранном языке. */
    fun wrap(base: Context, language: Language): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocale(localeOf(language))
        return base.createConfigurationContext(config)
    }
}
