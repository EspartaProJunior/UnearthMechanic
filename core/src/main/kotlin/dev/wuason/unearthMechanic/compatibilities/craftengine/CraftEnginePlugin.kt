package dev.wuason.unearthMechanic.compatibilities.craftengine

import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent
import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack


class CraftEnginePlugin {
    companion object {
        private var NAME: String = "CraftEngine"

        /*fun isCraftEngineLoaded(): Boolean {
            return Bukkit.getPluginManager().getPlugin(NAME) != null
        }*/

        private var isLoaded: Boolean = false

        @EventHandler
        fun craftEngineLoadEvent(event: CraftEngineReloadEvent) {
            if (event.isFirstReload()) isLoaded = true;
        }

        fun isCraftEngineLoaded(): Boolean {
            return isLoaded
        }

        fun isCraftEngineEnabled(): Boolean {
            return Bukkit.getPluginManager().getPlugin(NAME) != null && Bukkit.getPluginManager().getPlugin(NAME)!!
                .isEnabled
        }
    }
}