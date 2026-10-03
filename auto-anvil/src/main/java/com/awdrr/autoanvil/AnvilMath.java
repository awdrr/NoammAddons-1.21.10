package com.awdrr.autoanvil;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Predicate;

import it.unimi.dsi.fastutil.objects.Object2IntMap;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/** Mirrors vanilla's AnvilMenu#createResult for the "item + enchanted book" case. */
public final class AnvilMath {
	/** Vanilla refuses ("Too Expensive!") anything costing this much or more outside creative. */
	public static final int TOO_EXPENSIVE = 40;
	public static final int NOT_USEFUL = -1;

	private AnvilMath() {
	}

	/**
	 * Returns the level cost of putting {@code book} on {@code target}, or {@link #NOT_USEFUL} if the
	 * book shouldn't be used: it carries an enchantment the user didn't pick, one that doesn't fit this
	 * item or conflicts with what's already on it, or it wouldn't raise any enchantment level.
	 */
	public static int estimateCost(ItemStack target, ItemStack book, Predicate<String> allowed, boolean creative) {
		ItemEnchantments offered = book.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
		if (offered.isEmpty()) return NOT_USEFUL;

		Map<Holder<Enchantment>, Integer> result = new LinkedHashMap<>();
		ItemEnchantments existing = target.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
		for (Object2IntMap.Entry<Holder<Enchantment>> entry : existing.entrySet()) {
			result.put(entry.getKey(), entry.getIntValue());
		}

		int cost = target.getOrDefault(DataComponents.REPAIR_COST, 0) + book.getOrDefault(DataComponents.REPAIR_COST, 0);
		boolean improves = false;

		for (Object2IntMap.Entry<Holder<Enchantment>> entry : offered.entrySet()) {
			Holder<Enchantment> holder = entry.getKey();
			Enchantment enchantment = holder.value();

			if (!allowed.test(holder.getRegisteredName())) return NOT_USEFUL;
			if (!creative && !enchantment.canEnchant(target)) return NOT_USEFUL;

			for (Holder<Enchantment> other : result.keySet()) {
				if (!other.equals(holder) && !Enchantment.areCompatible(holder, other)) return NOT_USEFUL;
			}

			int current = result.getOrDefault(holder, 0);
			int level = entry.getIntValue();
			level = current == level ? level + 1 : Math.max(level, current);
			level = Math.min(level, enchantment.getMaxLevel());

			if (level > current) improves = true;
			result.put(holder, level);
			cost += Math.max(1, enchantment.getAnvilCost() / 2) * level;
		}

		return improves ? cost : NOT_USEFUL;
	}

	/** e.g. "Protection IV + Mending" */
	public static String describeBook(ItemStack book) {
		ItemEnchantments stored = book.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
		StringJoiner joiner = new StringJoiner(" + ");
		for (Object2IntMap.Entry<Holder<Enchantment>> entry : stored.entrySet()) {
			joiner.add(Catalog.enchantName(entry.getKey().getRegisteredName(), entry.getIntValue()));
		}
		return joiner.length() == 0 ? "Enchanted Book" : joiner.toString();
	}
}
