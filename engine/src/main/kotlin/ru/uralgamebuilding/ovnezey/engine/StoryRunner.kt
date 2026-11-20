package ru.uralgamebuilding.ovnezey.engine

/** Что сейчас должно быть показано игроку. */
sealed interface Step {
    data class Say(val speaker: String?, val side: Side, val text: String) : Step

    /** [options] только доступные при текущих флагах; в [choose] передаётся индекс в этом списке. */
    data class Choose(val options: List<Option>) : Step

    data object End : Step
}

/** Сохраняемое состояние: где мы в сюжете и какие флаги установлены. */
data class GameState(val scene: String, val index: Int, val flags: Set<String> = emptySet()) {
    fun encode(): String = "v1|$scene|$index|${flags.sorted().joinToString(",")}"

    companion object {
        private val ID = Regex("[A-Za-z0-9_]+")

        /** Возвращает null для любой строки, которая не является корректным сохранением. */
        fun decode(text: String?): GameState? {
            if (text == null) return null
            val parts = text.split("|")
            if (parts.size != 4 || parts[0] != "v1") return null
            val scene = parts[1].takeIf { ID.matches(it) } ?: return null
            val index = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val flags = if (parts[3].isEmpty()) emptySet() else parts[3].split(",").toSet()
            if (!flags.all { ID.matches(it) }) return null
            return GameState(scene, index, flags)
        }
    }
}

/**
 * Исполнитель сюжета. Не зависит от Android и интерфейса: интерфейс читает [step],
 * вызывает [advance] или [choose] и сохраняет [state].
 */
class StoryRunner(private val story: Story, initial: GameState? = null) {
    var state: GameState = initial ?: GameState(Story.START, 0)
        private set

    init {
        require(isValidPosition(state)) { "состояние не соответствует сюжету: $state" }
        settle()
    }

    val step: Step
        get() = when (val node = nodeAt(state)) {
            is Node.Say -> Step.Say(node.speaker, node.side, node.text)
            is Node.Choice -> Step.Choose(visibleOptions(node))
            Node.End -> Step.End
            else -> error("внутренняя ошибка: незавершённая команда $node")
        }

    /** Перейти к следующему узлу после реплики. */
    fun advance() {
        check(nodeAt(state) is Node.Say) { "advance() допустим только на реплике" }
        state = state.copy(index = state.index + 1)
        settle()
    }

    /** Выбрать вариант из списка [Step.Choose.options]. */
    fun choose(visibleIndex: Int) {
        val node = nodeAt(state)
        check(node is Node.Choice) { "choose() допустим только на выборе" }
        val option = visibleOptions(node).getOrNull(visibleIndex)
            ?: throw IllegalArgumentException("нет варианта с номером $visibleIndex")
        state = state.copy(scene = option.target, index = 0)
        settle()
    }

    private fun nodeAt(s: GameState): Node = story.scenes.getValue(s.scene)[s.index]

    private fun isValidPosition(s: GameState): Boolean {
        val nodes = story.scenes[s.scene] ?: return false
        return s.index in nodes.indices
    }

    private fun visibleOptions(node: Node.Choice): List<Option> = node.options.filter {
        it.requiredFlag == null || (it.requiredFlag in state.flags) != it.requireAbsent
    }

    /** Выполняет команды без участия игрока, пока не дойдёт до реплики, выбора или конца. */
    private fun settle() {
        var guard = 0
        while (true) {
            if (++guard > MAX_AUTO_STEPS) error("зацикливание в сцене «${state.scene}»: слишком много автоматических переходов")
            when (val node = nodeAt(state)) {
                is Node.Say, Node.End -> return
                is Node.Choice -> {
                    check(visibleOptions(node).isNotEmpty()) { "в сцене «${state.scene}» нет доступных вариантов выбора" }
                    return
                }
                is Node.SetFlag -> state = state.copy(index = state.index + 1, flags = state.flags + node.flag)
                is Node.Goto -> state = state.copy(scene = node.target, index = 0)
                is Node.IfGoto -> state =
                    if ((node.flag in state.flags) != node.negate) state.copy(scene = node.target, index = 0)
                    else state.copy(index = state.index + 1)
            }
        }
    }

    companion object {
        private const val MAX_AUTO_STEPS = 10_000

        /** Восстанавливает сохранение; при повреждённом или устаревшем сохранении начинает сюжет заново. */
        fun fromSave(story: Story, encoded: String?): StoryRunner {
            val saved = GameState.decode(encoded)
            if (saved != null) {
                try {
                    return StoryRunner(story, saved)
                } catch (e: IllegalArgumentException) {
                    // сохранение от другой версии сюжета, начинаем заново
                } catch (e: IllegalStateException) {
                }
            }
            return StoryRunner(story)
        }
    }
}
