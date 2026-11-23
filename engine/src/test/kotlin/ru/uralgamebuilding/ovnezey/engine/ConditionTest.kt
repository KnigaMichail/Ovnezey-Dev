package ru.uralgamebuilding.ovnezey.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConditionTest {
    private val state = GameState(
        "start", 0,
        flags = setOf("met"),
        params = mapOf("trust" to 2, "debt" to -3),
        inventory = setOf("key"),
    )

    @Test
    fun parsesAllForms() {
        assertEquals(Condition.Flag("met"), ConditionParser.parse("met"))
        assertEquals(Condition.Flag("met", negate = true), ConditionParser.parse("!met"))
        assertEquals(Condition.HasItem("key"), ConditionParser.parse("has:key"))
        assertEquals(Condition.HasItem("key", negate = true), ConditionParser.parse("!has:key"))
        assertEquals(Condition.Param("trust", CompareOp.GE, 2), ConditionParser.parse("trust>=2"))
        assertEquals(Condition.Param("debt", CompareOp.LT, -1), ConditionParser.parse("debt < -1"))
        assertEquals(
            Condition.All(listOf(Condition.Flag("met"), Condition.Param("trust", CompareOp.GT, 1))),
            ConditionParser.parse("met & trust > 1"),
        )
    }

    @Test
    fun rejectsInvalidConditions() {
        listOf("", "&", "met &", "trust>=", "trust>=x", "has:", "a b", "trust=>2", "trust>=99999999999")
            .forEach { assertNull(ConditionParser.parse(it), "должно быть отвергнуто: «$it»") }
    }

    @Test
    fun evaluatesAgainstState() {
        fun ok(text: String) = ConditionParser.parse(text)!!.test(state)
        assertTrue(ok("met"))
        assertFalse(ok("!met"))
        assertTrue(ok("has:key"))
        assertFalse(ok("has:lamp"))
        assertTrue(ok("!has:lamp"))
        assertTrue(ok("trust>=2"))
        assertFalse(ok("trust>2"))
        assertTrue(ok("trust==2"))
        assertTrue(ok("trust!=3"))
        assertTrue(ok("trust<=2 & trust>=2"))
        assertTrue(ok("debt<0"))
    }

    @Test
    fun unsetParameterIsZero() {
        assertTrue(ConditionParser.parse("missing==0")!!.test(state))
        assertFalse(ConditionParser.parse("missing>0")!!.test(state))
    }

    @Test
    fun allRequiresEveryPart() {
        assertFalse(ConditionParser.parse("met & has:lamp")!!.test(state))
    }
}
