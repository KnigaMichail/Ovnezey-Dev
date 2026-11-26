package ru.uralgamebuilding.ovnezey.engine

/** Область на картинке для показа: название на текущем языке и прямоугольник в долях картинки. */
data class HotspotView(val id: String, val label: String, val x: Float, val y: Float, val w: Float, val h: Float)

/** Предмет в рюкзаке для показа: тексты на текущем языке. */
data class ItemView(val id: String, val name: String, val description: String)

enum class RejectReason { NOT_EXPLORING, NO_SUCH_HOTSPOT, HOTSPOT_UNAVAILABLE, ITEM_NOT_OWNED, NO_EFFECT }

/** Итог применения предмета на область. [Rejected] значит, что состояние не изменилось. */
sealed interface UseResult {
    /** Сочетание подошло: запущена сцена из сценария, расходуемый предмет израсходован. */
    data object Used : UseResult

    /** Сочетание не подошло, но в сценарии есть реакция: запущена она, предмет не расходуется. */
    data object Reaction : UseResult

    data class Rejected(val reason: RejectReason) : UseResult
}

/** Что сейчас должно быть показано игроку. Тексты уже на языке [StoryRunner.language]. */
sealed interface Step {
    /** [speaker] отображаемое имя, [speakerId] идентификатор из сценария (ключ портрета). */
    data class Say(val speaker: String?, val side: Side, val text: String, val speakerId: String? = speaker) : Step

    /** [options] только доступные при текущем состоянии; в [StoryRunner.choose] передаётся индекс в этом списке. */
    data class Choose(val options: List<Option>) : Step

    /** Осмотр локации: доступные сейчас области. */
    data class Explore(val hotspots: List<HotspotView>) : Step

    /** Мини-игра. Итог передаётся в [StoryRunner.finishMinigame]. */
    data class Minigame(val kind: MinigameKind, val count: Int, val seconds: Int, val label: String) : Step

    /** Экранная вставка, продолжается через [StoryRunner.advance]. */
    data class Show(val kind: ShowKind, val text: String) : Step

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
            is Node.Explore -> Step.Explore(visibleHotspots(node).map {
                HotspotView(it.id, it.translations.resolve(it.label, language), it.x, it.y, it.w, it.h)
            })
            is Node.Minigame -> Step.Minigame(node.kind, node.count, node.seconds, node.translations.resolve(node.label, language))
            is Node.Show -> Step.Show(node.kind, node.translations.resolve(node.text, language))
            Node.End -> Step.End
            else -> error("внутренняя ошибка: незавершённая команда $node")
        }

    /** Предметы в рюкзаке в порядке объявления в сценарии; необъявленные идут в конце по алфавиту. */
    val inventoryItems: List<ItemView>
        get() {
            val defined = story.items.values.filter { it.id in state.inventory }
            val undefined = (state.inventory - story.items.keys).sorted()
            return defined.map { ItemView(it.id, it.name.get(language), it.description.get(language)) } +
                undefined.map { ItemView(it, it, "") }
        }

    /** Название текущей локации на текущем языке или null, если локация не задана. */
    val locationTitle: String?
        get() = story.locationTitle(state.location, language)

    /** Перейти к следующему узлу после реплики или экранной вставки. */
    fun advance() {
        check(nodeAt(state).let { it is Node.Say || it is Node.Show }) { "advance() допустим только на реплике или вставке" }
        state = state.copy(index = state.index + 1)
        settle()
    }

    /**
     * Итог мини-игры. Успех ведёт в цель успеха или дальше по сцене. Провал ведёт в цель провала;
     * без неё состояние не меняется, и интерфейс запускает мини-игру заново.
     */
    fun finishMinigame(success: Boolean) {
        val node = nodeAt(state)
        check(node is Node.Minigame) { "finishMinigame() допустим только на мини-игре" }
        if (success) {
            state = if (node.okTarget != null) state.copy(scene = node.okTarget, index = 0) else state.copy(index = state.index + 1)
        } else {
            val target = node.failTarget ?: return
            state = state.copy(scene = target, index = 0)
        }
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

    /** Нажатие на область во время осмотра: переход в сцену области. */
    fun tap(hotspotId: String) {
        val node = nodeAt(state)
        check(node is Node.Explore) { "tap() допустим только при осмотре локации" }
        val hotspot = node.hotspots.firstOrNull { it.id == hotspotId && it.isAvailable(state) }
            ?: throw IllegalArgumentException("нет доступной области «$hotspotId»")
        state = state.copy(scene = hotspot.target, index = 0)
        settle()
    }

    /**
     * Применение предмета [itemId] на область [hotspotId]. Неверное применение не портит состояние:
     * возвращается [UseResult.Rejected] либо запускается реакция из сценария ([UseResult.Reaction]).
     */
    fun useItem(itemId: String, hotspotId: String): UseResult {
        val node = nodeAt(state) as? Node.Explore ?: return UseResult.Rejected(RejectReason.NOT_EXPLORING)
        val hotspot = node.hotspots.firstOrNull { it.id == hotspotId }
            ?: return UseResult.Rejected(RejectReason.NO_SUCH_HOTSPOT)
        if (!hotspot.isAvailable(state)) return UseResult.Rejected(RejectReason.HOTSPOT_UNAVAILABLE)
        if (itemId !in state.inventory) return UseResult.Rejected(RejectReason.ITEM_NOT_OWNED)

        val rule = node.uses.firstOrNull {
            it.item == itemId && it.hotspot == hotspotId && (it.condition?.test(state) ?: true)
        }
        if (rule != null) {
            val consumed = story.items[itemId]?.consume == true
            state = state.copy(
                scene = rule.target,
                index = 0,
                inventory = if (consumed) state.inventory - itemId else state.inventory,
            )
            settle()
            return UseResult.Used
        }
        val fail = node.fails.firstOrNull { it.hotspot == hotspotId } ?: node.fails.firstOrNull { it.hotspot == FailRule.ANY }
        if (fail != null) {
            state = state.copy(scene = fail.target, index = 0)
            settle()
            return UseResult.Reaction
        }
        return UseResult.Rejected(RejectReason.NO_EFFECT)
    }

    private fun visibleHotspots(node: Node.Explore): List<Hotspot> = node.hotspots.filter { it.isAvailable(state) }

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
                is Node.Say, is Node.Show, is Node.Minigame, Node.End -> return
                is Node.Choice -> {
                    check(visibleOptions(node).isNotEmpty()) { "в сцене «${state.scene}» нет доступных вариантов выбора" }
                    return
                }
                is Node.Explore -> {
                    check(visibleHotspots(node).isNotEmpty()) { "в сцене «${state.scene}» нет доступных областей для осмотра" }
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
                is Node.Reset -> state = next(
                    flags = state.flags.intersect(node.keep),
                    params = emptyMap(),
                    inventory = emptySet(),
                    location = null,
                )
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
