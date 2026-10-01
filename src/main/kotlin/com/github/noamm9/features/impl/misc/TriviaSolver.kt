package com.github.noamm9.features.impl.misc

import com.github.noamm9.config.PogObject
import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.components.getValue
import com.github.noamm9.ui.clickgui.components.impl.ButtonSetting
import com.github.noamm9.ui.clickgui.components.impl.ToggleSetting
import com.github.noamm9.ui.clickgui.components.provideDelegate
import com.github.noamm9.ui.clickgui.components.withDescription
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.ThreadUtils

object TriviaSolver: Feature("Instantly answers multiple choice chat games using answers learned from previous rounds.") {
    private val answers = PogObject("trivia_answers", mutableMapOf<String, String>())

    private val autoAnswer by ToggleSetting("Auto Answer", true)
        .withDescription("Instantly types the answer in chat. When off, the answer is only shown to you.")

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
            if (r.isUnknown) {
                r.announced = true
                if (showAnswer.value) ChatUtils.modMessage("§7New trivia question, the answer will be learned once it's revealed.")
            }
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
            round = Round()
            return
        }

        val r = round ?: return

        revealRegex.matchEntire(line)?.let {
            learn(r, it.groupValues[1].trim())
            reset()
            return
        }

        if (r.question == null) {
            // skip the "Type the correct letter!" instruction line
            if (line.startsWith("Type the", true)) return
            r.question = line
            r.known = synchronized(answers) { answers.getData()[normalize(line)] }?.let(::normalize)
            return
        }

        optionRegex.matchEntire(line)?.let {
            val letter = it.groupValues[1].uppercase()[0]
            val text = it.groupValues[2].trim()
            r.options[letter] = text
            r.lastUpdate = System.currentTimeMillis()

            // answer the moment the learned answer shows up, no need to wait for the remaining options
            if (! r.answered && r.known != null && normalize(text) == r.known) answer(r, letter, text)
        }
    }

    private fun answer(r: Round, letter: Char, text: String) {
        r.answered = true
        if (autoAnswer.value) mc.player?.connection?.sendChat(letter.lowercase())
        if (showAnswer.value) ChatUtils.modMessage("§aTrivia answer: §f$letter. $text")
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
        round = null
    }

    private fun normalize(text: String) = text.lowercase().replace(symbolRegex, "").replace(spaceRegex, " ").trim()

    private class Round {
        val startedAt = System.currentTimeMillis()
        var lastUpdate = startedAt
        var question: String? = null
        var known: String? = null
        val options = linkedMapOf<Char, String>()
        var answered = false
        var announced = false

        val isExpired get() = System.currentTimeMillis() - startedAt > 120_000

        // all options are in and none of them matched a learned answer
        val isUnknown get() = ! answered && ! announced && options.size >= 2 && System.currentTimeMillis() - lastUpdate >= 150
    }
}
