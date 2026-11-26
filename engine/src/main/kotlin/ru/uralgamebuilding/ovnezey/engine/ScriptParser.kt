package ru.uralgamebuilding.ovnezey.engine

class StoryParseException(message: String, val line: Int? = null) :
    Exception(if (line != null) "строка $line: $message" else message)

/**
 * Разбор текстового формата сценария (.ovn). Справочник формата: README.md.
 * Парсер ничего не знает об Android и проверяет сценарий целиком: ссылки на сцены, области и предметы,
 * наличие сцены start, корректное завершение каждой сцены, названия локаций.
 */
object ScriptParser {
    private const val ID = "[A-Za-z0-9_]+"
    private const val NUM = """(\d+(?:\.\d+)?)"""
    private val OPTION = Regex("""^\*\s+(.+?)\s+->\s+($ID)(?:\s+\?\s*(.+))?$""")
    private val HOTSPOT = Regex("""^\*\s+($ID)\s+$NUM\s+$NUM\s+$NUM\s+$NUM\s+(.+?)\s+->\s+($ID)(?:\s+\?\s*(.+))?$""")
    private val USE = Regex("""^@use\s+($ID)\s+($ID)\s*->\s*($ID)(?:\s+\?\s*(.+))?$""")
    private val FAIL = Regex("""^@fail\s+($ID|\*)\s*->\s*($ID)$""")
    private val IF = Regex("""^@if\s+(.+?)\s*->\s*($ID)$""")
    private val PARAM = Regex("""^@param\s+($ID)\s+(?:([+-])\s*(\d+)|=\s*(-?\d+))$""")
    private val MINIGAME = Regex("""^@minigame\s+([a-z]+)\s+(\d+)\s+(\d+)\s+(.+?)(?:\s+->\s+($ID)(?:\s+else\s+($ID))?)?$""")
    private val SHOW = Regex("""^@show\s+([a-z]+)\s+(.+)$""")
    private val SAY = Regex("""^([^:@>*#|]+?)(?:@(left|right))?:\s*(.+)$""")
    private val TRANSLATION = Regex("""^\|([a-z]{2})\s+(.+)$""")
    private val ITEM_FIELD = Regex("""^(name|desc|use):\s*(.*)$""")
    private val ID_ONLY = Regex("^$ID$")

    private class ItemBuilder(val id: String, val line: Int) {
        var name: Named? = null
        var description: Named = Named("")
        var consume = false
    }

    private class ExploreBuilder(val line: Int) {
        val hotspots = mutableListOf<Hotspot>()
        val uses = mutableListOf<UseRule>()
        val fails = mutableListOf<FailRule>()
        val useLines = mutableListOf<Int>()
        val failLines = mutableListOf<Int>()
    }

