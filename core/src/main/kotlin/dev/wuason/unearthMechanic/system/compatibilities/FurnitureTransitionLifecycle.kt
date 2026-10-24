package dev.wuason.unearthMechanic.system.compatibilities

import org.bukkit.event.Event
import java.util.UUID

/** Optional lifecycle for compatibilities with asynchronous furniture application. */
interface FurnitureTransitionLifecycle {
    fun onFurnitureDispatchFinished(event: Event, hasDelay: Boolean)
    fun onFurnitureDelayFinished(event: Event)
    fun onFurnitureDelayStarted(event: Event, maximumTicks: Long)
    fun isFurnitureTransitionAlive(event: Event): Boolean
    fun checkFurnitureTransitionOwnership(event: Event, point: String): Boolean =
        isFurnitureTransitionAlive(event)
    /** A scheduled sequence now owns this exact source object; older manual work is stale. */
    fun onFurnitureSequenceClaim(uuid: UUID?, expectedSourceId: String?, point: String = "sequence_prelock") {}
}