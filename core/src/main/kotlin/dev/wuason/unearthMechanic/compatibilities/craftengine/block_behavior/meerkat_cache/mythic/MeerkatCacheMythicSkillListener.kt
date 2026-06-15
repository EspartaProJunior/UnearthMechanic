package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.meerkat_cache.mythic

import io.lumine.mythic.bukkit.events.MythicMechanicLoadEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

class MeerkatCacheMythicSkillListener : Listener {

    @EventHandler
    fun onMechanicLoad(event: MythicMechanicLoadEvent) {
        val manager = event.container.manager
        val line = event.container.configLine
        val config = event.config

        when (event.mechanicName.lowercase()) {
            "meerkattakenearbyitem" -> event.register(MeerkatTakeNearbyItemMechanic(manager, line, config))
            "meerkatburyhelditem" -> event.register(MeerkatBuryHeldItemMechanic(manager, line, config))
            "meerkatuseburrow" -> event.register(MeerkatUseBurrowMechanic(manager, line, config))
            "meerkattargetarachnid" -> event.register(MeerkatTargetArachnidMechanic(manager, line, config))
            "meerkatmountpig" -> event.register(MeerkatMountPigMechanic(manager, line, config))
            "meerkatdismountpig" -> event.register(MeerkatDismountPigMechanic(manager, line, config))
        }
    }
}
