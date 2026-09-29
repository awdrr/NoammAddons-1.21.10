package dev.donutgamble.math

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AmountsTest {
    @Test
    fun `parses suffixed amounts`() {
        assertEquals(5_000_000L, Amounts.parse("5m"))
        assertEquals(1_500_000_000L, Amounts.parse("1.5b"))
        assertEquals(500_000L, Amounts.parse("500k"))
        assertEquals(1234L, Amounts.parse("1234"))
        assertEquals(2_500L, Amounts.parse(" 2.5K "))
        assertEquals(1_000_000L, Amounts.parse("1,000,000"))
    }

    @Test
    fun `rejects bad amounts`() {
        assertNull(Amounts.parse("abc"))
        assertNull(Amounts.parse("-5"))
        assertNull(Amounts.parse("0"))
        assertNull(Amounts.parse(""))
        assertNull(Amounts.parse("5x"))
        assertNull(Amounts.parse("1.2345k"))
        assertNull(Amounts.parse("99999999999b"))
    }

    @Test
    fun `formats short amounts`() {
        assertEquals("500k", Amounts.format(500_000))
        assertEquals("5m", Amounts.format(5_000_000))
        assertEquals("1.5b", Amounts.format(1_500_000_000))
        assertEquals("999", Amounts.format(999))
        assertEquals("-55.56k", Amounts.formatShort(-55_555.5))
        assertEquals("+1.2m", Amounts.formatSigned(1_200_000.0))
    }

    @Test
    fun `validates multiplier and username`() {
        assertEquals(2.0, Amounts.parseMultiplier("2"))
        assertNull(Amounts.parseMultiplier("0.5"))
        assertNull(Amounts.parseMultiplier("6"))
        assertTrue(Amounts.isValidUsername("Some_Streamer1"))
        assertFalse(Amounts.isValidUsername("ab"))
        assertFalse(Amounts.isValidUsername("bad-name"))
        assertFalse(Amounts.isValidUsername("a".repeat(17)))
    }
}
