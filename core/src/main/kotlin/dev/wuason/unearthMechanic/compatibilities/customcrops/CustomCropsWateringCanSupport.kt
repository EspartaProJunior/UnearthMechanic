package dev.wuason.unearthMechanic.compatibilities.customcrops

import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.config.WateringCanSettings
import dev.wuason.unearthMechanic.system.LiveTool
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player

interface CustomCropsWateringCanSupport {
    fun validate(
        player: Player,
        liveTool: LiveTool,
        location: Location,
        settings: WateringCanSettings,
        notifyFailure: Boolean
    ): Boolean

    fun consumeAfterSuccess(
        player: Player,
        liveTool: LiveTool,
        location: Location,
        settings: WateringCanSettings
    ): Boolean
}

object CustomCropsPlugin {
    private const val PLUGIN_NAME = "CustomCrops"

    fun createSupport(core: UnearthMechanic): CustomCropsWateringCanSupport? {
        val plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME)
        if (plugin == null || !plugin.isEnabled) return null

        return runCatching { CustomCropsWateringCanSupportImpl(core) }
            .onFailure {
                core.logger.severe(
                    "Could not initialize CustomCrops watering-can support: ${it.message}"
                )
            }
            .getOrNull()
    }
}
