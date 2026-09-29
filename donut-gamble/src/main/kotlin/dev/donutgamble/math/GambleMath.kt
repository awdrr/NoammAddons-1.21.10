package dev.donutgamble.math

import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.sqrt

/** Probability of each number 1..9 coming out of one dispenser. Index 0 is the number 1. */
class Distribution private constructor(private val p: DoubleArray) {
    operator fun get(number: Int): Double = p[number - 1]

    val mean: Double get() = (1..9).sumOf { it * this[it] }

    companion object {
        val UNIFORM = Distribution(DoubleArray(9) { 1.0 / 9.0 })

        /**
         * One number per filled slot. A dispenser fires a random non-empty slot,
         * so every slot has probability 1 / (number of filled slots).
         */
        fun fromSlots(slots: List<Int>): Distribution {
            require(slots.size in 1..9) { "A dispenser has 1-9 filled slots" }
            require(slots.all { it in 1..9 }) { "Slot numbers must be 1-9" }
            val p = DoubleArray(9)
            for (n in slots) p[n - 1] += 1.0 / slots.size
            return Distribution(p)
        }

        /**
         * Posterior mean of a Dirichlet(prior, ..., prior) after observing [counts].
         * With 3 pseudo-counts per number the estimate stays close to fair until
         * real evidence piles up.
         */
        fun fromCounts(counts: IntArray, prior: Double = GambleMath.PRIOR_PSEUDO_COUNTS): Distribution {
            require(counts.size == 9)
            val total = counts.sum() + 9 * prior
            return Distribution(DoubleArray(9) { (counts[it] + prior) / total })
        }
    }
}

data class Roll(val mine: Int, val streamer: Int) {
    init {
        require(mine in 1..9 && streamer in 1..9) { "Rolls must be 1-9" }
    }

    /** Ties count as losses. */
    val won: Boolean get() = mine > streamer
    val tie: Boolean get() = mine == streamer
}

enum class Verdict { PAY_BIG, PAY_SMALL }

enum class Source { FAIR, LEARNED, MANUAL }

data class RiggingCheck(
    val n: Int,
    val wins: Int,
    val observedRate: Double,
    /** Normal-approximation z score against the fair 36/81. */
    val z: Double,
    /** Exact binomial P(wins <= observed) on a fair machine. */
    val pValue: Double,
) {
    val rigged: Boolean get() = pValue < GambleMath.RIGGING_P_THRESHOLD
}

data class Analysis(
    val win: Double,
    val lose: Double,
    val tie: Double,
    val multiplier: Double,
    /** Expected profit per 1 coin bet. -0.111 means you lose 11.1% of each bet on average. */
    val ev: Double,
    val verdict: Verdict,
    val mySource: Source,
    val streamerSource: Source,
    val rolls: Int,
    val wins: Int,
    val losses: Int,
    val ties: Int,
    val myAverage: Double?,
    val streamerAverage: Double?,
    /** Null until [GambleMath.RIGGING_MIN_ROLLS] rolls are logged. */
    val rigging: RiggingCheck?,
) {
    fun expectedProfit(bet: Long): Double = ev * bet
}

object GambleMath {
    const val FAIR_WIN = 36.0 / 81.0
    const val PRIOR_PSEUDO_COUNTS = 3.0
    const val RIGGING_MIN_ROLLS = 20

    /** Same tail probability as z = -2 on a normal curve, so the warning fires at the same strength of evidence. */
    const val RIGGING_P_THRESHOLD = 0.02275

    /** Win = sum over i > j of Pme(i) * Pstreamer(j). */
    fun winProbability(me: Distribution, streamer: Distribution): Double {
        var win = 0.0
        for (i in 2..9) {
            var below = 0.0
            for (j in 1 until i) below += streamer[j]
            win += me[i] * below
        }
        return win
    }

    fun tieProbability(me: Distribution, streamer: Distribution): Double =
        (1..9).sumOf { me[it] * streamer[it] }

    /** Expected result per 1 coin: win pays (multiplier - 1) profit, a loss or tie loses the bet. */
    fun expectedValue(win: Double, multiplier: Double): Double = win * (multiplier - 1.0) - (1.0 - win)

