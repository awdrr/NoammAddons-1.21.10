package com.github.noamm9.features.impl.misc

import com.github.noamm9.config.PogObject
import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.components.getValue
import com.github.noamm9.ui.clickgui.components.impl.ButtonSetting
import com.github.noamm9.ui.clickgui.components.impl.SliderSetting
import com.github.noamm9.ui.clickgui.components.impl.ToggleSetting
import com.github.noamm9.ui.clickgui.components.provideDelegate
import com.github.noamm9.ui.clickgui.components.showIf
import com.github.noamm9.ui.clickgui.components.withDescription
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.ThreadUtils
import java.util.concurrent.ScheduledFuture
import kotlin.random.Random

object TriviaSolver: Feature("Answers multiple choice chat games using answers learned from previous rounds.") {
    private val answers = PogObject("trivia_answers", mutableMapOf<String, String>())

    private val autoAnswer by ToggleSetting("Auto Answer", true)
        .withDescription("Types the answer in chat. When off, the answer is only shown to you.")

    private val minDelay by SliderSetting("Min Delay (ms)", 1500, 0, 10000, 100)
        .withDescription("Shortest wait before typing the answer.")
        .showIf { autoAnswer.value }

    private val maxDelay by SliderSetting("Max Delay (ms)", 3000, 0, 10000, 100)
        .withDescription("Longest wait before typing the answer. A random delay between min and max is used.")
        .showIf { autoAnswer.value }

    private val showAnswer by ToggleSetting("Show Answer", true)
        .withDescription("Shows the answer, or that the question is new, in your chat.")

    private val clearAnswers by ButtonSetting("Clear Learned Answers") {
        synchronized(answers) {
            answers.getData().clear()
            answers.save()
        }
        ChatUtils.modMessage("§aCleared all learned trivia answers.")
    }

    private val optionRegex = Regex("^([A-Za-z])[.)]\\s+(.+)$")
    private val revealRegex = Regex("^(?:the )?(?:correct )?answer(?::| was:?| is:?)\\s*(.+?)[.!]?$", RegexOption.IGNORE_CASE)
    private val solvedRegex = Regex("(?:chose|answered) correctly", RegexOption.IGNORE_CASE)
    private val symbolRegex = Regex("[^\\p{L}\\p{N} ]")
    private val spaceRegex = Regex("\\s+")

    private var round: Round? = null

    override fun init() {
        register<ChatMessageEvent> {
            val lines = event.unformattedText.split('\n')
            ThreadUtils.runOnMcThread { lines.forEach(::handleLine) }
        }

        register<TickEvent.Start> {
            val r = round ?: return@register
            if (r.isExpired) return@register reset()
            if (r.isReadyToAnswer) answer(r)
        }
    }

    override fun onDisable() {
        super.onDisable()
        reset()
    }

    private fun handleLine(raw: String) {
        val line = raw.trim()
        if (line.isEmpty()) return

        if (line.equals("MULTIPLE CHOICE", true)) {
            reset()
            round = Round()
            return
        }

        val r = round ?: return

        revealRegex.matchEntire(line)?.let {
            learn(r, it.groupValues[1].trim())
            reset()
            return
        }

        if (solvedRegex.containsMatchIn(line)) {
            r.solved = true
            r.pending?.cancel(false)
            return
        }

        if (r.question == null) {
            // skip the "Type the correct letter!" instruction line
            if (! line.startsWith("Type the", true)) r.question = line
            return
        }

        optionRegex.matchEntire(line)?.let {
            r.options[it.groupValues[1].uppercase()[0]] = it.groupValues[2].trim()
            r.lastUpdate = System.currentTimeMillis()
        }
    }

    private fun answer(r: Round) {
        r.handled = true
        val question = r.question ?: return
        val known = synchronized(answers) { answers.getData()[normalize(question)] }
        val letter = known?.let { answer -> r.options.entries.find { normalize(it.value) == normalize(answer) }?.key }

        if (letter == null) {
            if (showAnswer.value) ChatUtils.modMessage("§7New trivia question, the answer will be learned once it's revealed.")
            return
        }

        if (showAnswer.value) ChatUtils.modMessage("§aTrivia answer: §f$letter. ${r.options[letter]}")
        if (! autoAnswer.value) return

        val min = minOf(minDelay.value, maxDelay.value).toLong()
        val max = maxOf(minDelay.value, maxDelay.value).toLong()
        r.pending = ThreadUtils.setTimeout(Random.nextLong(min, max + 1)) {
            ThreadUtils.runOnMcThread {
                if (enabled && round === r && ! r.solved) ChatUtils.sendMessage(letter.lowercase())
            }
        }
    }

    private fun learn(r: Round, revealed: String) {
        val question = r.question ?: return
        val answer = optionRegex.matchEntire(revealed)?.groupValues?.get(2)
            ?: revealed.singleOrNull()?.let { r.options[it.uppercaseChar()] }
            ?: revealed.takeIf { it.length > 1 }
            ?: return

        synchronized(answers) {
            answers.getData()[normalize(question)] = answer
            answers.save()
        }
    }

    private fun reset() {
        round?.pending?.cancel(false)
        round = null
    }

    private fun normalize(text: String) = text.lowercase().replace(symbolRegex, "").replace(spaceRegex, " ").trim()

    private class Round {
        val startedAt = System.currentTimeMillis()
        var lastUpdate = startedAt
        var question: String? = null
        val options = linkedMapOf<Char, String>()
        var handled = false
        var solved = false
        var pending: ScheduledFuture<*>? = null

        val isExpired get() = System.currentTimeMillis() - startedAt > 120_000
        val isReadyToAnswer get() = ! handled && question != null && options.size >= 2 && System.currentTimeMillis() - lastUpdate >= 150
    }
}
