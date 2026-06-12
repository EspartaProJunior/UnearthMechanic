package dev.wuason.unearthMechanic.config

import org.bukkit.Location
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * IDrop represents an item drop mechanism, extending from IItem.
 * It includes functionality for dropping items at a specified location with an optional chance factor.
 */
interface IDrop : IItem {
    /**
     * Drops an item at the specified location.
     *
     * @param loc The location where the item will be dropped.
     * @param applyChance If true, a chance mechanism will be applied to the drop.
     * @return The dropped item, or null if no item was dropped.
     */
    fun dropItem(loc: Location, applyChance: Boolean): Item?

    fun rollItem(applyChance: Boolean): ItemStack?

    /**
     * Adds the item to the player's inventory when possible, otherwise drops it at the location.
     */
    fun dropItemOrAddToInventory(loc: Location, player: Player, applyChance: Boolean)
}