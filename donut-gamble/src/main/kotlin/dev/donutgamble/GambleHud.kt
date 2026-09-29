package dev.donutgamble

import net.minecraft.ChatFormatting
import net.minecraft.client.DeltaTracker
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

object GambleHud {
    fun render(graphics: GuiGraphics, @Suppress("UNUSED_PARAMETER") tracker: DeltaTracker) {
        val config = DonutGamble.config
        if (!config.showHud) return
        val mc = DonutGamble.mc
        if (mc.screen is GambleScreen) return
        val font = mc.font
        val a = DonutGamble.analysis()

        val lines = buildList<Component> {
            add(Component.literal("Gamble: ").withStyle(ChatFormatting.GOLD).append(StatsText.verdict(a)))
            add(StatsText.winLose(a))
            add(
                Component.literal("EV: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(StatsText.signedPct(a.ev)).withStyle(if (a.ev > 0) ChatFormatting.GREEN else ChatFormatting.RED))
            )
            add(StatsText.rolls(a))
            StatsText.rigging(a)?.let { add(it) }
        }

        val width = lines.maxOf { font.width(it) }
        val lineHeight = font.lineHeight + 1
        val x = config.hudX.coerceIn(0, maxOf(0, graphics.guiWidth() - width - 4))
        val y = config.hudY.coerceIn(0, maxOf(0, graphics.guiHeight() - lines.size * lineHeight - 4))

        graphics.fill(x, y, x + width + 4, y + lines.size * lineHeight + 3, 0x90000000.toInt())
        lines.forEachIndexed { i, line ->
            graphics.drawString(font, line, x + 2, y + 2 + i * lineHeight, -1, true)
        }
    }
}
