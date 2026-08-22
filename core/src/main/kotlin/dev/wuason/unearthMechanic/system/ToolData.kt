package dev.wuason.unearthMechanic.system

import org.bukkit.inventory.ItemStack
import java.util.UUID

data class ToolData(
    val itemStack: ItemStack,
    val slotPos : Int,
    val activationId: UUID = UUID.randomUUID()
)
