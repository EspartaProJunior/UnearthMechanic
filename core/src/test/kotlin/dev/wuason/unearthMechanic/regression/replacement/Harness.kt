package dev.wuason.unearthMechanic.regression.replacement

import org.bukkit.Location
import org.bukkit.entity.Entity
import java.util.UUID
import org.objenesis.ObjenesisStd
class Harness {
    private val type = Class.forName("dev.wuason.unearthMechanic.system.compatibilities.ia.ItemsAdderImpl")
    private val instance = ObjenesisStd().newInstance(type)
    private fun field(name: String): Any = type.getDeclaredField(name).apply { isAccessible = true }.get(null)
    @Suppress("UNCHECKED_CAST")
    val bases get() = field("furnitureBaseLocationByEntity") as MutableMap<UUID, Location>
    @Suppress("UNCHECKED_CAST")
    val rotationMap get() = field("rotationMap") as MutableMap<Location, Pair<Float, Float>>
    fun tryReplaceFurniture(entity: Entity, target: String, base: Location): Boolean {
        val method = type.getDeclaredMethod("tryReplaceFurniture", Entity::class.java, String::class.java, Location::class.java, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(instance, entity, target, base, true) as Boolean
    }
}
