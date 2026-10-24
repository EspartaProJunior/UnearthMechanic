package dev.wuason.unearthMechanic.system.compatibilities.ia

import dev.lone.itemsadder.api.CustomStack
import org.bukkit.configuration.ConfigurationSection

/** Read IA's resolved public configuration only when the item inherits a template. */
internal object ItemsAdderFurnitureSettings {
    @JvmStatic
    fun get(stack: CustomStack?): ConfigurationSection? {
        val config = stack?.config ?: return null
        var item = config.getConfigurationSection("items.${stack.id}") ?: return null

        if (item.contains("variant_of")) {
            item = runCatching {
                stack.javaClass
                    .getMethod("getConfigSectionCopy", Boolean::class.javaPrimitiveType!!)
                    .invoke(stack, true) as? ConfigurationSection
            }.getOrNull() ?: return null
        }

        return item.getConfigurationSection("behaviours.furniture")
    }
}