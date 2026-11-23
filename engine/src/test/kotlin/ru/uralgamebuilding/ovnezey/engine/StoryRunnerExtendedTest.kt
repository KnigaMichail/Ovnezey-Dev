package ru.uralgamebuilding.ovnezey.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoryRunnerExtendedTest {
    private val source = """
        @scenario demo 2
        @character charlie Чарли
        |en Charlie
        @scene start
        @location street Улица
        |en Street
        charlie@left: Привет
        |en Hello
        @param trust +2
        @give key
        @choice
        * Дружелюбно -> kind
        |en Friendly
        * Только для доверия -> trusted ? trust>=3
        |en Trusted only
        * С ключом -> keyed ? has:key
        |en With key
        @scene kind
        @param trust +1
        > Дальше
        |en Next
        @goto check
        @scene check
        @if trust>=3 & has:key -> good
        > Плохой исход
        @end
        @scene good
        @take key
        @location hall Зал
        |en Hall
        > Хороший исход
        |en Good outcome
        @end
        @scene trusted
        @end
        @scene keyed
        @end
    """.trimIndent()

    private val story = ScriptParser.parse(source)

    private fun StoryRunner.say() = step as Step.Say

    @Test
    fun parametersInventoryAndLocationChangeWithCommands() {
        val r = StoryRunner(story)
        assertEquals("street", r.state.location)
        assertEquals(0, r.state.param("trust"))
        r.advance()
        assertEquals(2, r.state.param("trust"))
        assertEquals(setOf("key"), r.state.inventory)
        assertEquals("street", r.state.location)
        assertEquals("Улица", r.locationTitle)
    }

    @Test
    fun optionVisibilityFollowsParametersAndItems() {
        val r = StoryRunner(story)
        r.advance()
        val options = (r.step as Step.Choose).options.map { it.text }
        assertEquals(listOf("Дружелюбно", "С ключом"), options)
    }

    @Test
    fun combinedConditionLeadsToGoodOutcome() {
        val r = StoryRunner(story)
        r.advance()
        r.choose(0)
        assertEquals("Дальше", r.say().text)
        r.advance()
        assertEquals("Хороший исход", r.say().text)
        assertFalse("key" in r.state.inventory)
        assertEquals("hall", r.state.location)
        assertEquals(3, r.state.param("trust"))
    }

    @Test
    fun languageSwitchChangesTextsWithoutChangingState() {
        val r = StoryRunner(story)
        val before = r.state
        assertEquals("Чарли", r.say().speaker)
        assertEquals("Привет", r.say().text)
        r.language = Language.EN
        assertEquals("Charlie", r.say().speaker)
        assertEquals("charlie", r.say().speakerId)
        assertEquals("Hello", r.say().text)
        assertEquals("Street", r.locationTitle)
        assertEquals(before, r.state)
        r.advance()
        assertEquals(listOf("Friendly", "With key"), (r.step as Step.Choose).options.map { it.text })
    }

    @Test
    fun missingTranslationFallsBackToBaseText() {
        val s = ScriptParser.parse("@scene start\n> Только по-русски\n@end")
        val r = StoryRunner(s, language = Language.EN)
        assertEquals("Только по-русски", (r.step as Step.Say).text)
    }

    @Test
    fun unknownSpeakerIsDisplayedAsWritten() {
        val s = ScriptParser.parse("@scene start\nСэм: Привет\n@end")
        val say = StoryRunner(s, language = Language.EN).step as Step.Say
        assertEquals("Сэм", say.speaker)
        assertEquals("Сэм", say.speakerId)
    }

    @Test
    fun parameterArithmeticSaturatesInsteadOfOverflowing() {
        val s = ScriptParser.parse(
            "@scene start\n@param x = 2147483647\n@param x +1\n@param y = -2147483648\n@param y -1\n> ok\n@end",
        )
        val r = StoryRunner(s)
        assertEquals(Int.MAX_VALUE, r.state.param("x"))
        assertEquals(Int.MIN_VALUE, r.state.param("y"))
    }

    @Test
    fun takeOfMissingItemIsNoOp() {
        val s = ScriptParser.parse("@scene start\n@take ghost\n> ok\n@end")
        assertTrue(StoryRunner(s).state.inventory.isEmpty())
    }

    @Test
    fun significantChangeIgnoresPlainAdvance() {
        val s = ScriptParser.parse("@scene start\n> A\n> B\n@set x\n> C\n@end")
        val r = StoryRunner(s)
        val before = r.state
        r.advance()
        assertFalse(r.state.isSignificantChangeFrom(before))
        r.advance()
        assertTrue(r.state.isSignificantChangeFrom(before))
        assertFalse(before.isSignificantChangeFrom(before.copy(index = 99)))
    }

    @Test
    fun restoreRoundTripKeepsEverything() {
        val r = StoryRunner(story)
        r.advance(); r.choose(0)
        val data = SaveData(r.state, story.scenarioId, story.scenarioVersion, 5L)
        val loaded = StoryRunner.restore(story, data)
        assertIs<LoadResult.Loaded>(loaded)
        assertEquals(r.state, loaded.runner.state)
        assertFalse(loaded.migrated)
    }

    @Test
    fun restoreNeverResetsSilently() {
        val state = GameState("start", 0)
        val ok = SaveData(state, "demo", 2)
        assertIs<LoadResult.Loaded>(StoryRunner.restore(story, ok))
        assertEquals(LoadResult.Incompatible(IncompatibleReason.VERSION_MISMATCH), StoryRunner.restore(story, ok.copy(scenarioVersion = 1)))
        assertEquals(LoadResult.Incompatible(IncompatibleReason.SCENARIO_MISMATCH), StoryRunner.restore(story, ok.copy(scenarioId = "other")))
        assertEquals(
            LoadResult.Incompatible(IncompatibleReason.INVALID_POSITION),
            StoryRunner.restore(story, SaveData(GameState("gone", 0), "demo", 2)),
        )
        assertEquals(
            LoadResult.Incompatible(IncompatibleReason.INVALID_POSITION),
            StoryRunner.restore(story, SaveData(GameState("start", 999), "demo", 2)),
        )
    }

    @Test
    fun legacySaveWithoutScenarioInfoIsAcceptedWhenPositionIsValid() {
        val legacy = SaveData(GameState("start", 0), null, null)
        val result = StoryRunner.restore(story, legacy, migrated = true)
        assertIs<LoadResult.Loaded>(result)
        assertTrue(result.migrated)
        assertEquals(
            LoadResult.Incompatible(IncompatibleReason.INVALID_POSITION),
            StoryRunner.restore(story, SaveData(GameState("gone", 0), null, null), migrated = true),
        )
    }

    @Test
    fun invalidInitialStateIsRejectedByConstructor() {
        assertFailsWith<IllegalArgumentException> { StoryRunner(story, GameState("nope", 0)) }
        assertNull(story.locationTitle(null, Language.RU))
    }
}
