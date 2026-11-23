package ru.uralgamebuilding.ovnezey.engine

/** Проверки сценария для тестов и сборки. */
object StoryChecks {
    /** Возвращает описания всех текстов, у которых нет перевода на [language]. Пустой список значит, что перевод полный. */
    fun missingTranslations(story: Story, language: Language = Language.EN): List<String> {
        require(language != Language.DEFAULT) { "основной язык не требует переводов" }
        val missing = ArrayList<String>()
        for ((scene, nodes) in story.scenes) {
            nodes.forEachIndexed { index, node ->
                when (node) {
                    is Node.Say -> if (language !in node.translations) missing += "$scene#$index: ${node.text.take(40)}"
                    is Node.Choice -> node.options.forEach {
                        if (language !in it.translations) missing += "$scene#$index: вариант «${it.text.take(40)}»"
                    }
                    else -> {}
                }
            }
        }
        for ((id, c) in story.characters) if (language !in c.name.translations) missing += "персонаж $id"
        for ((id, l) in story.locations) if (language !in l.translations) missing += "локация $id"
        return missing
    }
}
