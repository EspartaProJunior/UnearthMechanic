package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.ashes

import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.compatibilities.worldguard.WorldGuardPlugin
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks
import net.momirealms.craftengine.core.util.Key
import net.momirealms.craftengine.libraries.nbt.CompoundTag
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.Orientable
import org.bukkit.block.data.Waterlogged
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBurnEvent
import java.util.concurrent.ThreadLocalRandom

class BurnToAshesListener(
    private val core: UnearthMechanic,
    private val ashesEnvironmentListener: AshesEnvironmentListener
) : Listener {

    private val ashesKey = Key.of("elitefantasy:ashes_block")
    private val burnedLogKey = Key.of("elitefantasy:burned_log")

    private val ashesSpawnChance = 0.5

    private data class BurnResult(
        val key: Key,
        val tag: CompoundTag,
        val useRandomChance: Boolean
    )

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBurn(event: BlockBurnEvent) {
        val burnedBlock = event.block
        val burnedType = burnedBlock.type
        val location = burnedBlock.location.clone()

        //core.logger.info("BlockBurnEvent -> type=$burnedType loc=${location.blockX},${location.blockY},${location.blockZ}")

        val result = buildBurnResult(burnedBlock, burnedType)
        if (result == null) {
            //core.logger.info("Burn result is null for $burnedType")
            return
        }

        Bukkit.getScheduler().runTaskLater(core, Runnable {
            tryPlaceBurnResult(location, result, 0)
        }, 1L)
    }

    private fun buildBurnResult(block: Block, originalType: Material): BurnResult? {
        if (originalType.isAir) return null
        if (CraftEngineBlocks.isCustomBlock(block)) return null
        if (!originalType.isBurnable) return null

        if (isFlammableLog(originalType)) {
            val axis = (block.blockData as? Orientable)?.axis?.name?.lowercase()

            return BurnResult(
                key = burnedLogKey,
                tag = CompoundTag().apply {
                    if (axis != null) {
                        putString("axis", axis)
                    }
                },
                useRandomChance = false
            )
        }

        val layers = ThreadLocalRandom.current().nextInt(1, 5)

        return BurnResult(
            key = ashesKey,
            tag = CompoundTag().apply {
                putInt("layers", layers)
            },
            useRandomChance = true
        )
    }

    private fun isFlammableLog(type: Material): Boolean {
        val name = type.name
        return name.endsWith("_LOG") || name.endsWith("_WOOD")
    }

    private fun shouldSpawnAshes(): Boolean {
        return ThreadLocalRandom.current().nextDouble() < ashesSpawnChance
    }

    private companion object {
        const val MAX_PLACE_ATTEMPTS = 6
        const val RETRY_DELAY_TICKS = 2L
    }

    private fun tryPlaceBurnResult(location: Location, result: BurnResult, attempt: Int) {
        if (attempt >= MAX_PLACE_ATTEMPTS) {
            //core.logger.info("Burn place aborted: max attempts reached at $location")
            return
        }

        val world = location.world ?: run {
            //core.logger.info("Burn place aborted: world is null")
            return
        }

        val blockX = location.blockX
        val blockY = location.blockY
        val blockZ = location.blockZ

        if (!world.isChunkLoaded(blockX shr 4, blockZ shr 4)) {
            //core.logger.info("Burn place aborted: chunk not loaded at $location")
            return
        }

        val block = world.getBlockAt(blockX, blockY, blockZ)
        val type = block.type

        if (type == Material.FIRE || type == Material.SOUL_FIRE) {
            //core.logger.info("Burn place retry: still fire at $location attempt=$attempt")
            Bukkit.getScheduler().runTaskLater(core, Runnable {
                tryPlaceBurnResult(location, result, attempt + 1)
            }, RETRY_DELAY_TICKS)
            return
        }

        if (hasWater(block)) {
            //core.logger.info("Burn place aborted: water at $location")
            return
        }

        if (!type.isAir) {
            //core.logger.info("Burn place aborted: block is not air ($type) at $location")
            return
        }

        if (WorldGuardPlugin.isWorldGuardEnabled()) {
            val wg = core.getWorldGuardComp()
            if (!wg.canSpawnAshes(location)) {
                //core.logger.info("Burn place aborted: WorldGuard denied at $location")
                return
            }
        }

        if (result.useRandomChance && !shouldSpawnAshes()) {
            //core.logger.info("Burn place aborted: random chance failed at $location")
            return
        }

        val customBlock = CraftEngineBlocks.byId(result.key)
        if (customBlock == null) {
            //core.logger.info("Burn place aborted: custom block not found -> ${result.key}")
            return
        }

        val state = customBlock.getBlockState(result.tag)
        val placed = CraftEngineBlocks.place(location, state, false)

        //core.logger.info("Burn place result: placed=$placed key=${result.key} loc=$location")

        if (result.key == ashesKey) {
            val layers = result.tag.getInt("layers")
            ashesEnvironmentListener.trackAsh(location, layers)
        }
    }

    private fun hasWater(block: Block): Boolean {
        if (block.type == Material.WATER) return true

        val data = block.blockData
        return data is Waterlogged && data.isWaterlogged
    }
}