package dev.wuason.unearthMechanic.utils

import org.bukkit.configuration.ConfigurationSection

object DebugSettings {
    @Volatile
    private var itemsAdder = false

    @Volatile
    private var enabled = false

    /**
     * StageManager tracing is separate from the global debug switch because it
     * is intentionally very verbose and should stay off unless requested.
     */
    @Volatile
    private var stageManager = false

    @JvmStatic
    fun configure(config: ConfigurationSection?) {
        enabled = config?.getBoolean("debug.enabled", false) == true
        itemsAdder = enabled && config?.getBoolean("debug.itemsadder", false) == true
        stageManager = config?.getBoolean("debug.stage_manager", false) == true
    }

    @JvmStatic
    fun isEnabled(): Boolean = enabled

    @JvmStatic
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    @JvmStatic
    fun isItemsAdderEnabled(): Boolean = itemsAdder

    @JvmStatic
    fun setItemsAdderEnabled(value: Boolean) {
        itemsAdder = value
    }

    @JvmStatic
    fun isStageManagerEnabled(): Boolean = stageManager

    @JvmStatic
    fun setStageManagerEnabled(value: Boolean) {
        stageManager = value
    }
}
