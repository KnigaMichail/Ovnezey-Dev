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
                    is Node.Explore -> node.hotspots.forEach {
                        if (language !in it.translations) missing += "$scene#$index: область «${it.label.take(40)}»"
                    }
                    else -> {}
                }
            }
        }
        for ((id, c) in story.characters) if (language !in c.name.translations) missing += "персонаж $id"
        for ((id, l) in story.locations) if (language !in l.translations) missing += "локация $id"
        for ((id, item) in story.items) {
            if (language !in item.name.translations) missing += "предмет $id: название"
            if (item.description.name.isNotEmpty() && language !in item.description.translations) missing += "предмет $id: описание"
        }
        return missing
    }

    /** Идентификаторы предметов, которые используются в сценарии (выдача, изъятие, условия), но не объявлены через @item. */
    fun undefinedItems(story: Story): Set<String> {
        val used = LinkedHashSet<String>()
        fun scan(condition: Condition?) {
            when (condition) {
                is Condition.HasItem -> used += condition.item
                is Condition.All -> condition.parts.forEach { scan(it) }
                else -> {}
            }
        }
        for (nodes in story.scenes.values) {
            for (node in nodes) {
                when (node) {
                    is Node.Give -> used += node.item
                    is Node.Take -> node.item.let { used += it }
                    is Node.IfWhen -> scan(node.condition)
                    is Node.Choice -> node.options.forEach { scan(it.condition) }
                    is Node.Explore -> {
                        node.hotspots.forEach { scan(it.condition) }
                        node.uses.forEach { used += it.item; scan(it.condition) }
                    }
                    else -> {}
                }
            }
        }
        return used - story.items.keys
    }
}