    fun verdict(ev: Double): Verdict = if (ev > 0) Verdict.PAY_BIG else Verdict.PAY_SMALL

    /**
     * Rigging check against a fair machine (win 36/81).
     *
     * The requested z test uses a normal approximation, which is rough at n = 20.
     * The decision uses the exact binomial lower tail instead, with the threshold
     * matched to z = -2. The z score is still reported for display.
     */
    fun riggingCheck(n: Int, wins: Int): RiggingCheck? {
        if (n < RIGGING_MIN_ROLLS) return null
        val p = FAIR_WIN
        val observed = wins.toDouble() / n
        val z = (observed - p) / sqrt(p * (1 - p) / n)
        return RiggingCheck(n, wins, observed, z, binomialLowerTail(n, wins, p))
    }

    /** P(X <= k) for X ~ Binomial(n, p), summed in log space so large n does not underflow. */
    fun binomialLowerTail(n: Int, k: Int, p: Double): Double {
        if (k < 0) return 0.0
        if (k >= n) return 1.0
        val logP = ln(p)
        val logQ = ln(1 - p)
        var logChoose = 0.0 // ln C(n, 0)
        var sum = 0.0
        for (i in 0..k) {
            if (i > 0) logChoose += ln((n - i + 1).toDouble()) - ln(i.toDouble())
            sum += exp(logChoose + i * logP + (n - i) * logQ)
        }
        return sum.coerceIn(0.0, 1.0)
    }
}

/**
 * All the state the calculator needs: logged rolls, manual dispenser contents and settings.
 * Has no Minecraft dependencies so it can be unit tested.
 */
class GambleModel {
    private val _rolls = mutableListOf<Roll>()
    val rolls: List<Roll> get() = _rolls

    var learnFromRolls: Boolean = true
    var manualMine: List<Int>? = null
    var manualStreamer: List<Int>? = null

    fun log(roll: Roll) {
        _rolls += roll
    }

    fun undo(): Roll? = _rolls.removeLastOrNull()

    fun reset() = _rolls.clear()

    fun myDistribution(): Pair<Distribution, Source> =
        side(manualMine) { it.mine }

    fun streamerDistribution(): Pair<Distribution, Source> =
        side(manualStreamer) { it.streamer }

    private fun side(manual: List<Int>?, pick: (Roll) -> Int): Pair<Distribution, Source> {
        if (manual != null) return Distribution.fromSlots(manual) to Source.MANUAL
        if (learnFromRolls && _rolls.isNotEmpty()) {
            val counts = IntArray(9)
            for (r in _rolls) counts[pick(r) - 1]++
            return Distribution.fromCounts(counts) to Source.LEARNED
        }
        return Distribution.UNIFORM to Source.FAIR
    }

    fun analyze(multiplier: Double): Analysis {
        val (me, mySource) = myDistribution()
        val (streamer, streamerSource) = streamerDistribution()
        val win = GambleMath.winProbability(me, streamer)
        val tie = GambleMath.tieProbability(me, streamer)
        val ev = GambleMath.expectedValue(win, multiplier)
        val n = _rolls.size
        val wins = _rolls.count { it.won }
        return Analysis(
            win = win,
            lose = 1.0 - win,
            tie = tie,
            multiplier = multiplier,
            ev = ev,
            verdict = GambleMath.verdict(ev),
            mySource = mySource,
            streamerSource = streamerSource,
            rolls = n,
            wins = wins,
            losses = n - wins,
            ties = _rolls.count { it.tie },
            myAverage = if (n == 0) null else _rolls.sumOf { it.mine }.toDouble() / n,
            streamerAverage = if (n == 0) null else _rolls.sumOf { it.streamer }.toDouble() / n,
            rigging = GambleMath.riggingCheck(n, wins),
        )
    }

    companion object {
        /** Parses "1 2 3", "1,2,3" or "123456789" into slot numbers. Null if invalid. */
        fun parseSlots(input: String): List<Int>? {
            val tokens = input.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            val slots = mutableListOf<Int>()
            for (t in tokens) {
                if (!t.all { it in '1'..'9' }) return null
                t.forEach { slots += it - '0' }
            }
            return slots.takeIf { it.size in 1..9 }
        }
    }
}
