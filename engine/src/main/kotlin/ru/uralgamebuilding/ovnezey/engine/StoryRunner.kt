package ru.uralgamebuilding.ovnezey.engine

/** Что сейчас должно быть показано игроку. Тексты уже на языке [StoryRunner.language]. */
sealed interface Step {
    /** [speaker] отображаемое имя, [speakerId] идентификатор из сценария (ключ портрета). */
    data class Say(val speaker: String?, val side: Side, val text: String, val speakerId: String? = speaker) : Step

    /** [options] только доступные при текущем состоянии; в [StoryRunner.choose] передаётся индекс в этом списке. */
    data class Choose(val options: List<Option>) : Step

    data object End : Step
}

/**
 * Исполнитель сюжета. Не зависит от Android и интерфейса: интерфейс читает [step],
 * вызывает [advance] или [choose] и сохраняет [state]. Язык можно менять в любой момент.
 */
class StoryRunner(
    private val story: Story,
    initial: GameState? = null,
    var language: Language = Language.DEFAULT,
) {
    var state: GameState = initial ?: GameState(Story.START, 0)
        private set

    init {
        require(isValidPosition(state)) { "состояние не соответствует сюжету: $state" }
        settle()
    }

    val step: Step
        get() = when (val node = nodeAt(state)) {
            is Node.Say -> Step.Say(
                speaker = node.speaker?.let { story.characters[it]?.name?.get(language) ?: it },
                side = node.side,
                text = node.translations.resolve(node.text, language),
                speakerId = node.speaker,
            )
            is Node.Choice -> Step.Choose(visibleOptions(node).map { it.localized(language) })
            Node.End -> Step.End
            else -> error("внутренняя ошибка: незавершённая команда $node")
        }

    /** Название текущей локации на текущем языке или null, если локация не задана. */
    val locationTitle: String?
        get() = story.locationTitle(state.location, language)

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

    private fun visibleOptions(node: Node.Choice): List<Option> = node.options.filter { it.isVisible(state) }

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
                is Node.SetFlag -> state = next(flags = state.flags + node.flag)
                is Node.Goto -> state = state.copy(scene = node.target, index = 0)
                is Node.IfGoto -> state =
                    if ((node.flag in state.flags) != node.negate) state.copy(scene = node.target, index = 0) else next()
                is Node.IfWhen -> state =
                    if (node.condition.test(state)) state.copy(scene = node.target, index = 0) else next()
                is Node.ChangeParam -> {
                    val old = state.param(node.param).toLong()
                    val updated = (if (node.mode == ParamMode.ADD) old + node.value else node.value.toLong())
                        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                    state = next(params = state.params + (node.param to updated))
                }
                is Node.Give -> state = next(inventory = state.inventory + node.item)
                is Node.Take -> state = next(inventory = state.inventory - node.item)
                is Node.SetLocation -> state = next(location = node.location)
            }
        }
    }

    private fun next(
        flags: Set<String> = state.flags,
        params: Map<String, Int> = state.params,
        inventory: Set<String> = state.inventory,
        location: String? = state.location,
    ) = state.copy(index = state.index + 1, flags = flags, params = params, inventory = inventory, location = location)

    companion object {
        private const val MAX_AUTO_STEPS = 10_000

        /**
         * Восстанавливает прогресс из сохранения. Прогресс никогда не сбрасывается молча:
         * несовместимое сохранение возвращается как [LoadResult.Incompatible] с причиной.
         */
        fun restore(
            story: Story,
            data: SaveData,
            migrated: Boolean = false,
            language: Language = Language.DEFAULT,
        ): LoadResult {
            if (data.scenarioId != null && data.scenarioId != story.scenarioId) {
                return LoadResult.Incompatible(IncompatibleReason.SCENARIO_MISMATCH)
            }
            if (data.scenarioVersion != null && data.scenarioVersion != story.scenarioVersion) {
                return LoadResult.Incompatible(IncompatibleReason.VERSION_MISMATCH)
            }
            return try {
                LoadResult.Loaded(StoryRunner(story, data.state, language), migrated)
            } catch (e: IllegalArgumentException) {
                LoadResult.Incompatible(IncompatibleReason.INVALID_POSITION)
            } catch (e: IllegalStateException) {
                LoadResult.Incompatible(IncompatibleReason.INVALID_POSITION)
            }
        }
    }
}
