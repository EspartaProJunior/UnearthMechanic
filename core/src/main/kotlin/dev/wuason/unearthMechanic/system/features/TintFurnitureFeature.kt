package dev.wuason.unearthMechanic.system.features

import dev.wuason.unearthMechanic.config.IGeneric
import dev.wuason.unearthMechanic.config.IStage
import dev.wuason.unearthMechanic.system.ILiveTool
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.SoundCategory
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.Event
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.meta.LeatherArmorMeta

class TintFurnitureFeature: AbstractFeature() {

    fun isPossibleFurnitureEntity(entity: Entity): Boolean {
        return entity is ItemFrame || entity is ArmorStand || entity is ItemDisplay || entity is TextDisplay
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
        liveTool.getITool().getTintFurniture()?.let { color ->
            if (color.isNotBlank()) {
                val (r, g, b) = color.split(",").map { it.trim().toInt().coerceIn(0,255) }
                val rgbColor = Color.fromRGB(r, g, b)

                //Bukkit.getConsoleSender().sendMessage("[UM] asd ${r},${g},${b}")

                val center = loc.clone().add(0.5, 0.5, 0.5)
                val nearby = loc.world.getNearbyEntities(center, 1.5, 1.5, 1.5)
                //Bukkit.getConsoleSender().sendMessage("[UM][isValidUUID] nearby.size=${nearby.size} (center=$center)")

                for (entity in nearby) {
                    //println(entity.type)

                    if (!isPossibleFurnitureEntity(entity) || !entity.isValid || entity.isDead) { continue }

                    if (entity.location.block != loc.block) { continue }

                    if(entity is ItemDisplay) {
                        val item = entity.itemStack ?: return
                        val meta = item.itemMeta ?: return
                        when (meta) {
                            is LeatherArmorMeta -> {
                                meta.setColor(rgbColor)
                                item.setItemMeta(meta)
                                entity.itemStack = item

                                //p.sendMessage("§aColor cambiado a RGB(${r}, ${g}, ${b}) ItemDisplay")
                            }
                            else -> {
                                //p.sendMessage("§cEste modelo admite teñido pero el tipo de meta no fue reconocido.")
                            }
                        }
                    }else if(entity is ItemFrame){
                        val item = entity.item ?: return
                        val meta = item.itemMeta ?: return
                        when (meta) {
                            is LeatherArmorMeta -> {
                                meta.setColor(rgbColor)
                                item.setItemMeta(meta)
                                entity.setItem(item)

                                //p.sendMessage("§aColor cambiado a RGB(${r}, ${g}, ${b}) ItemFrame")
                            }
                            else -> {
                                //p.sendMessage("§cEste modelo admite teñido pero el tipo de meta no fue reconocido.")
                            }
                        }
                    }else if(entity is ArmorStand){
                        val item = entity.getItem(EquipmentSlot.HEAD) ?: return
                        val meta = item.itemMeta ?: return
                        when (meta) {
                            is LeatherArmorMeta -> {
                                meta.setColor(rgbColor)
                                item.setItemMeta(meta)
                                entity.setItem(EquipmentSlot.HEAD,item)

                                //p.sendMessage("§aColor cambiado a RGB(${r}, ${g}, ${b}) ArmorStand")
                            }
                            else -> {
                                //p.sendMessage("§cEste modelo admite teñido pero el tipo de meta no fue reconocido.")
                            }
                        }
                    }
                }
            }
        }
    }
}