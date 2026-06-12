package dev.wuason.unearthMechanic.compatibilities.mythicmobs

import org.bukkit.Bukkit

class MythicMobsPlugin {
    companion object {
        private const val NAME: String = "MythicMobs"

        fun isMythicMobsLoaded(): Boolean {
            return Bukkit.getPluginManager().getPlugin(NAME) != null
        }

        fun isMythicMobsEnabled(): Boolean {
            return Bukkit.getPluginManager().getPlugin(NAME) != null &&
                    Bukkit.getPluginManager().getPlugin(NAME)!!.isEnabled
        }
    }
}