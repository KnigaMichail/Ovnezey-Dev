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

    /** Сцены, в которые нет ни одного пути из [Story.START] (статический разбор переходов без учёта условий). */
    fun unreachableScenes(story: Story): Set<String> {
        val seen = LinkedHashSet<String>()
        val stack = ArrayDeque(listOf(Story.START))
        while (stack.isNotEmpty()) {
            val name = stack.removeLast()
            if (!seen.add(name)) continue
            for (node in story.scenes[name].orEmpty()) {
                when (node) {
                    is Node.Goto -> stack.add(node.target)
                    is Node.IfGoto -> stack.add(node.target)
                    is Node.IfWhen -> stack.add(node.target)
                    is Node.Choice -> node.options.forEach { stack.add(it.target) }
                    is Node.Explore -> {
                        node.hotspots.forEach { stack.add(it.target) }
                        node.uses.forEach { stack.add(it.target) }
                        node.fails.forEach { stack.add(it.target) }
                    }
                    else -> {}
                }
            }
        }
        return story.scenes.keys - seen
    }

    /** Флаги, которые проверяются в условиях, но нигде не устанавливаются через @set: условие никогда не выполнится. */
    fun flagsNeverSet(story: Story): Set<String> {
        val set = HashSet<String>()
        val read = LinkedHashSet<String>()
        fun scan(c: Condition?) {
            when (c) {
                is Condition.Flag -> read += c.name
                is Condition.All -> c.parts.forEach { scan(it) }
                else -> {}
            }
        }
        for (nodes in story.scenes.values) for (node in nodes) when (node) {
            is Node.SetFlag -> set += node.flag
            is Node.IfGoto -> read += node.flag
            is Node.IfWhen -> scan(node.condition)
            is Node.Choice -> node.options.forEach { o -> o.requiredFlag?.let { read += it }; scan(o.condition) }
            is Node.Explore -> {
                node.hotspots.forEach { scan(it.condition) }
                node.uses.forEach { scan(it.condition) }
            }
            else -> {}
        }
        return read - set
    }

    /** Итог полного обхода всех достижимых состояний. */
    data class WalkReport(val states: Int, val scenes: Set<String>, val endStates: Int, val errors: List<String>)

    /**
     * Обходит все состояния, достижимые из начала: каждая реплика, каждый вариант выбора, каждая область осмотра.
     * Ошибки исполнителя (зацикливание, пустой выбор, неверная позиция) собираются в [WalkReport.errors].
     */
    fun walkAll(story: Story, language: Language = Language.DEFAULT, stateLimit: Int = 500_000): WalkReport {
        val seen = HashSet<GameState>()
        val stack = ArrayDeque<GameState>()
        val scenes = LinkedHashSet<String>()
        val errors = ArrayList<String>()
        var ends = 0
        fun push(s: GameState) { if (seen.size < stateLimit && seen.add(s)) stack.add(s) }
        try {
            push(StoryRunner(story, language = language).state)
        } catch (e: Exception) {
            return WalkReport(0, emptySet(), 0, listOf("старт: ${e.message}"))
        }
        while (stack.isNotEmpty()) {
            val s = stack.removeLast()
            scenes += s.scene
            val actions: List<(StoryRunner) -> Unit>
            try {
                actions = when (val step = StoryRunner(story, s, language).step) {
                    is Step.Say -> listOf { r -> r.advance() }
                    is Step.Choose -> step.options.indices.map { i -> { r: StoryRunner -> r.choose(i) } }
                    is Step.Explore -> step.hotspots.map { h -> { r: StoryRunner -> r.tap(h.id) } }
                    Step.End -> { ends++; emptyList() }
                }
            } catch (e: Exception) {
                errors += "${s.scene}#${s.index}: ${e.message}"
                continue
            }
            for (action in actions) {
                try {
                    val r = StoryRunner(story, s, language)
                    action(r)
                    push(r.state)
                } catch (e: Exception) {
                    errors += "${s.scene}#${s.index}: ${e.message}"
                }
            }
        }
        return WalkReport(seen.size, scenes, ends, errors)
    }
}
