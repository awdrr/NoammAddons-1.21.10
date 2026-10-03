package com.awdrr.autoanvil;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import net.minecraft.world.item.ItemStack;

/**
 * Everything the user can pick from: enchantments (by registry id), item types and materials.
 * Ids are matched as strings so nothing here depends on obfuscated constants.
 */
public final class Catalog {
	private Catalog() {
	}

	public enum Group {
		GENERAL("General"),
		ARMOR("Armor"),
		MELEE("Melee"),
		TOOLS("Tools"),
		RANGED("Ranged");

		public final String label;

		Group(String label) {
			this.label = label;
		}
	}

	public record Ench(String id, String name, Group group, boolean defaultOn) {
	}

	public record ItemType(String key, String name, boolean defaultOn, Predicate<String> matcher) {
	}

	public record Material(String key, String name, String prefix, boolean defaultOn) {
	}

	public static final List<Ench> ENCHANTMENTS = List.of(
			new Ench("minecraft:mending", "Mending", Group.GENERAL, true),
			new Ench("minecraft:unbreaking", "Unbreaking", Group.GENERAL, true),
			new Ench("minecraft:vanishing_curse", "Curse of Vanishing", Group.GENERAL, true),
			new Ench("minecraft:binding_curse", "Curse of Binding", Group.GENERAL, false),

			new Ench("minecraft:protection", "Protection", Group.ARMOR, true),
			new Ench("minecraft:fire_protection", "Fire Protection", Group.ARMOR, false),
			new Ench("minecraft:blast_protection", "Blast Protection", Group.ARMOR, false),
			new Ench("minecraft:projectile_protection", "Projectile Prot.", Group.ARMOR, false),
			new Ench("minecraft:feather_falling", "Feather Falling", Group.ARMOR, true),
			new Ench("minecraft:aqua_affinity", "Aqua Affinity", Group.ARMOR, true),
			new Ench("minecraft:respiration", "Respiration", Group.ARMOR, false),
			new Ench("minecraft:depth_strider", "Depth Strider", Group.ARMOR, true),
			new Ench("minecraft:frost_walker", "Frost Walker", Group.ARMOR, false),
			new Ench("minecraft:thorns", "Thorns", Group.ARMOR, false),
			new Ench("minecraft:soul_speed", "Soul Speed", Group.ARMOR, false),
			new Ench("minecraft:swift_sneak", "Swift Sneak", Group.ARMOR, false),

			new Ench("minecraft:sharpness", "Sharpness", Group.MELEE, true),
			new Ench("minecraft:smite", "Smite", Group.MELEE, false),
			new Ench("minecraft:bane_of_arthropods", "Bane of Arthropods", Group.MELEE, false),
			new Ench("minecraft:looting", "Looting", Group.MELEE, true),
			new Ench("minecraft:sweeping_edge", "Sweeping Edge", Group.MELEE, true),
			new Ench("minecraft:fire_aspect", "Fire Aspect", Group.MELEE, true),
			new Ench("minecraft:knockback", "Knockback", Group.MELEE, false),
			new Ench("minecraft:lunge", "Lunge", Group.MELEE, true),
			new Ench("minecraft:density", "Density", Group.MELEE, true),
			new Ench("minecraft:breach", "Breach", Group.MELEE, false),
			new Ench("minecraft:wind_burst", "Wind Burst", Group.MELEE, true),

			new Ench("minecraft:efficiency", "Efficiency", Group.TOOLS, true),
			new Ench("minecraft:fortune", "Fortune", Group.TOOLS, true),
			new Ench("minecraft:silk_touch", "Silk Touch", Group.TOOLS, false),

			new Ench("minecraft:power", "Power", Group.RANGED, true),
			new Ench("minecraft:punch", "Punch", Group.RANGED, false),
			new Ench("minecraft:flame", "Flame", Group.RANGED, true),
			new Ench("minecraft:infinity", "Infinity", Group.RANGED, false),
			new Ench("minecraft:quick_charge", "Quick Charge", Group.RANGED, true),
			new Ench("minecraft:multishot", "Multishot", Group.RANGED, true),
			new Ench("minecraft:piercing", "Piercing", Group.RANGED, false),
			new Ench("minecraft:loyalty", "Loyalty", Group.RANGED, true),
			new Ench("minecraft:impaling", "Impaling", Group.RANGED, true),
			new Ench("minecraft:riptide", "Riptide", Group.RANGED, false),
			new Ench("minecraft:channeling", "Channeling", Group.RANGED, true),
			new Ench("minecraft:lure", "Lure", Group.RANGED, true),
			new Ench("minecraft:luck_of_the_sea", "Luck of the Sea", Group.RANGED, true)
	);

