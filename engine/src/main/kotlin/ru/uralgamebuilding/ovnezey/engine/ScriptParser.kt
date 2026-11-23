package ru.uralgamebuilding.ovnezey.engine

class StoryParseException(message: String, val line: Int? = null) :
    Exception(if (line != null) "строка $line: $message" else message)

/**
 * Разбор текстового формата сценария (.ovn). Справочник формата: README.md.
 * Парсер ничего не знает об Android и проверяет сценарий целиком: ссылки на сцены,
 * наличие сцены start, корректное завершение каждой сцены, названия локаций.
 */
object ScriptParser {
    private const val ID = "[A-Za-z0-9_]+"
    private val OPTION = Regex("""^\*\s+(.+?)\s+->\s+($ID)(?:\s+\?\s*(.+))?$""")
    private val IF = Regex("""^@if\s+(.+?)\s*->\s*($ID)$""")
    private val PARAM = Regex("""^@param\s+($ID)\s+(?:([+-])\s*(\d+)|=\s*(-?\d+))$""")
    private val SAY = Regex("""^([^:@>*#|]+?)(?:@(left|right))?:\s*(.+)$""")
    private val TRANSLATION = Regex("""^\|([a-z]{2})\s+(.+)$""")
    private val ID_ONLY = Regex("^$ID$")

