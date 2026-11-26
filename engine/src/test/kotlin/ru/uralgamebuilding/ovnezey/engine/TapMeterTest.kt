package ru.uralgamebuilding.ovnezey.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TapMeterTest {
    @Test
    fun enoughTapsCompleteEvenWithFrameUpdatesBetween() {
        for (needed in listOf(3, 6, 8, 10, 40)) {
            val m = TapMeter(needed, 0.12f)
            repeat(needed) {
                m.update(0.016f)
                m.tap()
                m.update(0.016f)
            }
            assertTrue(m.completed, "needed=$needed progress=${m.progress}")
        }
    }

    @Test
    fun floatSumOfTenthsStillCompletes() {
        val m = TapMeter(10, 0f)
        repeat(10) { m.tap() }
        assertTrue(m.completed)
        assertEquals(1f, m.progress)
    }

    @Test
    fun tooSlowTappingNeverCompletes() {
        val m = TapMeter(8, 0.12f)
        repeat(50) {
            m.tap()
            m.update(2f)
        }
        assertFalse(m.completed)
    }

    @Test
    fun completionIsFinalAndProgressNeverNegative() {
        val m = TapMeter(3, 0.12f)
        repeat(3) { m.tap() }
        m.update(100f)
        assertTrue(m.completed && m.progress == 1f)
        val idle = TapMeter(3, 1f)
        idle.update(5f)
        assertEquals(0f, idle.progress)
    }
}
