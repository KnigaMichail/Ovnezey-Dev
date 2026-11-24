package ru.uralgamebuilding.ovnezey.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExploreParserTest {
    private fun error(source: String): StoryParseException = assertFailsWith { ScriptParser.parse(source) }

    private val items = """
        @item key
        name: Ключ
        |en Key
        desc: Старый ключ.
        |en An old key.
        use: consume
        @item lamp
        name: Лампа
    """.trimIndent()

    private val room = """
        @scene start
        @explore
        * door 0.60 0.30 0.20 0.40 Дверь -> door_scene
        |en Door
        * box 0.10 0.50 0.25 0.25 Ящик -> box_scene ? !opened
        @use key door -> door_open
        @fail door -> door_fail
        @fail * -> anything_fail
        @scene door_scene
        @goto start
        @scene box_scene
        @goto start
        @scene door_open
        @end
        @scene door_fail
        @goto start
        @scene anything_fail
        @goto start
    """.trimIndent()

    @Test
    fun parsesItemsWithTranslationsAndRules() {
        val story = ScriptParser.parse(items + "\n" + room)
        val key = story.items.getValue("key")
        assertEquals(Named("Ключ", mapOf(Language.EN to "Key")), key.name)
        assertEquals(Named("Старый ключ.", mapOf(Language.EN to "An old key.")), key.description)
        assertTrue(key.consume)
        val lamp = story.items.getValue("lamp")
        assertEquals(false, lamp.consume)
        assertEquals("", lamp.description.name)
        assertEquals(listOf("key", "lamp"), story.items.keys.toList())
    }

    @Test
    fun parsesExploreBlock() {
        val story = ScriptParser.parse(items + "\n" + room)
        val explore = story.scenes.getValue("start").single() as Node.Explore
        assertEquals(2, explore.hotspots.size)
        val door = explore.hotspots[0]
        assertEquals("door", door.id)
        assertEquals(0.60f, door.x)
        assertEquals(0.40f, door.h)
        assertEquals("Дверь", door.label)
        assertEquals(mapOf(Language.EN to "Door"), door.translations)
        assertEquals("door_scene", door.target)
        assertEquals(Condition.Flag("opened", negate = true), explore.hotspots[1].condition)
        assertEquals(listOf(UseRule("key", "door", "door_open")), explore.uses)
        assertEquals(listOf(FailRule("door", "door_fail"), FailRule("*", "anything_fail")), explore.fails)
    }

    @Test
    fun exploreCountsAsSceneTerminator() {
        ScriptParser.parse("@scene start\n@explore\n* a 0 0 1 1 Вся картинка -> start")
    }

    @Test
    fun hotspotLabelMayContainSpacesAndConditionsMayBeCombined() {
        val story = ScriptParser.parse("@scene start\n@explore\n* a 0.1 0.1 0.2 0.2 Старый сейф у стены -> start ? has:key & trust>=1")
        val h = (story.scenes.getValue("start").single() as Node.Explore).hotspots.single()
        assertEquals("Старый сейф у стены", h.label)
        assertTrue(h.condition is Condition.All)
    }

    @Test
    fun rejectsBadHotspots() {
        assertTrue(error("@scene start\n@explore\n* a 0.1 0.1 Название -> start").message!!.contains("неверная область"))
        assertTrue(error("@scene start\n@explore\n* a 0.9 0.1 0.2 0.2 X -> start").message!!.contains("выходит за картинку"))
        assertTrue(error("@scene start\n@explore\n* a 0.1 0.9 0.2 0.2 X -> start").message!!.contains("выходит за картинку"))
        assertTrue(error("@scene start\n@explore\n* a 0.1 0.1 0 0.2 X -> start").message!!.contains("выходит за картинку"))
        assertTrue(error("@scene start\n@explore\n* a 0 0 0.1 0.1 X -> start\n* a 0.5 0.5 0.1 0.1 Y -> start").message!!.contains("уже объявлена"))
    }

    @Test
    fun rejectsEmptyExplore() {
        assertTrue(error("@scene start\n@explore\n@scene b\n@end").message!!.contains("пустой @explore"))
    }

    @Test
    fun rejectsUseAndFailOutsideExplore() {
        assertTrue(error("@scene start\n@use key door -> start").message!!.contains("только внутри блока @explore"))
        assertTrue(error("@scene start\n@fail door -> start").message!!.contains("только внутри блока @explore"))
    }

    @Test
    fun rejectsRulesForUnknownHotspots() {
        val head = "$items\n@scene start\n@explore\n* a 0 0 0.1 0.1 A -> start\n"
        assertTrue(error(head + "@use key ghost -> start").message!!.contains("несуществующую область"))
        assertTrue(error(head + "@fail ghost -> start").message!!.contains("несуществующую область"))
    }

    @Test
    fun rejectsUseOfUndeclaredItemAndMissingTargets() {
        val head = "@scene start\n@explore\n* a 0 0 0.1 0.1 A -> start\n"
        assertTrue(error(head + "@use ghost a -> start").message!!.contains("необъявленный предмет"))
        assertTrue(error("$items\n$head@use key a -> nowhere").message!!.contains("nowhere"))
        assertTrue(error("$items\n$head@fail * -> nowhere").message!!.contains("nowhere"))
        assertTrue(error("@scene start\n@explore\n* a 0 0 0.1 0.1 A -> nowhere").message!!.contains("nowhere"))
    }

    @Test
    fun rejectsBadUseAndFailSyntax() {
        val head = "$items\n@scene start\n@explore\n* a 0 0 0.1 0.1 A -> start\n"
        assertTrue(error(head + "@use key a").message!!.contains("@use"))
        assertTrue(error(head + "@fail").message!!.contains("@fail"))
    }

    @Test
    fun rejectsBadItemBlocks() {
        assertTrue(error("@item key\n@scene start\n@end").message!!.contains("нет строки «name"))
        assertTrue(error("@item key\nname: Ключ\nuse: maybe\n@scene start\n@end").message!!.contains("keep или consume"))
        assertTrue(error("@item key\nname: A\n@item key\nname: B\n@scene start\n@end").message!!.contains("уже объявлен"))
        assertTrue(error("@item key\nname: A\nname: B\n@scene start\n@end").message!!.contains("уже задано"))
        assertTrue(error("@item key\nпросто текст\n@scene start\n@end").message!!.contains("name:"))
        assertTrue(error("@item key\nname:\n@scene start\n@end").message!!.contains("пустое название"))
    }

    @Test
    fun itemBlockEndsWithAnyCommand() {
        val story = ScriptParser.parse("@item key\nname: Ключ\n@scene start\n> Привет: мир\n@end")
        assertEquals(1, story.items.size)
        assertEquals(Node.Say(null, Side.NONE, "Привет: мир"), story.scenes.getValue("start")[0])
    }

    @Test
    fun itemTranslationsCannotBeDuplicated() {
        assertTrue(error("@item key\nname: A\n|en One\n|en Two\n@scene start\n@end").message!!.contains("уже задан"))
    }

    @Test
    fun exploreBlockEndsAtNextScene() {
        val story = ScriptParser.parse("@scene start\n@explore\n* a 0 0 0.5 0.5 A -> b\n@scene b\n@end")
        assertEquals(1, story.scenes.getValue("start").size)
        assertEquals(1, story.scenes.getValue("b").size)
    }
}
