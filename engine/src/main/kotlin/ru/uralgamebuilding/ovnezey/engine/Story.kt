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

/** Предмет. Иконка берётся из assets/items/<id>.png. [consume] значит, что предмет расходуется при успешном применении. */
data class ItemDef(
    val id: String,
    val name: Named,
    val description: Named = Named(""),
    val consume: Boolean = false,
)

/**
 * Активная область на иллюстрации локации. Координаты в долях картинки: [x], [y] левый верхний угол, [w], [h] размеры.
 * Нажатие ведёт в сцену [target]. Область видна, если выполнено [condition].
 */
data class Hotspot(
    val id: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val label: String,
    val target: String,
    val translations: Map<Language, String> = emptyMap(),
    val condition: Condition? = null,
) {
    fun isAvailable(state: GameState): Boolean = condition?.test(state) ?: true
}

/** Применение предмета [item] на область [hotspot] ведёт в сцену [target], если выполнено [condition]. */
data class UseRule(val item: String, val hotspot: String, val target: String, val condition: Condition? = null)

/** Реакция на неподходящий предмет: для области [hotspot] или для любой, если [hotspot] равен [ANY]. */
data class FailRule(val hotspot: String, val target: String) {
    companion object {
        const val ANY = "*"
    }
}

/** Вид мини-игры. Правила и отрисовку определяет интерфейс, движок хранит только параметры. */
enum class MinigameKind(val code: String) {
    /** Проснуться: быстро тапать, пока «глаза» не откроются. Провала нет. */
    WAKE("wake"),

    /** Приложить пропуск к терминалу: один тап. */
    BADGE("badge"),

    /** Вскрыть ящик: [Node.Minigame.count] тапов без ограничения по времени. */
    CRATE("crate"),

    /** Отбиться: [Node.Minigame.count] тапов за [Node.Minigame.seconds] секунд, иначе провал. */
    FIGHT("fight"),

    /** Соединить [Node.Minigame.count] пар проводов. */
    WIRES("wires");

    companion object {
        fun fromCode(code: String): MinigameKind? = entries.firstOrNull { it.code == code }
    }
}

/** Вид экранной вставки: анимированный город или терминал с ошибкой. */
enum class ShowKind(val code: String) {
    CITY("city"),
    TERMINAL("terminal");

    companion object {
        fun fromCode(code: String): ShowKind? = entries.firstOrNull { it.code == code }
    }
}

/** Команда сценария. Say, Show, Minigame, Choice, Explore и End требуют реакции игрока, остальные выполняются автоматически. */
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

    /** Осмотр локации: игрок нажимает на области или применяет к ним предметы. Выход из сцены задаёт сценарий переходами. */
    data class Explore(
        val hotspots: List<Hotspot>,
        val uses: List<UseRule> = emptyList(),
        val fails: List<FailRule> = emptyList(),
    ) : Node

    /**
     * Мини-игра. Успех ведёт в [okTarget] или, если он не задан, к следующей команде. Провал ведёт в [failTarget];
     * если он не задан, мини-игра начинается заново без изменения состояния.
     */
    data class Minigame(
        val kind: MinigameKind,
        val count: Int,
        val seconds: Int,
        val label: String,
        val okTarget: String? = null,
        val failTarget: String? = null,
        val translations: Map<Language, String> = emptyMap(),
    ) : Node

    /** Экранная вставка с текстом [text] (заголовок или сообщение об ошибке). Игрок нажимает, чтобы продолжить. */
    data class Show(
        val kind: ShowKind,
        val text: String,
        val translations: Map<Language, String> = emptyMap(),
    ) : Node

    /** Сброс партии для петли: очищает флаги (кроме [keep]), параметры, рюкзак и локацию. */
    data class Reset(val keep: Set<String>) : Node

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
    val items: Map<String, ItemDef> = emptyMap(),
) {
    fun locationTitle(id: String?, language: Language): String? = id?.let { locations[it]?.get(language) }

    companion object {
        const val START = "start"
        const val DEFAULT_SCENARIO_ID = "story"
    }
}
