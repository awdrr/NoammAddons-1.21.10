package com.awdrr.autoanvil;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class AutoAnvilClient implements ClientModInitializer {
	public static final String MOD_ID = "autoanvil";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final int ANVIL_GUI_WIDTH = 176;
	public static final int ANVIL_GUI_HEIGHT = 166;

	private static KeyMapping toggleKey;
	private static KeyMapping configKey;
	private static boolean openConfigNextTick;

	@Override
	public void onInitializeClient() {
		AutoAnvilConfig.get();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.autoanvil.toggle", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), category));
		configKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.autoanvil.config", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleKey.consumeClick()) toggle();
			while (configKey.consumeClick()) client.setScreen(new ConfigScreen(client.screen));

			// Commands run from the chat screen, which closes right after, so open the GUI a tick later.
			if (openConfigNextTick) {
				openConfigNextTick = false;
				client.setScreen(new ConfigScreen(null));
			}

			AutoAnvilController.INSTANCE.tick(client);
		});

		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!(screen instanceof AnvilScreen anvil)) return;

			int left = (scaledWidth - ANVIL_GUI_WIDTH) / 2;
			int top = (scaledHeight - ANVIL_GUI_HEIGHT) / 2;
			Screens.getButtons(screen).add(Button.builder(toggleLabel(), button -> {
				toggle();
				button.setMessage(toggleLabel());
			}).bounds(left, top - 22, 110, 20).build());

			ScreenEvents.afterRender(screen).register((s, graphics, mouseX, mouseY, delta) ->
					AutoAnvilController.INSTANCE.renderStatus(anvil, graphics));
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommandManager.literal("autoanvil")
						.executes(context -> {
							openConfigNextTick = true;
							return 1;
						})
						.then(ClientCommandManager.literal("toggle").executes(context -> {
							toggle();
							return 1;
						}))
		));
	}

	private static void toggle() {
		AutoAnvilConfig config = AutoAnvilConfig.get();
		config.enabled = !config.enabled;
		config.save();
		AutoAnvilController.INSTANCE.reset();

		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.displayClientMessage(Component.literal("[Auto Anvil] ").withStyle(ChatFormatting.AQUA)
					.append(config.enabled
							? Component.literal("Enabled").withStyle(ChatFormatting.GREEN)
							: Component.literal("Disabled").withStyle(ChatFormatting.RED)), false);
		}
	}

	static Component onOff(String label, boolean on) {
		return Component.literal(label + ": ").append(on
				? Component.literal("ON").withStyle(ChatFormatting.GREEN)
				: Component.literal("OFF").withStyle(ChatFormatting.RED));
	}

	private static Component toggleLabel() {
		return onOff("Auto Anvil", AutoAnvilConfig.get().enabled);
	}
}
