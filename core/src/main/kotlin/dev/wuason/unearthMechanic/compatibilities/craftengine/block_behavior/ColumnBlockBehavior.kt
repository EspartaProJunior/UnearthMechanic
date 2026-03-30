package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior

import dev.wuason.unearthMechanic.compatibilities.craftengine.types.ColumnPosition
import dev.wuason.unearthMechanic.compatibilities.craftengine.types.WindowTile
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior
import net.momirealms.craftengine.bukkit.util.BlockStateUtils
import net.momirealms.craftengine.bukkit.world.BukkitWorld
import net.momirealms.craftengine.core.block.BlockStateWrapper
import net.momirealms.craftengine.core.block.CustomBlock
import net.momirealms.craftengine.core.block.ImmutableBlockState
import net.momirealms.craftengine.core.block.UpdateFlags
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory
import net.momirealms.craftengine.core.block.properties.Property
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import net.momirealms.craftengine.core.util.Direction
import net.momirealms.craftengine.core.world.BlockHitResult
import net.momirealms.craftengine.core.world.BlockPos
import net.momirealms.craftengine.core.world.ExistingBlock
import net.momirealms.craftengine.core.world.World
import net.momirealms.craftengine.core.world.context.BlockPlaceContext
import org.bukkit.Bukkit
import java.util.concurrent.Callable

