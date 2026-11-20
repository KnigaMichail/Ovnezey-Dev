package ru.uralgamebuilding.ovnezey.engine

/** С какой стороны экрана показывается говорящий персонаж. */
enum class Side { LEFT, RIGHT, NONE }

/** Вариант выбора. [requiredFlag] задаёт условие показа: флаг должен быть установлен (или отсутствовать при [requireAbsent]). */
data class Option(
    val text: String,
    val target: String,
    val requiredFlag: String? = null,
    val requireAbsent: Boolean = false,
)

/** Команда сценария. Say, Choice и End требуют реакции игрока, остальные выполняются автоматически. */
sealed interface Node {
    /** Реплика персонажа или текст рассказчика ([speaker] == null). */
    data class Say(val speaker: String?, val side: Side, val text: String) : Node
    data class Choice(val options: List<Option>) : Node
    data class Goto(val target: String) : Node
    data class SetFlag(val flag: String) : Node
    data class IfGoto(val flag: String, val negate: Boolean, val target: String) : Node
    data object End : Node
}

/** Сюжет: набор сцен. Начинается со сцены [START]. */
class Story(val scenes: Map<String, List<Node>>) {
    companion object {
        const val START = "start"
    }
}
