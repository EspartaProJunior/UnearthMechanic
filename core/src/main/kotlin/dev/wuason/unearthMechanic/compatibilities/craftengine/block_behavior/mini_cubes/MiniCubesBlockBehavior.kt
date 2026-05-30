package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.mini_cubes

import dev.wuason.unearthMechanic.compatibilities.craftengine.types.MiniCubeMaskState
import dev.wuason.unearthMechanic.utils.StorageUtils
import net.momirealms.craftengine.bukkit.api.CraftEngineItems
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition
import net.momirealms.craftengine.core.block.BlockDefinition
import net.momirealms.craftengine.core.block.ImmutableBlockState
import net.momirealms.craftengine.core.block.behavior.BlockBehavior
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory
import net.momirealms.craftengine.core.block.property.Property
import net.momirealms.craftengine.core.entity.player.InteractionResult
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import net.momirealms.craftengine.core.util.Direction
import net.momirealms.craftengine.core.util.Key
import net.momirealms.craftengine.core.world.BlockPos
import net.momirealms.craftengine.core.world.World
import net.momirealms.craftengine.core.world.context.BlockPlaceContext
import net.momirealms.craftengine.core.world.context.UseOnContext
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import java.util.*
import java.util.function.Function
import kotlin.collections.filter

class MiniCubesBlockBehavior(
    customBlock: BlockDefinition,
    private val cubesProperty: Property<MiniCubeMaskState>,
    private val itemId: String,
    private val mode: MiniCubeMode,
    private val removeWithShift: Boolean,
    private val flipX: Boolean,
    private val flipY: Boolean,
    private val flipZ: Boolean,
    private val placeSound: String,
    private val breakSound: String
) : BlockBehavior(customBlock) {

    private val debug = true

    private fun debug(message: String) {
        if (!debug) return

        Bukkit.getLogger().info(
            "[UM-MINI-CUBES] ${block().id()} | $message"
        )
    }

    private fun debugMask(label: String, mask: Int) {
        if (!debug) return

        debug(
            "$label mask=$mask binary=${MiniCubeMask.modelSuffix(mask)} count=${MiniCubeMask.count(mask)}"
        )
    }

    override fun updateStateForPlacement(
        context: BlockPlaceContext,
        state: ImmutableBlockState
    ): ImmutableBlockState {
        val bukkitPlayer = resolveBukkitPlayer(context.player)
        val clickedFace = resolveClickedFace(context)

        val initialBit = if (bukkitPlayer != null) {
            guessInitialBitFromPlacementHit(
                player = bukkitPlayer,
                pos = context.clickedPos,
                clickedFace = clickedFace
            )
        } else {
            0
        }

        val mappedInitialBit = MiniCubeBitMapper.map(
            bit = initialBit,
            flipX = flipX,
            flipY = flipY,
            flipZ = flipZ
        )

        val defaultMask = 1 shl mappedInitialBit

        debug(
            "updateStateForPlacement pos=${context.clickedPos} face=$clickedFace mode=$mode " +
                    "initialBit=$initialBit mappedInitialBit=$mappedInitialBit defaultMask=$defaultMask " +
                    "flipX=$flipX flipY=$flipY flipZ=$flipZ"
        )

        return state.with(cubesProperty, MiniCubeMask.state(defaultMask))
    }

    private fun resolveClickedFace(context: Any): Direction? {
        val byMethod = runCatching {
            context.javaClass.getMethod("clickedFace").invoke(context) as? Direction
        }.getOrNull()

        if (byMethod != null) return byMethod

        val byField = runCatching {
            val field = context.javaClass.getDeclaredField("clickedFace")
            field.isAccessible = true
            field.get(context) as? Direction
        }.getOrNull()

        if (byField != null) return byField

        return null
    }

    private fun guessInitialBitFromPlacementHit(
        player: org.bukkit.entity.Player,
        pos: BlockPos,
        clickedFace: Direction?
    ): Int {
        val hit = player.rayTraceBlocks(6.0)
        val hitPos = hit?.hitPosition

        if (hitPos == null) {
            debug("guessInitialBitFromPlacementHit fallback reason=no_bukkit_hit")
            return 0
        }

        val effectiveFace = clickedFace ?: blockFaceToDirection(hit.hitBlockFace)

        var localX = hitPos.x - pos.x()
        var localY = hitPos.y - pos.y()
        var localZ = hitPos.z - pos.z()

        localX = localX.coerceIn(0.0, 0.999999)
        localY = localY.coerceIn(0.0, 0.999999)
        localZ = localZ.coerceIn(0.0, 0.999999)

        var subX = if (localX < 0.5) 0 else 1
        var subY = if (localY < 0.5) 0 else 1
        var subZ = if (localZ < 0.5) 0 else 1

        when (effectiveFace?.name?.lowercase()) {
            "up" -> subY = 0
            "down" -> subY = 1
            "east" -> subX = 0
            "west" -> subX = 1
            "south" -> subZ = 0
            "north" -> subZ = 1
        }

        val bit = MiniCubeMask.bit(subX, subY, subZ)

        debug(
            "guessInitialBitFromPlacementHit pos=$pos face=$effectiveFace bukkitFace=${hit.hitBlockFace} " +
                    "hit=(${hitPos.x},${hitPos.y},${hitPos.z}) local=($localX,$localY,$localZ) " +
                    "sub=($subX,$subY,$subZ) bit=$bit mask=${1 shl bit}"
        )

        return bit
    }

    private fun blockFaceToDirection(face: org.bukkit.block.BlockFace?): Direction? {
        return when (face) {
            org.bukkit.block.BlockFace.UP -> Direction.UP
            org.bukkit.block.BlockFace.DOWN -> Direction.DOWN
            org.bukkit.block.BlockFace.EAST -> Direction.EAST
            org.bukkit.block.BlockFace.WEST -> Direction.WEST
            org.bukkit.block.BlockFace.SOUTH -> Direction.SOUTH
            org.bukkit.block.BlockFace.NORTH -> Direction.NORTH
            else -> null
        }
    }

    override fun useWithoutItem(
        context: UseOnContext,
        state: ImmutableBlockState
    ): InteractionResult {
        return handleUse(context, state)
    }

    private fun handleUse(
        context: UseOnContext,
        state: ImmutableBlockState
    ): InteractionResult {
        val player = context.player ?: return InteractionResult.PASS
        val bukkitPlayer = resolveBukkitPlayer(player) ?: return InteractionResult.PASS

        val world = context.level
        val pos = context.clickedPos

        val currentMaskState = state.get(cubesProperty)
        val currentMask = MiniCubeMask.mask(currentMaskState)

        debug(
            "handleUse player=${bukkitPlayer.name} pos=$pos face=${context.clickedFace} sneaking=${bukkitPlayer.isSneaking} mode=$mode itemId=$itemId"
        )

        debugMask("current", currentMask)

        val directTargetBit = guessBitInsideCurrentBlock(
            player = bukkitPlayer,
            pos = pos,
            face = context.clickedFace
        )

        val shouldRemove = removeWithShift && bukkitPlayer.isSneaking

        debug(
            "shouldRemove=$shouldRemove removeWithShift=$removeWithShift directBit=$directTargetBit"
        )

        if (!shouldRemove && !canConsumeFromHand(bukkitPlayer)) {
            return InteractionResult.PASS
        }

        if (
            !shouldRemove &&
            MiniCubeMask.validBit(directTargetBit) &&
            isBitAllowedByMode(directTargetBit) &&
            !MiniCubeMask.has(currentMask, directTargetBit)
        ) {
            return addToExistingMiniCubeBlock(
                world = world,
                pos = pos,
                state = state,
                targetBit = directTargetBit,
                bukkitPlayer = bukkitPlayer
            )
        }

        val targetedHit = MiniCubeRaytrace.findTargetedHit(
            player = player,
            world = world,
            pos = pos,
            mask = currentMask
        ) ?: MiniCubeRaytrace.findHitOnClickedFace(
            mask = currentMask,
            face = context.clickedFace
        )

        if (targetedHit == null) {
            debug("handleUse result=CANCEL reason=no_targeted_hit contextFace=${context.clickedFace} directBit=$directTargetBit")
            return InteractionResult.SUCCESS_AND_CANCEL
        }

        val targetedBit = targetedHit.bit
        val targetedFace = when {
            context.clickedFace == Direction.UP -> Direction.UP
            context.clickedFace == Direction.DOWN -> Direction.DOWN

            targetedHit.face == Direction.UP && context.clickedFace != Direction.UP -> context.clickedFace
            targetedHit.face == Direction.DOWN && context.clickedFace != Direction.DOWN -> context.clickedFace

            else -> targetedHit.face
        }

        debug(
            "raytrace targetedBit=$targetedBit rayFace=${targetedHit.face} contextFace=${context.clickedFace} " +
                    "usedFace=$targetedFace valid=${MiniCubeMask.validBit(targetedBit)} " +
                    "sub=(${MiniCubeMask.x(targetedBit)},${MiniCubeMask.y(targetedBit)},${MiniCubeMask.z(targetedBit)})"
        )

        debugMask("current", currentMask)

        if (!MiniCubeMask.validBit(targetedBit)) {
            return InteractionResult.PASS
        }

        if (shouldRemove) {
            val removeBit = when {
                MiniCubeMask.validBit(directTargetBit) && MiniCubeMask.has(currentMask, directTargetBit) -> directTargetBit
                MiniCubeMask.validBit(targetedBit) && MiniCubeMask.has(currentMask, targetedBit) -> targetedBit
                else -> -1
            }

            if (!MiniCubeMask.validBit(removeBit)) {
                debug("removeMiniCube result=CANCEL reason=no_removable_bit directBit=$directTargetBit targetedBit=$targetedBit")
                return InteractionResult.SUCCESS_AND_CANCEL
            }

            return removeMiniCube(
                world = world,
                pos = pos,
                state = state,
                currentMask = currentMask,
                targetedBit = removeBit,
                bukkitPlayer = bukkitPlayer
            )
        }

        val hitNearOuterBoundary = isHitNearOuterBoundary(
            player = bukkitPlayer,
            pos = pos,
            face = targetedFace
        )

        if (!hitNearOuterBoundary) {
            if (MiniCubeMask.validBit(directTargetBit)) {
                val insideNeighborBit = oppositeBitInsideCurrentBlock(
                    bit = directTargetBit,
                    face = context.clickedFace
                )

                debug(
                    "insidePartialShape directBit=$directTargetBit face=${context.clickedFace} " +
                            "insideNeighborBit=$insideNeighborBit hasInsideNeighbor=${
                                MiniCubeMask.validBit(insideNeighborBit) && MiniCubeMask.has(currentMask, insideNeighborBit)
                            }"
                )

                if (MiniCubeMask.validBit(insideNeighborBit) && !MiniCubeMask.has(currentMask, insideNeighborBit)) {
                    return addToExistingMiniCubeBlock(
                        world = world,
                        pos = pos,
                        state = state,
                        targetBit = insideNeighborBit,
                        bukkitPlayer = bukkitPlayer
                    )
                }
            }

            debug("handleUse result=CANCEL reason=partial_shape_no_inside_target directBit=$directTargetBit face=${context.clickedFace}")
            return InteractionResult.SUCCESS_AND_CANCEL
        }

        debug(
            "outerPlacement fromBit=$targetedBit face=$targetedFace " +
                    "directBit=$directTargetBit"
        )

        val computedTarget = MiniCubePlacement.computeTarget(
            pos = pos,
            hitBit = targetedBit,
            face = targetedFace
        ) ?: return InteractionResult.SUCCESS_AND_CANCEL

        val cursorTargetBit = if (computedTarget.pos != pos) {
            guessBitForAdjacentBlockFromCursor(
                player = bukkitPlayer,
                sourcePos = pos,
                face = targetedFace
            )
        } else {
            computedTarget.bit
        }

        val target = MiniCubePlacement.Target(
            pos = computedTarget.pos,
            bit = if (MiniCubeMask.validBit(cursorTargetBit)) cursorTargetBit else computedTarget.bit
        )

        debug(
            "computedTarget pos=${target.pos} bit=${target.bit} " +
                    "fromBit=$targetedBit face=$targetedFace rawBit=${computedTarget.bit} " +
                    "cursorBit=$cursorTargetBit adjacent=${computedTarget.pos != pos} " +
                    "allowedByMode=${isBitAllowedByMode(target.bit)}"
        )

        if (!isBitAllowedByMode(target.bit)) {
            return InteractionResult.FAIL
        }

        val targetState = world.getBlock(target.pos).customBlockState()

        if (targetState != null && targetState.owner().value().id() == block().id()) {
            return addToExistingMiniCubeBlock(
                world,
                target.pos,
                targetState,
                target.bit,
                bukkitPlayer
            )
        }

        if (canReplace(world, target.pos)) {
            return placeNewMiniCubeBlock(
                world,
                target.pos,
                context,
                target.bit,
                bukkitPlayer
            )
        }

        debug("final result=FAIL reason=target_not_replaceable_or_not_supported targetPos=${target.pos}")

        return InteractionResult.FAIL
    }

    private fun isHitNearOuterBoundary(
        player: org.bukkit.entity.Player?,
        pos: BlockPos,
        face: Direction
    ): Boolean {
        val hit = player?.rayTraceBlocks(6.0)?.hitPosition ?: return true

        val localX = (hit.x - pos.x()).coerceIn(0.0, 0.999999)
        val localY = (hit.y - pos.y()).coerceIn(0.0, 0.999999)
        val localZ = (hit.z - pos.z()).coerceIn(0.0, 0.999999)

        val epsilon = 0.03

        return when (face) {
            Direction.UP -> localY >= 1.0 - epsilon
            Direction.DOWN -> localY <= epsilon
            Direction.EAST -> localX >= 1.0 - epsilon
            Direction.WEST -> localX <= epsilon
            Direction.SOUTH -> localZ >= 1.0 - epsilon
            Direction.NORTH -> localZ <= epsilon
            else -> true
        }
    }

    private fun oppositeBitInsideCurrentBlock(bit: Int, face: Direction): Int {
        if (!MiniCubeMask.validBit(bit)) return -1

        val x = MiniCubeMask.x(bit)
        val y = MiniCubeMask.y(bit)
        val z = MiniCubeMask.z(bit)

        return when (face) {
            Direction.UP -> if (y == 0) MiniCubeMask.bit(x, 1, z) else -1
            Direction.DOWN -> if (y == 1) MiniCubeMask.bit(x, 0, z) else -1

            Direction.EAST -> if (x == 1) MiniCubeMask.bit(0, y, z) else -1
            Direction.WEST -> if (x == 0) MiniCubeMask.bit(1, y, z) else -1

            Direction.SOUTH -> if (z == 1) MiniCubeMask.bit(x, y, 0) else -1
            Direction.NORTH -> if (z == 0) MiniCubeMask.bit(x, y, 1) else -1

            else -> -1
        }
    }

    private fun guessBitForAdjacentBlockFromCursor(
        player: org.bukkit.entity.Player,
        sourcePos: BlockPos,
        face: Direction
    ): Int {
        val hit = player.rayTraceBlocks(6.0)
        val hitPos = hit?.hitPosition ?: return -1

        var localX = (hitPos.x - sourcePos.x()).coerceIn(0.0, 0.999999)
        var localY = (hitPos.y - sourcePos.y()).coerceIn(0.0, 0.999999)
        var localZ = (hitPos.z - sourcePos.z()).coerceIn(0.0, 0.999999)

        var subX = if (localX < 0.5) 0 else 1
        var subY = if (localY < 0.5) 0 else 1
        var subZ = if (localZ < 0.5) 0 else 1

        when (face) {
            Direction.UP -> subY = 0
            Direction.DOWN -> subY = 1
            Direction.EAST -> subX = 0
            Direction.WEST -> subX = 1
            Direction.SOUTH -> subZ = 0
            Direction.NORTH -> subZ = 1
            else -> {}
        }

        val logicalBit = MiniCubeMask.bit(subX, subY, subZ)

        debug(
            "guessBitForAdjacentBlockFromCursor sourcePos=$sourcePos face=$face " +
                    "hit=(${hitPos.x},${hitPos.y},${hitPos.z}) " +
                    "local=($localX,$localY,$localZ) sub=($subX,$subY,$subZ) " +
                    "logicalBit=$logicalBit mappedBit=${MiniCubeBitMapper.map(logicalBit, flipX, flipY, flipZ)}"
        )

        return MiniCubeBitMapper.map(
            bit = logicalBit,
            flipX = flipX,
            flipY = flipY,
            flipZ = flipZ
        )
    }

    private fun guessBitInsideCurrentBlock(
        player: org.bukkit.entity.Player,
        pos: BlockPos,
        face: Direction
    ): Int {
        val hit = player.rayTraceBlocks(6.0)
        val hitPos = hit?.hitPosition ?: return -1

        var localX = hitPos.x - pos.x()
        var localY = hitPos.y - pos.y()
        var localZ = hitPos.z - pos.z()

        val epsilon = 0.0001

        when (face) {
            Direction.EAST -> localX -= epsilon
            Direction.WEST -> localX += epsilon
            Direction.UP -> localY -= epsilon
            Direction.DOWN -> localY += epsilon
            Direction.SOUTH -> localZ -= epsilon
            Direction.NORTH -> localZ += epsilon
            else -> {}
        }

        localX = localX.coerceIn(0.0, 0.999999)
        localY = localY.coerceIn(0.0, 0.999999)
        localZ = localZ.coerceIn(0.0, 0.999999)

        val logicalBit = MiniCubeMask.bit(
            if (localX < 0.5) 0 else 1,
            if (localY < 0.5) 0 else 1,
            if (localZ < 0.5) 0 else 1
        )

        return MiniCubeBitMapper.map(
            bit = logicalBit,
            flipX = flipX,
            flipY = flipY,
            flipZ = flipZ
        )
    }

    private fun removeMiniCube(
        world: World,
        pos: BlockPos,
        state: ImmutableBlockState,
        currentMask: Int,
        targetedBit: Int,
        bukkitPlayer: org.bukkit.entity.Player
    ): InteractionResult {
        val newMask = MiniCubeMask.remove(currentMask, targetedBit)

        debug(
            "removeMiniCube pos=$pos targetedBit=$targetedBit currentMask=$currentMask newMask=$newMask"
        )

        debugMask("before-remove", currentMask)
        debugMask("after-remove", newMask)

        if (newMask <= 0) {
            debug("removeMiniCube result=setAir because newMask <= 0")
            setAir(world, pos)
        } else {
            debug("removeMiniCube result=setBlockState state=${MiniCubeMask.state(newMask)}")

            val changed = setBlockState(
                world = world,
                pos = pos,
                state = state.with(cubesProperty, MiniCubeMask.state(newMask))
            )

            if (!changed) {
                debug("removeMiniCube result=FAIL reason=setBlockState_failed")
                return InteractionResult.FAIL
            }
        }

        playSound(world, pos, breakSound)
        dropOnePiece(bukkitPlayer)
        return InteractionResult.SUCCESS_AND_CANCEL
    }

    private fun addToExistingMiniCubeBlock(
        world: World,
        pos: BlockPos,
        state: ImmutableBlockState,
        targetBit: Int,
        bukkitPlayer: org.bukkit.entity.Player
    ): InteractionResult {
        val targetMask = MiniCubeMask.mask(state.get(cubesProperty))

        debug(
            "addToExistingMiniCubeBlock pos=$pos targetBit=$targetBit targetMask=$targetMask hasBit=${MiniCubeMask.has(targetMask, targetBit)}"
        )

        debugMask("target-before-add", targetMask)

        if (MiniCubeMask.has(targetMask, targetBit)) {
            debug("addToExistingMiniCubeBlock result=CANCEL reason=bit_already_exists")
            return InteractionResult.SUCCESS_AND_CANCEL
        }

        val newMask = MiniCubeMask.add(targetMask, targetBit)

        debugMask("target-after-add", newMask)

        val changed = setBlockState(
            world = world,
            pos = pos,
            state = state.with(cubesProperty, MiniCubeMask.state(newMask))
        )

        if (!changed) {
            debug("addToExistingMiniCubeBlock result=FAIL reason=setBlockState_failed")
            return InteractionResult.FAIL
        }

        playSound(world, pos, placeSound)
        consumeOne(bukkitPlayer)
        return InteractionResult.SUCCESS_AND_CANCEL
    }

    private fun placeNewMiniCubeBlock(
        world: World,
        pos: BlockPos,
        context: UseOnContext,
        targetBit: Int,
        bukkitPlayer: org.bukkit.entity.Player
    ): InteractionResult {
        val newMask = 1 shl targetBit

        debug(
            "placeNewMiniCubeBlock pos=$pos targetBit=$targetBit newMask=$newMask state=${MiniCubeMask.state(newMask)} facing=${context.horizontalDirection}"
        )

        debugMask("new-block", newMask)

        val newState = block()
            .defaultState()
            .with(cubesProperty, MiniCubeMask.state(newMask))

        val changed = setBlockState(world, pos, newState)

        if (!changed) {
            debug("placeNewMiniCubeBlock result=FAIL reason=setBlockState_failed")
            return InteractionResult.FAIL
        }

        playSound(world, pos, placeSound)
        consumeOne(bukkitPlayer)
        return InteractionResult.SUCCESS_AND_CANCEL
    }

    private fun isBitAllowedByMode(bit: Int): Boolean {
        if (!MiniCubeMask.validBit(bit)) return false

        return when (mode) {
            MiniCubeMode.FULL_8 -> true

            // Solo capa inferior: bits 0, 1, 2, 3.
            MiniCubeMode.LOWER_4 -> bit in 0..3

            // Por ahora lo dejamos abierto.
            // Luego aquí podemos restringirlo a una lógica tipo vertical slab.
            MiniCubeMode.VERTICAL_SLAB_LIKE -> true
        }
    }

    private fun setAir(world: World, pos: BlockPos) {
        debug("setAir world=${world.name()} pos=$pos")

        val bukkitWorld = Bukkit.getWorld(world.name()) ?: run {
            debug("setAir failed reason=bukkit_world_not_found world=${world.name()}")
            return
        }

        bukkitWorld.getBlockAt(pos.x(), pos.y(), pos.z()).type = Material.AIR
    }

    private fun setBlockState(world: World, pos: BlockPos, state: ImmutableBlockState): Boolean {
        return runCatching {
            val method = world.javaClass.methods.firstOrNull { method ->
                method.name == "setBlockState" &&
                        method.parameterTypes.size == 3 &&
                        method.parameterTypes[0].isAssignableFrom(BlockPos::class.java) &&
                        method.parameterTypes[1].isAssignableFrom(ImmutableBlockState::class.java) &&
                        method.parameterTypes[2] == Int::class.javaPrimitiveType
            } ?: run {
                debug("setBlockState failed reason=method_not_found worldClass=${world.javaClass.name}")
                return false
            }

            debug(
                "setBlockState world=${world.name()} pos=$pos state=${state.get(cubesProperty)} method=${method.name}(BlockPos, ImmutableBlockState, int)"
            )

            method.invoke(world, pos, state, 3)
            true
        }.getOrElse {
            debug("setBlockState failed exception=${it.javaClass.simpleName}: ${it.message}")
            it.printStackTrace()
            false
        }
    }

    private fun playSound(world: World, pos: BlockPos, sound: String) {
        val bukkitWorld = Bukkit.getWorld(world.name()) ?: return
        val location = org.bukkit.Location(
            bukkitWorld,
            pos.x() + 0.5,
            pos.y() + 0.5,
            pos.z() + 0.5
        )
        bukkitWorld.playSound(location, sound, 1.0f, 1.0f)
    }

    private fun canReplace(world: World, pos: BlockPos): Boolean {
        val bukkitWorld = Bukkit.getWorld(world.name()) ?: run {
            debug("canReplace=false reason=bukkit_world_not_found world=${world.name()} pos=$pos")
            return false
        }

        val block = bukkitWorld.getBlockAt(pos.x(), pos.y(), pos.z())

        val result = block.type.isAir ||
                block.type == Material.SHORT_GRASS ||
                block.type == Material.TALL_GRASS ||
                block.type == Material.FERN ||
                block.type == Material.LARGE_FERN ||
                block.type == Material.SNOW

        debug(
            "canReplace pos=$pos type=${block.type} result=$result"
        )

        return result
    }

    private fun canConsumeFromHand(player: org.bukkit.entity.Player): Boolean {
        val handItem = player.inventory.itemInMainHand

        if (handItem.type.isAir) {
            debug("canConsumeFromHand=false reason=empty_hand")
            return false
        }

        if (!isExpectedMiniCubeItem(handItem)) {
            debug("canConsumeFromHand=false reason=item_mismatch expected=$itemId hand=${handItem.type}")
            return false
        }

        return true
    }

    private fun isExpectedMiniCubeItem(handItem: ItemStack): Boolean {
        val expectedItem = createCraftEngineItem(itemId) ?: run {
            debug("isExpectedMiniCubeItem=false reason=could_not_create_expected_item expected=$itemId")
            return false
        }

        val handCopy = handItem.clone()
        val expectedCopy = expectedItem.clone()

        handCopy.amount = 1
        expectedCopy.amount = 1

        return handCopy.isSimilar(expectedCopy)
    }

    private fun consumeOne(player: org.bukkit.entity.Player) {
        if (player.gameMode == GameMode.CREATIVE) {
            debug("consumeOne skipped player=${player.name} reason=creative")
            return
        }

        val item = player.inventory.itemInMainHand

        if (item.type.isAir) {
            debug("consumeOne skipped player=${player.name} reason=empty_hand")
            return
        }

        debug(
            "consumeOne player=${player.name} item=${item.type} amountBefore=${item.amount}"
        )

        if (item.amount <= 1) {
            player.inventory.setItemInMainHand(null)
            debug("consumeOne result=clear_main_hand")
        } else {
            item.amount = item.amount - 1
            player.inventory.setItemInMainHand(item)
            debug("consumeOne result=amountAfter=${item.amount}")
        }
    }

    private fun dropOnePiece(bukkitPlayer: org.bukkit.entity.Player) {
        //val bukkitWorld = Bukkit.getWorld(world.name()) ?: return
        val dropStack = resolveDropItem() ?: return

        if(bukkitPlayer.gameMode == GameMode.CREATIVE || bukkitPlayer.gameMode == GameMode.SPECTATOR) return

        StorageUtils.addItemToInventoryOrDrop(bukkitPlayer, dropStack)

        /*val location = org.bukkit.Location(
            bukkitWorld,
            pos.x() + 0.25 + MiniCubeMask.x(bit) * 0.5,
            pos.y() + 0.25 + MiniCubeMask.y(bit) * 0.5,
            pos.z() + 0.25 + MiniCubeMask.z(bit) * 0.5
        )

        bukkitWorld.dropItemNaturally(location, dropStack)*/
    }

    private fun resolveDropItem(): ItemStack? {
        return createCraftEngineItem(itemId)
    }

    private fun createCraftEngineItem(id: String): ItemStack? {
        return if (id.length < 2) null else Optional.ofNullable<BukkitItemDefinition?>(
            CraftEngineItems.byId(
                Key.of(
                    id
                )
            )
        )
            .map<ItemStack?>(Function { obj: BukkitItemDefinition? -> obj!!.buildBukkitItem() })
            .orElse(null);
    }

    private fun resolveBukkitPlayer(player: Any?): org.bukkit.entity.Player? {
        if (player == null) return null

        if (player is org.bukkit.entity.Player) {
            return player
        }

        val uuid = runCatching {
            player.javaClass.getMethod("uuid").invoke(player) as? UUID
        }.getOrNull()

        if (uuid != null) {
            return Bukkit.getPlayer(uuid)
        }

        val uniqueId = runCatching {
            player.javaClass.getMethod("uniqueId").invoke(player) as? UUID
        }.getOrNull()

        if (uniqueId != null) {
            return Bukkit.getPlayer(uniqueId)
        }

        return null
    }

    override fun isPathFindable(thisBlock: Any, args: Array<out Any>): Boolean {
        return false
    }

    override fun fallOn(thisBlock: Any, args: Array<out Any>) {
    }

    override fun updateEntityMovementAfterFallOn(thisBlock: Any, args: Array<out Any>) {
    }

    companion object {
        val FACTORY = Factory()

        class Factory : BlockBehaviorFactory<MiniCubesBlockBehavior> {
            override fun create(
                block: BlockDefinition,
                section: ConfigSection
            ): MiniCubesBlockBehavior {
                val cubesPropertyName = section.getString("cubes", "mini_cubes")

                val cubesProperty: Property<MiniCubeMaskState> = BlockBehaviorFactory.getProperty(
                    section.path(),
                    block,
                    cubesPropertyName,
                    MiniCubeMaskState::class.java
                )

                val itemId = section.getString("item", "")
                val mode = MiniCubeMode.from(section.getString("mode", "full_8"))
                val removeWithShift = section.getBoolean("remove-with-shift", true)

                val flipX = section.getBoolean("flip-x", false)
                val flipY = section.getBoolean("flip-y", false)
                val flipZ = section.getBoolean("flip-z", false)

                val placeSound = section.getString("sounds.place-sound", "minecraft:block.stone.place")
                val breakSound = section.getString("sounds.break-sound", "minecraft:block.stone.break")

                return MiniCubesBlockBehavior(
                    customBlock = block,
                    cubesProperty = cubesProperty,
                    itemId = itemId,
                    mode = mode,
                    removeWithShift = removeWithShift,
                    flipX,
                    flipY,
                    flipZ,
                    placeSound,
                    breakSound
                )
            }
        }
    }
}