package dev.wuason.unearthMechanic.config

import dev.wuason.adapter.AdapterData
import dev.wuason.unearthMechanic.utils.ItemBuilder
import dev.wuason.unearthMechanic.utils.MathUtils
import dev.wuason.unearthMechanic.utils.StorageUtils
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

open class Item(private val adapterData: AdapterData, private val amount: String, private val chance: Int) : IItem {

    override fun getItemStackChance(): ItemStack? {
        return if (isDroppable()) ItemBuilder(adapterData.toString(), getRandAmount()).build() else null
    }

    override fun getItemStack(): ItemStack {
        return ItemBuilder(adapterData.toString(), getRandAmount()).build()
    }

    private fun isDroppable(): Boolean {
        return MathUtils.chance(chance.toFloat())
    }

    private fun getRandAmount(): Int {
        return MathUtils.randomNumberString(amount)
    }

    override fun getAdapterData(): AdapterData {
        return adapterData
    }

    override fun addItem(player: Player, applyChance: Boolean) {
        if (applyChance) StorageUtils.addItemToInventoryOrDrop(player, getItemStackChance()?: return)
        else StorageUtils.addItemToInventoryOrDrop(player, getItemStack())
    }
}
