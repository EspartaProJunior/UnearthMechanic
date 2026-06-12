package dev.wuason.unearthMechanic.compatibilities.craftengine.item_behavior.blockspeed

import net.momirealms.craftengine.bukkit.api.CraftEngineItems
import net.momirealms.craftengine.core.util.Key
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerItemBreakEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class BlockSpeedListener(
    private val plugin: Plugin
) : Listener {

    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (event.from.blockX == event.to.blockX &&
            event.from.blockY == event.to.blockY &&
            event.from.blockZ == event.to.blockZ
        ) {
            return
        }

        updateSpeed(event.player)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) = updateSpeed(event.player)

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        plugin.server.scheduler.runTask(plugin, Runnable {
            updateSpeed(event.player)
        })
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) = updateSpeed(event.player)

    @EventHandler
    fun onItemBreak(event: PlayerItemBreakEvent) = updateSpeed(event.player)

    private fun updateSpeed(player: Player) {
        val behavior = blockSpeedBehavior(player.inventory.boots)
        val canApplySpeed = behavior != null && isOnConfiguredBlock(player, behavior)

        if (!canApplySpeed || behavior == null) {
            return
        }

        applySpeedEffect(player, behavior)
    }

    private fun isOnConfiguredBlock(player: Player, behavior: BlockSpeedItemBehavior): Boolean {
        val block = player.location.subtract(0.0, 0.1, 0.0).block
        return behavior.blocks.isEmpty() || block.type.name in behavior.blocks
    }

    private fun applySpeedEffect(player: Player, behavior: BlockSpeedItemBehavior) {
        val current = player.getPotionEffect(PotionEffectType.SPEED)

        if (current != null && current.amplifier > behavior.speedAmplifier) {
            return
        }

        if (current != null &&
            current.amplifier == behavior.speedAmplifier &&
            current.duration > behavior.durationTicks / 2
        ) {
            return
        }

        player.addPotionEffect(
            PotionEffect(
                PotionEffectType.SPEED,
                behavior.durationTicks,
                behavior.speedAmplifier,
                true,
                false,
                false
            )
        )
    }

    private fun blockSpeedBehavior(itemStack: ItemStack?): BlockSpeedItemBehavior? {
        if (itemStack == null || itemStack.type == Material.AIR) {
            return null
        }

        val itemKey = customItemKey(itemStack) ?: return null
        return BlockSpeedItemBehavior.behaviorFor(itemKey)
    }

    private fun customItemKey(itemStack: ItemStack): Key? {
        return CraftEngineItems.getCustomItemId(itemStack)
    }
}