package dev.donutgamble

import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/** Saved to config/donutgamble.json. Logged rolls are not saved. */
class GambleConfig {
    var streamer: String = ""
    var minAmount: String = "500k"
    var maxAmount: String = "5m"
    var multiplier: Double = 2.0
    var learnFromRolls: Boolean = true
    var showHud: Boolean = true
    var hudX: Int = 4
    var hudY: Int = 4
    var sendInstantly: Boolean = false

    fun save() {
        try {
            Files.createDirectories(PATH.parent)
            Files.writeString(PATH, GSON.toJson(this))
        } catch (e: Exception) {
            DonutGamble.LOGGER.error("Could not save {}", PATH, e)
        }
    }

    companion object {
        private val GSON = GsonBuilder().setPrettyPrinting().create()
        private val PATH = FabricLoader.getInstance().configDir.resolve("donutgamble.json")

        fun load(): GambleConfig {
            val config = try {
                if (Files.exists(PATH)) GSON.fromJson(Files.readString(PATH), GambleConfig::class.java) else null
            } catch (e: Exception) {
                DonutGamble.LOGGER.error("Could not read {}, using defaults", PATH, e)
                null
            } ?: GambleConfig()
            // Gson skips constructors, so fix up anything missing or out of range.
            @Suppress("SENSELESS_COMPARISON")
            if (config.streamer == null) config.streamer = ""
            @Suppress("SENSELESS_COMPARISON")
            if (config.minAmount == null) config.minAmount = "500k"
            @Suppress("SENSELESS_COMPARISON")
            if (config.maxAmount == null) config.maxAmount = "5m"
            if (config.multiplier !in 1.0..5.0) config.multiplier = 2.0
            config.save()
            return config
        }
    }
}
