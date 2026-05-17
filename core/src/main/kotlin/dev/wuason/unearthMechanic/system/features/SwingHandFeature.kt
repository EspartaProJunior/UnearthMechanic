package dev.wuason.unearthMechanic.system.features

import dev.wuason.unearthMechanic.config.IGeneric
import dev.wuason.unearthMechanic.config.IStage
import dev.wuason.unearthMechanic.events.FakePlayerInteractEvent
import dev.wuason.unearthMechanic.system.ILiveTool
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

class SwingHandFeature : AbstractFeature() {

    override fun onPreApply(
        p: Player,
        comp: ICompatibility,
        event: Event,
        loc: Location,
        liveTool: ILiveTool,
        iStage: IStage,
        iGeneric: IGeneric
    ) {
        // Prevents the steps in the sequences from running automatically.
        if (event is FakePlayerInteractEvent) return

        if (event is PlayerInteractEvent && event.hand != EquipmentSlot.HAND) return

        p.swingMainHand()
    }
}