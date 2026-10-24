package dev.wuason.unearthMechanic.system.compatibilities

import dev.wuason.unearthMechanic.system.StageManager
import org.bukkit.Location
import org.bukkit.event.Event

/** Small bridge used to preserve the shipped StageManager bytecode during local assembly. */
object FurnitureTransitionHooks {
    @JvmStatic fun dispatchFinished(manager: StageManager, event: Event, compatibility: ICompatibility, location: Location) {
        (compatibility as? FurnitureTransitionLifecycle)?.onFurnitureDispatchFinished(event, manager.getDelays().containsKey(location.block.location))
    }
    @JvmStatic fun delayFinished(compatibility: ICompatibility, event: Event) {
        (compatibility as? FurnitureTransitionLifecycle)?.onFurnitureDelayFinished(event)
    }
    @JvmStatic fun delayStarted(compatibility: ICompatibility, event: Event, maximumTicks: Long) {
        (compatibility as? FurnitureTransitionLifecycle)?.onFurnitureDelayStarted(event, maximumTicks)
    }
    @JvmStatic fun alive(compatibility: ICompatibility, event: Event): Boolean =
        (compatibility as? FurnitureTransitionLifecycle)?.isFurnitureTransitionAlive(event) ?: true
}