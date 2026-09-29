package dev.donutgamble

import com.mojang.blaze3d.platform.InputConstants
import dev.donutgamble.math.Amounts
import dev.donutgamble.math.GambleModel
import dev.donutgamble.math.Roll
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object DonutGamble : ClientModInitializer {
    const val MOD_ID = "donutgamble"
    val LOGGER: Logger = LoggerFactory.getLogger("Donut Gamble")

    lateinit var config: GambleConfig
        private set

    /** Rolls live only for this game session. */
    val model = GambleModel()

    private lateinit var openKey: KeyMapping
    private var openNextTick = false

    override fun onInitializeClient() {
        config = GambleConfig.load()
        model.learnFromRolls = config.learnFromRolls

        val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"))
        openKey = KeyBindingHelper.registerKeyBinding(
            KeyMapping("key.$MOD_ID.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, category)
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (openKey.consumeClick()) openNextTick = true
            if (openNextTick) {
                openNextTick = false
                client.setScreen(GambleScreen())
            }
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ -> GambleCommands.register(dispatcher) }

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "hud"), GambleHud::render)
    }

    /** Opens the GUI on the next tick, after the chat screen that ran /gamble has closed. */
    fun requestOpen() {
        openNextTick = true
    }

    fun analysis() = model.analyze(config.multiplier)

    /** Logs a roll and returns the one-line summary, e.g. "Roll 7 vs 3: You win (12 logged)". */
    fun logRoll(mine: Int, streamer: Int): String {
        val roll = Roll(mine, streamer)
        model.log(roll)
        val result = when {
            roll.won -> "You win"
            roll.tie -> "Tie - you lose"
            else -> "You lose"
        }
        return "Roll $mine vs $streamer: $result (${model.rolls.size} logged)"
    }

    fun setLearnFromRolls(on: Boolean) {
        config.learnFromRolls = on
        model.learnFromRolls = on
        config.save()
    }

    /** Null when the settings are valid, otherwise the error to show. */
    fun settingsError(): String? {
        if (!Amounts.isValidUsername(config.streamer)) return "Streamer name must be 3-16 letters, numbers or _"
        val min = Amounts.parse(config.minAmount) ?: return "Min amount must be a positive number (e.g. 500k)"
        val max = Amounts.parse(config.maxAmount) ?: return "Max amount must be a positive number (e.g. 5m)"
        if (min > max) return "Min amount is greater than max amount"
        return null
    }

    val mc: Minecraft get() = Minecraft.getInstance()
}
