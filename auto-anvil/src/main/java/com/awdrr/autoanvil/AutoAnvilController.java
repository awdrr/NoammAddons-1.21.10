package com.awdrr.autoanvil;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Drives the anvil one click at a time: put a piece of gear in the left slot, put the best book in
 * the right slot, wait until the player has enough levels, take the result and put it back in the
 * left slot, repeat. Every step re-reads the real slot contents, so a server correction or the
 * player moving things around just changes what the next step does.
 */
public final class AutoAnvilController {
	public static final AutoAnvilController INSTANCE = new AutoAnvilController();

	private static final int LEFT = 0;
	private static final int RIGHT = 1;
	private static final int RESULT = 2;

	/** Ticks to let the server answer after a book goes in, before trusting the result slot. */
	private static final int SETTLE_TICKS = 2;
	/** If no result shows up after this many ticks, the server refused the combination. */
	private static final int RESULT_TIMEOUT_TICKS = 40;
	/** Shift-clicking the same slot this many times in a row means the inventory is full. */
	private static final int MAX_QUICK_MOVE_RETRIES = 4;

	private record Candidate(int slot, ItemStack stack, int cost) {
	}

	private int containerId = -1;
	private int cooldown;
	private int settle;
	private int waitTicks;
	private int pendingPlace = -1;
	private int lastQuickMoveSlot = -1;
	private int quickMoveRepeats;
	private int applied;
	private boolean ownsLeft;
	private boolean ownsRight;
	private boolean halted;
	private boolean announcedWaiting;
	private boolean announcedDone;
	private String pendingMessage;
	private String status = "";
	private final Set<String> failed = new HashSet<>();
	private final Set<String> reported = new HashSet<>();

	private AutoAnvilController() {
	}

	public void reset() {
		containerId = -1;
		cooldown = 0;
		settle = 0;
		waitTicks = 0;
		pendingPlace = -1;
		lastQuickMoveSlot = -1;
		quickMoveRepeats = 0;
		applied = 0;
		ownsLeft = false;
		ownsRight = false;
		halted = false;
		announcedWaiting = false;
		announcedDone = false;
		pendingMessage = null;
		status = "";
		failed.clear();
		reported.clear();
	}

	public void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (!(mc.screen instanceof AnvilScreen screen) || player == null || mc.gameMode == null) {
			if (containerId != -1) reset();
			return;
		}

		AnvilMenu menu = screen.getMenu();
		if (player.containerMenu != menu) return;

		if (menu.containerId != containerId) {
			reset();
			containerId = menu.containerId;
		}

		AutoAnvilConfig config = AutoAnvilConfig.get();
		if (!config.enabled) {
			status = "";
			return;
		}

		if (halted) return;
		if (cooldown > 0) {
			cooldown--;
			return;
		}

