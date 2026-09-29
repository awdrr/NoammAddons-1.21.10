package dev.donutgamble

import dev.donutgamble.math.Amounts
import dev.donutgamble.math.Verdict
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class GambleScreen : Screen(Component.literal("Donut Gamble")) {
    private val config get() = DonutGamble.config

    private lateinit var streamerBox: EditBox
    private lateinit var minBox: EditBox
    private lateinit var maxBox: EditBox
    private lateinit var multiplierBox: EditBox
    private lateinit var paySmall: Button
    private lateinit var payBig: Button
    private val mineButtons = arrayOfNulls<Button>(9)
    private val streamerButtons = arrayOfNulls<Button>(9)

    private var multiplierValid = true
    private var selectedMine: Int? = null
    private var selectedStreamer: Int? = null
    private var status: Component? = null

    // Layout, computed in init().
    private var left = 0
    private var colW = 0
    private var right = 0
    private var top = 0
    private var settingsY = 0
    private var rollY = 0
    private var panelW = 0

    override fun init() {
        panelW = minOf(440, width - 16)
        left = (width - panelW) / 2
        colW = (panelW - 10) / 2
        right = left + colW + 10
        top = maxOf(4, (height - 256) / 2)
        settingsY = top + 16
        rollY = settingsY + 146

        val labelW = 72
        val fieldX = left + labelW
        val fieldW = colW - labelW

        streamerBox = textBox(fieldX, settingsY, fieldW, "Streamer", config.streamer, 16) {
            config.streamer = it.trim()
            config.save()
        }
        minBox = textBox(fieldX, settingsY + 18, fieldW, "Min amount", config.minAmount, 24) {
            config.minAmount = it.trim()
            config.save()
        }
        maxBox = textBox(fieldX, settingsY + 36, fieldW, "Max amount", config.maxAmount, 24) {
            config.maxAmount = it.trim()
            config.save()
        }
        multiplierBox = textBox(fieldX, settingsY + 54, fieldW, "Multiplier", formatMultiplier(config.multiplier), 6) {
            val m = Amounts.parseMultiplier(it)
            multiplierValid = m != null
            if (m != null) {
                config.multiplier = m
                config.save()
            }
        }

        toggle(settingsY + 74, "Learn From Rolls", { config.learnFromRolls }) { DonutGamble.setLearnFromRolls(it) }
        toggle(settingsY + 94, "Show HUD", { config.showHud }) {
            config.showHud = it
            config.save()
        }
        toggle(settingsY + 114, "Send instantly (no Enter needed)", { config.sendInstantly }) {
            config.sendInstantly = it
            config.save()
        }

        // Roll entry: pick one number in each row and the roll is logged.
        val rollLabelW = 84
        val gap = 2
        val btnW = minOf(24, (panelW - rollLabelW - gap * 8) / 9)
        for (i in 0 until 9) {
            val n = i + 1
            val x = left + rollLabelW + i * (btnW + gap)
            mineButtons[i] = addRenderableWidget(Button.builder(Component.literal("$n")) {
                selectedMine = n
                tryLogRoll()
            }.bounds(x, rollY, btnW, 18).build())
            streamerButtons[i] = addRenderableWidget(Button.builder(Component.literal("$n")) {
                selectedStreamer = n
                tryLogRoll()
            }.bounds(x, rollY + 20, btnW, 18).build())
        }

        addRenderableWidget(Button.builder(Component.literal("Undo last roll")) {
            val removed = DonutGamble.model.undo()
            status = Component.literal(
                if (removed == null) "No rolls to undo"
                else "Removed ${removed.mine} vs ${removed.streamer} (${DonutGamble.model.rolls.size} logged)"
            ).withStyle(ChatFormatting.GRAY)
        }.bounds(left, rollY + 42, 90, 18).build())

        addRenderableWidget(Button.builder(Component.literal("Reset rolls")) {
            val self = this
            minecraft?.setScreen(
                ConfirmScreen(
                    BooleanConsumer { yes ->
                        if (yes) {
                            DonutGamble.model.reset()
                            status = Component.literal("All rolls cleared").withStyle(ChatFormatting.GRAY)
                        }
                        minecraft?.setScreen(self)
                    },
                    Component.literal("Reset all logged rolls?"),
                    Component.literal("This clears ${DonutGamble.model.rolls.size} rolls and can't be undone."),
                    Component.literal("Reset"),
                    Component.literal("Cancel"),
                )
            )
        }.bounds(left + 94, rollY + 42, 80, 18).build())

        val payY = rollY + 66
        paySmall = addRenderableWidget(Button.builder(Component.empty()) {
            Amounts.parse(config.minAmount)?.let { PayAction.pay(it, this) }
        }.bounds(left, payY, colW, 24).build())
        payBig = addRenderableWidget(Button.builder(Component.empty()) {
            Amounts.parse(config.maxAmount)?.let { PayAction.pay(it, this) }
        }.bounds(right, payY, colW, 24).build())

        refresh()
    }

    private fun textBox(x: Int, y: Int, w: Int, name: String, value: String, maxLength: Int, onChange: (String) -> Unit): EditBox {
        val box = EditBox(font, x, y, w, 14, Component.literal(name))
        box.setMaxLength(maxLength)
        box.value = value
        box.setResponder {
            onChange(it)
            refresh()
        }
        return addRenderableWidget(box)
    }

    private fun toggle(y: Int, name: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        fun label() = Component.literal("$name: ").append(
            if (get()) Component.literal("ON").withStyle(ChatFormatting.GREEN)
            else Component.literal("OFF").withStyle(ChatFormatting.RED)
        )
        addRenderableWidget(Button.builder(label()) { button ->
            set(!get())
            button.message = label()
        }.bounds(left, y, colW, 18).build())
    }

    private fun tryLogRoll() {
        val mine = selectedMine
        val streamer = selectedStreamer
        if (mine != null && streamer != null) {
            val won = mine > streamer
            status = Component.literal(DonutGamble.logRoll(mine, streamer))
                .withStyle(if (won) ChatFormatting.GREEN else ChatFormatting.RED)
            selectedMine = null
            selectedStreamer = null
        }
    }

    private fun error(): String? {
        DonutGamble.settingsError()?.let { return it }
        if (!multiplierValid) return "Multiplier must be a number from 1.0 to 5.0"
        return null
    }

    private fun refresh() {
        val ok = error() == null
        paySmall.active = ok
        payBig.active = ok
        val min = Amounts.parse(config.minAmount)
        val max = Amounts.parse(config.maxAmount)
        paySmall.message = Component.literal("PAY SMALL (${min?.let(Amounts::format) ?: "?"})")
        payBig.message = Component.literal("PAY BIG (${max?.let(Amounts::format) ?: "?"})")
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        val a = DonutGamble.analysis()
        val white = -1
        val gray = 0xFFAAAAAA.toInt()

        graphics.drawCenteredString(font, Component.literal("Donut Gamble").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), width / 2, top, white)

        // Settings labels.
        val labels = listOf("Streamer", "Min (small)", "Max (big)", "Multiplier")
        labels.forEachIndexed { i, label ->
            graphics.drawString(font, label, left, settingsY + 3 + i * 18, gray, true)
        }
        error()?.let {
            drawWrapped(graphics, Component.literal(it).withStyle(ChatFormatting.RED), left, settingsY + 134 - 1, colW, maxLines = 1)
        }

        // Stats.
        var y = settingsY
        graphics.drawString(font, Component.literal("Verdict: ").withStyle(ChatFormatting.GRAY).append(StatsText.verdict(a)), right, y, white, true)
        y += 13
        val stats = buildList<Component> {
            add(StatsText.winLose(a))
            add(StatsText.avgProfit(a))
            add(StatsText.perBet("At min", Amounts.parse(config.minAmount), a))
            add(StatsText.perBet("At max", Amounts.parse(config.maxAmount), a))
            add(StatsText.rolls(a))
            add(StatsText.averages(a))
            add(StatsText.sources(a))
            StatsText.rigging(a)?.let { add(it) }
        }
        for (line in stats) y = drawWrapped(graphics, line, right, y, colW)
        if (a.rigging?.rigged == true) {
            drawWrapped(graphics, Component.literal("You're losing far more than a fair machine allows.").withStyle(ChatFormatting.RED), right, y, colW)
        }

        // Roll entry.
        graphics.drawString(font, "My roll", left, rollY + 5, gray, true)
        graphics.drawString(font, "Streamer's roll", left, rollY + 25, gray, true)
        selectedMine?.let { outline(graphics, mineButtons[it - 1]!!, 0xFFFFFF55.toInt()) }
        selectedStreamer?.let { outline(graphics, streamerButtons[it - 1]!!, 0xFFFFFF55.toInt()) }
        status?.let { drawWrapped(graphics, it, left + 180, rollY + 47, panelW - 180, maxLines = 1) }

        // Recommended pay button in green, the other in red.
        val green = 0xFF55FF55.toInt()
        val red = 0xFFFF5555.toInt()
        val bigRecommended = a.verdict == Verdict.PAY_BIG
        outline(graphics, payBig, if (bigRecommended) green else red)
        outline(graphics, paySmall, if (bigRecommended) red else green)
    }

    /** Draws [text] wrapped to [maxWidth] and returns the y below it. */
    private fun drawWrapped(graphics: GuiGraphics, text: Component, x: Int, y: Int, maxWidth: Int, maxLines: Int = 3): Int {
        var cy = y
        for (line in font.split(text, maxWidth).take(maxLines)) {
            graphics.drawString(font, line, x, cy, -1, true)
            cy += font.lineHeight + 2
        }
        return cy
    }

    private fun outline(graphics: GuiGraphics, w: AbstractWidget, color: Int) {
        val x0 = w.getX() - 1
        val y0 = w.getY() - 1
        val x1 = w.getX() + w.getWidth() + 1
        val y1 = w.getY() + w.getHeight() + 1
        graphics.fill(x0, y0, x1, y0 + 1, color)
        graphics.fill(x0, y1 - 1, x1, y1, color)
        graphics.fill(x0, y0, x0 + 1, y1, color)
        graphics.fill(x1 - 1, y0, x1, y1, color)
    }

    override fun isPauseScreen() = false

    private fun formatMultiplier(m: Double) = if (m == Math.floor(m)) "%.1f".format(m) else m.toString()
}
