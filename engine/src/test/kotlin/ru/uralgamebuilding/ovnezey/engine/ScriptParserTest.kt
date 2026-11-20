package ru.uralgamebuilding.ovnezey.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScriptParserTest {
    private fun parseError(source: String): StoryParseException =
        assertFailsWith { ScriptParser.parse(source) }

    @Test
    fun parsesNarrationAndDialogueWithSides() {
        val story = ScriptParser.parse(
            """
            # комментарий
            @scene start
            > Рассказчик
            Чарли@left: Привет
            Сэм@right: Здорово
            Чарли: Ещё раз
            @end
            """.trimIndent()
        )
        val nodes = story.scenes.getValue("start")
        assertEquals(Node.Say(null, Side.NONE, "Рассказчик"), nodes[0])
        assertEquals(Node.Say("Чарли", Side.LEFT, "Привет"), nodes[1])
        assertEquals(Node.Say("Сэм", Side.RIGHT, "Здорово"), nodes[2])
        // сторона запоминается за персонажем
        assertEquals(Node.Say("Чарли", Side.LEFT, "Ещё раз"), nodes[3])
        assertEquals(Node.End, nodes[4])
    }

    @Test
    fun speakerNameMayContainSpacesAndTextMayContainColon() {
        val story = ScriptParser.parse("@scene start\nПравящий ИИ@right: Время: 12:00\n@end")
        assertEquals(Node.Say("Правящий ИИ", Side.RIGHT, "Время: 12:00"), story.scenes.getValue("start")[0])
    }

    @Test
    fun parsesChoiceWithConditionsAndEndsBlockOnNextCommand() {
        val story = ScriptParser.parse(
            """
            @scene start
            @choice
            * Взять -> take
            * Идти дальше -> skip ? !took
            * Показать письмо -> skip ? took
            @scene take
            @end
            @scene skip
            @end
            """.trimIndent()
        )
        val choice = story.scenes.getValue("start").single() as Node.Choice
        assertEquals(
            listOf(
                Option("Взять", "take"),
                Option("Идти дальше", "skip", "took", true),
                Option("Показать письмо", "skip", "took", false),
            ),
            choice.options,
        )
    }

    @Test
    fun handlesBomAndWindowsLineEndings() {
        val story = ScriptParser.parse("\uFEFF@scene start\r\n> Текст\r\n@end\r\n")
        assertEquals(2, story.scenes.getValue("start").size)
    }

    @Test
    fun convertsEscapedNewline() {
        val story = ScriptParser.parse("@scene start\n> Первая\\nВторая\n@end")
        assertEquals("Первая\nВторая", (story.scenes.getValue("start")[0] as Node.Say).text)
    }

    @Test
    fun reportsLineNumberForBadLines() {
        val e = parseError("@scene start\n> ok\nэто не реплика\n@end")
        assertEquals(3, e.line)
    }

    @Test
    fun rejectsTextOutsideScene() {
        assertEquals(1, parseError("> Текст до сцены").line)
    }

    @Test
    fun rejectsDuplicateScene() {
        assertTrue(parseError("@scene start\n@end\n@scene start\n@end").message!!.contains("уже объявлена"))
    }

    @Test
    fun rejectsEmptyChoice() {
        assertTrue(parseError("@scene start\n@choice\n@end").message!!.contains("пустой @choice"))
    }

    @Test
    fun rejectsMissingStartScene() {
        assertTrue(parseError("@scene other\n@end").message!!.contains("start"))
    }

    @Test
    fun rejectsUnknownTarget() {
        assertTrue(parseError("@scene start\n@goto nowhere").message!!.contains("nowhere"))
    }

    @Test
    fun rejectsSceneWithoutTerminator() {
        assertTrue(parseError("@scene start\n> Текст").message!!.contains("не завершена"))
    }

    @Test
    fun rejectsInvalidIdentifiers() {
        assertTrue(parseError("@scene сцена\n@end").message!!.contains("недопустимое имя"))
    }

    @Test
    fun rejectsUnknownCommand() {
        assertTrue(parseError("@scene start\n@jump x").message!!.contains("неизвестная команда"))
    }

    @Test
    fun emptySourceIsRejected() {
        assertTrue(parseError("").message!!.contains("start"))
    }
}
