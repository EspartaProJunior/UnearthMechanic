package dev.wuason.unearthMechanic.compatibilities.craftengine.item_behavior.crop_accelerator

import dev.wuason.unearthMechanic.utils.FoliaUtils
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Dispenser
import org.bukkit.block.data.Directional
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.inventory.ItemStack

class CropAcceleratorDispenserListener : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDispense(event: BlockDispenseEvent) {
        val settings = CropAcceleratorRegistry.settingsFor(event.item) ?: return
        val target = targetBlock(event.block) ?: return

        if (!CropAccelerator.canApply(target, settings)) {
            event.isCancelled = true
            return
        }

        event.isCancelled = true

        FoliaUtils.runAtLocation(target.location) {
            val dispenser = event.block.state as? Dispenser ?: return@runAtLocation
            if (!removeOneMatching(dispenser, event.item)) return@runAtLocation

            CropAccelerator.harvestAndReset(target, event.item, settings)
            CropAccelerator.playApplyFx(target)
            dispenser.update(true, false)
        }
    }

    private fun targetBlock(dispenserBlock: Block): Block? {
        val directional = dispenserBlock.blockData as? Directional ?: return null
        return dispenserBlock.getRelative(directional.facing)
    }

    private fun removeOneMatching(dispenser: Dispenser, sample: ItemStack): Boolean {
        val inventory = dispenser.inventory
        for (slot in 0 until inventory.size) {
            val stack = inventory.getItem(slot) ?: continue
            if (stack.type == Material.AIR) continue
            if (!stack.isSimilar(sample)) continue

            if (stack.amount <= 1) {
                inventory.setItem(slot, null)
            } else {
                stack.amount -= 1
                inventory.setItem(slot, stack)
            }
            return true
        }
        return false
    }
}