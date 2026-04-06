package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.ashes

import dev.wuason.unearthMechanic.UnearthMechanic
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import java.util.UUID

class AshesEnvironmentListener(
    private val core: UnearthMechanic
) : Listener {

    private val ashesId = "elitefantasy:ashes_block"

    // position -> layers
    private val trackedAshes = hashMapOf<TrackedPos, Int>()
    private val ashesByChunk = hashMapOf<TrackedChunk, MutableSet<TrackedPos>>()

    private data class TrackedChunk(
        val worldId: UUID,
        val chunkX: Int,
        val chunkZ: Int
    ) {
        companion object {
            fun of(pos: TrackedPos): TrackedChunk {
                return TrackedChunk(
                    pos.worldId,
                    pos.x shr 4,
                    pos.z shr 4
                )
            }
        }
    }

    init {
        startRainTask()

        // Reindex existing ashes once the plugin has finished loading
        Bukkit.getScheduler().runTask(core, Runnable {
            scanLoadedChunks()
        })
    }

    fun trackAsh(location: Location, layers: Int) {
        //core.logger.info("trackAsh layers=$layers loc=${location.blockX},${location.blockY},${location.blockZ}")
        updateAsh(TrackedPos.from(location), layers)
    }

    private fun putAsh(pos: TrackedPos, layers: Int) {
        trackedAshes[pos] = layers
        ashesByChunk
            .getOrPut(TrackedChunk.of(pos)) { hashSetOf() }
            .add(pos)
    }

    private fun removeAsh(pos: TrackedPos) {
        trackedAshes.remove(pos)

        val chunk = TrackedChunk.of(pos)
        val positions = ashesByChunk[chunk] ?: return
        positions.remove(pos)

        if (positions.isEmpty()) {
            ashesByChunk.remove(chunk)
        }
    }

    private fun updateAsh(pos: TrackedPos, layers: Int) {
        if (layers <= 0) {
            removeAsh(pos)
            return
        }
        putAsh(pos, layers)
    }

    @EventHandler(ignoreCancelled = true)
    fun onChunkLoad(event: ChunkLoadEvent) {
        scanChunk(event.chunk)
    }

    @EventHandler(ignoreCancelled = true)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        val chunkPos = TrackedChunk(
            event.chunk.world.uid,
            event.chunk.x,
            event.chunk.z
        )

        val positions = ashesByChunk.remove(chunkPos) ?: return
        for (pos in positions) {
            trackedAshes.remove(pos)
        }
    }

    private fun getAshLayersNearLanding(location: Location): Int? {
        val world = location.world ?: return null

        var maxLayers: Int? = null

        for (x in (location.blockX - 1)..(location.blockX + 1)) {
            for (z in (location.blockZ - 1)..(location.blockZ + 1)) {
                for (y in (location.blockY - 1)..location.blockY) {
                    val block = world.getBlockAt(x, y, z)
                    val layers = getAshLayers(block) ?: continue

                    if (maxLayers == null || layers > maxLayers) {
                        maxLayers = layers
                    }
                }
            }
        }

        return maxLayers
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onAshLandingFallDamage(event: EntityDamageEvent) {
        if (event.cause != EntityDamageEvent.DamageCause.FALL) return

        val layers = getAshLayersNearLanding(event.entity.location) ?: return
        if (layers < 2) return

        event.isCancelled = true
        event.damage = 0.0
        event.entity.fallDistance = 0f

        /*core.logger.info(
            "Ash fall damage cancelled -> entity=${event.entity.type} layers=$layers loc=${event.entity.location}"
        )*/
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        removeAsh(TrackedPos.from(event.block.location))
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        removeAsh(TrackedPos.from(event.block.location))
    }

    private fun scanLoadedChunks() {
        for (world in Bukkit.getWorlds()) {
            for (chunk in world.loadedChunks) {
                scanChunk(chunk)
            }
        }
    }

    private fun scanChunk(chunk: Chunk) {
        val world = chunk.world
        val minY = world.minHeight
        val maxY = world.maxHeight
        var found = 0

        for (x in 0 until 16) {
            for (z in 0 until 16) {
                for (y in minY until maxY) {
                    val block = chunk.getBlock(x, y, z)
                    val layers = getAshLayers(block) ?: continue
                    putAsh(TrackedPos.from(block.location), layers)
                    found++
                }
            }
        }

        if (found > 0) {
            //core.logger.info("scanChunk found=$found world=${world.name} chunk=${chunk.x},${chunk.z}")
        }
    }

    private fun removeAshFromIterator(
        iterator: MutableIterator<MutableMap.MutableEntry<TrackedPos, Int>>,
        pos: TrackedPos
    ) {
        iterator.remove()

        val chunk = TrackedChunk.of(pos)
        val positions = ashesByChunk[chunk]
        positions?.remove(pos)
        if (positions != null && positions.isEmpty()) {
            ashesByChunk.remove(chunk)
        }
    }

    private fun startRainTask() {
        Bukkit.getScheduler().runTaskTimer(core, Runnable {
            if (trackedAshes.isEmpty()) return@Runnable
            if (Bukkit.getWorlds().none { it.hasStorm() }) return@Runnable

            val iterator = trackedAshes.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val pos = entry.key
                val trackedLayers = entry.value

                val world = Bukkit.getWorld(pos.worldId)

                if(world == null){
                    removeAshFromIterator(iterator, pos)
                    continue
                }

                if (!world.hasStorm()) continue

                if (!world.isChunkLoaded(pos.x shr 4, pos.z shr 4)) {
                    removeAshFromIterator(iterator, pos)
                    continue
                }

                val block = world.getBlockAt(pos.x, pos.y, pos.z)
                val actualLayers = getAshLayers(block)

                if (actualLayers == null) {
                    removeAshFromIterator(iterator, pos)
                    continue
                }

                if (actualLayers != trackedLayers) {
                    entry.setValue(actualLayers)
                }

                if (!isRainingOn(block)) continue

                if (CraftEngineBlocks.remove(block)) {
                    removeAshFromIterator(iterator, pos)
                }
            }
        }, 400L, 600L)
    }

    private fun getAshLayers(block: Block): Int? {
        val state = CraftEngineBlocks.getCustomBlockState(block) ?: return null

        val stateId = state.owner().keyOptional()
            .map { it.location().asString() }
            .orElse(null) ?: return null

        if (stateId != ashesId) {
            return null
        }

        for ((property, value) in state.propertyEntries()) {
            if (property.name() != "layers") continue

            return when (value) {
                is Number -> value.toInt()
                else -> value.toString().toIntOrNull()
            }
        }

        return 1
    }

    private fun isRainingOn(block: Block): Boolean {
        val world = block.world
        if (!world.hasStorm()) return false
        return world.getHighestBlockYAt(block.x, block.z) <= block.y
    }

    private data class TrackedPos(
        val worldId: UUID,
        val x: Int,
        val y: Int,
        val z: Int
    ) {
        companion object {
            fun from(location: Location): TrackedPos {
                val world = location.world ?: error("Location without world")
                return TrackedPos(
                    world.uid,
                    location.blockX,
                    location.blockY,
                    location.blockZ
                )
            }
        }
    }
}