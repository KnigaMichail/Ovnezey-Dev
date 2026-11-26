package ru.uralgamebuilding.ovnezey.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MinigameTest {
    private fun parse(body: String) = ScriptParser.parse("@scene start\n$body\n")

    @Test
    fun minigameWithoutTargetContinuesAndWithTargetsBranches() {
        val story = ScriptParser.parse(
            "@scene start\n@minigame wake 5 0 Очнуться\n|en Wake up\n@minigame fight 8 4 Бей -> win else lose\n@scene win\n@end\n@scene lose\n@end\n",
        )
        val r = StoryRunner(story, language = Language.EN)
        assertEquals(Step.Minigame(MinigameKind.WAKE, 5, 0, "Wake up"), r.step)
        r.finishMinigame(true)
        assertTrue(r.step is Step.Minigame && (r.step as Step.Minigame).kind == MinigameKind.FIGHT)
        r.finishMinigame(false)
        assertEquals("lose", r.state.scene)
    }

    @Test
    fun failureWithoutFailTargetLeavesStateUnchanged() {
        val story = ScriptParser.parse("@scene start\n@minigame crate 5 0 Взломать -> ok\n@scene ok\n@end\n")
        val r = StoryRunner(story)
        val before = r.state
        r.finishMinigame(false)
        assertEquals(before, r.state)
    }

    @Test
    fun showAdvancesLikeALine() {
        val r = StoryRunner(parse("@show terminal ERROR\n@end"))
        assertEquals(Step.Show(ShowKind.TERMINAL, "ERROR"), r.step)
        r.advance()
        assertEquals(Step.End, r.step)
    }

    @Test
    fun invalidDefinitionsAreRejected() {
        assertFailsWith<StoryParseException> { parse("@minigame dance 5 0 X\n@end") }
        assertFailsWith<StoryParseException> { parse("@minigame fight 5 0 X\n@end") }
        assertFailsWith<StoryParseException> { parse("@minigame wires 9 0 X\n@end") }
        assertFailsWith<StoryParseException> { parse("@minigame badge 2 0 X\n@end") }
        assertFailsWith<StoryParseException> { parse("@minigame wake 5 0 X") }
        assertFailsWith<StoryParseException> { parse("@minigame wake 5 0 X -> nowhere") }
        assertFailsWith<StoryParseException> { parse("@show city\n@end") }
    }

    @Test
    fun resetKeepsOnlyListedFlags() {
        val story = ScriptParser.parse("@scene start\n@set A\n@set B\n@param p +3\n@give k\n@reset A\n@end\n")
        val r = StoryRunner(story)
        assertEquals(setOf("A"), r.state.flags)
        assertTrue(r.state.params.isEmpty() && r.state.inventory.isEmpty())
    }
}
