package dev.donutgamble.math

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/** Parsing and formatting of coin amounts like "500k", "5m" and "1.5b". */
object Amounts {
    private val PATTERN = Regex("^(\\d+(?:\\.\\d+)?|\\.\\d+)([kmb])?$")
    private val MULTIPLIERS = mapOf(
        null to BigDecimal.ONE,
        "k" to BigDecimal("1000"),
        "m" to BigDecimal("1000000"),
        "b" to BigDecimal("1000000000"),
    )
    private val MAX = BigDecimal(Long.MAX_VALUE)

    /** Returns the whole-coin amount, or null if the text is not a positive whole number of coins. */
    fun parse(text: String): Long? {
        val cleaned = text.trim().lowercase().replace(",", "").replace("_", "")
        val match = PATTERN.matchEntire(cleaned) ?: return null
        val number = BigDecimal(match.groupValues[1])
        val suffix = match.groupValues[2].ifEmpty { null }
        val value = number.multiply(MULTIPLIERS.getValue(suffix))
        if (value.signum() <= 0 || value > MAX) return null
        // Fractions of a coin (e.g. "1.2345k") can't be paid.
        if (value.stripTrailingZeros().scale() > 0) return null
        return value.toLong()
    }

    /** Short form: 500000 -> "500k", 1500000000 -> "1.5b". */
    fun format(amount: Long): String = formatShort(amount.toDouble())

    /** Short form of a possibly fractional, possibly negative value, e.g. -55555.5 -> "-55.56k". */
    fun formatShort(value: Double): String {
        val a = abs(value)
        val (scaled, suffix) = when {
            a >= 1e9 -> a / 1e9 to "b"
            a >= 1e6 -> a / 1e6 to "m"
            a >= 1e3 -> a / 1e3 to "k"
            else -> a to ""
        }
        val digits = BigDecimal(scaled).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        val sign = if (value < 0 && digits != "0") "-" else ""
        return sign + digits + suffix
    }

    /** Like [formatShort] but always with a sign, for profit/loss. */
    fun formatSigned(value: Double): String {
        val s = formatShort(value)
        return if (s.startsWith("-") || s == "0") s else "+$s"
    }

    fun parseMultiplier(text: String): Double? {
        val v = text.trim().removeSuffix("x").toDoubleOrNull() ?: return null
        return v.takeIf { it in 1.0..5.0 }
    }

    private val USERNAME = Regex("^[A-Za-z0-9_]{3,16}$")

    fun isValidUsername(name: String): Boolean = USERNAME.matches(name)
}
