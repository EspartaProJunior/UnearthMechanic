package dev.wuason.unearthMechanic.compatibilities.worldguard

import org.bukkit.Bukkit

class WorldGuardPlugin {
    companion object {
        private var NAME: String = "WorldGuard"

        fun isWorldGuardLoaded(): Boolean {
            return Bukkit.getPluginManager().getPlugin(NAME) != null
        }

        fun isWorldGuardEnabled(): Boolean {
            return Bukkit.getPluginManager().getPlugin(NAME) != null && Bukkit.getPluginManager().getPlugin(NAME)!!
                .isEnabled
        }
    }
}