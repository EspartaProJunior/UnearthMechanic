package dev.wuason.unearthMechanic.system.compatibilities.ia

import org.bukkit.Location
import org.bukkit.event.Event
import java.util.UUID
import org.bukkit.util.BoundingBox
import java.util.IdentityHashMap
import kotlin.math.floor

/** Identity-owned leases: an old callback cannot release or execute a newer transition. */
class FurnitureTransitionGate {
    companion object {
        /** Mirrors IA's inclusive unit grid and five-locations-per-tick barrier batching. */
        fun settlingTicks(box: BoundingBox?): Long {
            if (box == null) return 6L
            val b = box.clone().expand(-0.5)
            val count = (floor(b.widthX).toLong() + 1) * (floor(b.height).toLong() + 1) * (floor(b.widthZ).toLong() + 1)
            return 6L + if (count > 15) (count + 4L) / 5L else 0L
        }
    }
    enum class Phase { DISPATCH, DELAY, SCHEDULED, SETTLING }
    class Lease(
        val uuid: UUID,
        val event: Event,
        val base: Location,
        val startedTick: Int,
        val expectedSourceId: String? = null,
        val generation: Long
    ) {
        @Volatile var phase = Phase.DISPATCH
        @Volatile var stale = false
        @Volatile var staleReason: String? = null
    }
    private val leases = mutableMapOf<UUID, Lease>()
    private val eventLeases = IdentityHashMap<Event, Lease>()
    private var nextGeneration = 0L

    @JvmOverloads @Synchronized fun acquire(uuid: UUID, event: Event, base: Location, tick: Int, expectedSourceId: String? = null): Lease? {
        if (leases.containsKey(uuid)) return null
        return Lease(uuid, event, base.clone(), tick, expectedSourceId, ++nextGeneration).also {
            leases[uuid] = it
            eventLeases[event] = it
        }
    }
    @Synchronized fun current(uuid: UUID): Lease? = leases[uuid]
    @Synchronized fun forEvent(event: Event): Lease? = eventLeases[event]?.takeIf { leases[it.uuid] === it }
    @Synchronized fun recordForEvent(event: Event): Lease? = eventLeases[event]
    @Synchronized fun owns(lease: Lease): Boolean = leases[lease.uuid] === lease
    @Synchronized fun phase(lease: Lease, phase: Phase): Boolean {
        if (!owns(lease)) return false
        lease.phase = phase
        return true
    }
    @Synchronized fun release(lease: Lease): Boolean {
        if (!owns(lease)) return false
        leases.remove(lease.uuid)
        eventLeases.remove(lease.event, lease)
        return true
    }
    @Synchronized fun invalidateForSequence(uuid: UUID, reason: String): Lease? {
        val lease = leases.remove(uuid) ?: return null
        lease.stale = true
        lease.staleReason = reason
        return lease
    }
    @Synchronized fun discardRecord(lease: Lease) {
        eventLeases.remove(lease.event, lease)
    }
}