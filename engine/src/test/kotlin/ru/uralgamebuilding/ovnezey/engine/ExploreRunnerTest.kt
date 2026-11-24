package ru.uralgamebuilding.ovnezey.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExploreRunnerTest {
    private val source = """
        @scenario room 1
        @item key
        name: Ключ
        |en Key
        desc: Открывает ящик.
        |en Opens the box.
        use: consume
        @item lamp
        name: Лампа
        |en Lamp
        @item card
        name: Карта
        |en Card
        use: keep

        @scene start
        @location room Комната
        |en Room
        @give key
        @give lamp
        @goto hub

        @scene hub
        @explore
        * box 0.1 0.5 0.2 0.2 Ящик -> box_closed ? !box_open
        |en Box
        * open_box 0.1 0.5 0.2 0.2 Открытый ящик -> box_inside ? box_open
        |en Open box
        * door 0.7 0.2 0.2 0.5 Дверь -> door_locked
        |en Door
        @use key box -> box_unlock
        @use card door -> door_unlock ? box_open
        @fail door -> door_wrong
        @fail * -> generic_wrong

        @scene box_closed
        > Ящик заперт.
        |en The box is locked.
        @goto hub

        @scene box_unlock
        @set box_open
        @give card
        > Ящик открыт, внутри карта.
        |en The box is open, a card is inside.
        @goto hub

        @scene box_inside
        > Внутри пусто.
        |en Empty inside.
        @goto hub

        @scene door_locked
        > Дверь закрыта.
        |en The door is locked.
        @goto hub

        @scene door_unlock
        > Карта подошла.
        |en The card fits.
        @end

        @scene door_wrong
        > Не подходит к двери.
        |en Does not fit the door.
        @goto hub

        @scene generic_wrong
        > Не подходит.
        |en Does not fit.
        @goto hub
    """.trimIndent()

    private val story = ScriptParser.parse(source)

    private fun runner(language: Language = Language.RU) = StoryRunner(story, language = language)

    private fun StoryRunner.say() = (step as Step.Say).text

    private fun StoryRunner.hotspotIds() = (step as Step.Explore).hotspots.map { it.id }

    @Test
    fun startsAtExploreWithItemsGiven() {
        val r = runner()
        assertIs<Step.Explore>(r.step)
        assertEquals(setOf("key", "lamp"), r.state.inventory)
        assertEquals("Комната", r.locationTitle)
    }

    @Test
    fun onlyAvailableHotspotsAreShown() {
        val r = runner()
        assertEquals(listOf("box", "door"), r.hotspotIds())
        val first = (r.step as Step.Explore).hotspots[0]
        assertEquals(HotspotView("box", "Ящик", 0.1f, 0.5f, 0.2f, 0.2f), first)
    }

    @Test
    fun tapMovesToHotspotSceneAndBack() {
        val r = runner()
        r.tap("box")
        assertEquals("Ящик заперт.", r.say())
        r.advance()
        assertIs<Step.Explore>(r.step)
    }

    @Test
    fun tapRejectsUnknownHiddenAndWrongState() {
        val r = runner()
        assertFailsWith<IllegalArgumentException> { r.tap("ghost") }
        assertFailsWith<IllegalArgumentException> { r.tap("open_box") }
        r.tap("box")
        assertFailsWith<IllegalStateException> { r.tap("box") }
    }

    @Test
    fun correctUseConsumesItemAndRunsScene() {
        val r = runner()
        assertEquals(UseResult.Used, r.useItem("key", "box"))
        assertEquals("Ящик открыт, внутри карта.", r.say())
        assertFalse("key" in r.state.inventory)
        assertTrue("card" in r.state.inventory)
        assertTrue("box_open" in r.state.flags)
        r.advance()
        assertEquals(listOf("open_box", "door"), r.hotspotIds())
    }

    @Test
    fun keptItemStaysInInventory() {
        val r = runner()
        r.useItem("key", "box"); r.advance()
        assertEquals(UseResult.Used, r.useItem("card", "door"))
        assertTrue("card" in r.state.inventory)
        assertEquals("Карта подошла.", r.say())
    }

    @Test
    fun useRuleConditionIsRespected() {
        val r = runner()
        r.useItem("key", "box"); r.advance()
        r.state.let { assertTrue("box_open" in it.flags) }
        val locked = StoryRunner(story, GameState("hub", 0, inventory = setOf("card")))
        assertEquals(UseResult.Reaction, locked.useItem("card", "door"))
        assertEquals("Не подходит к двери.", locked.say())
    }

    @Test
    fun wrongItemOnHotspotRunsSpecificReactionWithoutConsuming() {
        val r = runner()
        assertEquals(UseResult.Reaction, r.useItem("lamp", "door"))
        assertEquals("Не подходит к двери.", r.say())
        assertEquals(setOf("key", "lamp"), r.state.inventory)
        r.advance()
        assertIs<Step.Explore>(r.step)
    }

    @Test
    fun wrongItemFallsBackToGenericReaction() {
        val r = runner()
        assertEquals(UseResult.Reaction, r.useItem("lamp", "box"))
        assertEquals("Не подходит.", r.say())
    }

    @Test
    fun wrongItemWithoutAnyReactionIsRejectedAndStateUntouched() {
        val s = ScriptParser.parse(
            "@item a\nname: A\n@item b\nname: B\n@scene start\n@give a\n@give b\n@goto hub\n" +
                "@scene hub\n@explore\n* h 0 0 0.5 0.5 H -> hub\n@use a h -> done\n@scene done\n@end",
        )
        val r = StoryRunner(s)
        val before = r.state
        assertEquals(UseResult.Rejected(RejectReason.NO_EFFECT), r.useItem("b", "h"))
        assertEquals(before, r.state)
    }

    @Test
    fun invalidUseIsRejectedWithoutChangingState() {
        val r = runner()
        val before = r.state
        assertEquals(UseResult.Rejected(RejectReason.ITEM_NOT_OWNED), r.useItem("card", "door"))
        assertEquals(UseResult.Rejected(RejectReason.NO_SUCH_HOTSPOT), r.useItem("key", "ghost"))
        assertEquals(UseResult.Rejected(RejectReason.HOTSPOT_UNAVAILABLE), r.useItem("key", "open_box"))
        assertEquals(before, r.state)
        r.tap("box")
        val mid = r.state
        assertEquals(UseResult.Rejected(RejectReason.NOT_EXPLORING), r.useItem("key", "box"))
        assertEquals(mid, r.state)
    }

    @Test
    fun repeatedUseOfConsumedItemIsRejected() {
        val r = runner()
        r.useItem("key", "box"); r.advance()
        assertEquals(UseResult.Rejected(RejectReason.ITEM_NOT_OWNED), r.useItem("key", "open_box"))
    }

    @Test
    fun languageSwitchTranslatesHotspotsAndItems() {
        val r = runner(Language.EN)
        assertEquals(listOf("Box", "Door"), (r.step as Step.Explore).hotspots.map { it.label })
        assertEquals(
            listOf(ItemView("key", "Key", "Opens the box."), ItemView("lamp", "Lamp", "")),
            r.inventoryItems.map { if (it.id == "lamp") it.copy(description = "") else it },
        )
        r.language = Language.RU
        assertEquals(listOf("Ящик", "Дверь"), (r.step as Step.Explore).hotspots.map { it.label })
        assertEquals("Ключ", r.inventoryItems.first { it.id == "key" }.name)
    }

    @Test
    fun inventoryFollowsDeclarationOrderNotAcquisitionOrder() {
        val r = runner()
        r.useItem("key", "box"); r.advance()
        assertEquals(listOf("lamp", "card"), r.inventoryItems.map { it.id })
    }

    @Test
    fun undeclaredInventoryIdsAreListedLast() {
        val s = ScriptParser.parse("@item a\nname: A\n@scene start\n@give zzz\n@give a\n> x\n@end")
        assertEquals(listOf("a", "zzz"), StoryRunner(s).inventoryItems.map { it.id })
    }

    @Test
    fun saveAndRestoreAtExploreRoundTrips() {
        val r = runner()
        r.useItem("key", "box"); r.advance()
        val data = SaveData(r.state, story.scenarioId, story.scenarioVersion, 1L)
        val text = SaveFormat.encode(data)
        val decoded = (SaveFormat.decode(text) as DecodeResult.Ok).data
        val loaded = StoryRunner.restore(story, decoded) as LoadResult.Loaded
        assertEquals(r.state, loaded.runner.state)
        assertEquals(listOf("open_box", "door"), loaded.runner.hotspotIds())
        assertEquals(UseResult.Used, loaded.runner.useItem("card", "door"))
    }

    @Test
    fun exploreWithoutAvailableHotspotsIsReported() {
        val s = ScriptParser.parse("@scene start\n@explore\n* h 0 0 0.5 0.5 H -> start ? never")
        assertFailsWith<IllegalStateException> { StoryRunner(s) }
    }

    @Test
    fun advanceAndChooseAreInvalidDuringExplore() {
        val r = runner()
        assertFailsWith<IllegalStateException> { r.advance() }
        assertFailsWith<IllegalStateException> { r.choose(0) }
    }

    @Test
    fun explorationIsSignificantStateChange() {
        val r = runner()
        val before = r.state
        r.tap("box")
        assertTrue(r.state.isSignificantChangeFrom(before))
    }
}
