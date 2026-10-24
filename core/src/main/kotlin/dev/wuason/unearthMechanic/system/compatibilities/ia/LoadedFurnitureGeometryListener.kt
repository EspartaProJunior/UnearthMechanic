package dev.wuason.unearthMechanic.system.compatibilities.ia

import dev.lone.itemsadder.api.ItemsAdder
import dev.lone.itemsadder.api.Events.ItemsAdderLoadDataEvent
import dev.wuason.unearthMechanic.utils.FoliaUtils
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.ItemFrame
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.event.world.EntitiesUnloadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.Plugin
import java.util.UUID

/** One loaded-chunk catch-up; afterwards finite event work. Never polls or loads chunks. */
class LoadedFurnitureGeometryListener(
    private val plugin: Plugin,
    private val repair: (ItemFrame, String) -> Unit
) : Listener {
    private class Candidate(val entity: ItemFrame, val source: String, val world: UUID, val location: Location)
    private val pending = LinkedHashMap<UUID, Candidate>()
    private val inFlight = HashMap<UUID, Candidate>()
    private val startupChunks = ArrayDeque<Chunk>()
    private var ready = false
    private var seeded = false
    private var scheduled = false
    private var draining = false
    private class ChunkJob(val world: UUID)
    private val chunkJobs = HashSet<ChunkJob>()
    @Volatile private var stopped = false

    fun start() {
        if (Bukkit.getPluginManager().getPlugin("ItemsAdder")?.description?.version != "4.0.18") return
        Bukkit.getPluginManager().registerEvents(this, plugin)
        // u=true follows registry construction. Geometry reads only that completed registry.
        if (ItemsAdder.areItemsLoaded()) dataReady()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun dataLoaded(event: ItemsAdderLoadDataEvent) { dataReady() }

    @Synchronized private fun dataReady() {
        if (stopped) return
        ready = LoadedFurnitureGeometry.supported()
        if (!ready) { stopped = true; pending.clear(); startupChunks.clear(); return }
        if (!seeded) {
            seeded = true
            // Only snapshots already loaded chunks. Entity access happens in bounded batches.
            for (world in Bukkit.getWorlds()) startupChunks.addAll(world.loadedChunks)
        }
        schedule()
    }

    @Synchronized private fun enqueue(entity: ItemFrame, source: String) {
        val uuid = entity.uniqueId
        if (!inFlight.containsKey(uuid)) pending.putIfAbsent(uuid,
            Candidate(entity, source, entity.world.uid, entity.location.clone()))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    @Synchronized fun entitiesLoaded(event: EntitiesLoadEvent) {
        if (stopped || !ready) return
        // Event-supplied list; never request entities or load a chunk here.
        for (entity in event.entities) if (entity is ItemFrame) enqueue(entity, "chunk_load")
        schedule()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    @Synchronized fun entitiesUnloaded(event: EntitiesUnloadEvent) {
        for (entity in event.entities) if (entity is ItemFrame) {
            pending.remove(entity.uniqueId)
            inFlight.remove(entity.uniqueId)
        }
        schedule()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Synchronized fun worldUnloaded(event: WorldUnloadEvent) {
        startupChunks.removeAll { it.world == event.world }
        pending.values.removeAll { it.world == event.world.uid }
        inFlight.values.removeAll { it.world == event.world.uid }
        chunkJobs.removeAll { it.world == event.world.uid }
        schedule()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    @Synchronized fun pluginDisabled(event: PluginDisableEvent) {
        if (event.plugin === plugin || event.plugin.name == "ItemsAdder") {
            stopped = true
            pending.clear(); inFlight.clear(); startupChunks.clear()
            chunkJobs.clear()
        }
    }

    @Synchronized private fun schedule() {
        if (stopped || !ready || scheduled || draining || chunkJobs.isNotEmpty() || inFlight.isNotEmpty()
            || (pending.isEmpty() && startupChunks.isEmpty())) return
        scheduled = true
        try { FoliaUtils.runNextTick { drain() } }
        catch (failure: Throwable) {
            scheduled = false; pending.clear(); startupChunks.clear()
            plugin.logger.warning("[UM-IA] Loaded geometry scheduling failed: ${failure.javaClass.simpleName}")
        }
    }

    @Synchronized private fun inspect(chunk: Chunk, job: ChunkJob) {
        try {
            // getEntities may fetch NBT if entities are absent: explicitly disallow that.
            if (!stopped && chunkJobs.contains(job) && chunk.isLoaded && chunk.isEntitiesLoaded)
                for (entity in chunk.entities) if (entity is ItemFrame) enqueue(entity, "startup_loaded_chunk")
        } finally { chunkJobs.remove(job); schedule() }
    }

    @Synchronized private fun complete(uuid: UUID, candidate: Candidate) {
        inFlight.remove(uuid, candidate)
        schedule()
    }

    @Synchronized private fun drain() {
        scheduled = false
        if (stopped || !plugin.isEnabled || !ready) {
            pending.clear(); inFlight.clear(); startupChunks.clear(); return
        }
        draining = true
        try {
            repeat(minOf(4, startupChunks.size)) {
                val chunk = startupChunks.removeFirst()
                val job = ChunkJob(chunk.world.uid)
                chunkJobs.add(job)
                try {
                    FoliaUtils.runAtLocation(Location(chunk.world, (chunk.x * 16 + 8).toDouble(),
                        chunk.world.minHeight.toDouble(), (chunk.z * 16 + 8).toDouble())) { inspect(chunk, job) }
                } catch (failure: Throwable) { chunkJobs.remove(job); plugin.logger.warning("[UM-IA] Loaded chunk inspection scheduling failed: ${failure.javaClass.simpleName}") }
            }
            val batch = pending.entries.take(32).map { it.key to it.value }
            for ((uuid, candidate) in batch) {
                pending.remove(uuid); inFlight[uuid] = candidate
                try {
                    // Region callback always completes, even if an entity was removed meanwhile.
                    FoliaUtils.runAtLocation(candidate.location) {
                        try {
                            val owns = synchronized(this) { inFlight[uuid] === candidate }
                            val entity = candidate.entity
                            if (owns && !stopped && plugin.isEnabled && entity.isValid && !entity.isDead) {
                                val p = entity.location
                                if (p.world == candidate.location.world && p.blockX shr 4 == candidate.location.blockX shr 4
                                    && p.blockZ shr 4 == candidate.location.blockZ shr 4
                                    && entity.world.isChunkLoaded(p.blockX shr 4, p.blockZ shr 4))
                                    repair(entity, candidate.source)
                            }
                        } finally { complete(uuid, candidate) }
                    }
                } catch (failure: Throwable) {
                    complete(uuid, candidate)
                    plugin.logger.warning("[UM-IA] Loaded geometry region scheduling failed: ${failure.javaClass.simpleName}")
                }
            }
        } finally { draining = false; schedule() }
    }

    @Synchronized internal fun pendingCount(): Int = pending.size + inFlight.size + startupChunks.size
}