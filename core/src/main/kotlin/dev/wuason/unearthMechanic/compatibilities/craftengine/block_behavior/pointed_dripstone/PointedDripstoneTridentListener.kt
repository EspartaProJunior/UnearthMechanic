package dev.wuason.unearthMechanic.compatibilities.craftengine.listeners

import net.momirealms.craftengine.bukkit.api.BukkitAdaptor
import net.momirealms.craftengine.bukkit.api.CraftEngineItems
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition
import net.momirealms.craftengine.core.util.Key
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Trident
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import java.util.Optional

class PointedDripstoneTridentListener(
    private val plugin: Plugin,
    private val blockId: Key,
    private val itemId: Key = blockId
) : Listener {

    @EventHandler(ignoreCancelled = true)
    fun onProjectileHit(event: ProjectileHitEvent) {
        val trident = event.entity as? Trident ?: return
        val velocity = trident.velocity.clone()

        val block = event.hitBlock ?: return
        val ceBlock = BukkitAdaptor.adapt(block.world).getBlock(block.x, block.y, block.z)
        val customState = ceBlock.customBlockState() ?: return

        if (customState.owner().value().id() != blockId) return

        dropItem(block)
        block.type = Material.AIR
        releaseTrident(trident, velocity, block)

        block.world.playSound(block.location, "minecraft:block.pointed_dripstone.break", 1.0f, 1.0f)
    }

    private fun dropItem(block: Block) {
        block.world.dropItemNaturally(
            block.location.clone().add(0.5, 0.25, 0.5),
            createCraftEngineItem(itemId) ?: ItemStack(Material.POINTED_DRIPSTONE)
        )
    }

    private fun createCraftEngineItem(id: Key): ItemStack? {
        return Optional.ofNullable<BukkitItemDefinition>(
            CraftEngineItems.byId(id)
        )
            .map<ItemStack?> { itemDefinition -> itemDefinition.buildBukkitItem() }
            .orElse(null)
    }

    private fun releaseTrident(trident: Trident, previousVelocity: Vector, brokenBlock: Block) {
        val direction = if (previousVelocity.lengthSquared() > 0.0001) {
            previousVelocity.clone().normalize()
        } else {
            trident.location.direction.normalize()
        }

        val nextLocation = brokenBlock.location.clone().add(0.5, 0.5, 0.5).add(direction.clone().multiply(0.65))

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (!trident.isValid) return@Runnable

            trident.remove()
            nextLocation.world?.dropItemNaturally(nextLocation, ItemStack(Material.TRIDENT))
        }, 1L)
    }
}