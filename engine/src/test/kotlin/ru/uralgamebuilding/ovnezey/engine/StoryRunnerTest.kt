package ru.uralgamebuilding.ovnezey.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoryRunnerTest {
    private val source = """
        @scene start
        > Начало
        Чарли@left: Реплика
        @choice
        * Взять письмо -> take
        * Пройти мимо -> skip
        @scene take
        @set has_letter
        @goto finish
        @scene skip
        > Мимо
        @goto finish
        @scene finish
        @if has_letter -> with_letter
        > Без письма
        @end
        @scene with_letter
        > С письмом
        @end
    """.trimIndent()

    private val story = ScriptParser.parse(source)

    private fun Step.text() = (this as Step.Say).text

    @Test
    fun walksThroughLinearTextAndChoice() {
        val r = StoryRunner(story)
        assertEquals("Начало", r.step.text())
        r.advance()
        assertEquals("Реплика", r.step.text())
        r.advance()
        val choose = r.step as Step.Choose
        assertEquals(listOf("Взять письмо", "Пройти мимо"), choose.options.map { it.text })
    }

    @Test
    fun flagBranchTakesLetterPath() {
        val r = StoryRunner(story)
        r.advance(); r.advance()
        r.choose(0)
        assertEquals("С письмом", r.step.text())
        assertTrue("has_letter" in r.state.flags)
        r.advance()
        assertEquals(Step.End, r.step)
    }

    @Test
    fun flagBranchFallsThroughWithoutLetter() {
        val r = StoryRunner(story)
        r.advance(); r.advance()
        r.choose(1)
        assertEquals("Мимо", r.step.text())
        r.advance()
        assertEquals("Без письма", r.step.text())
    }

    @Test
    fun conditionalOptionsAreFilteredAndIndexedByVisibleList() {
        val s = ScriptParser.parse(
            """
            @scene start
            @set a
            @choice
            * Скрытый -> x ? !a
            * Видимый -> y ? a
            * Всегда -> z
            @scene x
            > X
            @end
            @scene y
            > Y
            @end
            @scene z
            > Z
            @end
            """.trimIndent()
        )
        val r = StoryRunner(s)
        assertEquals(listOf("Видимый", "Всегда"), (r.step as Step.Choose).options.map { it.text })
        r.choose(1)
        assertEquals("Z", r.step.text())
    }

    @Test
    fun invalidCallsAreRejected() {
        val r = StoryRunner(story)
        assertFailsWith<IllegalStateException> { r.choose(0) }
        r.advance(); r.advance()
        assertFailsWith<IllegalStateException> { r.advance() }
        assertFailsWith<IllegalArgumentException> { r.choose(5) }
    }

    @Test
    fun detectsInfiniteAutomaticLoop() {
        val s = ScriptParser.parse("@scene start\n@goto start")
        assertFailsWith<IllegalStateException> { StoryRunner(s) }
    }

    @Test
    fun failsWhenAllOptionsAreHidden() {
        val s = ScriptParser.parse("@scene start\n@choice\n* Нет -> start ? never")
        assertFailsWith<IllegalStateException> { StoryRunner(s) }
    }

    @Test
    fun saveRoundTripRestoresPositionAndFlags() {
        val r = StoryRunner(story)
        r.advance(); r.advance()
        r.choose(0)
        val restored = StoryRunner.fromSave(story, r.state.encode())
        assertEquals(r.state, restored.state)
        assertEquals("С письмом", restored.step.text())
    }

    @Test
    fun savedStateIsEncodedDeterministically() {
        assertEquals("v1|start|2|a,b", GameState("start", 2, setOf("b", "a")).encode())
        assertEquals(GameState("start", 2, setOf("a", "b")), GameState.decode("v1|start|2|a,b"))
        assertEquals(GameState("start", 0), GameState.decode("v1|start|0|"))
    }

    @Test
    fun corruptedSavesAreRejectedByDecode() {
        listOf(null, "", "garbage", "v2|start|0|", "v1|start|-1|", "v1|start|x|", "v1|sta rt|0|", "v1|start|0|bad flag", "v1|start|0")
            .forEach { assertNull(GameState.decode(it), "должно быть отвергнуто: $it") }
    }

    @Test
    fun savesFromAnotherStoryVersionStartOver() {
        val fresh = StoryRunner(story)
        assertEquals(fresh.state, StoryRunner.fromSave(story, "v1|removed_scene|0|").state)
        assertEquals(fresh.state, StoryRunner.fromSave(story, "v1|start|99|").state)
        assertEquals(fresh.state, StoryRunner.fromSave(story, "garbage").state)
        assertEquals(fresh.state, StoryRunner.fromSave(story, null).state)
    }
}
