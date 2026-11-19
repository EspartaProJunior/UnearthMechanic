package dev.wuason.unearthMechanic.system.features

import dev.wuason.unearthMechanic.config.IGeneric
import dev.wuason.unearthMechanic.config.IStage
import dev.wuason.unearthMechanic.system.ILiveTool
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility
import dev.wuason.unearthMechanic.utils.AdventureUtils
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

/**
 * Applies the tintfurniture tool option without linking this class to optional
 * furniture-plugin APIs. The active [ICompatibility] selects the integration.
 */
class TintFurnitureFeature : AbstractFeature() {

    private val scanRadius = 1.75

    private fun parseRgb(raw: String): Color? {
        val parts = raw.split(",").map { it.trim() }
        if (parts.size != 3) return null

        val red = parts[0].toIntOrNull()?.coerceIn(0, 255) ?: return null
        val green = parts[1].toIntOrNull()?.coerceIn(0, 255) ?: return null
        val blue = parts[2].toIntOrNull()?.coerceIn(0, 255) ?: return null

        return Color.fromRGB(red, green, blue)
    }

    private fun adapterIs(comp: ICompatibility, name: String): Boolean {
        return comp.adapterComp()?.type?.equals(name, ignoreCase = true) == true
    }

    private fun isPossibleFurnitureEntity(entity: Entity): Boolean {
        return entity is ItemFrame ||
                entity is ArmorStand ||
                entity is ItemDisplay ||
                entity is TextDisplay ||
                entity is Interaction
    }

    private fun tintItemStack(item: ItemStack?, color: Color): ItemStack? {
        if (item == null || item.type.isAir) return null

        val meta = item.itemMeta as? LeatherArmorMeta ?: return null
        meta.setColor(color)
        item.itemMeta = meta
        return item
    }

    private fun tintEntity(
        entity: Entity,
        color: Color,
        visited: MutableSet<Int> = mutableSetOf()
    ): Boolean {
        if (!entity.isValid || entity.isDead) return false
        if (!visited.add(System.identityHashCode(entity))) return false

        var changed = when (entity) {
            is ItemDisplay -> tintItemStack(entity.itemStack, color)?.let {
                entity.itemStack = it
                true
            } ?: false

            is ItemFrame -> tintItemStack(entity.item, color)?.let {
                entity.setItem(it)
                true
            } ?: false

            is ArmorStand -> tintArmorStand(entity, color)
            else -> false
        }

        entity.passengers.forEach { passenger ->
            if (tintEntity(passenger, color, visited)) changed = true
        }

        return changed
    }

    private fun tintArmorStand(entity: ArmorStand, color: Color): Boolean {
        var changed = false

        listOf(
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET,
            EquipmentSlot.HAND,
            EquipmentSlot.OFF_HAND
        ).forEach { slot ->
            val tinted = tintItemStack(entity.getItem(slot), color) ?: return@forEach
            entity.setItem(slot, tinted)
            changed = true
        }

        return changed
    }

    private fun callNoArg(target: Any, name: String): Any? {
        return runCatching {
            target.javaClass.methods
                .firstOrNull { it.name == name && it.parameterCount == 0 }
                ?.invoke(target)
        }.getOrNull()
    }

    private fun invokeSingleArg(target: Any, name: String, argument: Any): Boolean {
        return runCatching {
            val method = target.javaClass.methods.firstOrNull {
                it.name == name &&
                        it.parameterCount == 1 &&
                        it.parameterTypes[0].isAssignableFrom(argument.javaClass)
            } ?: return false

            method.invoke(target, argument)
            true
        }.getOrDefault(false)
    }

    /**
     * CraftEngine furniture is rendered client-side. Its source item carries the
     * dyed-color component and refreshElements() republishes the visual elements.
     *
     * Reflection is intentionally limited to this adapter route so the class can
     * be loaded when CraftEngine is absent.
     */
    private fun tintCraftEngineFurniture(event: Event, color: Color): Boolean {
        if (event.javaClass.name !=
            "net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent"
        ) return false

        return runCatching {
            val furniture = callNoArg(event, "furniture") ?: return false
            val sourceItem = callNoArg(furniture, "sourceItem")
                ?: callNoArg(furniture, "buildNewFurnitureItem")
                ?: return false

            val colorClass = Class.forName("net.momirealms.craftengine.core.util.Color")
            val craftEngineColor = colorClass
                .getConstructor(Integer.TYPE, Integer.TYPE, Integer.TYPE)
                .newInstance(color.red, color.green, color.blue)

            if (!invokeSingleArg(sourceItem, "dyedColor", craftEngineColor)) return false
            if (!invokeSingleArg(furniture, "setSourceItem", sourceItem)) return false

            callNoArg(furniture, "refreshElements")
            true
        }.getOrDefault(false)
    }

    private fun tintPluginFurnitureEntity(event: Event, color: Color): Boolean {
        val entity = when (event.javaClass.name) {
            "dev.lone.itemsadder.api.Events.FurnitureInteractEvent" -> callNoArg(event, "getBukkitEntity")
            "com.nexomc.nexo.api.events.furniture.NexoFurnitureInteractEvent" -> callNoArg(event, "getBaseEntity")
            "io.th0rgal.oraxen.api.events.furniture.OraxenFurnitureInteractEvent" -> callNoArg(event, "getBaseEntity")
            else -> null
        } as? Entity ?: return false

        return isPossibleFurnitureEntity(entity) && tintEntity(entity, color)
    }

    private fun tintUsingAdapter(
        comp: ICompatibility,
        event: Event,
        color: Color
    ): Boolean {
        return when {
            adapterIs(comp, "CraftEngine") || adapterIs(comp, "ce") ->
                tintCraftEngineFurniture(event, color)

            adapterIs(comp, "ItemsAdder") ||
                    adapterIs(comp, "Nexo") ||
                    adapterIs(comp, "Oraxen") ->
                tintPluginFurnitureEntity(event, color)

            else -> false
        }
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

        if (tintUsingAdapter(comp, event, rgbColor)) return

        if (event is PlayerInteractEntityEvent) {
            val clicked = event.rightClicked
            if (isPossibleFurnitureEntity(clicked)) {
                tintEntity(clicked, rgbColor)
            }
            return
        }

        val world = loc.world ?: return
        val center = loc.clone().add(0.5, 0.5, 0.5)
        val target = world.getNearbyEntities(center, scanRadius, scanRadius, scanRadius)
            .asSequence()
            .filter(::isPossibleFurnitureEntity)
            .minByOrNull { it.location.distanceSquared(center) }
            ?: return

        tintEntity(target, rgbColor)
    }
}