    fun parse(source: String): Story {
        val scenes = LinkedHashMap<String, MutableList<Node>>()
        val sceneLines = HashMap<String, Int>()
        val lastSide = HashMap<String, Side>()
        val characters = LinkedHashMap<String, Character>()
        val locationTitles = LinkedHashMap<String, Named>()
        val usedLocations = LinkedHashMap<String, Int>()
        val items = LinkedHashMap<String, ItemDef>()
        var scenarioId = Story.DEFAULT_SCENARIO_ID
        var scenarioVersion = 1
        var scenarioDeclared = false
        var current: MutableList<Node>? = null
        var options: MutableList<Option>? = null
        var choiceLine = 0
        var itemBuilder: ItemBuilder? = null
        var explore: ExploreBuilder? = null
        // Принимает строку перевода «|en ...» для последней текстовой строки; null, если переводить нечего.
        var attach: ((Language, String, Int) -> Unit)? = null

        fun closeChoice() {
            val opts = options ?: return
            if (opts.isEmpty()) throw StoryParseException("пустой @choice, нужен хотя бы один вариант «* текст -> сцена»", choiceLine)
            current!!.add(Node.Choice(opts.toList()))
            options = null
        }

        fun closeExplore() {
            val b = explore ?: return
            if (b.hotspots.isEmpty()) throw StoryParseException("пустой @explore, нужна хотя бы одна область «* id x y w h Название -> сцена»", b.line)
            val ids = b.hotspots.map { it.id }.toSet()
            b.uses.forEachIndexed { i, u ->
                if (u.hotspot !in ids) throw StoryParseException("@use ссылается на несуществующую область «${u.hotspot}»", b.useLines[i])
            }
            b.fails.forEachIndexed { i, f ->
                if (f.hotspot != FailRule.ANY && f.hotspot !in ids) {
                    throw StoryParseException("@fail ссылается на несуществующую область «${f.hotspot}»", b.failLines[i])
                }
            }
            current!!.add(Node.Explore(b.hotspots.toList(), b.uses.toList(), b.fails.toList()))
            explore = null
        }

        fun closeItem() {
            val b = itemBuilder ?: return
            val name = b.name ?: throw StoryParseException("у предмета «${b.id}» нет строки «name: ...»", b.line)
            items[b.id] = ItemDef(b.id, name, b.description, b.consume)
            itemBuilder = null
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

            // Блок @item: поля name:, desc:, use:. Заканчивается любой командой на «@».
            itemBuilder?.let { b ->
                if (!line.startsWith("@")) {
                    val m = ITEM_FIELD.matchEntire(line)
                        ?: throw StoryParseException("в блоке @item допустимы строки «name: ...», «desc: ...», «use: keep|consume»", n)
                    val value = m.groupValues[2].trim()
                    when (m.groupValues[1]) {
                        "name" -> {
                            if (value.isEmpty()) throw StoryParseException("пустое название предмета", n)
                            if (b.name != null) throw StoryParseException("name: для предмета «${b.id}» уже задано", n)
                            b.name = Named(value.unescape())
                            attach = { lang, tr, ln ->
                                val cur = b.name!!
                                if (lang in cur.translations) duplicate(lang, ln)
                                b.name = cur.copy(translations = cur.translations + (lang to tr))
                            }
                        }
                        "desc" -> {
                            b.description = Named(value.unescape())
                            attach = { lang, tr, ln ->
                                val cur = b.description
                                if (lang in cur.translations) duplicate(lang, ln)
                                b.description = cur.copy(translations = cur.translations + (lang to tr))
                            }
                        }
                        else -> b.consume = when (value) {
                            "keep" -> false
                            "consume" -> true
                            else -> throw StoryParseException("use: принимает keep или consume, получено «$value»", n)
                        }
                    }
                    return@forEachIndexed
                }
                closeItem()
            }

            // Блок @explore: области «* id x y w h Название -> сцена», правила @use и @fail.
            explore?.let { b ->
                when {
                    line.startsWith("*") -> {
                        val m = HOTSPOT.matchEntire(line) ?: throw StoryParseException(
                            "неверная область, ожидается «* id x y w h Название -> сцена» или «... -> сцена ? условие»", n,
                        )
                        val g = m.groupValues
                        val hotspotId = g[1]
                        if (b.hotspots.any { it.id == hotspotId }) throw StoryParseException("область «$hotspotId» уже объявлена", n)
                        val x = g[2].toFloat(); val y = g[3].toFloat(); val w = g[4].toFloat(); val h = g[5].toFloat()
                        if (w <= 0f || h <= 0f || x + w > 1.0001f || y + h > 1.0001f) {
                            throw StoryParseException("область «$hotspotId» выходит за картинку: x+w и y+h не больше 1, w и h больше 0", n)
                        }
                        val cond = g[8].takeIf { it.isNotEmpty() }?.let { condition(it, n) }
                        b.hotspots.add(Hotspot(hotspotId, x, y, w, h, g[6].unescape(), g[7], condition = cond))
                        val idx = b.hotspots.lastIndex
                        attach = { lang, tr, ln ->
                            val cur = b.hotspots[idx]
                            if (lang in cur.translations) duplicate(lang, ln)
                            b.hotspots[idx] = cur.copy(translations = cur.translations + (lang to tr))
                        }
                        return@forEachIndexed
                    }
                    line.startsWith("@use") -> {
                        val m = USE.matchEntire(line) ?: throw StoryParseException("ожидается «@use предмет область -> сцена»", n)
                        val cond = m.groupValues[4].takeIf { it.isNotEmpty() }?.let { condition(it, n) }
                        b.uses.add(UseRule(m.groupValues[1], m.groupValues[2], m.groupValues[3], cond))
                        b.useLines.add(n)
                        return@forEachIndexed
                    }
                    line.startsWith("@fail") -> {
                        val m = FAIL.matchEntire(line) ?: throw StoryParseException("ожидается «@fail область -> сцена» или «@fail * -> сцена»", n)
                        b.fails.add(FailRule(m.groupValues[1], m.groupValues[2]))
                        b.failLines.add(n)
                        return@forEachIndexed
                    }
                    else -> closeExplore()
                }
            }

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
                    "@item" -> {
                        val itemId = id(arg, n, "предмета")
                        if (items.containsKey(itemId)) throw StoryParseException("предмет «$itemId» уже объявлен", n)
                        itemBuilder = ItemBuilder(itemId, n)
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
                            "@explore" -> explore = ExploreBuilder(n)
                            "@use", "@fail" -> throw StoryParseException("$cmd допустим только внутри блока @explore", n)
                            "@goto" -> scene.add(Node.Goto(id(arg, n, "сцены")))
                            "@set" -> scene.add(Node.SetFlag(id(arg, n, "флага")))
                            "@end" -> scene.add(Node.End)
                            "@reset" -> scene.add(
                                Node.Reset(arg.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { id(it, n, "флага") }.toSet()),
                            )
                            "@minigame" -> {
                                val m = MINIGAME.matchEntire(line) ?: throw StoryParseException(
                                    "ожидается «@minigame вид число секунды Подпись» с необязательным «-> успех» и «else провал»", n,
                                )
                                val g = m.groupValues
                                val kind = MinigameKind.fromCode(g[1]) ?: throw StoryParseException(
                                    "неизвестная мини-игра «${g[1]}», допустимо: ${MinigameKind.entries.joinToString { it.code }}", n,
                                )
                                val count = g[2].toIntOrNull() ?: throw StoryParseException("слишком большое число", n)
                                val seconds = g[3].toIntOrNull() ?: throw StoryParseException("слишком большое число секунд", n)
                                val range = when (kind) {
                                    MinigameKind.WAKE -> 3..40
                                    MinigameKind.BADGE -> 1..1
                                    MinigameKind.CRATE -> 2..40
                                    MinigameKind.FIGHT -> 3..60
                                    MinigameKind.WIRES -> 2..6
                                }
                                if (count !in range) throw StoryParseException("для «${kind.code}» число должно быть от ${range.first} до ${range.last}", n)
                                if (kind == MinigameKind.FIGHT && seconds < 1) throw StoryParseException("для «fight» нужно ограничение по времени: секунд не меньше 1", n)
                                val ok = g[5].ifEmpty { null }
                                val fail = g[6].ifEmpty { null }
                                scene.add(Node.Minigame(kind, count, seconds, g[4].unescape(), ok, fail))
                                val idx = scene.lastIndex
                                attach = { lang, tr, ln ->
                                    val cur = scene[idx] as Node.Minigame
                                    if (lang in cur.translations) duplicate(lang, ln)
                                    scene[idx] = cur.copy(translations = cur.translations + (lang to tr))
                                }
                            }
                            "@show" -> {
                                val m = SHOW.matchEntire(line) ?: throw StoryParseException("ожидается «@show вид Текст»", n)
                                val kind = ShowKind.fromCode(m.groupValues[1]) ?: throw StoryParseException(
                                    "неизвестная вставка «${m.groupValues[1]}», допустимо: ${ShowKind.entries.joinToString { it.code }}", n,
                                )
                                scene.add(Node.Show(kind, m.groupValues[2].unescape()))
                                val idx = scene.lastIndex
                                attach = { lang, tr, ln ->
                                    val cur = scene[idx] as Node.Show
                                    if (lang in cur.translations) duplicate(lang, ln)
                                    scene[idx] = cur.copy(translations = cur.translations + (lang to tr))
                                }
                            }
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
                val idx = addSay(scene, null, Side.NONE, line.substring(1).trim().unescape())
                attach = sayAttach(scene, idx) { lang, ln -> duplicate(lang, ln) }
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
            val idx = addSay(scene, speaker, resolved, text.unescape())
            attach = sayAttach(scene, idx) { lang, ln -> duplicate(lang, ln) }
        }
        closeItem()
        closeChoice()
        closeExplore()
        validate(scenes, usedLocations, locationTitles, items)
        return Story(scenes, scenarioId, scenarioVersion, characters, locationTitles, items)
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
        items: Map<String, ItemDef>,
    ) {
        if (!scenes.containsKey(Story.START)) throw StoryParseException("нет сцены «${Story.START}»")
        for ((name, nodes) in scenes) {
            if (nodes.isEmpty()) throw StoryParseException("сцена «$name» пуста")
            val last = nodes.last()
            val endsWithMinigameJump = last is Node.Minigame && last.okTarget != null
            if (last !is Node.End && last !is Node.Goto && last !is Node.Choice && last !is Node.Explore && !endsWithMinigameJump) {
                throw StoryParseException("сцена «$name» не завершена: последней командой должна быть @end, @goto, @choice, @explore или @minigame с «-> сцена»")
            }
            for (node in nodes) {
                val targets = when (node) {
                    is Node.Goto -> listOf(node.target)
                    is Node.IfGoto -> listOf(node.target)
                    is Node.IfWhen -> listOf(node.target)
                    is Node.Choice -> node.options.map { it.target }
                    is Node.Explore -> node.hotspots.map { it.target } + node.uses.map { it.target } + node.fails.map { it.target }
                    is Node.Minigame -> listOfNotNull(node.okTarget, node.failTarget)
                    else -> emptyList()
                }
                for (t in targets) {
                    if (!scenes.containsKey(t)) throw StoryParseException("сцена «$name» ссылается на несуществующую сцену «$t»")
                }
                if (node is Node.Explore) {
                    for (use in node.uses) {
                        if (!items.containsKey(use.item)) {
                            throw StoryParseException("в сцене «$name» @use использует необъявленный предмет «${use.item}», объявите его через @item")
                        }
                    }
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
