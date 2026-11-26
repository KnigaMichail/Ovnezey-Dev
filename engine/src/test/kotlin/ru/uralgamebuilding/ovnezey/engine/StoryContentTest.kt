package ru.uralgamebuilding.ovnezey.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Проверки реального сюжета (демо: пролог и глава 1): структура, переводы, достижимость, полный обход, ключевые ветки. */
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
    fun medModuleIsGivenOnlyForSelfRepair() {
        assertTrue("med_module" in StoryChecks.autoplay(story, prefer = setOf("ch1_repair")).state.inventory)
        assertTrue("med_module" !in StoryChecks.autoplay(story, prefer = setOf("ch1_tech")).state.inventory)
    }

    @Test
    fun crateLabelIsGivenOnlyWhenCrateIsOpened() {
        assertTrue("label_513" in StoryChecks.autoplay(story, prefer = setOf("ch1_crate_call")).state.inventory)
        assertTrue("label_513" !in StoryChecks.autoplay(story, prefer = setOf("ch1_crate_ignore")).state.inventory)
    }

    @Test
    fun givingPdaEndsQuietlyAndRefusingLeadsToTheFight() {
        val gave = StoryChecks.autoplay(story, prefer = setOf("ch1_pda_give"))
        assertTrue("CH1_PDA_GIVEN" in gave.state.flags && "CH1_DEFENDED_HOME" !in gave.state.flags)
        val won = StoryChecks.autoplay(story, prefer = setOf("ch1_pda_refuse"))
        assertTrue("CH1_DEFENDED_HOME" in won.state.flags)
    }

    @Test
    fun losingTheFightLoopsBackToTheStairsWithCleanState() {
        val loop = StoryChecks.autoplay(
            story,
            prefer = setOf("ch1_pda_refuse", "ch1_repair"),
            minigameSuccess = { it.kind != MinigameKind.FIGHT },
            stopAt = { it.state.scene == "loop_intro" },
        )
        assertEquals("loop_intro", loop.state.scene)
        assertEquals(setOf("LOOPED"), loop.state.flags)
        assertTrue(loop.state.inventory.isEmpty() && loop.state.params.isEmpty())
    }

    @Test
    fun repeatedInspectionDoesNotRepeatContent() {
        val r = StoryChecks.autoplay(story, stopAt = { it.step is Step.Explore })
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
    fun saveAtEveryStepOfAPlaythroughRestoresSamePosition() {
        val slots = SaveSlots(InMemorySaveStore())
        var saved = 0
        StoryChecks.autoplay(story, stopAt = { r ->
            if (r.step is Step.Say) {
                slots.saveManual(1, r.state, story)
                val loaded = slots.load("slot1", story) as LoadResult.Loaded
                assertEquals(r.state, loaded.runner.state)
                saved++
            }
            false
        })
        assertTrue(saved > 100)
    }
}
