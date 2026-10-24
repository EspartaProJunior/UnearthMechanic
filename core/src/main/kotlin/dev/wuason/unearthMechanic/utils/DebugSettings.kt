package dev.wuason.unearthMechanic.utils

import org.bukkit.configuration.ConfigurationSection

object DebugSettings {
    @Volatile
    private var itemsAdder = false

    @Volatile
    private var enabled = false

    @JvmStatic
    fun configure(config: ConfigurationSection?) {
        enabled = config?.getBoolean("debug.enabled", false) == true
        itemsAdder = enabled && config?.getBoolean("debug.itemsadder", false) == true
    }

    @JvmStatic
    fun isEnabled(): Boolean = enabled

    @JvmStatic
    fun isItemsAdderEnabled(): Boolean = itemsAdder
}