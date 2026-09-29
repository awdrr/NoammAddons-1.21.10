package dev.donutgamble

import dev.donutgamble.math.Amounts
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

object PayAction {
    /**
     * Either opens chat pre-filled with "/pay <streamer> <amount>" (default), or, with
     * "Send instantly" on, asks for confirmation and sends it. At most one /pay per click.
     */
    fun pay(amount: Long, returnTo: Screen) {
        val config = DonutGamble.config
        val command = "pay ${config.streamer} $amount"
        val mc = DonutGamble.mc

        if (!config.sendInstantly) {
            mc.setScreen(openChat("/$command"))
            return
        }

        var sent = false
        mc.setScreen(
            ConfirmScreen(
                BooleanConsumer { confirmed ->
                    if (confirmed && !sent) {
                        sent = true
                        mc.setScreen(null)
                        mc.player?.connection?.sendCommand(command)
                    } else if (!confirmed) {
                        mc.setScreen(returnTo)
                    }
                },
                Component.literal("Pay ${config.streamer} ${Amounts.format(amount)}?"),
                Component.literal("This sends /$command right away."),
                Component.literal("Confirm"),
                Component.literal("Cancel"),
            )
        )
    }

    /**
     * ChatScreen's constructor gained extra parameters over recent versions (e.g. a draft flag),
     * so pick whichever constructor starts with the initial text and fill the rest with defaults.
     */
    private fun openChat(text: String): Screen {
        val ctor = ChatScreen::class.java.constructors
            .filter { it.parameterTypes.firstOrNull() == String::class.java }
            .minByOrNull { it.parameterCount }
            ?: error("No ChatScreen(String, ...) constructor found")
        val args = arrayOfNulls<Any>(ctor.parameterCount)
        args[0] = text
        for (i in 1 until ctor.parameterCount) {
            args[i] = when (ctor.parameterTypes[i]) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                else -> null
            }
        }
        return ctor.newInstance(*args) as Screen
    }
}
