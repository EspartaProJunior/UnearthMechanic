package dev.wuason.unearthMechanic.compatibilities.customcrops

import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.config.WateringCanSettings
import dev.wuason.unearthMechanic.config.WaterChargeMode
import dev.wuason.unearthMechanic.config.WaterCreativeMode
import dev.wuason.unearthMechanic.system.LiveTool
import dev.wuason.unearthMechanic.utils.AdventureUtils
import net.momirealms.customcrops.api.BukkitCustomCropsPlugin
import net.momirealms.customcrops.api.action.ActionManager
import net.momirealms.customcrops.api.context.Context
import net.momirealms.customcrops.api.context.ContextKeys
import net.momirealms.customcrops.api.core.BuiltInItemMechanics
import net.momirealms.customcrops.api.core.Registries
import net.momirealms.customcrops.api.core.item.WateringCanItem
import net.momirealms.customcrops.api.core.mechanic.wateringcan.WateringCanConfig
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CustomCropsWateringCanSupportImpl(
    private val core: UnearthMechanic
) : CustomCropsWateringCanSupport {

    private data class ResolvedCan(
        val item: ItemStack,
        val itemId: String,
        val config: WateringCanConfig,
        val mechanic: WateringCanItem,
        val currentWater: Int
    )

    private val committedActivations = ConcurrentHashMap<UUID, Int>()
    private val notifiedFailures = ConcurrentHashMap<UUID, Int>()

    override fun validate(
        player: Player,
        liveTool: LiveTool,
        location: Location,
        settings: WateringCanSettings,
        notifyFailure: Boolean
    ): Boolean {
        cleanupOldActivations()

        if (settings.chargeMode == WaterChargeMode.ACTIVATION &&
            committedActivations.containsKey(liveTool.getActivationId())
        ) {
            return true
        }

        val resolved = resolveCan(liveTool, settings)
        if (resolved == null) {
            if (notifyFailure) notifyFailure(player, liveTool, settings, 0, 0, null)
            return false
        }

        if (settings.creativeMode == WaterCreativeMode.BYPASS && player.gameMode == GameMode.CREATIVE) {
            return true
        }

        if (settings.respectInfinite && resolved.config.infinite()) {
            return true
        }

        if (resolved.currentWater >= settings.waterNeeded()) {
            return true
        }

        if (notifyFailure) {
            notifyFailure(
                player,
                liveTool,
                settings,
                resolved.currentWater,
                resolved.config.storage(),
                resolved.itemId
            )
        }
        return false
    }

    override fun consumeAfterSuccess(
        player: Player,
        liveTool: LiveTool,
        location: Location,
        settings: WateringCanSettings
    ): Boolean {
        if (settings.consumeWater <= 0) return true
        if (settings.creativeMode == WaterCreativeMode.BYPASS && player.gameMode == GameMode.CREATIVE) return true

        val activationId = liveTool.getActivationId()
        val tick = Bukkit.getCurrentTick()
        val ownsActivation = if (settings.chargeMode == WaterChargeMode.ACTIVATION) {
            committedActivations.putIfAbsent(activationId, tick) == null
        } else {
            true
        }
        if (!ownsActivation) return true

        try {
            val resolved = resolveCan(liveTool, settings)
            if (resolved == null) {
                notifyFailure(player, liveTool, settings, 0, 0, null)
                releaseActivation(settings, activationId)
                return false
            }

            if (settings.respectInfinite && resolved.config.infinite()) return true
            if (resolved.currentWater < settings.consumeWater) {
                notifyFailure(
                    player,
                    liveTool,
                    settings,
                    resolved.currentWater,
                    resolved.config.storage(),
                    resolved.itemId
                )
                releaseActivation(settings, activationId)
                return false
            }

            val currentWater = resolved.currentWater - settings.consumeWater
            val context = Context.player(player)
            context.updateLocation(location)
            context.arg(ContextKeys.SLOT, EquipmentSlot.HAND)
            context.arg(
                ContextKeys.WATER_BAR,
                resolved.config.waterBar()?.getWaterBar(currentWater, resolved.config.storage()) ?: ""
            )
            context.arg(ContextKeys.STORAGE, resolved.config.storage())
            context.arg(ContextKeys.CURRENT_WATER, currentWater)

            ActionManager.trigger(context, resolved.config.consumeWaterActions())
            resolved.mechanic.setCurrentWater(resolved.item, resolved.config, currentWater, context)
            liveTool.setItemMainHand(resolved.item)
            return true
        } catch (exception: Throwable) {
            releaseActivation(settings, activationId)
            core.logger.severe(
                "Could not consume CustomCrops watering-can water for ${player.name}: ${exception.message}"
            )
            return false
        }
    }

    private fun resolveCan(
        liveTool: LiveTool,
        settings: WateringCanSettings
    ): ResolvedCan? {
        val item = liveTool.getItemMainHand()
        if (item.type.isAir) return null

        val plugin = BukkitCustomCropsPlugin.getInstance()
        val itemId = plugin.itemManager.id(item)
        val config = Registries.ITEM_TO_WATERING_CAN.get(itemId) ?: return null
        if (!settings.acceptsCan(itemId) && !settings.acceptsCan(config.id())) return null

        val mechanic = BuiltInItemMechanics.WATERING_CAN.mechanic() as? WateringCanItem ?: return null
        return ResolvedCan(item, itemId, config, mechanic, mechanic.getCurrentWater(item))
    }

    private fun notifyFailure(
        player: Player,
        liveTool: LiveTool,
        settings: WateringCanSettings,
        currentWater: Int,
        capacity: Int,
        canId: String?
    ) {
        val activationId = liveTool.getActivationId()
        if (notifiedFailures.putIfAbsent(activationId, Bukkit.getCurrentTick()) != null) return

        fun placeholders(text: String): String = text
            .replace("{water}", currentWater.toString())
            .replace("{capacity}", capacity.toString())
            .replace("{require}", settings.waterNeeded().toString())
            .replace("{consume}", settings.consumeWater.toString())
            .replace("{can}", canId ?: "unknown")

        settings.onFail.actionbar?.let {
            player.sendActionBar(AdventureUtils.deserialize(placeholders(it)))
        }
        settings.onFail.sound?.let {
            player.playSound(player.location, placeholders(it), 1.0f, 1.0f)
        }
    }

    private fun releaseActivation(settings: WateringCanSettings, activationId: UUID) {
        if (settings.chargeMode == WaterChargeMode.ACTIVATION) {
            committedActivations.remove(activationId)
        }
    }

    private fun cleanupOldActivations() {
        val oldestAllowedTick = Bukkit.getCurrentTick() - ACTIVATION_TTL_TICKS
        committedActivations.entries.removeIf { it.value < oldestAllowedTick }
        notifiedFailures.entries.removeIf { it.value < oldestAllowedTick }
    }

    private companion object {
        const val ACTIVATION_TTL_TICKS = 20 * 60
    }
}
