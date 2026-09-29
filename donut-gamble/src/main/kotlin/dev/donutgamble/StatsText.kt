package dev.donutgamble

import dev.donutgamble.math.Amounts
import dev.donutgamble.math.Analysis
import dev.donutgamble.math.Source
import dev.donutgamble.math.Verdict
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

/** Text lines shared by the GUI and the HUD. */
object StatsText {
    fun pct(x: Double) = "%.1f%%".format(x * 100)

    fun signedPct(x: Double) = (if (x >= 0) "+" else "") + pct(x)

    fun verdict(a: Analysis): MutableComponent =
        if (a.verdict == Verdict.PAY_BIG) Component.literal("PAY BIG").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
        else Component.literal("PAY SMALL").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)

    fun winLose(a: Analysis): Component =
        Component.literal("Win ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(pct(a.win)).withStyle(ChatFormatting.GREEN))
            .append(Component.literal(" / Lose ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(pct(a.lose)).withStyle(ChatFormatting.RED))
            .append(Component.literal(" (ties ${pct(a.tie)})").withStyle(ChatFormatting.DARK_GRAY))

    fun avgProfit(a: Analysis): Component =
        Component.literal("Avg profit per bet: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(signedPct(a.ev)).withStyle(if (a.ev > 0) ChatFormatting.GREEN else ChatFormatting.RED))

    fun perBet(label: String, amount: Long?, a: Analysis): Component {
        val base = Component.literal("$label: ").withStyle(ChatFormatting.GRAY)
        if (amount == null) return base.append(Component.literal("-").withStyle(ChatFormatting.DARK_GRAY))
        val profit = a.expectedProfit(amount)
        return base
            .append(Component.literal("${Amounts.format(amount)} bet -> ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("${Amounts.formatSigned(profit)} expected").withStyle(if (profit > 0) ChatFormatting.GREEN else ChatFormatting.RED))
    }

    fun rolls(a: Analysis): Component =
        Component.literal("Rolls: ${a.rolls} (").withStyle(ChatFormatting.GRAY)
            .append(Component.literal("W ${a.wins}").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(" / ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("L ${a.losses}").withStyle(ChatFormatting.RED))
            .append(Component.literal(")").withStyle(ChatFormatting.GRAY))

    fun averages(a: Analysis): Component {
        val mine = a.myAverage?.let { "%.2f".format(it) } ?: "-"
        val theirs = a.streamerAverage?.let { "%.2f".format(it) } ?: "-"
        return Component.literal("Avg roll: you $mine vs streamer $theirs (fair 5.00)").withStyle(ChatFormatting.GRAY)
    }

    fun sources(a: Analysis): Component =
        Component.literal("Odds from: you ${name(a.mySource)}, streamer ${name(a.streamerSource)}").withStyle(ChatFormatting.DARK_GRAY)

    private fun name(s: Source) = when (s) {
        Source.FAIR -> "fair 1-9"
        Source.LEARNED -> "rolls"
        Source.MANUAL -> "manual"
    }

    /** Null unless the rigging check has fired. */
    fun rigging(a: Analysis): Component? {
        val r = a.rigging?.takeIf { it.rigged } ?: return null
        return Component.literal("⚠ RIGGED? You win ${pct(r.observedRate)} vs fair 44.4% (z=${"%.1f".format(r.z)}, p=${"%.3f".format(r.pValue)})")
            .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
    }
}
