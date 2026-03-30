package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.ashes

import dev.wuason.unearthMechanic.UnearthMechanic
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks
import net.momirealms.craftengine.core.util.Key
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.FallingBlock
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
    private val ashesKey = Key.of(ashesId)

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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFallingBlockSpawn(event: EntitySpawnEvent) {
        val falling = event.entity as? FallingBlock ?: return
        val pos = TrackedPos.from(event.location)

        val layers =
            trackedAshes.remove(pos)
                ?: trackedAshes.remove(pos.up())
                ?: trackedAshes.remove(pos.down())
                ?: return

        ashFallingEntities[falling.uniqueId] = layers

        if (layers >= 2) {
            falling.setHurtEntities(false)
            falling.damagePerBlock = 0f
            falling.maxDamage = 0
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onAshesDamageByEntity(event: EntityDamageByEntityEvent) {
        val falling = event.damager as? FallingBlock ?: return
        val layers = ashFallingEntities[falling.uniqueId] ?: return

        if (layers >= 2) {
            event.isCancelled = true
            falling.setHurtEntities(false)
            falling.damagePerBlock = 0f
            falling.maxDamage = 0
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onAshesGenericDamage(event: EntityDamageEvent) {
        if (event.cause != EntityDamageEvent.DamageCause.FALLING_BLOCK) return

        val victim = event.entity

        for (nearby in victim.world.getNearbyEntities(victim.location, 1.5, 2.5, 1.5)) {
            val falling = nearby as? FallingBlock ?: continue
            val layers = ashFallingEntities[falling.uniqueId] ?: continue

            if (layers >= 2) {
                event.isCancelled = true
                falling.setHurtEntities(false)
                falling.damagePerBlock = 0f
                falling.maxDamage = 0
                return
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onAshesLand(event: EntityChangeBlockEvent) {
        val falling = event.entity as? FallingBlock ?: return
        val layers = ashFallingEntities.remove(falling.uniqueId) ?: return

        if (event.to.isAir) return

        Bukkit.getScheduler().runTask(core, Runnable {
            val landedBlock = event.block
            val actualLayers = getAshLayers(landedBlock) ?: layers
            trackAsh(landedBlock.location, actualLayers)
        })
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

        for (x in 0 until 16) {
            for (z in 0 until 16) {
                for (y in minY until maxY) {
                    val block = chunk.getBlock(x, y, z)
                    val layers = getAshLayers(block) ?: continue
                    trackedAshes[TrackedPos.from(block.location)] = layers
                }
            }
        }
    }

    private fun startRainTask() {
        Bukkit.getScheduler().runTaskTimer(core, Runnable {
            val snapshot = trackedAshes.entries.map { it.key to it.value }

            val positionsToRemove = mutableSetOf<TrackedPos>()
            val positionsToUpdate = hashMapOf<TrackedPos, Int>()

            for ((pos, trackedLayers) in snapshot) {
                val world = Bukkit.getWorld(pos.worldId)
                if (world == null) {
                    positionsToRemove += pos
                    continue
                }

                if (!world.isChunkLoaded(pos.x shr 4, pos.z shr 4)) {
                    continue
                }

                val block = world.getBlockAt(pos.x, pos.y, pos.z)
                val actualLayers = getAshLayers(block)

                if (actualLayers == null) {
                    positionsToRemove += pos
                    continue
                }

                if (actualLayers != trackedLayers) {
                    positionsToUpdate[pos] = actualLayers
                }

                if (!isRainingOn(block)) {
                    continue
                }

                CraftEngineBlocks.remove(block)
                positionsToRemove += pos
            }

            for (pos in positionsToRemove) {
                trackedAshes.remove(pos)
            }

            for ((pos, layers) in positionsToUpdate) {
                if (!positionsToRemove.contains(pos)) {
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

        val highestY = world.getHighestBlockYAt(block.x, block.z)
        return highestY <= block.y
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