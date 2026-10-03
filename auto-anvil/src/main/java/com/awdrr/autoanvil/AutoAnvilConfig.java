package com.awdrr.autoanvil;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.world.item.ItemStack;

/** Saved to config/autoanvil.json. Missing entries are filled from the catalog defaults. */
public final class AutoAnvilConfig {
	public static final int MIN_DELAY = 1;
	public static final int MAX_DELAY = 10;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static AutoAnvilConfig instance;

	public boolean enabled = true;
	public int clickDelayTicks = 3;
	public boolean chatMessages = true;
	public Map<String, Boolean> enchantments = new LinkedHashMap<>();
	public Map<String, Boolean> items = new LinkedHashMap<>();
	public Map<String, Boolean> materials = new LinkedHashMap<>();

	public static AutoAnvilConfig get() {
		if (instance == null) instance = load();
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("autoanvil.json");
	}

	private static AutoAnvilConfig load() {
		AutoAnvilConfig config = null;
		Path path = path();

		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				config = GSON.fromJson(reader, AutoAnvilConfig.class);
			} catch (Exception e) {
				AutoAnvilClient.LOGGER.warn("Couldn't read {}, using defaults", path, e);
			}
		}

		if (config == null) config = new AutoAnvilConfig();
		config.fillDefaults();
		config.save();
		return config;
	}

	private void fillDefaults() {
		if (enchantments == null) enchantments = new LinkedHashMap<>();
		if (items == null) items = new LinkedHashMap<>();
		if (materials == null) materials = new LinkedHashMap<>();

		for (Catalog.Ench ench : Catalog.ENCHANTMENTS) enchantments.putIfAbsent(ench.id(), ench.defaultOn());
		for (Catalog.ItemType type : Catalog.ITEM_TYPES) items.putIfAbsent(type.key(), type.defaultOn());
		for (Catalog.Material material : Catalog.MATERIALS) materials.putIfAbsent(material.key(), material.defaultOn());

		clickDelayTicks = Math.clamp(clickDelayTicks, MIN_DELAY, MAX_DELAY);
	}

	public void save() {
		Path path = path();

		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			AutoAnvilClient.LOGGER.warn("Couldn't save {}", path, e);
		}
	}

	public boolean isEnchantOn(String id) {
		return enchantments.getOrDefault(id, false);
	}

	public boolean isItemOn(String key) {
		return items.getOrDefault(key, false);
	}

	public boolean isMaterialOn(String key) {
		return materials.getOrDefault(key, false);
	}

	/** Whether this stack is gear the user wants enchanted (right type, allowed material, not stacked). */
	public boolean isTarget(ItemStack stack) {
		if (stack.isEmpty() || stack.getCount() != 1) return false;

		Catalog.ItemType type = Catalog.typeOf(stack);
		if (type == null || !isItemOn(type.key())) return false;

		Catalog.Material material = Catalog.materialOf(stack);
		return material == null || isMaterialOn(material.key());
	}
}
