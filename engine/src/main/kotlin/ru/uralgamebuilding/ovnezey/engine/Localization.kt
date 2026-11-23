package ru.uralgamebuilding.ovnezey.engine

/** Поддерживаемые языки. Основной язык текстов сценария русский, переводы идут строками «|en ...». */
enum class Language(val code: String) {
    RU("ru"),
    EN("en");

    companion object {
        val DEFAULT = RU

        fun fromCode(code: String): Language? = entries.firstOrNull { it.code == code }
    }
}

/** Выбирает перевод; если перевода нет, возвращает основной текст. */
fun Map<Language, String>.resolve(base: String, language: Language): String =
    if (language == Language.DEFAULT) base else this[language] ?: base

/** Название с переводами: имя персонажа, название локации. */
data class Named(val name: String, val translations: Map<Language, String> = emptyMap()) {
    fun get(language: Language): String = translations.resolve(name, language)
}
