package dev.wuason.unearthMechanic.compatibilities.luckperms

import org.bukkit.Bukkit


class LuckPermsPlugin {
    companion object {
        private var NAME: String = "LuckPerms"

        fun isLuckPermsLoaded(): Boolean {
            return Bukkit.getPluginManager().getPlugin(NAME) != null
        }

        fun isLuckPermsEnabled(): Boolean {
            return Bukkit.getPluginManager().getPlugin(NAME) != null && Bukkit.getPluginManager().getPlugin(NAME)!!
                .isEnabled
        }
    }
}