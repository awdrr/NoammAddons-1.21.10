package dev.donutgamble.math

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GambleMathTest {
    private val eps = 1e-12

    @Test
    fun `uniform vs uniform wins 36 of 81 with EV minus one ninth`() {
        val win = GambleMath.winProbability(Distribution.UNIFORM, Distribution.UNIFORM)
        assertEquals(36.0 / 81.0, win, eps)
        assertEquals(-1.0 / 9.0, GambleMath.expectedValue(win, 2.0), eps)
        assertEquals(Verdict.PAY_SMALL, GambleMath.verdict(GambleMath.expectedValue(win, 2.0)))
    }

    @Test
    fun `fresh model is fair and says pay small`() {
        val a = GambleModel().analyze(2.0)
        assertEquals(36.0 / 81.0, a.win, eps)
        assertEquals(45.0 / 81.0, a.lose, eps)
        assertEquals(-1.0 / 9.0, a.ev, eps)
        assertEquals(Verdict.PAY_SMALL, a.verdict)
    }

    @Test
    fun `streamer holding only 1s against fair player says pay big`() {
        val model = GambleModel().apply { manualStreamer = listOf(1, 1, 1, 1, 1, 1, 1, 1, 1) }
        val a = model.analyze(2.0)
        assertEquals(8.0 / 9.0, a.win, eps)
        assertTrue(a.ev > 0)
        assertEquals(Verdict.PAY_BIG, a.verdict)
    }

    @Test
    fun `manual slots give each filled slot equal weight`() {
        val d = Distribution.fromSlots(listOf(9, 9, 1))
        assertEquals(2.0 / 3.0, d[9], eps)
        assertEquals(1.0 / 3.0, d[1], eps)
        assertEquals(0.0, d[5], eps)
    }

    @Test
    fun `learned estimate starts from 3 pseudo counts`() {
        val model = GambleModel()
        model.log(Roll(9, 1))
        val (me, source) = model.myDistribution()
        assertEquals(Source.LEARNED, source)
        assertEquals(4.0 / 28.0, me[9], eps)
        assertEquals(3.0 / 28.0, me[5], eps)
    }

    @Test
    fun `learning off keeps fair odds`() {
        val model = GambleModel().apply { learnFromRolls = false }
        repeat(30) { model.log(Roll(1, 9)) }
        assertEquals(36.0 / 81.0, model.analyze(2.0).win, eps)
    }

    @Test
    fun `ties count as losses`() {
        val model = GambleModel()
        model.log(Roll(5, 5))
        val a = model.analyze(2.0)
        assertEquals(0, a.wins)
        assertEquals(1, a.losses)
        assertEquals(1, a.ties)
    }

    @Test
    fun `rigging check needs 20 rolls and flags a losing streak`() {
        assertNull(GambleMath.riggingCheck(19, 0))
        val bad = GambleMath.riggingCheck(40, 8)
        assertNotNull(bad)
        assertTrue(bad!!.z < -2)
        assertTrue(bad.rigged)
        val fine = GambleMath.riggingCheck(81, 36)!!
        assertFalse(fine.rigged)
    }

    @Test
    fun `binomial tail matches a hand computed value`() {
        // P(X <= 1), n = 3, p = 0.5 -> (1 + 3) / 8
        assertEquals(0.5, GambleMath.binomialLowerTail(3, 1, 0.5), eps)
    }

    @Test
    fun `slot parsing accepts spaces commas and digit runs`() {
        assertEquals(listOf(1, 2, 3), GambleModel.parseSlots("1 2 3"))
        assertEquals(listOf(1, 2, 3), GambleModel.parseSlots("1,2,3"))
        assertEquals((1..9).toList(), GambleModel.parseSlots("123456789"))
        assertNull(GambleModel.parseSlots("0 1"))
        assertNull(GambleModel.parseSlots("1234567891"))
        assertNull(GambleModel.parseSlots(""))
    }
}
