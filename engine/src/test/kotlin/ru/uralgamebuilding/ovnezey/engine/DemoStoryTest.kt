package ru.uralgamebuilding.ovnezey.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Проходит встроенную заглушку целиком: выбор, осмотр, предметы, реакции, конец. */
class DemoStoryTest {
    private fun loadDemo(): Story? {
        val file = listOf("../app/src/main/assets/story/demo.ovn", "app/src/main/assets/story/demo.ovn")
            .map { File(it) }.firstOrNull { it.isFile }
        org.junit.Assume.assumeTrue("demo.ovn не найден относительно рабочей папки", file != null)
        return file?.let { ScriptParser.parse(it.readText(Charsets.UTF_8)) }
    }

    private fun StoryRunner.text() = (step as Step.Say).text

    private fun walkToRoom(story: Story, language: Language, takeLetter: Boolean): StoryRunner {
        val r = StoryRunner(story, language = language)
        while (r.step is Step.Say) r.advance()
        r.choose(if (takeLetter) 0 else 1)
        while (r.step is Step.Say) r.advance()
        assertIs<Step.Explore>(r.step)
        return r
    }

    @Test
    fun fullWalkthroughWithLetter() {
        val story = loadDemo() ?: return
        val r = walkToRoom(story, Language.RU, takeLetter = true)
        assertEquals(setOf("letter", "test_key"), r.state.inventory)
        assertEquals(listOf("box", "terminal"), (r.step as Step.Explore).hotspots.map { it.id })

        r.tap("box"); assertEquals("Ящик заперт.", r.text()); r.advance()
        assertEquals(UseResult.Reaction, r.useItem("test_key", "terminal"))
        assertEquals("Это не подходит к терминалу.", r.text()); r.advance()
        assertEquals(UseResult.Reaction, r.useItem("letter", "box"))
        assertEquals("Это сюда не подходит.", r.text()); r.advance()
        assertTrue("test_key" in r.state.inventory)

        assertEquals(UseResult.Used, r.useItem("test_key", "box")); r.advance()
        assertFalse("test_key" in r.state.inventory)
        assertTrue("test_card" in r.state.inventory)
        assertEquals(listOf("box_opened", "terminal"), (r.step as Step.Explore).hotspots.map { it.id })

        assertEquals(UseResult.Used, r.useItem("test_card", "terminal")); r.advance()
        assertFalse("test_card" in r.state.inventory)
        assertEquals(listOf("box_opened", "terminal_working", "exit"), (r.step as Step.Explore).hotspots.map { it.id })

        r.tap("exit")
        assertEquals("Конец заглушки, письмо у вас.", r.text())
        r.advance()
        assertEquals(Step.End, r.step)
    }

    @Test
    fun walkthroughWithoutLetterEndsDifferently() {
        val story = loadDemo() ?: return
        val r = walkToRoom(story, Language.EN, takeLetter = false)
        r.useItem("test_key", "box"); r.advance()
        r.useItem("test_card", "terminal"); r.advance()
        r.tap("exit")
        assertEquals("End of the placeholder.", r.text())
    }

    @Test
    fun inventoryTextsAreTranslated() {
        val story = loadDemo() ?: return
        val r = walkToRoom(story, Language.EN, takeLetter = true)
        assertEquals(listOf("Letter", "Test key"), r.inventoryItems.map { it.name })
    }

    @Test
    fun demoSurvivesSaveInTheMiddleOfExploration() {
        val story = loadDemo() ?: return
        val r = walkToRoom(story, Language.RU, takeLetter = true)
        r.useItem("test_key", "box"); r.advance()
        val slots = SaveSlots(InMemorySaveStore())
        slots.saveManual(1, r.state, story)
        val loaded = slots.load("slot1", story) as LoadResult.Loaded
        assertEquals(r.state, loaded.runner.state)
        assertEquals(UseResult.Used, loaded.runner.useItem("test_card", "terminal"))
    }
}
