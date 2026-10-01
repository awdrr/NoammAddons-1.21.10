package com.github.noamm9.features.impl.misc

import com.github.noamm9.config.PogObject
import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.components.getValue
import com.github.noamm9.ui.clickgui.components.impl.ButtonSetting
import com.github.noamm9.ui.clickgui.components.impl.TextInputSetting
import com.github.noamm9.ui.clickgui.components.impl.ToggleSetting
import com.github.noamm9.ui.clickgui.components.provideDelegate
import com.github.noamm9.ui.clickgui.components.showIf
import com.github.noamm9.ui.clickgui.components.withDescription
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.ThreadUtils
import com.github.noamm9.utils.network.WebUtils
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

object TriviaSolver: Feature("Instantly answers multiple choice chat games using answers learned from previous rounds.") {
    private val answers = PogObject("trivia_answers", mutableMapOf<String, String>())

    private val autoAnswer by ToggleSetting("Auto Answer", true)
        .withDescription("Instantly types the answer in chat. When off, the answer is only shown to you.")

    private val showAnswer by ToggleSetting("Show Answer", true)
        .withDescription("Shows the answer, or that the question is new, in your chat.")

    private val aiAnswers by ToggleSetting("AI Answers")
        .withDescription("Asks Claude to answer questions that haven't been learned yet. Needs an Anthropic API key.")

    private val apiKey by TextInputSetting("API Key", "")
        .withDescription("Your Anthropic API key from console.anthropic.com. It is saved in plain text in your NoammAddons config.")
        .showIf { aiAnswers.value }

    private val clearAnswers by ButtonSetting("Clear Learned Answers") {
        synchronized(answers) {
            answers.getData().clear()
            answers.save()
        }
        ChatUtils.modMessage("§aCleared all learned trivia answers.")
    }

    private const val CLAUDE_URL = "https://api.anthropic.com/v1/messages"
    private const val CLAUDE_SYSTEM = "You answer multiple choice trivia questions from a Minecraft server chat game. Reply with only the letter of the correct option."

    private val optionRegex = Regex("^([A-Za-z])[.)]\\s+(.+)$")
    private val revealRegex = Regex("^(?:the )?(?:correct )?answer(?::| was:?| is:?)\\s*(.+?)[.!]?$", RegexOption.IGNORE_CASE)
    private val solvedRegex = Regex("(?:chose|answered) correctly", RegexOption.IGNORE_CASE)
    private val letterRegex = Regex("\\b([A-Z])\\b")
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
                if (aiAnswers.value && apiKey.value.isNotBlank()) askClaude(r)
                else if (showAnswer.value) ChatUtils.modMessage("§7New trivia question, the answer will be learned once it's revealed.")
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

        if (solvedRegex.containsMatchIn(line)) {
            r.solved = true
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

    private fun answer(r: Round, letter: Char, text: String, fromAi: Boolean = false) {
        r.answered = true
        if (autoAnswer.value) mc.player?.connection?.sendChat(letter.lowercase())
        if (showAnswer.value) ChatUtils.modMessage("${if (fromAi) "§bAI answer" else "§aTrivia answer"}: §f$letter. $text")
    }

    private fun askClaude(r: Round) {
        val options = r.options.toMap()
        val prompt = buildString {
            appendLine(r.question)
            options.forEach { (letter, text) -> appendLine("$letter. $text") }
        }

        val body = buildJsonObject {
            put("model", "claude-opus-5-5")
            put("max_tokens", 4096)
            put("fallbacks", "default")
            putJsonObject("output_config") { put("effort", "low") }
            put("system", CLAUDE_SYSTEM)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            }
        }

        val headers = mapOf(
            "x-api-key" to apiKey.value.trim(),
            "anthropic-version" to "2023-06-01",
            "anthropic-beta" to "server-side-fallback-2026-07-01"
        )

        scope.launch {
            val result = WebUtils.post(CLAUDE_URL, body, headers).mapCatching { parseLetter(it, options.keys) }

            ThreadUtils.runOnMcThread {
                result.onSuccess { letter ->
                    // skip if the round already ended or someone beat us to it while waiting for the reply
                    if (enabled && round === r && ! r.answered && ! r.solved) answer(r, letter, options.getValue(letter), true)
                }.onFailure {
                    if (showAnswer.value) ChatUtils.modMessage("§cAI answer failed: ${it.message?.take(150)}")
                }
            }
        }
    }

    private fun parseLetter(response: String, letters: Set<Char>): Char {
        val json = Json.parseToJsonElement(response).jsonObject
        if (json["stop_reason"]?.jsonPrimitive?.content == "refusal") error("Claude declined to answer")

        val text = json["content"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "text" }
            .joinToString("") { it["text"]?.jsonPrimitive?.content.orEmpty() }

        return letterRegex.findAll(text).map { it.groupValues[1][0] }.firstOrNull { it in letters }
            ?: error("Unexpected reply: ${text.take(50)}")
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
        var solved = false

        val isExpired get() = System.currentTimeMillis() - startedAt > 120_000

        // all options are in and none of them matched a learned answer
        val isUnknown get() = ! answered && ! announced && options.size >= 2 && System.currentTimeMillis() - lastUpdate >= 150
    }
}
