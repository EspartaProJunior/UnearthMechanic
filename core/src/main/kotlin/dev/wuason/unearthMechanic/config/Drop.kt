package dev.wuason.unearthMechanic.config

import dev.wuason.adapter.AdapterData
import org.bukkit.Location
import org.bukkit.inventory.ItemStack

class Drop(private val adapterData: AdapterData, private val amount: String, private val chance: Int) : Item(adapterData, amount, chance), IDrop {

    override fun dropItem(loc: Location, applyChance: Boolean): org.bukkit.entity.Item? {
        val item: ItemStack? = if (applyChance) getItemStackChance() else getItemStack()
        return if (item != null) loc.world.dropItem(loc, item) else null
    }

    override fun rollItem(applyChance: Boolean): ItemStack? {
        return if (applyChance) getItemStackChance() else getItemStack()
    }
}
