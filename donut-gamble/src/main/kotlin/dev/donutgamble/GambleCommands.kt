package dev.donutgamble

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import dev.donutgamble.math.GambleModel
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component

object GambleCommands {
    private const val OK = 1

    fun register(dispatcher: CommandDispatcher<FabricClientCommandSource>) {
        dispatcher.register(
            literal("gamble")
                .executes {
                    DonutGamble.requestOpen()
                    OK
                }
                .then(literal("undo").executes { ctx ->
                    val removed = DonutGamble.model.undo()
                    if (removed == null) ctx.error("No rolls to undo")
                    else ctx.reply("Removed roll ${removed.mine} vs ${removed.streamer} (${DonutGamble.model.rolls.size} logged)")
                    OK
                })
                .then(literal("reset").executes { ctx ->
                    DonutGamble.model.reset()
                    ctx.reply("All rolls cleared")
                    OK
                })
                .then(
                    literal("set")
                        .then(setSide("me", mine = true))
                        .then(setSide("streamer", mine = false))
                )
                .then(
                    argument("mine", IntegerArgumentType.integer(1, 9))
                        .then(argument("streamer", IntegerArgumentType.integer(1, 9)).executes { ctx ->
                            val mine = IntegerArgumentType.getInteger(ctx, "mine")
                            val streamer = IntegerArgumentType.getInteger(ctx, "streamer")
                            ctx.reply(DonutGamble.logRoll(mine, streamer))
                            OK
                        })
                )
        )
    }

    private fun setSide(name: String, mine: Boolean) =
        literal(name).then(argument("numbers", StringArgumentType.greedyString()).executes { ctx ->
            val input = StringArgumentType.getString(ctx, "numbers").trim()
            val who = if (mine) "Your" else "Streamer's"
            if (input.equals("clear", true) || input.equals("fair", true)) {
                if (mine) DonutGamble.model.manualMine = null else DonutGamble.model.manualStreamer = null
                ctx.reply("$who dispenser: manual contents cleared")
                return@executes OK
            }
            val slots = GambleModel.parseSlots(input)
            if (slots == null) {
                ctx.error("Give 1-9 numbers from 1 to 9, e.g. /gamble set $name 1 2 3 4 5 6 7 8 9 (or 'clear')")
                return@executes 0
            }
            if (mine) DonutGamble.model.manualMine = slots else DonutGamble.model.manualStreamer = slots
            val a = DonutGamble.analysis()
            ctx.reply("$who dispenser set to ${slots.joinToString(" ")}. Win ${"%.1f".format(a.win * 100)}%")
            OK
        })

    private fun CommandContext<FabricClientCommandSource>.reply(text: String) =
        source.sendFeedback(Component.literal("[Gamble] ").withStyle(ChatFormatting.GOLD).append(Component.literal(text).withStyle(ChatFormatting.WHITE)))

    private fun CommandContext<FabricClientCommandSource>.error(text: String) =
        source.sendError(Component.literal("[Gamble] $text"))
}
