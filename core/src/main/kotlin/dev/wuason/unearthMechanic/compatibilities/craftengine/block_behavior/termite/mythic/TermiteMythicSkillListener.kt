package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.termite.mythic

import io.lumine.mythic.bukkit.events.MythicMechanicLoadEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

class TermiteMythicSkillListener : Listener {

    @EventHandler
    fun onMechanicLoad(event: MythicMechanicLoadEvent) {
        val manager = event.container.manager
        val line = event.container.configLine
        val config = event.config

        when (event.mechanicName.lowercase()) {
            "termitebefriendatcomposter" -> event.register(TermiteBefriendAtComposterMechanic(manager, line, config))
            "termiteconsumewood" -> event.register(TermiteConsumeWoodMechanic(manager, line, config))
            "termiteenternest" -> event.register(TermiteEnterNestMechanic(manager, line, config))
            "termitereturnhome" -> event.register(TermiteReturnHomeMechanic(manager, line, config))
        }
    }
}