class ColumnBlockBehavior(
    customBlock: CustomBlock,
    private val positionProperty: Property<ColumnPosition>
) : BukkitBlockBehavior(customBlock) {

    override fun updateShape(thisBlock: Any, args: Array<Any>, superMethod: Callable<Any>): Any {
        val optionalState = BlockStateUtils.getOptionalCustomBlockState(args[0]) ?: return superMethod.call()
        val state = optionalState.get()

        val world = args[3] as? World ?: return superMethod.call()
        val pos = args[4] as? BlockPos ?: return superMethod.call()

        Bukkit.getScheduler().runTaskLater(
            Bukkit.getPluginManager().getPlugin("UnearthMechanic")!!,
            Runnable {
                updateNeighbors(world, pos)
            },
            1L
        )

        val newPosition = calculateNewPosition(world, pos)
        return state.with(positionProperty, newPosition).customBlockState().literalObject()
    }

    override fun updateStateForPlacement(context: BlockPlaceContext, state: ImmutableBlockState): ImmutableBlockState {
        val world = context.level
        val pos = context.clickedPos

        val blockBelow = world.getBlock(pos.offset(0, -1, 0)).blockState()
        val isPlacingOnTopOfSameBlock = isSameBlock(blockBelow)

        if (isPlacingOnTopOfSameBlock) {
            //Bukkit.getConsoleSender().sendMessage("✅ Se está colocando sobre otro bloque de columna.")
        }

        val newPosition = calculateNewPosition(world, pos)
        return state.with(positionProperty, newPosition)
    }

    override fun onPlace(thisBlock: Any, args: Array<Any>, superMethod: Callable<Any>) {
        //Bukkit.getConsoleSender().sendMessage("⚠️ onPlace() fue llamado con args=${args.contentToString()}")

        /*args.forEachIndexed { index, arg ->
            Bukkit.getConsoleSender().sendMessage("🧪 Arg[$index] = ${arg::class.qualifiedName} -> $arg")
        }*/

        val nmsWorld = args.getOrNull(1)
        val nmsPos = args.getOrNull(2)

        if (nmsWorld == null || nmsPos == null) {
            //Bukkit.getConsoleSender().sendMessage("❌ No se pudo extraer el ServerLevel o el NMS BlockPos.")
            superMethod.call()
            return
        }

        // Buscar el BukkitWorld correspondiente al ServerLevel
        val craftWorld = Bukkit.getWorlds().firstOrNull { world ->
            val handle = world.javaClass.getMethod("getHandle").invoke(world)
            handle == nmsWorld
        }

        if (craftWorld == null) {
            //Bukkit.getConsoleSender().sendMessage("❌ No se pudo encontrar un CraftWorld para el ServerLevel dado.")
            superMethod.call()
            return
        }

        val x = nmsPos.javaClass.getMethod("getX").invoke(nmsPos) as Int
        val y = nmsPos.javaClass.getMethod("getY").invoke(nmsPos) as Int
        val z = nmsPos.javaClass.getMethod("getZ").invoke(nmsPos) as Int

        val cePos = net.momirealms.craftengine.core.world.BlockPos(x, y, z)

        val ceWorld = BukkitAdaptor.adapt(craftWorld)

        //Bukkit.getConsoleSender().sendMessage("✅ CraftEngine.World y BlockPos convertidos correctamente (${craftWorld.name} @ $x, $y, $z)")

        // Ejecutar updateNeighbors un tick después
        Bukkit.getScheduler().runTaskLater(
            Bukkit.getPluginManager().getPlugin("UnearthMechanic")!!,
            Runnable {
                updateNeighbors(ceWorld, cePos)
            },
            1L
        )

        superMethod.call()
    }

    /*override fun affectNeighborsAfterRemoval(thisBlock: Any, args: Array<Any>, superMethod: Callable<Any>) {
        //Bukkit.getConsoleSender().sendMessage("(affectNeighborsAfterRemoval)")
        //val world = args[0] as? World ?: return
        //val pos = args[1] as? BlockPos ?: return

        val nmsWorld = args.getOrNull(1)
        val nmsPos = args.getOrNull(2)

        if (nmsWorld == null || nmsPos == null) {
            //Bukkit.getConsoleSender().sendMessage("❌ No se pudo extraer el ServerLevel o el NMS BlockPos. (onRemove)")
            superMethod.call()
            return
        }

        val craftWorld = Bukkit.getWorlds().firstOrNull { world ->
            val handle = world.javaClass.getMethod("getHandle").invoke(world)
            handle == nmsWorld
        }

        if (craftWorld == null) {
            //Bukkit.getConsoleSender().sendMessage("❌ No se pudo encontrar un CraftWorld para el ServerLevel dado. (onRemove)")
            superMethod.call()
            return
        }

        val x = nmsPos.javaClass.getMethod("getX").invoke(nmsPos) as Int
        val y = nmsPos.javaClass.getMethod("getY").invoke(nmsPos) as Int
        val z = nmsPos.javaClass.getMethod("getZ").invoke(nmsPos) as Int

        val cePos = net.momirealms.craftengine.core.world.BlockPos(x, y, z)

        val ceWorld = BukkitAdaptors.adapt(craftWorld)

        Bukkit.getScheduler().runTaskLater(
            Bukkit.getPluginManager().getPlugin("UnearthMechanic")!!,
            Runnable {
                updateNeighbors(ceWorld, cePos)
            },
            1L
        )

        superMethod.call()
    }

    override fun onRemove(thisBlock: Any, args: Array<Any>, superMethod: Callable<Any>) {
        //Bukkit.getConsoleSender().sendMessage("(onRemove)")
        //val world = args[0] as? World ?: return
        //val pos = args[1] as? BlockPos ?: return

        val nmsWorld = args.getOrNull(1)
        val nmsPos = args.getOrNull(2)

        if (nmsWorld == null || nmsPos == null) {
            //Bukkit.getConsoleSender().sendMessage("❌ No se pudo extraer el ServerLevel o el NMS BlockPos. (onRemove)")
            superMethod.call()
            return
        }

        val craftWorld = Bukkit.getWorlds().firstOrNull { world ->
            val handle = world.javaClass.getMethod("getHandle").invoke(world)
            handle == nmsWorld
        }

        if (craftWorld == null) {
            //Bukkit.getConsoleSender().sendMessage("❌ No se pudo encontrar un CraftWorld para el ServerLevel dado. (onRemove)")
            superMethod.call()
            return
        }

        val x = nmsPos.javaClass.getMethod("getX").invoke(nmsPos) as Int
        val y = nmsPos.javaClass.getMethod("getY").invoke(nmsPos) as Int
        val z = nmsPos.javaClass.getMethod("getZ").invoke(nmsPos) as Int

        val cePos = net.momirealms.craftengine.core.world.BlockPos(x, y, z)

        val ceWorld = BukkitAdaptors.adapt(craftWorld)

        Bukkit.getScheduler().runTaskLater(
            Bukkit.getPluginManager().getPlugin("UnearthMechanic")!!,
            Runnable {
                updateNeighbors(ceWorld, cePos)
            },
            1L
        )

        superMethod.call()
    }*/

    private fun calculateNewPosition(world: World, pos: BlockPos): ColumnPosition {
        val aboveState = world.getBlock(pos.offset(0, 1, 0)).blockState()
        val belowState = world.getBlock(pos.offset(0, -1, 0)).blockState()

        val hasAbove = isSameBlock(aboveState)
        val hasBelow = isSameBlock(belowState)

        return when {
            hasAbove && hasBelow -> ColumnPosition.middle
            hasAbove && !hasBelow -> ColumnPosition.down
            !hasAbove && hasBelow -> ColumnPosition.up
            else -> ColumnPosition.single
        }
    }

    private fun updateNeighbors(world: World, pos: BlockPos) {
        //Bukkit.getConsoleSender().sendMessage("🌀 Ejecutando updateNeighbors en ${pos.x()}, ${pos.y()}, ${pos.z()}")
        listOf(1, -1).forEach { offsetY ->
            if (world !is BukkitWorld) {
                return@forEach
            }

            val neighborPos = pos.offset(0, offsetY, 0)
            //val neighborBlock = world.getBlockAt(neighborPos)
            val neighborBlock = world.getBlock(neighborPos)
            val neighborStateOpt = neighborBlock.customBlockState()
            val neighborWrapper = neighborBlock.blockState()

            if (neighborStateOpt != null && isSameBlockWrapper(neighborWrapper)) {
                val newPositionValue = calculateNewPosition(world, neighborPos)
                val updatedState = neighborStateOpt.with(positionProperty, newPositionValue)

                val bukkitWorld = Bukkit.getWorld(world.name())

                if (bukkitWorld == null) {
                    return
                }

                val bukkitLoc = org.bukkit.Location(
                    bukkitWorld as org.bukkit.World?,
                    neighborPos.x().toDouble(),
                    neighborPos.y().toDouble(),
                    neighborPos.z().toDouble()
                )

                /*bukkitLoc.world.spawnParticle(
                    org.bukkit.Particle.HAPPY_VILLAGER,
                    bukkitLoc.clone().add(0.5, 0.5, 0.5),
                    10, 0.1, 0.1, 0.1
                )*/

                //Bukkit.getConsoleSender().sendMessage("Actualizando bloque en ${bukkitLoc.blockX}, ${bukkitLoc.blockY}, ${bukkitLoc.blockZ}")

                BukkitAdaptor.adapt(bukkitLoc.world).setBlockState(
                    bukkitLoc.blockX,
                    bukkitLoc.blockY,
                    bukkitLoc.blockZ,
                    updatedState,
                    UpdateFlags.UPDATE_ALL
                )
            }
        }
    }

    private fun isSameBlockWrapper(wrapper: BlockStateWrapper?): Boolean {
        return wrapper != null && wrapper.ownerId() == customBlock.id()
    }

    private fun isSameBlock(state: BlockStateWrapper?): Boolean {
        return state != null && state.ownerId() == customBlock.id()
    }

    companion object {
        val FACTORY = Factory()

        class Factory : BlockBehaviorFactory<ColumnBlockBehavior> {
            override fun create(block: CustomBlock, section: ConfigSection): ColumnBlockBehavior {
                val prop = block.getProperty("position")
                    ?: throw IllegalArgumentException("Missing 'position' property")
                @Suppress("UNCHECKED_CAST")
                val tileProperty = prop as Property<ColumnPosition>
                return ColumnBlockBehavior(block, tileProperty)
            }
        }
    }
}
