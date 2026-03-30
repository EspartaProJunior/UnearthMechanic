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

        val result = buildBurnResult(burnedBlock, burnedType) ?: return

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
        if (!type.isBurnable) return false

        val name = type.name
        return name.endsWith("_LOG") || name.endsWith("_WOOD")
    }

    private fun shouldSpawnAshes(): Boolean {
        return ThreadLocalRandom.current().nextDouble() < ashesSpawnChance
    }

    private fun tryPlaceBurnResult(location: Location, result: BurnResult, attempt: Int) {
        if (attempt >= 6) return

        val block = location.block

        if (block.type == Material.FIRE || block.type == Material.SOUL_FIRE) {
            Bukkit.getScheduler().runTaskLater(core, Runnable {
                tryPlaceBurnResult(location, result, attempt + 1)
            }, 2L)
            return
        }

        if (!block.type.isAir) return
        if (hasWater(block)) return

        if (WorldGuardPlugin.isWorldGuardEnabled()) {
            val wg = core.getWorldGuardComp() ?: return
            if (!wg.canSpawnAshes(location)) return
        }

        if (result.useRandomChance && !shouldSpawnAshes()) return

        val customBlock = CraftEngineBlocks.byId(result.key) ?: return
        val state = customBlock.getBlockState(result.tag)

        CraftEngineBlocks.place(location, state, false)

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