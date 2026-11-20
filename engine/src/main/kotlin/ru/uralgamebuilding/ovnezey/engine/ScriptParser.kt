package ru.uralgamebuilding.ovnezey.engine

class StoryParseException(message: String, val line: Int? = null) :
    Exception(if (line != null) "строка $line: $message" else message)

/**
 * Разбор текстового формата сценария (.ovn). Справочник формата: README.md.
 * Парсер ничего не знает об Android и проверяет сценарий целиком: ссылки на сцены,
 * наличие сцены start, корректное завершение каждой сцены.
 */
object ScriptParser {
    private val ID = "[A-Za-z0-9_]+"
    private val OPTION = Regex("""^\*\s+(.+?)\s+->\s+($ID)(?:\s+\?\s*(!?)($ID))?$""")
    private val IF = Regex("""^@if\s+(!?)($ID)\s*->\s*($ID)$""")
    private val SAY = Regex("""^([^:@>*#]+?)(?:@(left|right))?:\s*(.+)$""")
    private val ID_ONLY = Regex("^$ID$")

    fun parse(source: String): Story {
        val scenes = LinkedHashMap<String, MutableList<Node>>()
        val sceneLines = HashMap<String, Int>()
        val lastSide = HashMap<String, Side>()
        var current: MutableList<Node>? = null
        var options: MutableList<Option>? = null
        var choiceLine = 0

        fun closeChoice() {
            val opts = options ?: return
            if (opts.isEmpty()) throw StoryParseException("пустой @choice, нужен хотя бы один вариант «* текст -> сцена»", choiceLine)
            current!!.add(Node.Choice(opts.toList()))
            options = null
        }

        fun id(value: String, n: Int, what: String): String {
            if (!ID_ONLY.matches(value)) throw StoryParseException("недопустимое имя $what «$value» (только латиница, цифры и _)", n)
            return value
        }

        source.removePrefix("\uFEFF").lines().forEachIndexed { i, raw ->
            val n = i + 1
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

            if (options != null) {
                if (line.startsWith("*")) {
                    val m = OPTION.matchEntire(line)
                        ?: throw StoryParseException("неверный вариант выбора, ожидается «* текст -> сцена» или «* текст -> сцена ? флаг»", n)
                    val (text, target, bang, flag) = m.destructured
                    options!!.add(Option(text.unescape(), target, flag.ifEmpty { null }, bang == "!"))
                    return@forEachIndexed
                }
                closeChoice()
            }

            if (line.startsWith("@")) {
                val parts = line.split(Regex("\\s+"), limit = 2)
                val cmd = parts[0]
                val arg = parts.getOrNull(1)?.trim().orEmpty()
                if (cmd == "@scene") {
                    closeChoice()
                    val name = id(arg, n, "сцены")
                    if (scenes.containsKey(name)) throw StoryParseException("сцена «$name» уже объявлена в строке ${sceneLines[name]}", n)
                    current = mutableListOf<Node>().also { scenes[name] = it }
                    sceneLines[name] = n
                    return@forEachIndexed
                }
                val scene = current ?: throw StoryParseException("команда $cmd вне сцены, сначала нужна «@scene имя»", n)
                when (cmd) {
                    "@choice" -> { options = mutableListOf(); choiceLine = n }
                    "@goto" -> scene.add(Node.Goto(id(arg, n, "сцены")))
                    "@set" -> scene.add(Node.SetFlag(id(arg, n, "флага")))
                    "@end" -> scene.add(Node.End)
                    "@if" -> {
                        val m = IF.matchEntire(line) ?: throw StoryParseException("ожидается «@if флаг -> сцена» или «@if !флаг -> сцена»", n)
                        val (bang, flag, target) = m.destructured
                        scene.add(Node.IfGoto(flag, bang == "!", target))
                    }
                    else -> throw StoryParseException("неизвестная команда $cmd", n)
                }
                return@forEachIndexed
            }

            val scene = current ?: throw StoryParseException("текст вне сцены, сначала нужна «@scene имя»", n)
            if (line.startsWith(">")) {
                scene.add(Node.Say(null, Side.NONE, line.substring(1).trim().unescape()))
                return@forEachIndexed
            }
            val m = SAY.matchEntire(line)
                ?: throw StoryParseException("не удалось разобрать строку: «$line». Реплика пишется «Имя: текст», рассказчик «> текст»", n)
            val (name, side, text) = m.destructured
            val speaker = name.trim()
            val resolved = when (side) {
                "left" -> Side.LEFT
                "right" -> Side.RIGHT
                else -> lastSide[speaker] ?: Side.LEFT
            }
            lastSide[speaker] = resolved
            scene.add(Node.Say(speaker, resolved, text.unescape()))
        }
        closeChoice()
        validate(scenes)
        return Story(scenes)
    }

    private fun String.unescape() = replace("\\n", "\n")

    private fun validate(scenes: Map<String, List<Node>>) {
        if (!scenes.containsKey(Story.START)) throw StoryParseException("нет сцены «${Story.START}»")
        for ((name, nodes) in scenes) {
            if (nodes.isEmpty()) throw StoryParseException("сцена «$name» пуста")
            val last = nodes.last()
            if (last !is Node.End && last !is Node.Goto && last !is Node.Choice) {
                throw StoryParseException("сцена «$name» не завершена: последней командой должна быть @end, @goto или @choice")
            }
            for (node in nodes) {
                val targets = when (node) {
                    is Node.Goto -> listOf(node.target)
                    is Node.IfGoto -> listOf(node.target)
                    is Node.Choice -> node.options.map { it.target }
                    else -> emptyList()
                }
                for (t in targets) {
                    if (!scenes.containsKey(t)) throw StoryParseException("сцена «$name» ссылается на несуществующую сцену «$t»")
                }
            }
        }
    }
}
