package dev.wuason.unearthMechanic.system.features

import com.nexomc.nexo.api.events.furniture.NexoFurnitureInteractEvent
import dev.lone.itemsadder.api.Events.FurnitureInteractEvent
import dev.wuason.unearthMechanic.compatibilities.craftengine.CraftEnginePlugin
import dev.wuason.unearthMechanic.config.IGeneric
import dev.wuason.unearthMechanic.config.IStage
import dev.wuason.unearthMechanic.system.ILiveTool
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility
import dev.wuason.unearthMechanic.utils.AdventureUtils
import io.th0rgal.oraxen.api.events.furniture.OraxenFurnitureInteractEvent
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.Event
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta
import kotlin.math.abs

class TintFurnitureFeature : AbstractFeature() {

    private val scanRadius = 1.75

    private fun isCraftEngineFurnitureEntity(entity: Entity): Boolean {
        if (!CraftEnginePlugin.isCraftEngineEnabled() || !CraftEnginePlugin.isCraftEngineLoaded()) return false

        return runCatching {
            CraftEngineFurniture.isFurniture(entity) ||
                    CraftEngineFurniture.isCollisionEntity(entity) ||
                    CraftEngineFurniture.isSeat(entity)
        }.getOrDefault(false)
    }

    private fun getCraftEngineFurniture(entity: Entity): Any? {
        if (!CraftEnginePlugin.isCraftEngineEnabled() || !CraftEnginePlugin.isCraftEngineLoaded()) return null

        return runCatching {
            CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity)
                ?: CraftEngineFurniture.getLoadedFurnitureByCollider(entity)
        }.getOrNull()
    }

    private fun isPossibleFurnitureEntity(entity: Entity): Boolean {
        return entity is ItemFrame ||
                entity is ArmorStand ||
                entity is ItemDisplay ||
                entity is TextDisplay ||
                entity is Interaction ||
                isCraftEngineFurnitureEntity(entity)
    }

    private fun parseRgb(raw: String): Color? {
        val parts = raw.split(",").map { it.trim() }
        if (parts.size != 3) return null

        val r = parts[0].toIntOrNull()?.coerceIn(0, 255) ?: return null
        val g = parts[1].toIntOrNull()?.coerceIn(0, 255) ?: return null
        val b = parts[2].toIntOrNull()?.coerceIn(0, 255) ?: return null

        return Color.fromRGB(r, g, b)
    }

    private fun isNearFurnitureBlock(entity: Entity, loc: Location): Boolean {
        val world = loc.world ?: return false
        if (entity.world != world) return false

        val center = loc.clone().add(0.5, 0.5, 0.5)

        val furniture = getCraftEngineFurniture(entity)
        if (furniture != null) {
            val furnitureLoc = runCatching {
                val method = furniture.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 &&
                            Location::class.java.isAssignableFrom(it.returnType) &&
                            (
                                    it.name == "location" ||
                                            it.name == "getLocation" ||
                                            it.name == "baseLocation" ||
                                            it.name == "getBaseLocation"
                                    )
                }

                method?.invoke(furniture) as? Location
            }.getOrNull()

            if (furnitureLoc != null && furnitureLoc.world == world) {
                val dx = abs(furnitureLoc.x - center.x)
                val dy = abs(furnitureLoc.y - center.y)
                val dz = abs(furnitureLoc.z - center.z)

                if (dx <= scanRadius && dy <= scanRadius && dz <= scanRadius) {
                    return true
                }
            }
        }

        val eLoc = entity.location

        val dx = abs(eLoc.x - center.x)
        val dy = abs(eLoc.y - center.y)
        val dz = abs(eLoc.z - center.z)

        if (dx <= scanRadius && dy <= scanRadius && dz <= scanRadius) {
            return true
        }

        val box = entity.boundingBox
        return box.expand(0.35).contains(center.toVector())
    }

    private fun tintItemStack(item: ItemStack?, color: Color): ItemStack? {
        if (item == null || item.type.isAir) return null

        val meta = item.itemMeta ?: return null

        if (meta !is LeatherArmorMeta) {
            return null
        }

        meta.setColor(color)
        item.setItemMeta(meta)
        return item
    }

    private fun tintItemDisplay(entity: ItemDisplay, color: Color): Boolean {
        val tinted = tintItemStack(entity.itemStack, color) ?: return false
        entity.itemStack = tinted
        return true
    }

    private fun tintItemFrame(entity: ItemFrame, color: Color): Boolean {
        val tinted = tintItemStack(entity.item, color) ?: return false
        entity.setItem(tinted)
        return true
    }

    private fun tintArmorStand(entity: ArmorStand, color: Color): Boolean {
        var changed = false

        val slots = listOf(
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET,
            EquipmentSlot.HAND,
            EquipmentSlot.OFF_HAND
        )

        for (slot in slots) {
            val current = entity.getItem(slot)
            val tinted = tintItemStack(current, color) ?: continue
            entity.setItem(slot, tinted)
            changed = true
        }

        return changed
    }

    private fun tintCraftEngineFurnitureEntities(
        entity: Entity,
        color: Color,
        visited: MutableSet<Int>
    ): Boolean {
        val furniture = getCraftEngineFurniture(entity) ?: return false

        var changed = false

        for (method in furniture.javaClass.methods) {
            if (method.parameterCount != 0) continue
            if (method.name == "getClass") continue

            val value = runCatching {
                method.invoke(furniture)
            }.getOrNull() ?: continue

            when (value) {
                is Entity -> {
                    if (tintEntity(value, color, visited)) changed = true
                }

                is Collection<*> -> {
                    for (entry in value) {
                        if (entry is Entity && tintEntity(entry, color, visited)) {
                            changed = true
                        }
                    }
                }

                is Array<*> -> {
                    for (entry in value) {
                        if (entry is Entity && tintEntity(entry, color, visited)) {
                            changed = true
                        }
                    }
                }

                is Map<*, *> -> {
                    for (entry in value.values) {
                        when (entry) {
                            is Entity -> {
                                if (tintEntity(entry, color, visited)) changed = true
                            }

                            is Collection<*> -> {
                                for (subEntry in entry) {
                                    if (subEntry is Entity && tintEntity(subEntry, color, visited)) {
                                        changed = true
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return changed
    }

    private fun tintEntity(
        entity: Entity,
        color: Color,
        visited: MutableSet<Int> = mutableSetOf()
    ): Boolean {
        if (!entity.isValid || entity.isDead) return false

        val id = System.identityHashCode(entity)
        if (!visited.add(id)) return false

        var changed = when (entity) {
            is ItemDisplay -> tintItemDisplay(entity, color)
            is ItemFrame -> tintItemFrame(entity, color)
            is ArmorStand -> tintArmorStand(entity, color)
            else -> false
        }

        for (passenger in entity.passengers) {
            if (tintEntity(passenger, color, visited)) {
                changed = true
            }
        }

        if (isCraftEngineFurnitureEntity(entity)) {
            if (tintCraftEngineFurnitureEntities(entity, color, visited)) {
                changed = true
            }
        }

        return changed
    }

    override fun onPreApply(
        p: Player,
        comp: ICompatibility,
        event: Event,
        loc: Location,
        liveTool: ILiveTool,
        iStage: IStage,
        iGeneric: IGeneric
    ) {
        val rawColor = liveTool.getITool().getTintFurniture() ?: return
        if (rawColor.isBlank()) return

        val rgbColor = parseRgb(rawColor) ?: run {
            AdventureUtils.sendMessage(
                p,
                "<red>Invalid format in tintfurniture= <white>$rawColor <gray>use: <white>R,G,B"
            )
            return
        }

        // Minecraft Entity
        if (event is PlayerInteractEntityEvent) {
            val clicked = event.rightClicked

            if (!isPossibleFurnitureEntity(clicked)) return

            tintEntity(clicked, rgbColor)
            return
        }
        // Oraxen Furniture
        if (event is OraxenFurnitureInteractEvent) {
            val entity = event.baseEntity
            if (!isPossibleFurnitureEntity(entity)) return

            tintEntity(entity, rgbColor)
            return
        }
        // Nexo Furniture
        if (event is NexoFurnitureInteractEvent) {
            val entity = event.baseEntity
            if (!isPossibleFurnitureEntity(entity)) return

            tintEntity(entity, rgbColor)
            return
        }
        // ItemsAdder Furniture
        if (event is FurnitureInteractEvent) {
            val entity = event.bukkitEntity
            if (!isPossibleFurnitureEntity(entity)) return

            tintEntity(entity, rgbColor)
            return
        }
        // CraftEngine Furniture
        if (event is net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent) {
            val entity = event.furniture().bukkitEntity()
            val visited = mutableSetOf<Int>()

            if (entity != null && isPossibleFurnitureEntity(entity)) {
                tintEntity(entity, rgbColor, visited)
            }

            for (method in event.furniture().javaClass.methods) {
                if (method.parameterCount != 0) continue
                if (method.name == "getClass") continue

                val value = runCatching {
                    method.invoke(event.furniture())
                }.getOrNull() ?: continue

                when (value) {
                    is Entity -> {
                        tintEntity(value, rgbColor, visited)
                    }

                    is Collection<*> -> {
                        for (entry in value) {
                            if (entry is Entity) {
                                tintEntity(entry, rgbColor, visited)
                            }
                        }
                    }

                    is Array<*> -> {
                        for (entry in value) {
                            if (entry is Entity) {
                                tintEntity(entry, rgbColor, visited)
                            }
                        }
                    }

                    is Map<*, *> -> {
                        for (entry in value.values) {
                            if (entry is Entity) {
                                tintEntity(entry, rgbColor, visited)
                            }
                        }
                    }
                }
            }

            return
        }

        // Fallback
        val world = loc.world ?: return
        val center = loc.clone().add(0.5, 0.5, 0.5)

        val target = world.getNearbyEntities(
            center,
            scanRadius,
            scanRadius,
            scanRadius
        )
            .asSequence()
            .filter { isPossibleFurnitureEntity(it) }
            .filter { isNearFurnitureBlock(it, loc) }
            .minByOrNull { it.location.distanceSquared(center) }
            ?: return

        tintEntity(target, rgbColor)
    }
}