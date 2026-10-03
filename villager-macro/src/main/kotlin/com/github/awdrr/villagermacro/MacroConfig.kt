package com.github.awdrr.villagermacro

import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.util.*

/** Settings changed with /villagermacro, saved to config/villagermacro.properties. */
object MacroConfig {
    /** Max distance (blocks, from your eyes) to the villagers, the crafting table and the chest. */
    var reach = 4.5

    /** Ticks to wait between trades and inventory clicks. 0 = several clicks per tick. */
    var clickDelay = 1

    /** Milliseconds spent turning towards a villager or block before using it. */
    var rotationTime = 150

    /** Seconds to wait before checking a sold out villager again. */
    var restockDelay = 60

    /** Command (without the slash) run whenever the macro runs out of string. */
    var stringCommand = "string"

    /**
     * When out of string with this many empty slots or fewer, craft and store the emeralds before getting more.
     * /string only fills empty slots, so with few of them it hands out very little string.
     */
    var craftAtFreeSlots = 10

    /** Prints /string timings in chat (not saved). */
    var debug = false

    private val file get() = FabricLoader.getInstance().configDir.resolve("villagermacro.properties")

    fun load() {
        val props = Properties()
        runCatching { Files.newInputStream(file).use { props.load(it) } }.onFailure { return }

        props.getProperty("reach")?.toDoubleOrNull()?.let { reach = it.coerceIn(3.0, 5.5) }
        props.getProperty("clickDelay")?.toIntOrNull()?.let { clickDelay = it.coerceIn(0, 10) }
        props.getProperty("rotationTime")?.toIntOrNull()?.let { rotationTime = it.coerceIn(0, 3000) }
        props.getProperty("restockDelay")?.toIntOrNull()?.let { restockDelay = it.coerceIn(10, 600) }
        props.getProperty("stringCommand")?.removePrefix("/")?.trim()?.takeIf { it.isNotEmpty() }?.let { stringCommand = it }
        props.getProperty("craftAtFreeSlots")?.toIntOrNull()?.let { craftAtFreeSlots = it.coerceIn(0, 36) }
    }

    fun save() {
        val props = Properties()
        props.setProperty("reach", reach.toString())
        props.setProperty("clickDelay", clickDelay.toString())
        props.setProperty("rotationTime", rotationTime.toString())
        props.setProperty("restockDelay", restockDelay.toString())
        props.setProperty("stringCommand", stringCommand)
        props.setProperty("craftAtFreeSlots", craftAtFreeSlots.toString())
        runCatching { Files.newOutputStream(file).use { props.store(it, "Villager Trade Macro") } }
    }
}