		step(mc, player, menu, config);
	}

	private void step(Minecraft mc, LocalPlayer player, AnvilMenu menu, AutoAnvilConfig config) {
		boolean creative = player.hasInfiniteMaterials();

		// 1. Something on the cursor: either we picked it up a moment ago, or the player is holding it.
		if (!menu.getCarried().isEmpty()) {
			if (pendingPlace >= 0 && menu.getSlot(pendingPlace).getItem().isEmpty()) {
				int target = pendingPlace;
				pendingPlace = -1;
				click(mc, menu, target, ClickType.PICKUP);

				if (target == LEFT) {
					ownsLeft = true;
					if (pendingMessage != null) {
						message(pendingMessage);
						pendingMessage = null;
					}
				} else if (target == RIGHT) {
					ownsRight = true;
					settle = SETTLE_TICKS;
					waitTicks = 0;
				}
				return;
			}

			pendingPlace = -1;
			status = "Put down the item on your cursor";
			return;
		}
		pendingPlace = -1;

		ItemStack left = menu.getSlot(LEFT).getItem();
		ItemStack right = menu.getSlot(RIGHT).getItem();

		// 2. Left slot empty: clear the right slot if it's ours, then bring in the next piece of gear.
		if (left.isEmpty()) {
			ownsLeft = false;

			if (!right.isEmpty()) {
				if (ownsRight) quickMove(mc, menu, RIGHT);
				else status = "Take the item out of the second slot";
				return;
			}
			ownsRight = false;

			Candidate gear = findGear(menu, config, creative);
			if (gear == null) {
				status = applied > 0 ? "Done! Applied " + applied + " book" + (applied == 1 ? "" : "s") : "Nothing to apply";
				if (applied > 0 && !announcedDone) {
					announcedDone = true;
					message("Done! Applied " + applied + " book" + (applied == 1 ? "" : "s") + ".");
				}
				return;
			}

			announcedDone = false;
			status = "Inserting " + gear.stack().getHoverName().getString();
			click(mc, menu, gear.slot(), ClickType.PICKUP);
			pendingPlace = LEFT;
			return;
		}

		// 3. Never take over an item the player put in themselves unless there's work to do on it.
		if (!ownsLeft) {
			if (config.isTarget(left) && findBook(menu, left, config, creative, false) != null) {
				ownsLeft = true;
			} else {
				status = "Nothing to apply to the item in the anvil";
				return;
			}
		}

		// 4. Right slot empty: add the next book, or send the finished gear back to the inventory.
		if (right.isEmpty()) {
			ownsRight = false;

			Candidate book = findBook(menu, left, config, creative, true);
			if (book == null) {
				status = "Finished " + left.getHoverName().getString();
				quickMove(mc, menu, LEFT);
				return;
			}

			status = "Adding " + AnvilMath.describeBook(book.stack());
			click(mc, menu, book.slot(), ClickType.PICKUP);
			pendingPlace = RIGHT;
			return;
		}

		// 5. Both inputs filled. Make sure the book is one we actually want on this item.
		if (AnvilMath.estimateCost(left, right, config::isEnchantOn, creative) == AnvilMath.NOT_USEFUL || failed.contains(pairKey(left, right))) {
			if (ownsRight) quickMove(mc, menu, RIGHT);
			else status = "The book in the second slot isn't on your list";
			return;
		}
		ownsRight = true;

		if (settle > 0) {
			settle--;
			return;
		}

		ItemStack result = menu.getSlot(RESULT).getItem();
		int cost = menu.getCost();
		if (result.isEmpty() || cost <= 0) {
			if (++waitTicks > RESULT_TIMEOUT_TICKS) {
				waitTicks = 0;
				failed.add(pairKey(left, right));
				message("The anvil won't combine " + AnvilMath.describeBook(right) + " with " + left.getHoverName().getString() + ", skipping it.");
				quickMove(mc, menu, RIGHT);
			} else {
				status = "Waiting for the anvil...";
			}
			return;
		}
		waitTicks = 0;

		// 6. Not enough levels: wait here until someone splashes enough XP.
		if (!creative && player.experienceLevel < cost) {
			status = "Waiting for XP: level " + player.experienceLevel + " / " + cost;
			if (!announcedWaiting) {
				announcedWaiting = true;
				message("Waiting for XP: need " + cost + " levels for " + AnvilMath.describeBook(right) + " (you have " + player.experienceLevel + ").");
			}
			return;
		}

		// 7. Take the result; step 1 puts it back in the left slot for the next book.
		announcedWaiting = false;
		applied++;
		pendingMessage = "Applied " + AnvilMath.describeBook(right) + " to " + left.getHoverName().getString() + " (" + cost + " levels)";
		status = "Applying " + AnvilMath.describeBook(right);
		click(mc, menu, RESULT, ClickType.PICKUP);
		pendingPlace = LEFT;
		ownsRight = false;
	}

	/** First enabled piece of gear (in catalog order) that has at least one usable book. */
	private Candidate findGear(AnvilMenu menu, AutoAnvilConfig config, boolean creative) {
		for (Catalog.ItemType type : Catalog.ITEM_TYPES) {
			if (!config.isItemOn(type.key())) continue;

			for (Slot slot : menu.slots) {
				if (!(slot.container instanceof Inventory)) continue;

				ItemStack stack = slot.getItem();
				if (Catalog.typeOf(stack) != type || !config.isTarget(stack)) continue;
				if (findBook(menu, stack, config, creative, false) != null) return new Candidate(slot.index, stack, 0);
			}
		}
		return null;
	}

	/**
	 * The most expensive usable book for this item. Applying expensive books first keeps every
	 * step under the "Too Expensive!" limit for as long as possible, because the prior-work penalty
	 * doubles with each anvil use and is best paired with the cheap books at the end.
	 */
	private Candidate findBook(AnvilMenu menu, ItemStack target, AutoAnvilConfig config, boolean creative, boolean report) {
		Candidate best = null;

		for (Slot slot : menu.slots) {
			if (!(slot.container instanceof Inventory)) continue;

			ItemStack stack = slot.getItem();
			if (!stack.is(Items.ENCHANTED_BOOK)) continue;

			int cost = AnvilMath.estimateCost(target, stack, config::isEnchantOn, creative);
			if (cost == AnvilMath.NOT_USEFUL || failed.contains(pairKey(target, stack))) continue;

			if (!creative && cost >= AnvilMath.TOO_EXPENSIVE) {
				String key = pairKey(target, stack);
				if (report && reported.add(key)) {
					message(AnvilMath.describeBook(stack) + " on " + target.getHoverName().getString() + " would cost " + cost + " levels (Too Expensive!), skipping it.");
				}
				continue;
			}

			if (best == null || cost > best.cost()) best = new Candidate(slot.index, stack, cost);
		}

		return best;
	}

	private void click(Minecraft mc, AnvilMenu menu, int slot, ClickType type) {
		if (type != ClickType.QUICK_MOVE) {
			lastQuickMoveSlot = -1;
			quickMoveRepeats = 0;
		}

		mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, type, mc.player);
		cooldown = AutoAnvilConfig.get().clickDelayTicks;
	}

	private void quickMove(Minecraft mc, AnvilMenu menu, int slot) {
		if (slot == lastQuickMoveSlot) {
			if (++quickMoveRepeats >= MAX_QUICK_MOVE_RETRIES) {
				halted = true;
				status = "Inventory full! Make room and reopen the anvil";
				message("Your inventory is full, so I can't take items out of the anvil. Make room and reopen it.");
				return;
			}
		} else {
			lastQuickMoveSlot = slot;
			quickMoveRepeats = 0;
		}

		click(mc, menu, slot, ClickType.QUICK_MOVE);
	}

	private static String pairKey(ItemStack target, ItemStack book) {
		return Catalog.itemPath(target) + "|" + AnvilMath.describeBook(book);
	}

	private void message(String text) {
		if (!AutoAnvilConfig.get().chatMessages) return;

		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return;

		player.displayClientMessage(Component.literal("[Auto Anvil] ").withStyle(ChatFormatting.AQUA)
				.append(Component.literal(text).withStyle(ChatFormatting.GRAY)), false);
	}

	/** Status line drawn under the anvil GUI. */
	public void renderStatus(AnvilScreen screen, GuiGraphics graphics) {
		if (!AutoAnvilConfig.get().enabled || status.isEmpty()) return;

		Font font = Minecraft.getInstance().font;
		String text = "Auto Anvil: " + status;
		int x = (screen.width - font.width(text)) / 2;
		int y = (screen.height + AutoAnvilClient.ANVIL_GUI_HEIGHT) / 2 + 4;
		graphics.drawString(font, text, x, y, 0xFFFFFF55, true);
	}
}
