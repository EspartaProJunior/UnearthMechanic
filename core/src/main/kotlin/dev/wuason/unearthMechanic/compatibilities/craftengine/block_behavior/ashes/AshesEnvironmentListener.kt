package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.ashes

import dev.wuason.unearthMechanic.UnearthMechanic
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks
import net.momirealms.craftengine.core.util.Key
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.LivingEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import java.util.UUID

class AshesEnvironmentListener(
    private val core: UnearthMechanic
) : Listener {

    private val ashesId = "elitefantasy:ashes_block"

    // position -> layers
    private val trackedAshes = hashMapOf<TrackedPos, Int>()

    // falling entity -> layers
    private val ashFallingEntities = hashMapOf<UUID, Int>()

    init {
        startRainTask()

        // Reindex existing ashes once the plugin has finished loading
        Bukkit.getScheduler().runTask(core, Runnable {
            scanLoadedChunks()
        })
    }

    fun trackAsh(location: Location, layers: Int) {
        if (layers <= 0) return
        //core.logger.info("trackAsh layers=$layers loc=${location.blockX},${location.blockY},${location.blockZ}")
        trackedAshes[TrackedPos.from(location)] = layers
    }

    @EventHandler(ignoreCancelled = true)
    fun onChunkLoad(event: ChunkLoadEvent) {
        scanChunk(event.chunk)
    }

    @EventHandler(ignoreCancelled = true)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        val chunk = event.chunk
        val worldId = chunk.world.uid
        val chunkX = chunk.x
        val chunkZ = chunk.z

        val iterator = trackedAshes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val pos = entry.key

            if (pos.worldId == worldId && (pos.x shr 4) == chunkX && (pos.z shr 4) == chunkZ) {
                iterator.remove()
            }
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
        trackedAshes.remove(TrackedPos.from(event.block.location))
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        trackedAshes.remove(TrackedPos.from(event.block.location))
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
                    trackedAshes[TrackedPos.from(block.location)] = layers
                    found++
                }
            }
        }

        if (found > 0) {
            //core.logger.info("scanChunk found=$found world=${world.name} chunk=${chunk.x},${chunk.z}")
        }
    }

    private fun startRainTask() {
        Bukkit.getScheduler().runTaskTimer(core, Runnable {
            var anyStorm = false
            for (world in Bukkit.getWorlds()) {
                if (world.hasStorm()) {
                    anyStorm = true
                    break
                }
            }

            if (!anyStorm) return@Runnable

            scanLoadedChunks()

            if (trackedAshes.isEmpty()) return@Runnable

            val snapshot = trackedAshes.toMap()
            val positionsToRemove = mutableSetOf<TrackedPos>()
            val positionsToUpdate = hashMapOf<TrackedPos, Int>()

            for ((pos, trackedLayers) in snapshot) {
                val world = Bukkit.getWorld(pos.worldId)
                if (world == null) {
                    positionsToRemove += pos
                    continue
                }

                if (!world.hasStorm()) continue
                if (!world.isChunkLoaded(pos.x shr 4, pos.z shr 4)) continue

                val block = world.getBlockAt(pos.x, pos.y, pos.z)
                val actualLayers = getAshLayers(block)

                if (actualLayers == null) {
                    positionsToRemove += pos
                    continue
                }

                if (actualLayers != trackedLayers) {
                    positionsToUpdate[pos] = actualLayers
                }

                val raining = isRainingOn(block)
                //core.logger.info("Rain check -> pos=$pos layers=$actualLayers raining=$raining")

                if (!raining) {
                    continue
                }

                val removed = CraftEngineBlocks.remove(block)
                //core.logger.info("Rain remove ash -> removed=$removed pos=$pos layers=$actualLayers")

                if (removed) {
                    positionsToRemove += pos
                }
            }

            for (pos in positionsToRemove) {
                trackedAshes.remove(pos)
            }

            for ((pos, layers) in positionsToUpdate) {
                if (pos !in positionsToRemove) {
                    trackedAshes[pos] = layers
                }
            }
        }, 40L, 40L)
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

        val x = block.x
        val z = block.z

        // blocks rain if there is something solid or liquid above it
        for (y in (block.y + 1) until world.maxHeight) {
            val above = world.getBlockAt(x, y, z)
            if (!above.type.isAir) {
                return false
            }
        }

        return true
    }

    private data class TrackedPos(
        val worldId: UUID,
        val x: Int,
        val y: Int,
        val z: Int
    ) {
        fun up(): TrackedPos = copy(y = y + 1)
        fun down(): TrackedPos = copy(y = y - 1)

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