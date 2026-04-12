package dev.wuason.unearthMechanic.utils

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

object StorageUtils {
    @JvmStatic
    fun addItemToInventoryOrDrop(player: Player, itemStack: ItemStack) {
        val location = player.location
        val world = location.world ?: return

        player.inventory.addItem(itemStack).values.forEach { remainingItem ->
            world.dropItem(location, remainingItem).pickupDelay = 40
        }
    }
}