package ru.uralgamebuilding.ovnezey.engine

/** С какой стороны экрана показывается говорящий персонаж. */
enum class Side { LEFT, RIGHT, NONE }

enum class ParamMode { ADD, SET }

/** Вариант выбора. Условие показа: простой флаг задаётся [requiredFlag] и [requireAbsent], остальные условия в [condition]. */
data class Option(
    val text: String,
    val target: String,
    val requiredFlag: String? = null,
    val requireAbsent: Boolean = false,
    val translations: Map<Language, String> = emptyMap(),
    val condition: Condition? = null,
) {
    fun isVisible(state: GameState): Boolean =
        (requiredFlag == null || (requiredFlag in state.flags) != requireAbsent) &&
            (condition?.test(state) ?: true)

    fun localized(language: Language): Option = copy(text = translations.resolve(text, language), translations = emptyMap())
}

/** Команда сценария. Say, Choice и End требуют реакции игрока, остальные выполняются автоматически. */
sealed interface Node {
    /** Реплика персонажа или текст рассказчика ([speaker] == null). В [speaker] хранится идентификатор или имя из сценария. */
    data class Say(
        val speaker: String?,
        val side: Side,
        val text: String,
        val translations: Map<Language, String> = emptyMap(),
    ) : Node

    data class Choice(val options: List<Option>) : Node
    data class Goto(val target: String) : Node
    data class SetFlag(val flag: String) : Node
    data class IfGoto(val flag: String, val negate: Boolean, val target: String) : Node
    data class IfWhen(val condition: Condition, val target: String) : Node
    data class ChangeParam(val param: String, val mode: ParamMode, val value: Int) : Node
    data class Give(val item: String) : Node
    data class Take(val item: String) : Node
    data class SetLocation(val location: String) : Node
    data object End : Node
}

data class Character(val id: String, val name: Named)

/** Сюжет: набор сцен и справочники. Начинается со сцены [START]. */
class Story(
    val scenes: Map<String, List<Node>>,
    val scenarioId: String = DEFAULT_SCENARIO_ID,
    val scenarioVersion: Int = 1,
    val characters: Map<String, Character> = emptyMap(),
    val locations: Map<String, Named> = emptyMap(),
) {
    fun locationTitle(id: String?, language: Language): String? = id?.let { locations[it]?.get(language) }

    companion object {
        const val START = "start"
        const val DEFAULT_SCENARIO_ID = "story"
    }
}
