# Auto Anvil (Fabric, Minecraft 1.21.11)

A small client-side mod that applies the enchanted books in your inventory to your gear in an anvil, one book at a time, and **waits for XP** whenever you don't have enough levels, so a friend can keep splashing bottles of enchanting at you while it works.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for **1.21.11** and put **Fabric API** in your `mods` folder.
2. Get `autoanvil-1.0.0.jar` from the **AutoAnvil** artifact of the latest `auto-anvil` GitHub Actions run (or build it yourself with `./gradlew build` in this folder using **JDK 25**; the jar ends up in `build/libs/` and runs on Minecraft's normal Java 21).
3. Drop it into `.minecraft/mods`.

## Use

1. Take off any armor you want enchanted (the anvil can only reach your main inventory and hotbar).
2. Carry the gear and the enchanted books.
3. Open an anvil. Each piece of gear goes into the left slot, gets every book that fits it, and goes back into your inventory.
4. When the next book costs more levels than you have, it shows `Waiting for XP: level 12 / 23` under the anvil. Splash XP and it carries on by itself.

An **Auto Anvil: ON/OFF** button sits above the anvil window to pause it at any time.

> Tip: if you're wearing damaged gear with Mending, the splashed XP repairs that gear first and won't raise your level as fast.

## Choosing what gets applied

Run `/autoanvil` to open the settings (or bind a key under *Controls → Auto Anvil*):

- **Items**: helmets, chestplates, leggings, boots, swords, spears, axes, maces, tridents, bows, crossbows, pickaxes, shovels, hoes (elytra, fishing rods, shears and shields are off by default).
- **Materials**: only **Netherite** and **Diamond** gear by default. Gear without a material (bows, tridents, maces, elytra...) is always allowed.
- **General / Armor / Melee / Tools / Ranged**: which enchantments to use. A book is only used if *every* enchantment on it is switched on, fits the item, doesn't conflict with what's already on it, and actually raises a level.

Defaults match the classic setups: Protection IV, Unbreaking III, Mending, Curse of Vanishing, Feather Falling, Aqua Affinity and Depth Strider for armor; Sharpness, Looting, Sweeping Edge, Fire Aspect and Lunge for swords and spears; Efficiency and Fortune for tools; and so on. Conflicting pairs like Fortune/Silk Touch and Infinity/Mending only have one side on.

`/autoanvil toggle` turns it on or off.

## How it picks books

Every anvil use doubles the item's prior-work penalty, so it applies the **most expensive book first** and leaves cheap ones (like Mending) for last. This keeps each step under the 40-level "Too Expensive!" limit as long as possible. A book that would still be too expensive is skipped with a chat message. Settings are saved in `config/autoanvil.json`.
