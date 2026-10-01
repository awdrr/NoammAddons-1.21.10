package com.github.awdrr.villagermacro

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

object VillagerMacroMod: ClientModInitializer {
    private val mc get() = Minecraft.getInstance()

    private val toggleKey = KeyMapping("key.villagermacro.toggle", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.value, KeyMapping.Category.MISC)
    private var toggleWasDown = false
    private var escapeWasDown = false

    override fun onInitializeClient() {
        MacroConfig.load()
        KeyBindingHelper.registerKeyBinding(toggleKey)

        ClientTickEvents.START_CLIENT_TICK.register {
            pollKeys()
            TradeMacro.onTick()
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> TradeMacro.stop(null) }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                literal("villagermacro")
                    .executes { TradeMacro.toggle(); 1 }
                    .then(literal("start").executes { TradeMacro.start(); 1 })
                    .then(literal("stop").executes { TradeMacro.stop("§cStopped."); 1 })
                    .then(literal("settings").executes { showSettings(it.source); 1 })
                    .then(literal("reach").then(argument("blocks", DoubleArgumentType.doubleArg(3.0, 5.5)).executes {
                        MacroConfig.reach = DoubleArgumentType.getDouble(it, "blocks")
                        saved(it)
                    }))
                    .then(literal("delay").then(argument("ticks", IntegerArgumentType.integer(1, 10)).executes {
                        MacroConfig.clickDelay = IntegerArgumentType.getInteger(it, "ticks")
                        saved(it)
                    }))
                    .then(literal("rotation").then(argument("ms", IntegerArgumentType.integer(0, 500)).executes {
                        MacroConfig.rotationTime = IntegerArgumentType.getInteger(it, "ms")
                        saved(it)
                    }))
                    .then(literal("restock").then(argument("seconds", IntegerArgumentType.integer(10, 600)).executes {
                        MacroConfig.restockDelay = IntegerArgumentType.getInteger(it, "seconds")
                        saved(it)
                    }))
                    .then(literal("command").then(argument("command", StringArgumentType.greedyString()).executes {
                        MacroConfig.stringCommand = StringArgumentType.getString(it, "command").removePrefix("/").trim().ifEmpty { "string" }
                        saved(it)
                    }))
                    .then(literal("craftat").then(argument("slots", IntegerArgumentType.integer(0, 36)).executes {
                        MacroConfig.craftAtFreeSlots = IntegerArgumentType.getInteger(it, "slots")
                        saved(it)
                    }))
            )
        }
    }

    fun chat(message: String) {
        mc.gui.chat.addMessage(Component.literal("§6[Villager Macro]§r $message"))
    }

    /** Polled instead of using key events so the key also works while the macro has a trade/crafting/chest menu open. */
    private fun pollKeys() {
        while (toggleKey.consumeClick()) Unit

        val screen = mc.screen
        val toggleDown = isDown(KeyBindingHelper.getBoundKeyOf(toggleKey))
        if (toggleDown && ! toggleWasDown && (screen == null || screen is AbstractContainerScreen<*>)) TradeMacro.toggle()
        toggleWasDown = toggleDown

        val escapeDown = InputConstants.isKeyDown(mc.window, GLFW.GLFW_KEY_ESCAPE)
        if (escapeDown && ! escapeWasDown) TradeMacro.stop("§cStopped.")
        escapeWasDown = escapeDown
    }

    private fun isDown(key: InputConstants.Key): Boolean {
        if (key == InputConstants.UNKNOWN) return false
        return if (key.type == InputConstants.Type.MOUSE) GLFW.glfwGetMouseButton(mc.window.handle(), key.value) == GLFW.GLFW_PRESS
        else InputConstants.isKeyDown(mc.window, key.value)
    }

    private fun saved(ctx: CommandContext<FabricClientCommandSource>): Int {
        MacroConfig.save()
        showSettings(ctx.source)
        return 1
    }

    private fun showSettings(source: FabricClientCommandSource) {
        source.sendFeedback(Component.literal(
            "§6[Villager Macro]§r §7Reach: §f${MacroConfig.reach} §7| Click delay: §f${MacroConfig.clickDelay} ticks" +
                " §7| Rotation: §f${MacroConfig.rotationTime} ms §7| Restock check: §f${MacroConfig.restockDelay} s" +
                " §7| String command: §f/${MacroConfig.stringCommand}" +
                " §7| Craft at: §f${MacroConfig.craftAtFreeSlots} free slots"
        ))
    }
}