	/** Listed in the order gear is processed, so earlier types get first pick of shared books. */
	public static final List<ItemType> ITEM_TYPES = List.of(
			new ItemType("helmet", "Helmets", true, p -> p.endsWith("_helmet")),
			new ItemType("chestplate", "Chestplates", true, p -> p.endsWith("_chestplate")),
			new ItemType("leggings", "Leggings", true, p -> p.endsWith("_leggings")),
			new ItemType("boots", "Boots", true, p -> p.endsWith("_boots")),
			new ItemType("sword", "Swords", true, p -> p.endsWith("_sword")),
			new ItemType("spear", "Spears", true, p -> p.endsWith("_spear")),
			new ItemType("axe", "Axes", true, p -> p.endsWith("_axe")),
			new ItemType("mace", "Maces", true, p -> p.equals("mace")),
			new ItemType("trident", "Tridents", true, p -> p.equals("trident")),
			new ItemType("bow", "Bows", true, p -> p.equals("bow")),
			new ItemType("crossbow", "Crossbows", true, p -> p.equals("crossbow")),
			new ItemType("pickaxe", "Pickaxes", true, p -> p.endsWith("_pickaxe")),
			new ItemType("shovel", "Shovels", true, p -> p.endsWith("_shovel")),
			new ItemType("hoe", "Hoes", true, p -> p.endsWith("_hoe")),
			new ItemType("elytra", "Elytra", false, p -> p.equals("elytra")),
			new ItemType("fishing_rod", "Fishing Rods", false, p -> p.equals("fishing_rod")),
			new ItemType("shears", "Shears", false, p -> p.equals("shears")),
			new ItemType("shield", "Shields", false, p -> p.equals("shield"))
	);

	/** Gear without a material prefix (bows, tridents, maces, elytra...) is never filtered by material. */
	public static final List<Material> MATERIALS = List.of(
			new Material("netherite", "Netherite", "netherite_", true),
			new Material("diamond", "Diamond", "diamond_", true),
			new Material("iron", "Iron", "iron_", false),
			new Material("golden", "Gold", "golden_", false),
			new Material("copper", "Copper", "copper_", false),
			new Material("chainmail", "Chainmail", "chainmail_", false),
			new Material("stone", "Stone", "stone_", false),
			new Material("wooden", "Wood", "wooden_", false),
			new Material("leather", "Leather", "leather_", false),
			new Material("turtle", "Turtle Shell", "turtle_", false)
	);

	private static final Map<String, String> ENCHANT_NAMES = ENCHANTMENTS.stream()
			.collect(Collectors.toMap(Ench::id, Ench::name));

	public static String itemPath(ItemStack stack) {
		String id = stack.getItemHolder().getRegisteredName();
		int colon = id.indexOf(':');
		return colon >= 0 ? id.substring(colon + 1) : id;
	}

	public static ItemType typeOf(ItemStack stack) {
		String path = itemPath(stack);
		for (ItemType type : ITEM_TYPES) {
			if (type.matcher().test(path)) return type;
		}
		return null;
	}

	public static Material materialOf(ItemStack stack) {
		String path = itemPath(stack);
		for (Material material : MATERIALS) {
			if (path.startsWith(material.prefix())) return material;
		}
		return null;
	}

	public static String enchantName(String id, int level) {
		String name = ENCHANT_NAMES.get(id);
		if (name == null) name = id.substring(id.indexOf(':') + 1);
		return level > 1 || !isSingleLevel(id) ? name + " " + roman(level) : name;
	}

	private static boolean isSingleLevel(String id) {
		return switch (id) {
			case "minecraft:mending", "minecraft:vanishing_curse", "minecraft:binding_curse", "minecraft:aqua_affinity",
					"minecraft:silk_touch", "minecraft:flame", "minecraft:infinity", "minecraft:multishot",
					"minecraft:channeling" -> true;
			default -> false;
		};
	}

	private static String roman(int n) {
		return switch (n) {
			case 1 -> "I";
			case 2 -> "II";
			case 3 -> "III";
			case 4 -> "IV";
			case 5 -> "V";
			default -> String.valueOf(n);
		};
	}
}
