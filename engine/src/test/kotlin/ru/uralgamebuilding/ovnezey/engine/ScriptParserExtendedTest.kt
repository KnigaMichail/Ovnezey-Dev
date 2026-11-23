package ru.uralgamebuilding.ovnezey.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScriptParserExtendedTest {
    private fun error(source: String): StoryParseException = assertFailsWith { ScriptParser.parse(source) }

    @Test
    fun attachesTranslationsToSayOptionsCharactersAndLocations() {
        val story = ScriptParser.parse(
            """
            @character charlie Чарли
            |en Charlie
            @scene start
            @location street Улица
            |en Street
            charlie@left: Привет
            |en Hello
            > Рассказчик
            |en Narrator
            @choice
            * Идти -> start2
            |en Go
            @scene start2
            @end
            """.trimIndent()
        )
        assertEquals(Named("Чарли", mapOf(Language.EN to "Charlie")), story.characters.getValue("charlie").name)
        assertEquals(Named("Улица", mapOf(Language.EN to "Street")), story.locations.getValue("street"))
        val nodes = story.scenes.getValue("start")
        assertEquals(Node.Say("charlie", Side.LEFT, "Привет", mapOf(Language.EN to "Hello")), nodes[1])
        assertEquals(Node.Say(null, Side.NONE, "Рассказчик", mapOf(Language.EN to "Narrator")), nodes[2])
        val choice = nodes[3] as Node.Choice
        assertEquals(mapOf(Language.EN to "Go"), choice.options[0].translations)
    }

    @Test
    fun translationMayBeSeparatedByBlankLineOrComment() {
        val story = ScriptParser.parse("@scene start\nПривет: Текст\n\n# комментарий\n|en Text\n@end")
        assertEquals(mapOf(Language.EN to "Text"), (story.scenes.getValue("start")[0] as Node.Say).translations)
    }

    @Test
    fun rejectsBadTranslations() {
        assertTrue(error("@scene start\n|en Text\n@end").message!!.contains("сразу под строкой"))
        assertTrue(error("@scene start\n> A\n|de Text\n@end").message!!.contains("неподдерживаемый язык"))
        assertTrue(error("@scene start\n> A\n|ru Текст\n@end").message!!.contains("основной строке"))
        assertTrue(error("@scene start\n> A\n|en One\n|en Two\n@end").message!!.contains("уже задан"))
        assertEquals(3, error("@scene start\n> A\n|en\n@end").line)
    }

    @Test
    fun translationAfterNonTextCommandIsRejected() {
        assertTrue(error("@scene start\n> A\n@set x\n|en Late\n@end").message!!.contains("сразу под строкой"))
    }

    @Test
    fun parsesScenarioHeader() {
        val story = ScriptParser.parse("@scenario ovnezey 3\n@scene start\n@end")
        assertEquals("ovnezey", story.scenarioId)
        assertEquals(3, story.scenarioVersion)
        val plain = ScriptParser.parse("@scene start\n@end")
        assertEquals(Story.DEFAULT_SCENARIO_ID, plain.scenarioId)
        assertEquals(1, plain.scenarioVersion)
    }

    @Test
    fun rejectsBadScenarioHeader() {
        assertTrue(error("@scenario x 0\n@scene start\n@end").message!!.contains("@scenario"))
        assertTrue(error("@scenario x\n@scene start\n@end").message!!.contains("@scenario"))
        assertTrue(error("@scenario x 1\n@scenario y 2\n@scene start\n@end").message!!.contains("уже объявлен"))
    }

    @Test
    fun parsesParamsItemsLocationsAndConditions() {
        val story = ScriptParser.parse(
            """
            @scene start
            @location hall Холл
            @param trust +2
            @param trust -1
            @param money = -5
            @give key
            @take key
            @if trust>=1 & has:key -> done
            @if !met -> done
            @choice
            * Один -> done ? trust>=1
            * Два -> done ? has:key
            * Три -> done ? !met
            @scene done
            @end
            """.trimIndent()
        )
        val n = story.scenes.getValue("start")
        assertEquals(Node.SetLocation("hall"), n[0])
        assertEquals(Node.ChangeParam("trust", ParamMode.ADD, 2), n[1])
        assertEquals(Node.ChangeParam("trust", ParamMode.ADD, -1), n[2])
        assertEquals(Node.ChangeParam("money", ParamMode.SET, -5), n[3])
        assertEquals(Node.Give("key"), n[4])
        assertEquals(Node.Take("key"), n[5])
        assertTrue(n[6] is Node.IfWhen)
        assertEquals(Node.IfGoto("met", true, "done"), n[7])
        val options = (n[8] as Node.Choice).options
        assertEquals(Condition.Param("trust", CompareOp.GE, 1), options[0].condition)
        assertEquals(Condition.HasItem("key"), options[1].condition)
        assertEquals("met", options[2].requiredFlag)
        assertTrue(options[2].requireAbsent)
    }

    @Test
    fun rejectsBadNewCommands() {
        assertTrue(error("@scene start\n@param x\n@end").message!!.contains("@param"))
        assertTrue(error("@scene start\n@param x +99999999999\n@end").message!!.contains("слишком большое"))
        assertTrue(error("@scene start\n@give\n@end").message!!.contains("недопустимое имя"))
        assertTrue(error("@scene start\n@if trust>= -> start\n@end").message!!.contains("неверное условие"))
        assertTrue(error("@scene start\n@choice\n* A -> start ? ???").message!!.contains("неверное условие"))
        assertTrue(error("@character x").message!!.contains("@character"))
    }

    @Test
    fun locationNeedsTitleOnFirstUse() {
        assertTrue(error("@scene start\n@location hall\n@end").message!!.contains("нет названия"))
        val ok = ScriptParser.parse("@scene start\n@location hall Холл\n@goto two\n@scene two\n@location hall\n@end")
        assertEquals(setOf("hall"), ok.locations.keys)
        assertTrue(error("@scene start\n@location hall Холл\n@location hall Холл2\n@end").message!!.contains("уже задано"))
    }

    @Test
    fun rejectsDuplicateCharacter() {
        assertTrue(error("@character a Имя\n@character a Имя2\n@scene start\n@end").message!!.contains("уже объявлен"))
    }

    @Test
    fun characterCanBeDeclaredBetweenScenes() {
        val story = ScriptParser.parse("@scene start\n@goto b\n@character x Икс\n@scene b\n@end")
        assertEquals(setOf("x"), story.characters.keys)
    }
}
