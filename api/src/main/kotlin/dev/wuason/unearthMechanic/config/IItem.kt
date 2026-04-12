package dev.wuason.unearthMechanic.config

import dev.wuason.adapter.AdapterData
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Represents an item with various functionalities such as
 * obtaining its stack form, determining its chance-based stack,
 * getting its identifier, and adding it to a player's inventory.
 */
interface IItem {
    /**
     * Retrieves the ItemStack associated with this item.
     *
     * @return the ItemStack instance representing this item.
     */
    fun getItemStack(): ItemStack

    /**
     * Computes and retrieves an ItemStack instance based on specific chance-based logic.
     *
     * @return an ItemStack if the chance criteria are met; otherwise, returns null.
     */
    fun getItemStackChance(): ItemStack?


    /**
     * Retrieves the adapter data associated with this item.
     *
     * @return The AdapterData instance containing relevant configuration or characteristics for the adapter.
     */
    fun getAdapterData(): AdapterData

    /**
     * Adds an item to the player's inventory. The item can optionally be added based on a chance.
     *
     * @param player The player to whom the item will be added.
     * @param applyChance If true, the item will be added based on a probabilistic chance; otherwise, it will be added directly.
     */
    fun addItem(player: Player, applyChance: Boolean)
}