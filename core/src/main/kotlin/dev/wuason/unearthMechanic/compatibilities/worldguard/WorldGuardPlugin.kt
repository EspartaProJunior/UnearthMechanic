package dev.wuason.unearthMechanic.compatibilities.worldguard

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import org.bukkit.Bukkit
import org.bukkit.Location

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

        fun getRegionIdsAt(location: Location): Set<String> {
            if (!isWorldGuardEnabled()) return emptySet()

            val world = location.world ?: return emptySet()

            return try {
                val container = WorldGuard.getInstance().platform.regionContainer
                val query = container.createQuery()
                val applicableSet = query.getApplicableRegions(BukkitAdapter.adapt(location))

                applicableSet.regions
                    .map { it.id }
                    .toSet()
            } catch (ex: Throwable) {
                emptySet()
            }
        }
    }
}