package dev.wuason.unearthMechanic.system.features

import dev.wuason.unearthMechanic.config.IGeneric
import dev.wuason.unearthMechanic.config.IStage
import dev.wuason.unearthMechanic.events.FakePlayerInteractEvent
import dev.wuason.unearthMechanic.system.ILiveTool
import dev.wuason.unearthMechanic.system.compatibilities.ClientFurnitureInteraction
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility
import dev.wuason.unearthMechanic.utils.DebugSettings
import org.bukkit.Bukkit
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
        if (event is FakePlayerInteractEvent) {
            if (DebugSettings.isItemsAdderEnabled()) diagnostic(comp, event, false, false, "sequence_fake_event")
            return
        }

        if (event is PlayerInteractEvent && event.hand != EquipmentSlot.HAND) {
            if (DebugSettings.isItemsAdderEnabled()) diagnostic(comp, event, false, false, "off_hand")
            return
        }

        if (comp is ClientFurnitureInteraction && comp.isClientFurnitureInteraction(event)) {
            if (DebugSettings.isItemsAdderEnabled()) diagnostic(comp, event, true, false, "itemsadder_furniture_client_input")
            return
        }

        p.swingMainHand()
        if (DebugSettings.isItemsAdderEnabled()) diagnostic(comp, event, false, true, "existing_server_swing")
    }

    private fun diagnostic(comp: ICompatibility, event: Event, expected: Boolean, executed: Boolean, reason: String) {
        try {
            Bukkit.getLogger().info("[UM-IA-DIAG] SWING_DECISION eventClass=${event.javaClass.name} " +
                    "compatibility=${comp.name()} clientInteractionExpected=$expected serverSwingExecuted=$executed " +
                    "reason=$reason tick=${Bukkit.getCurrentTick()}")
        } catch (_: Exception) {
        } catch (_: LinkageError) {
        }
    }
}