    fun parse(source: String): Story {
        val scenes = LinkedHashMap<String, MutableList<Node>>()
        val sceneLines = HashMap<String, Int>()
        val lastSide = HashMap<String, Side>()
        val characters = LinkedHashMap<String, Character>()
        val locationTitles = LinkedHashMap<String, Named>()
        val usedLocations = LinkedHashMap<String, Int>()
        var scenarioId = Story.DEFAULT_SCENARIO_ID
        var scenarioVersion = 1
        var scenarioDeclared = false
        var current: MutableList<Node>? = null
        var options: MutableList<Option>? = null
        var choiceLine = 0
        // Принимает строку перевода «|en ...» для последней текстовой строки; null, если переводить нечего.
        var attach: ((Language, String, Int) -> Unit)? = null

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

        fun condition(text: String, n: Int): Condition =
            ConditionParser.parse(text) ?: throw StoryParseException(
                "неверное условие «$text». Допустимо: флаг, !флаг, параметр>=число, has:предмет, !has:предмет, части через &",
                n,
            )

        fun duplicate(language: Language, n: Int): Nothing =
            throw StoryParseException("перевод на язык «${language.code}» для этой строки уже задан", n)

        source.removePrefix("\uFEFF").lines().forEachIndexed { i, raw ->
            val n = i + 1
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

            if (line.startsWith("|")) {
                val m = TRANSLATION.matchEntire(line)
                    ?: throw StoryParseException("неверный перевод, ожидается «|en текст»", n)
                val language = Language.fromCode(m.groupValues[1])
                    ?: throw StoryParseException("неподдерживаемый язык «${m.groupValues[1]}»", n)
                if (language == Language.DEFAULT) {
                    throw StoryParseException("текст на языке «${language.code}» пишется в основной строке, не в переводе", n)
                }
                val target = attach ?: throw StoryParseException("перевод должен идти сразу под строкой, к которой он относится", n)
                target(language, m.groupValues[2].unescape(), n)
                return@forEachIndexed
            }

            attach = null

            if (options != null) {
                if (line.startsWith("*")) {
                    val m = OPTION.matchEntire(line)
                        ?: throw StoryParseException("неверный вариант выбора, ожидается «* текст -> сцена» или «* текст -> сцена ? условие»", n)
                    val text = m.groupValues[1]
                    val target = m.groupValues[2]
                    val cond = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { condition(it, n) }
                    val simple = cond as? Condition.Flag
                    val opts = options!!
                    val index = opts.size
                    opts.add(
                        Option(
                            text = text.unescape(),
                            target = target,
                            requiredFlag = simple?.name,
                            requireAbsent = simple?.negate ?: false,
                            condition = if (simple == null) cond else null,
                        ),
                    )
                    attach = { lang, tr, ln ->
                        val cur = opts[index]
                        if (lang in cur.translations) duplicate(lang, ln)
                        opts[index] = cur.copy(translations = cur.translations + (lang to tr))
                    }
                    return@forEachIndexed
                }
                closeChoice()
            }

            if (line.startsWith("@")) {
                val parts = line.split(Regex("\\s+"), limit = 2)
                val cmd = parts[0]
                val arg = parts.getOrNull(1)?.trim().orEmpty()
                when (cmd) {
                    "@scenario" -> {
                        if (scenarioDeclared) throw StoryParseException("@scenario уже объявлен", n)
                        val args = arg.split(Regex("\\s+"))
                        val version = args.getOrNull(1)?.toIntOrNull()?.takeIf { it >= 1 && args.size == 2 }
                            ?: throw StoryParseException("ожидается «@scenario идентификатор версия», версия целое число от 1", n)
                        scenarioId = id(args[0], n, "сценария")
                        scenarioVersion = version
                        scenarioDeclared = true
                    }
                    "@character" -> {
                        val args = arg.split(Regex("\\s+"), limit = 2)
                        if (args.size < 2) throw StoryParseException("ожидается «@character идентификатор Имя»", n)
                        val charId = id(args[0], n, "персонажа")
                        if (characters.containsKey(charId)) throw StoryParseException("персонаж «$charId» уже объявлен", n)
                        characters[charId] = Character(charId, Named(args[1].unescape()))
                        attach = { lang, tr, ln ->
                            val cur = characters.getValue(charId)
                            if (lang in cur.name.translations) duplicate(lang, ln)
                            characters[charId] = cur.copy(name = cur.name.copy(translations = cur.name.translations + (lang to tr)))
                        }
                    }
                    "@scene" -> {
                        closeChoice()
                        val name = id(arg, n, "сцены")
                        if (scenes.containsKey(name)) throw StoryParseException("сцена «$name» уже объявлена в строке ${sceneLines[name]}", n)
                        current = mutableListOf<Node>().also { scenes[name] = it }
                        sceneLines[name] = n
                    }
                    else -> {
                        val scene = current ?: throw StoryParseException("команда $cmd вне сцены, сначала нужна «@scene имя»", n)
                        when (cmd) {
                            "@choice" -> { options = mutableListOf(); choiceLine = n }
                            "@goto" -> scene.add(Node.Goto(id(arg, n, "сцены")))
                            "@set" -> scene.add(Node.SetFlag(id(arg, n, "флага")))
                            "@end" -> scene.add(Node.End)
                            "@give" -> scene.add(Node.Give(id(arg, n, "предмета")))
                            "@take" -> scene.add(Node.Take(id(arg, n, "предмета")))
                            "@param" -> {
                                val m = PARAM.matchEntire(line)
                                    ?: throw StoryParseException("ожидается «@param имя +N», «@param имя -N» или «@param имя =N»", n)
                                val (name, sign, magnitude, absolute) = m.destructured
                                val node = if (absolute.isNotEmpty()) {
                                    Node.ChangeParam(name, ParamMode.SET, absolute.toIntOrNull() ?: throw StoryParseException("слишком большое число", n))
                                } else {
                                    val value = magnitude.toIntOrNull() ?: throw StoryParseException("слишком большое число", n)
                                    Node.ChangeParam(name, ParamMode.ADD, if (sign == "-") -value else value)
                                }
                                scene.add(node)
                            }
                            "@location" -> {
                                val args = arg.split(Regex("\\s+"), limit = 2)
                                val locId = id(args[0], n, "локации")
                                scene.add(Node.SetLocation(locId))
                                usedLocations.putIfAbsent(locId, n)
                                if (args.size == 2) {
                                    if (locationTitles.containsKey(locId)) throw StoryParseException("название локации «$locId» уже задано", n)
                                    locationTitles[locId] = Named(args[1].unescape())
                                    attach = { lang, tr, ln ->
                                        val cur = locationTitles.getValue(locId)
                                        if (lang in cur.translations) duplicate(lang, ln)
                                        locationTitles[locId] = cur.copy(translations = cur.translations + (lang to tr))
                                    }
                                }
                            }
                            "@if" -> {
                                val m = IF.matchEntire(line)
                                    ?: throw StoryParseException("ожидается «@if условие -> сцена»", n)
                                val cond = condition(m.groupValues[1], n)
                                val target = m.groupValues[2]
                                scene.add(if (cond is Condition.Flag) Node.IfGoto(cond.name, cond.negate, target) else Node.IfWhen(cond, target))
                            }
                            else -> throw StoryParseException("неизвестная команда $cmd", n)
                        }
                    }
                }
                return@forEachIndexed
            }

            val scene = current ?: throw StoryParseException("текст вне сцены, сначала нужна «@scene имя»", n)
            if (line.startsWith(">")) {
                addSay(scene, null, Side.NONE, line.substring(1).trim().unescape()).also { idx ->
                    attach = sayAttach(scene, idx) { lang, ln -> duplicate(lang, ln) }
                }
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
            addSay(scene, speaker, resolved, text.unescape()).also { idx ->
                attach = sayAttach(scene, idx) { lang, ln -> duplicate(lang, ln) }
            }
        }
        closeChoice()
        validate(scenes, usedLocations, locationTitles)
        return Story(scenes, scenarioId, scenarioVersion, characters, locationTitles)
    }

    private fun addSay(scene: MutableList<Node>, speaker: String?, side: Side, text: String): Int {
        scene.add(Node.Say(speaker, side, text))
        return scene.lastIndex
    }

    private fun sayAttach(
        scene: MutableList<Node>,
        index: Int,
        onDuplicate: (Language, Int) -> Nothing,
    ): (Language, String, Int) -> Unit = { lang, tr, ln ->
        val cur = scene[index] as Node.Say
        if (lang in cur.translations) onDuplicate(lang, ln)
        scene[index] = cur.copy(translations = cur.translations + (lang to tr))
    }

    private fun String.unescape() = replace("\\n", "\n")

    private fun validate(
        scenes: Map<String, List<Node>>,
        usedLocations: Map<String, Int>,
        locationTitles: Map<String, Named>,
    ) {
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
                    is Node.IfWhen -> listOf(node.target)
                    is Node.Choice -> node.options.map { it.target }
                    else -> emptyList()
                }
                for (t in targets) {
                    if (!scenes.containsKey(t)) throw StoryParseException("сцена «$name» ссылается на несуществующую сцену «$t»")
                }
            }
        }
        for ((locId, line) in usedLocations) {
            if (!locationTitles.containsKey(locId)) {
                throw StoryParseException("у локации «$locId» нет названия, задайте его при первом использовании: «@location $locId Название»", line)
            }
        }
    }
}
