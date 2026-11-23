package ru.uralgamebuilding.ovnezey.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SaveSlotsTest {
    private val story = ScriptParser.parse(
        "@scenario demo 1\n@scene start\n@location hall Холл\n> A\n> B\n@set x\n> C\n@end",
    )
    private var now = 1_000L
    private val store = InMemorySaveStore()
    private val slots = SaveSlots(store) { now }

    private fun stateAfter(advances: Int): GameState {
        val r = StoryRunner(story)
        repeat(advances) { r.advance() }
        return r.state
    }

    @Test
    fun emptySlotsAreReportedAsEmpty() {
        val all = slots.manualSummaries()
        assertEquals(5, all.size)
        assertTrue(all.all { it.status == SlotStatus.Empty })
        assertEquals(SlotStatus.Empty, slots.summary(SaveSlots.AUTO_KEY).status)
        assertEquals(LoadResult.Empty, slots.load("slot1", story))
    }

    @Test
    fun manualSaveAndLoadRestoresPosition() {
        slots.saveManual(2, stateAfter(1), story)
        val loaded = slots.load("slot2", story)
        assertIs<LoadResult.Loaded>(loaded)
        assertEquals(stateAfter(1), loaded.runner.state)
        val summary = slots.summary("slot2").status
        assertIs<SlotStatus.Filled>(summary)
        assertEquals(1_000L, summary.savedAtMillis)
        assertEquals("hall", summary.location)
        assertEquals("demo", summary.scenarioId)
    }

    @Test
    fun autosaveNeverTouchesManualSlots() {
        slots.saveManual(1, stateAfter(0), story)
        val before = store.data["slot1"]
        now = 2_000L
        slots.saveAuto(stateAfter(2), story)
        assertEquals(before, store.data["slot1"])
        assertTrue("auto" in store.data)
        assertEquals(setOf("slot1", "auto"), store.data.keys)
    }

    @Test
    fun overwritingReplacesOnlyThatSlot() {
        slots.saveManual(1, stateAfter(0), story)
        slots.saveManual(2, stateAfter(1), story)
        now = 5_000L
        slots.saveManual(1, stateAfter(2), story)
        val s1 = slots.summary("slot1").status as SlotStatus.Filled
        val s2 = slots.summary("slot2").status as SlotStatus.Filled
        assertEquals(5_000L, s1.savedAtMillis)
        assertEquals(1_000L, s2.savedAtMillis)
    }

    @Test
    fun invalidSlotNumbersAndKeysAreRejected() {
        assertFailsWith<IllegalArgumentException> { slots.saveManual(0, stateAfter(0), story) }
        assertFailsWith<IllegalArgumentException> { slots.saveManual(6, stateAfter(0), story) }
        assertFailsWith<IllegalArgumentException> { slots.summary("slot9") }
        assertFailsWith<IllegalArgumentException> { slots.load("../x", story) }
    }

    @Test
    fun corruptedSlotIsReportedAndKeptUntouched() {
        slots.saveManual(3, stateAfter(1), story)
        val damaged = store.data.getValue("slot3").replace("index=", "index=9")
        store.data["slot3"] = damaged
        assertEquals(SlotStatus.Broken(InvalidReason.CORRUPTED), slots.summary("slot3").status)
        val result = slots.load("slot3", story)
        assertIs<LoadResult.Unreadable>(result)
        assertEquals(InvalidReason.CORRUPTED, result.reason)
        assertEquals(damaged, store.data["slot3"])
    }

    @Test
    fun otherScenarioVersionIsIncompatibleAndKeptUntouched() {
        slots.saveManual(1, stateAfter(1), story)
        val raw = store.data.getValue("slot1")
        val newer = ScriptParser.parse("@scenario demo 2\n@scene start\n@location hall Холл\n> A\n> B\n@end")
        assertEquals(LoadResult.Incompatible(IncompatibleReason.VERSION_MISMATCH), slots.load("slot1", newer))
        assertEquals(raw, store.data["slot1"])
        val other = ScriptParser.parse("@scenario other 1\n@scene start\n> A\n@end")
        assertEquals(LoadResult.Incompatible(IncompatibleReason.SCENARIO_MISMATCH), slots.load("slot1", other))
    }

    @Test
    fun legacySlotIsMigratedAndMarked() {
        store.data["auto"] = LegacySaveV1.encode(GameState("start", 0))
        val summary = slots.summary("auto").status
        assertIs<SlotStatus.Filled>(summary)
        assertTrue(summary.migrated)
        val loaded = slots.load("auto", story)
        assertIs<LoadResult.Loaded>(loaded)
        assertTrue(loaded.migrated)
    }

    @Test
    fun loadedRunnerUsesRequestedLanguage() {
        val s = ScriptParser.parse("@scenario demo 1\n@scene start\n> Привет\n|en Hello\n@end")
        slots.saveManual(1, StoryRunner(s).state, s)
        val loaded = slots.load("slot1", s, Language.EN) as LoadResult.Loaded
        assertEquals("Hello", (loaded.runner.step as Step.Say).text)
        assertNotEquals(Language.DEFAULT, loaded.runner.language)
    }
}
