package dev.wuason.unearthMechanic.utils

import dev.wuason.unearthMechanic.UnearthMechanic
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.inventory.ItemStack
import java.util.function.Function

class ItemRemoverManager(
    private val core: UnearthMechanic
) : Listener {

    companion object {
        private val checks = mutableListOf<Function<ItemStack, Boolean>>()

        @JvmStatic
        fun addCheck(check: Function<ItemStack, Boolean>) = checks.add(check)

        @JvmStatic
        fun removeCheck(check: Function<ItemStack, Boolean>) = checks.remove(check)

        @JvmStatic
        fun clearChecks() = checks.clear()

        @JvmStatic
        fun getChecks(): List<Function<ItemStack, Boolean>> = checks
    }

    private fun shouldRemove(itemStack: ItemStack): Boolean {
        if (itemStack.type.isAir) return false
        return checks.any { it.apply(itemStack) }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrop(event: PlayerDropItemEvent) {
        if (event.isCancelled) return

        val item = event.itemDrop
        val itemStack = item.itemStack

        if (!shouldRemove(itemStack)) return

        event.isCancelled = true
        item.remove()
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPickup(event: EntityPickupItemEvent) {
        if (event.isCancelled) return

        val item = event.item
        val itemStack = item.itemStack

        if (!shouldRemove(itemStack)) return

        event.isCancelled = true
        item.remove()
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlace(event: BlockPlaceEvent) {
        if (event.isCancelled) return

        runCatching {
            val itemStack = event.itemInHand
            if (!shouldRemove(itemStack)) return

            event.isCancelled = true
            event.player.inventory.setItem(event.hand, ItemStack(Material.AIR))
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.isCancelled) return
        if (event.clickedInventory?.type != InventoryType.PLAYER) return

        runCatching {
            val itemStack = event.currentItem ?: return
            if (!shouldRemove(itemStack)) return

            event.isCancelled = true
            event.whoClicked.inventory.remove(itemStack)
        }
    }
}