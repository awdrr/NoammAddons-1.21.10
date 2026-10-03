package com.awdrr.autoanvil;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Tabs of ON/OFF buttons: general settings, which gear to enchant, and which enchantments to use. */
public final class ConfigScreen extends Screen {
	private enum Tab {
		SETTINGS("Settings"),
		ITEMS("Items"),
		MATERIALS("Materials"),
		GENERAL("General"),
		ARMOR("Armor"),
		MELEE("Melee"),
		TOOLS("Tools"),
		RANGED("Ranged");

		final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	/** One button: its current label, what clicking does, and (for bulk buttons) how to force a value. */
	private record Entry(Supplier<Component> label, Runnable onClick, Consumer<Boolean> setter) {
	}

	private static final String[] HELP = {
			"Open an anvil with your gear and enchanted books in your inventory.",
			"Books go on one at a time, most expensive first, and it waits for XP",
			"whenever you don't have enough levels. Worn armor must be taken off."
	};

	private static Tab lastTab = Tab.SETTINGS;

	private final Screen parent;
	private final AutoAnvilConfig config = AutoAnvilConfig.get();
	private int helpY = -1;

	public ConfigScreen(Screen parent) {
		super(Component.literal("Auto Anvil Settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		Tab[] tabs = Tab.values();
		int tabWidth = 52;
		int gap = 2;
		int perRow = Math.max(1, Math.min(tabs.length, (this.width - 20) / (tabWidth + gap)));
		int y = 22;

		for (int i = 0; i < tabs.length; i += perRow) {
			int count = Math.min(perRow, tabs.length - i);
			int x = (this.width - (count * (tabWidth + gap) - gap)) / 2;

			for (int j = 0; j < count; j++) {
				Tab tab = tabs[i + j];
				Button button = Button.builder(Component.literal(tab.label), b -> {
					lastTab = tab;
					this.rebuildWidgets();
				}).bounds(x + j * (tabWidth + gap), y, tabWidth, 20).build();
				button.active = tab != lastTab;
				this.addRenderableWidget(button);
			}
			y += 20 + gap;
		}

		List<Entry> entries = entries(lastTab);
		int columns = this.width >= 470 ? 3 : 2;
		int buttonWidth = Math.min(170, (this.width - 20 - (columns - 1) * 4) / columns);
		int gridX = (this.width - (columns * buttonWidth + (columns - 1) * 4)) / 2;
		int gridY = y + 8;

		for (int i = 0; i < entries.size(); i++) {
			Entry entry = entries.get(i);
			int column = i % columns;
			int row = i / columns;
			this.addRenderableWidget(Button.builder(entry.label().get(), b -> {
				entry.onClick().run();
				config.save();
				b.setMessage(entry.label().get());
			}).bounds(gridX + column * (buttonWidth + 4), gridY + row * 22, buttonWidth, 20).build());
		}

		int rows = (entries.size() + columns - 1) / columns;
		helpY = lastTab == Tab.SETTINGS ? gridY + rows * 22 + 8 : -1;

		int bottom = this.height - 26;
		if (lastTab == Tab.SETTINGS) {
			this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
					.bounds(this.width / 2 - 75, bottom, 150, 20).build());
		} else {
			this.addRenderableWidget(Button.builder(Component.literal("All On"), b -> setAll(entries, true))
					.bounds(this.width / 2 - 154, bottom, 100, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("All Off"), b -> setAll(entries, false))
					.bounds(this.width / 2 - 50, bottom, 100, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
					.bounds(this.width / 2 + 54, bottom, 100, 20).build());
		}
	}

	private void setAll(List<Entry> entries, boolean value) {
		for (Entry entry : entries) {
			if (entry.setter() != null) entry.setter().accept(value);
		}
		config.save();
		this.rebuildWidgets();
	}

	private List<Entry> entries(Tab tab) {
		List<Entry> entries = new ArrayList<>();

		switch (tab) {
			case SETTINGS -> {
				entries.add(new Entry(() -> AutoAnvilClient.onOff("Auto Anvil", config.enabled), () -> {
					config.enabled = !config.enabled;
					AutoAnvilController.INSTANCE.reset();
				}, null));
				entries.add(new Entry(() -> Component.literal("Click delay: " + config.clickDelayTicks + (config.clickDelayTicks == 1 ? " tick" : " ticks")),
						() -> config.clickDelayTicks = config.clickDelayTicks >= AutoAnvilConfig.MAX_DELAY ? AutoAnvilConfig.MIN_DELAY : config.clickDelayTicks + 1, null));
				entries.add(new Entry(() -> AutoAnvilClient.onOff("Chat messages", config.chatMessages),
						() -> config.chatMessages = !config.chatMessages, null));
			}
			case ITEMS -> {
				for (Catalog.ItemType type : Catalog.ITEM_TYPES) {
					entries.add(toggle(type.name(), () -> config.isItemOn(type.key()), value -> config.items.put(type.key(), value)));
				}
			}
			case MATERIALS -> {
				for (Catalog.Material material : Catalog.MATERIALS) {
					entries.add(toggle(material.name(), () -> config.isMaterialOn(material.key()), value -> config.materials.put(material.key(), value)));
				}
			}
			default -> {
				Catalog.Group group = Catalog.Group.valueOf(tab.name());
				for (Catalog.Ench ench : Catalog.ENCHANTMENTS) {
					if (ench.group() != group) continue;
					entries.add(toggle(ench.name(), () -> config.isEnchantOn(ench.id()), value -> config.enchantments.put(ench.id(), value)));
				}
			}
		}

		return entries;
	}

	private static Entry toggle(String name, Supplier<Boolean> getter, Consumer<Boolean> setter) {
		return new Entry(() -> AutoAnvilClient.onOff(name, getter.get()), () -> setter.accept(!getter.get()), setter);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);
		graphics.drawString(this.font, this.title, (this.width - this.font.width(this.title)) / 2, 8, 0xFFFFFFFF, true);

		if (helpY >= 0) {
			for (int i = 0; i < HELP.length; i++) {
				String line = HELP[i];
				graphics.drawString(this.font, line, (this.width - this.font.width(line)) / 2, helpY + i * 11, 0xFFAAAAAA, true);
			}
		}
	}

	@Override
	public void onClose() {
		config.save();
		this.minecraft.setScreen(parent);
	}
}
