package ru.uralgamebuilding.ovnezey.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Проверки реального сюжета (пролог и глава 1): структура, переводы, достижимость, полный обход состояний. */
class StoryContentTest {
    private val story: Story by lazy {
        val file = listOf("../app/src/main/assets/story/ovnezey.ovn", "app/src/main/assets/story/ovnezey.ovn")
            .map { File(it) }.firstOrNull { it.isFile }
            ?: error("ovnezey.ovn не найден относительно рабочей папки ${File(".").absolutePath}")
        ScriptParser.parse(file.readText(Charsets.UTF_8))
    }

    @Test
    fun englishTranslationIsComplete() = assertEquals(emptyList(), StoryChecks.missingTranslations(story, Language.EN))

    @Test
    fun allUsedItemsAreDeclared() = assertEquals(emptySet(), StoryChecks.undefinedItems(story))

    @Test
    fun everySceneIsReachable() = assertEquals(emptySet(), StoryChecks.unreachableScenes(story))

    @Test
    fun hotspotsStayAboveTextPanel() = assertEquals(emptyList(), StoryChecks.hotspotsUnderTextPanel(story))

    @Test
    fun noFlagIsReadWithoutBeingSet() = assertEquals(emptySet(), StoryChecks.flagsNeverSet(story))

    @Test
    fun everyReachableStateRunsToTheEnd() {
        for (language in Language.entries) {
            val report = StoryChecks.walkAll(story, language)
            assertEquals(emptyList(), report.errors, "ошибки обхода ($language)")
            assertEquals(story.scenes.keys, report.scenes, "не все сцены посещены ($language)")
            assertTrue(report.endStates > 0, "конец сюжета недостижим ($language)")
        }
    }

    @Test
    fun repairedModuleIsGivenOnlyForSelfRepair() {
        fun play(repair: Boolean): StoryRunner {
            val r = StoryRunner(story)
            while (true) when (val s = r.step) {
                is Step.Say -> r.advance()
                is Step.Choose -> {
                    val texts = s.options.map { it.target }
                    r.choose(
                        when {
                            "ch1_choice_05_a" in texts -> texts.indexOf(if (repair) "ch1_choice_05_a" else "ch1_choice_05_b")
                            else -> 0
                        },
                    )
                }
                is Step.Explore -> r.tap("done")
                Step.End -> return r
            }
        }
        assertTrue("contact_module" in play(repair = true).state.inventory)
        assertTrue("contact_module" !in play(repair = false).state.inventory)
    }

    @Test
    fun repeatedInspectionDoesNotRepeatContent() {
        val r = StoryRunner(story)
        while (r.step !is Step.Explore) {
            if (r.step is Step.Choose) r.choose(0) else r.advance()
        }
        r.tap("table")
        while (r.step is Step.Say) r.advance()
        val firstFlags = r.state.flags
        r.tap("table")
        assertEquals("Здесь я уже всё осмотрел.", (r.step as Step.Say).text)
        r.advance()
        assertEquals(firstFlags, r.state.flags)
        assertTrue(r.step is Step.Explore)
    }

    @Test
    fun saveAtEveryReachableSceneRestoresSamePosition() {
        val slots = SaveSlots(InMemorySaveStore())
        val r = StoryRunner(story)
        var saved = 0
        while (true) {
            when (r.step) {
                is Step.Say -> {
                    slots.saveManual(1, r.state, story)
                    val loaded = slots.load("slot1", story) as LoadResult.Loaded
                    assertEquals(r.state, loaded.runner.state)
                    saved++
                    r.advance()
                }
                is Step.Choose -> r.choose(0)
                is Step.Explore -> r.tap("done")
                Step.End -> break
            }
        }
        assertTrue(saved > 100)
    }
}
