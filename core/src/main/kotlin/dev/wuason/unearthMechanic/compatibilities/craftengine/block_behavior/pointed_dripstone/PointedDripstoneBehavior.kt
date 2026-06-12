package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.pointed_dripstone

import dev.wuason.unearthMechanic.compatibilities.craftengine.types.PointedDripstoneThickness
import dev.wuason.unearthMechanic.compatibilities.craftengine.types.PointedDripstoneVerticalDirection
import dev.wuason.unearthMechanic.utils.FoliaUtils
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor
import net.momirealms.craftengine.bukkit.api.CraftEngineItems
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition
import net.momirealms.craftengine.bukkit.util.BlockStateUtils
import net.momirealms.craftengine.bukkit.world.BukkitWorld
import net.momirealms.craftengine.core.block.BlockDefinition
import net.momirealms.craftengine.core.block.BlockStateWrapper
import net.momirealms.craftengine.core.block.ImmutableBlockState
import net.momirealms.craftengine.core.block.UpdateFlags
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory
import net.momirealms.craftengine.core.block.behavior.RandomTickBlock
import net.momirealms.craftengine.core.block.property.Property
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import net.momirealms.craftengine.core.util.Key
import net.momirealms.craftengine.core.world.BlockPos
import net.momirealms.craftengine.core.world.World
import net.momirealms.craftengine.core.world.context.BlockPlaceContext
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.Display
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Trident
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import java.util.concurrent.ThreadLocalRandom
import java.util.Optional
import kotlin.math.max

class PointedDripstoneBehavior(
    customBlock: BlockDefinition,
    private val thicknessProperty: Property<PointedDripstoneThickness>,
    private val verticalDirectionProperty: Property<PointedDripstoneVerticalDirection>,
    private val waterloggedProperty: Property<Boolean>? = null,
    private val maxScan: Int = 32,
    private val maxGrowthLength: Int = 7,
    private val growthChance: Int = 10
) : BukkitBlockBehavior(customBlock), RandomTickBlock {

    private val debug = false

    private fun debug(message: String) {
        if (!debug) return

        Bukkit.getLogger().info(
            "[UM-POINTED-DRIPSTONE] ${blockDefinition.id()} | $message"
        )
    }

    init {
        debug("behavior created")
    }

    override fun updateShape(thisBlock: Any, args: Array<Any>): Any {
        val optionalState = BlockStateUtils.getOptionalCustomBlockState(args[0]) ?: return super.updateShape(thisBlock, args)
        val state = optionalState.get()

        val (world, pos) = worldAndPosFromUpdateShapeArgs(args) ?: return super.updateShape(thisBlock, args)
        debug("updateShape at ${formatPos(pos)} state=${describeState(state)}")

        scheduleColumnUpdate(world, pos, 1L)
        schedulePhysicsCheck(world, pos, 1L)

        return recalculateState(world, pos, state).customBlockState().minecraftState()
    }

    override fun updateStateForPlacement(context: BlockPlaceContext, state: ImmutableBlockState): ImmutableBlockState {
        val world = context.level
        val pos = context.clickedPos

        val direction = placementDirection(context, world, pos)
        var placedState = state.with(verticalDirectionProperty, direction)
        val waterAtPlacement = isWaterAt(world, pos)
        debug("updateStateForPlacement at ${formatPos(pos)} direction=$direction water=$waterAtPlacement")

        waterloggedProperty?.let { property ->
            placedState = placedState.with(property, waterAtPlacement)
        }

        scheduleColumnUpdate(world, pos, 1L)
        schedulePhysicsCheck(world, pos, 1L)
        return recalculateState(world, pos, placedState)
    }

    override fun neighborChanged(thisBlock: Any, args: Array<Any>) {
        val (world, pos) = worldAndPosFromNmsArgs(args) ?: return
        debug("neighborChanged at ${formatPos(pos)}")

        scheduleColumnUpdate(world, pos, 1L)
        schedulePhysicsCheck(world, pos, 1L)
    }

    override fun tick(thisBlock: Any, args: Array<Any>) {
        val (world, pos) = worldAndPosFromNmsArgs(args) ?: return
        debug("tick at ${formatPos(pos)}")

        scheduleColumnUpdate(world, pos, 1L)
        schedulePhysicsCheck(world, pos, 1L)
    }

    override fun randomTick(thisBlock: Any, args: Array<Any>) {
        val (world, pos) = worldAndPosFromNmsArgs(args) ?: run {
            debug("randomTick ignored: cannot read world/pos from args=${args.mapIndexed { index, value -> "$index=${value.javaClass.name}" }}")
            return
        }
        val state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null) ?: run {
            debug("randomTick ignored at ${formatPos(pos)}: args[0] has no custom state")
            return
        }

        scheduleColumnUpdate(world, pos, 1L)
        schedulePhysicsCheck(world, pos, 1L)

        val chance = growthChance.coerceAtLeast(1)
        val roll = ThreadLocalRandom.current().nextInt(chance)
        debug("randomTick at ${formatPos(pos)} state=${describeState(state)} roll=$roll/$chance")

        if (roll == 0) {
            tryGrow(world, pos, state)
        }
    }

    override fun canSurvive(thisBlock: Any, args: Array<Any>): Boolean {
        val state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null) ?: return true
        val (world, pos) = worldAndPosFromNmsArgs(args) ?: return true

        return canSurvive(world, pos, state)
    }

    override fun onPlace(thisBlock: Any, args: Array<Any>) {
        val (world, pos) = worldAndPosFromNmsArgs(args) ?: run {
            super.onPlace(thisBlock, args)
            return
        }
        debug("onPlace at ${formatPos(pos)}")

        scheduleColumnUpdate(world, pos, 1L)
        schedulePhysicsCheck(world, pos, 1L)
        super.onPlace(thisBlock, args)
    }

    override fun affectNeighborsAfterRemoval(thisBlock: Any, args: Array<Any>) {
        val worldAndPos = worldAndPosFromNmsArgs(args)

        super.affectNeighborsAfterRemoval(thisBlock, args)

        if (worldAndPos != null) {
            val (world, pos) = worldAndPos
            scheduleColumnUpdate(world, pos, 1L)
            schedulePhysicsCheck(world, pos, 1L)
        }
    }

    override fun stepOn(thisBlock: Any, args: Array<Any>) {
        super.stepOn(thisBlock, args)
    }

    override fun entityInside(thisBlock: Any, args: Array<Any>) {
        return
    }

    override fun onProjectileHit(thisBlock: Any, args: Array<Any>) {
        val projectile = args.getOrNull(3).toBukkitEntity()

        if (projectile !is Trident) return
        val tridentVelocity = projectile.velocity.clone()

        val world = worldFromNmsWorld(args.getOrNull(0)) ?: return
        val pos = blockPosFromHitResult(args.getOrNull(2)) ?: return
        val state = world.getBlock(pos).customBlockState() ?: return

        if (!isSameBlock(state)) return

        val location = toBukkitLocation(world, pos) ?: return
        forceDropCustomItem(location)
        playBreakSound(location)
        setAir(world, pos)
        releaseTrident(projectile, tridentVelocity, location)

        scheduleColumnUpdate(world, pos, 1L)
        schedulePhysicsCheck(world, pos, 1L)
    }

    override fun fallOn(thisBlock: Any, args: Array<Any>) {
        val state = BlockStateUtils.getOptionalCustomBlockState(args[1]).orElse(null) ?: return super.fallOn(thisBlock, args)

        if (state.get(verticalDirectionProperty) != PointedDripstoneVerticalDirection.up) {
            super.fallOn(thisBlock, args)
            return
        }

        val fallDistance = when (val value = args.getOrNull(4)) {
            is Double -> value
            is Float -> value.toDouble()
            is Number -> value.toDouble()
            else -> 0.0
        }

        val damage = if (fallDistance < 2.0) {
            1.0
        } else {
            max(1.0, fallDistance * 2.0)
        }

        damageEntity(args.getOrNull(3), damage, respectNoDamageTicks = false)
        super.updateEntityMovementAfterFallOn(thisBlock, arrayOf(args[0], args[3]))
    }

    private fun worldAndPosFromUpdateShapeArgs(args: Array<Any>): Pair<World, BlockPos>? {
        val currentVersion = (args.getOrNull(1) as? World)?.let { world ->
            (args.getOrNull(3) as? BlockPos)?.let { pos -> world to pos }
        }

        if (currentVersion != null) return currentVersion

        val oldVersion = (args.getOrNull(3) as? World)?.let { world ->
            (args.getOrNull(4) as? BlockPos)?.let { pos -> world to pos }
        }

        return oldVersion
    }

    private fun worldAndPosFromNmsArgs(args: Array<Any>, worldIndex: Int = 1, posIndex: Int = 2): Pair<World, BlockPos>? {
        val nmsWorld = args.getOrNull(worldIndex) ?: return null
        val nmsPos = args.getOrNull(posIndex) ?: return null

        val world = worldFromNmsWorld(nmsWorld) ?: return null
        val pos = blockPosFromNms(nmsPos) ?: return null

        return world to pos
    }

    private fun worldFromNmsWorld(nmsWorld: Any?): World? {
        if (nmsWorld == null) return null

        val craftWorld = Bukkit.getWorlds().firstOrNull { world ->
            val handle = world.javaClass.getMethod("getHandle").invoke(world)
            handle == nmsWorld
        } ?: return null

        return BukkitAdaptor.adapt(craftWorld)
    }

    private fun blockPosFromNms(nmsPos: Any?): BlockPos? {
        if (nmsPos == null) return null

        val x = nmsPos.javaClass.getMethod("getX").invoke(nmsPos) as Int
        val y = nmsPos.javaClass.getMethod("getY").invoke(nmsPos) as Int
        val z = nmsPos.javaClass.getMethod("getZ").invoke(nmsPos) as Int

        return BlockPos(x, y, z)
    }

    private fun blockPosFromHitResult(hitResult: Any?): BlockPos? {
        if (hitResult == null) return null

        val nmsPos = runCatching {
            hitResult.javaClass.getMethod("getBlockPos").invoke(hitResult)
        }.getOrNull() ?: return null

        return blockPosFromNms(nmsPos)
    }

    private fun placementDirection(
        context: BlockPlaceContext,
        world: World,
        pos: BlockPos
    ): PointedDripstoneVerticalDirection {
        val clickedFace = readClickedFaceName(context)

        if (clickedFace == "down") return PointedDripstoneVerticalDirection.down
        if (clickedFace == "up") return PointedDripstoneVerticalDirection.up

        val above = world.getBlock(pos.offset(0, 1, 0)).customBlockState()
        val below = world.getBlock(pos.offset(0, -1, 0)).customBlockState()

        if (above != null && isSameBlock(above)) return PointedDripstoneVerticalDirection.down
        if (below != null && isSameBlock(below)) return PointedDripstoneVerticalDirection.up

        return PointedDripstoneVerticalDirection.up
    }

    private fun readClickedFaceName(context: BlockPlaceContext): String? {
        val candidates = arrayOf("clickedFace", "getClickedFace", "face", "getFace")

        for (name in candidates) {
            val value = runCatching {
                val method = context.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                    ?: return@runCatching null
                method.invoke(context)
            }.getOrNull() ?: continue

            return value.toString().substringAfterLast('.').lowercase()
        }

        return null
    }

    private fun recalculateState(world: World, pos: BlockPos, state: ImmutableBlockState): ImmutableBlockState {
        val direction = state.get(verticalDirectionProperty)
        val thickness = calculateThickness(world, pos, direction)
        return state.with(thicknessProperty, thickness)
    }

    private fun calculateThickness(
        world: World,
        pos: BlockPos,
        direction: PointedDripstoneVerticalDirection
    ): PointedDripstoneThickness {
        val ahead = pos.offset(0, direction.stepY, 0)
        val behind = pos.offset(0, -direction.stepY, 0)

        val aheadState = world.getBlock(ahead).customBlockState()
        val behindState = world.getBlock(behind).customBlockState()

        val aheadSameDirection = aheadState != null && isSameBlock(aheadState) && aheadState.get(verticalDirectionProperty) == direction
        val behindSameDirection = behindState != null && isSameBlock(behindState) && behindState.get(verticalDirectionProperty) == direction

        if (!aheadSameDirection) {
            val mergesWithOppositeTip = aheadState != null &&
                    isSameBlock(aheadState) &&
                    aheadState.get(verticalDirectionProperty) == direction.opposite() &&
                    aheadState.get(thicknessProperty) in setOf(PointedDripstoneThickness.tip, PointedDripstoneThickness.tip_merge)

            return if (mergesWithOppositeTip) PointedDripstoneThickness.tip_merge else PointedDripstoneThickness.tip
        }

        return if (distanceToTip(world, pos, direction) == 1) {
            PointedDripstoneThickness.frustum
        } else {
            if (!behindSameDirection) {
                PointedDripstoneThickness.base
            } else {
                PointedDripstoneThickness.middle
            }
        }
    }

    private fun distanceToTip(
        world: World,
        pos: BlockPos,
        direction: PointedDripstoneVerticalDirection
    ): Int {
        var distance = 0
        var cursor = pos

        repeat(maxScan) {
            cursor = cursor.offset(0, direction.stepY, 0)
            val state = world.getBlock(cursor).customBlockState()

            if (state == null || !isSameBlock(state) || state.get(verticalDirectionProperty) != direction) {
                return distance
            }

            distance++
        }

        return distance
    }

    private fun scheduleColumnUpdate(world: World, pos: BlockPos, delay: Long) {
        val bukkitLoc = toBukkitLocation(world, pos) ?: return

        FoliaUtils.runLater(delay) {
            FoliaUtils.runAtLocation(bukkitLoc) {
                updateColumn(world, pos)
            }
        }
    }

    private fun schedulePhysicsCheck(world: World, pos: BlockPos, delay: Long) {
        val bukkitLoc = toBukkitLocation(world, pos) ?: return

        FoliaUtils.runLater(delay) {
            FoliaUtils.runAtLocation(bukkitLoc) {
                checkPhysicsAround(world, pos)
            }
        }
    }

    private fun tryGrow(world: World, pos: BlockPos, state: ImmutableBlockState) {
        if (!isSameBlock(state)) {
            debug("grow cancelled at ${formatPos(pos)}: state is not this block: ${describeState(state)}")
            return
        }

        val direction = state.get(verticalDirectionProperty)
        val tipPos = findTipPos(world, pos, direction) ?: run {
            debug("grow cancelled at ${formatPos(pos)}: cannot find tip direction=$direction")
            return
        }
        val tipState = world.getBlock(tipPos).customBlockState() ?: run {
            debug("grow cancelled at ${formatPos(pos)}: tip ${formatPos(tipPos)} has no custom state")
            return
        }

        if (!isSameBlock(tipState)) {
            debug("grow cancelled at ${formatPos(pos)}: tip ${formatPos(tipPos)} is another custom block: ${describeState(tipState)}")
            return
        }

        val tipThickness = tipState.get(thicknessProperty)
        if (tipThickness !in setOf(PointedDripstoneThickness.tip, PointedDripstoneThickness.tip_merge)) {
            debug("grow cancelled at ${formatPos(pos)}: found ${formatPos(tipPos)} but thickness=$tipThickness is not tip/tip_merge")
            return
        }

        if (!hasGrowthRoot(world, tipPos, direction)) {
            debug("grow cancelled at ${formatPos(pos)}: no valid growth root behind tip=${formatPos(tipPos)} direction=$direction")
            return
        }

        val length = columnLengthFromTip(world, tipPos, direction)
        if (length >= maxGrowthLength) {
            debug("grow cancelled at ${formatPos(pos)}: max length reached length=$length max=$maxGrowthLength tip=${formatPos(tipPos)} direction=$direction")
            return
        }

        val targetPos = tipPos.offset(0, direction.stepY, 0)
        val targetState = world.getBlock(targetPos).customBlockState()
        debug("grow attempt from ${formatPos(pos)} tip=${formatPos(tipPos)} target=${formatPos(targetPos)} targetCustom=${targetState?.let { describeState(it) }} targetBukkit=${describeBukkitBlock(world, targetPos)}")

        if (targetState != null && isSameBlock(targetState)) {
            if (targetState.get(verticalDirectionProperty) == direction.opposite() &&
                targetState.get(thicknessProperty) in setOf(PointedDripstoneThickness.tip, PointedDripstoneThickness.tip_merge)
            ) {
                debug("grow merge at ${formatPos(tipPos)} with opposite tip ${formatPos(targetPos)}")
                setBlockState(world, tipPos, tipState.with(thicknessProperty, PointedDripstoneThickness.tip_merge))
                setBlockState(world, targetPos, targetState.with(thicknessProperty, PointedDripstoneThickness.tip_merge))
                scheduleColumnUpdate(world, tipPos, 1L)
                scheduleColumnUpdate(world, targetPos, 1L)
            } else {
                debug("grow cancelled at ${formatPos(pos)}: target is same block but not opposite tip: ${describeState(targetState)}")
            }
            return
        }

        if (targetState != null) {
            debug("grow cancelled at ${formatPos(pos)}: target has another custom block: ${describeState(targetState)}")
            return
        }

        if (!isEmptyAt(world, targetPos)) {
            debug("grow cancelled at ${formatPos(pos)}: target is not empty/water: ${describeBukkitBlock(world, targetPos)}")
            return
        }

        setBlockState(
            world,
            targetPos,
            createPointedDripstoneState(
                direction,
                PointedDripstoneThickness.tip,
                isWaterAt(world, targetPos)
            )
        )
        scheduleColumnUpdate(world, tipPos, 1L)
        scheduleColumnUpdate(world, targetPos, 1L)
        schedulePhysicsCheck(world, targetPos, 1L)
        debug("grow placed new tip at ${formatPos(targetPos)} direction=$direction waterlogged=${isWaterAt(world, targetPos)}")
    }

    private fun createPointedDripstoneState(
        direction: PointedDripstoneVerticalDirection,
        thickness: PointedDripstoneThickness,
        waterlogged: Boolean
    ): ImmutableBlockState {
        val waterloggedProp = this.waterloggedProperty
        val exactState = blockDefinition.variantProvider().states().firstOrNull { candidate ->
            candidate.get(verticalDirectionProperty) == direction &&
                    candidate.get(thicknessProperty) == thickness &&
                    (waterloggedProp == null || candidate.get(waterloggedProp) == waterlogged)
        }

        if (exactState != null) {
            debug("created exact state direction=$direction thickness=$thickness waterlogged=$waterlogged")
            return exactState
        }

        debug("exact state not found direction=$direction thickness=$thickness waterlogged=$waterlogged; falling back to property mutation")
        var state = blockDefinition.variantProvider().states().first()
            .with(verticalDirectionProperty, direction)
            .with(thicknessProperty, thickness)

        if (waterloggedProp != null) {
            state = state.with(waterloggedProp, waterlogged)
        }

        return state
    }

    private fun findTipPos(
        world: World,
        pos: BlockPos,
        direction: PointedDripstoneVerticalDirection
    ): BlockPos? {
        var cursor = pos
        var lastSameDirection: BlockPos? = null

        repeat(maxScan) {
            val state = world.getBlock(cursor).customBlockState()

            if (state == null || !isSameBlock(state) || state.get(verticalDirectionProperty) != direction) {
                return lastSameDirection
            }

            lastSameDirection = cursor

            if (state.get(thicknessProperty) in setOf(PointedDripstoneThickness.tip, PointedDripstoneThickness.tip_merge)) {
                return cursor
            }

            cursor = cursor.offset(0, direction.stepY, 0)
        }

        return lastSameDirection
    }

    private fun hasGrowthRoot(world: World, pos: BlockPos, direction: PointedDripstoneVerticalDirection): Boolean {
        var cursor = pos

        repeat(maxScan) {
            val state = world.getBlock(cursor).customBlockState()

            if (state == null || !isSameBlock(state) || state.get(verticalDirectionProperty) != direction) {
                val result = isGrowthRoot(world, cursor)
                debug("growth root check tip=${formatPos(pos)} direction=$direction rootCandidate=${formatPos(cursor)} result=$result custom=${state?.let { describeState(it) }} bukkit=${describeBukkitBlock(world, cursor)}")
                return result
            }

            cursor = cursor.offset(0, -direction.stepY, 0)
        }

        debug("growth root check failed tip=${formatPos(pos)} direction=$direction: maxScan=$maxScan reached")
        return false
    }

    private fun columnLengthFromTip(
        world: World,
        tipPos: BlockPos,
        direction: PointedDripstoneVerticalDirection
    ): Int {
        var length = 0
        var cursor = tipPos

        repeat(maxScan) {
            val state = world.getBlock(cursor).customBlockState()

            if (state == null || !isSameBlock(state) || state.get(verticalDirectionProperty) != direction) {
                return length
            }

            length++
            cursor = cursor.offset(0, -direction.stepY, 0)
        }

        return length
    }

    private fun isGrowthRoot(world: World, pos: BlockPos): Boolean {
        val customState = world.getBlock(pos).customBlockState()
        val customId = customState?.owner()?.value()?.id()?.toString()

        if (customId != null && isCustomGrowthRoot(customId)) {
            return true
        }

        val location = toBukkitLocation(world, pos) ?: return false
        return location.block.type in setOf(
            Material.ICE,
            Material.PACKED_ICE,
            Material.BLUE_ICE,
            Material.DRIPSTONE_BLOCK
        )
    }

    private fun isCustomGrowthRoot(customId: String): Boolean {
        return customId.endsWith(":ice_dripstone_block") ||
                customId.endsWith(":ice_stone") ||
                customId.endsWith(":ice_deepslate") ||
                customId.endsWith(":reinforced_ice_deepslate")
    }

    private fun isEmptyAt(world: World, pos: BlockPos): Boolean {
        val location = toBukkitLocation(world, pos) ?: return false
        return location.block.isEmpty || location.block.type == Material.WATER
    }

    private fun isWaterAt(world: World, pos: BlockPos): Boolean {
        val location = toBukkitLocation(world, pos) ?: return false
        return location.block.type == Material.WATER
    }

    private fun formatPos(pos: BlockPos): String {
        return "${pos.x()},${pos.y()},${pos.z()}"
    }

    private fun describeState(state: ImmutableBlockState): String {
        val id = state.owner().value().id()
        val thickness = runCatching { state.get(thicknessProperty) }
            .getOrNull()
            ?.let { "thickness=$it" }
        val direction = runCatching { state.get(verticalDirectionProperty) }
            .getOrNull()
            ?.let { "vertical_direction=$it" }
        val waterlogged = waterloggedProperty
            ?.let { property -> runCatching { state.get(property) }.getOrNull() }
            ?.let { "waterlogged=$it" }

        val properties = listOfNotNull(thickness, direction, waterlogged).joinToString(",")
        return if (properties.isEmpty()) id.toString() else "$id[$properties]"
    }

    private fun describeBukkitBlock(world: World, pos: BlockPos): String {
        val location = toBukkitLocation(world, pos) ?: return "unknown"
        val block = location.block
        return "${block.type.name.lowercase()} empty=${block.isEmpty} passable=${block.isPassable}"
    }

    private fun checkPhysicsAround(world: World, pos: BlockPos) {
        if (world !is BukkitWorld) return

        val targets = linkedSetOf<BlockPos>()
        targets += pos

        listOf(PointedDripstoneVerticalDirection.up, PointedDripstoneVerticalDirection.down).forEach { direction ->
            var cursor = pos

            for (ignored in 0 until maxScan) {
                cursor = cursor.offset(0, direction.stepY, 0)
                val state = world.getBlock(cursor).customBlockState()

                if (state == null || !isSameBlock(state)) break
                targets += cursor
            }
        }

        targets
            .sortedByDescending { it.y() }
            .forEach { target ->
                val state = world.getBlock(target).customBlockState() ?: return@forEach
                if (!isSameBlock(state)) return@forEach
                if (canSurvive(world, target, state)) return@forEach

                if (state.get(verticalDirectionProperty) == PointedDripstoneVerticalDirection.down) {
                    turnIntoCustomFallingDripstone(world, target, state)
                } else {
                    breakUnsupportedDripstone(world, target)
                }
            }
    }

    private fun canSurvive(world: World, pos: BlockPos, state: ImmutableBlockState): Boolean {
        val direction = state.get(verticalDirectionProperty)
        val supportPos = pos.offset(0, -direction.stepY, 0)
        val supportState = world.getBlock(supportPos).customBlockState()

        if (supportState != null && isSameBlock(supportState)) {
            return supportState.get(verticalDirectionProperty) == direction
        }

        val supportLocation = toBukkitLocation(world, supportPos) ?: return true
        return supportLocation.block.type.isSolid
    }

    private fun turnIntoCustomFallingDripstone(world: World, pos: BlockPos, state: ImmutableBlockState) {
        val location = toBukkitLocation(world, pos) ?: return
        val bukkitWorld = location.world ?: return

        setAir(world, pos)

        val display = bukkitWorld.spawn(location.add(0.5, 0.0, 0.5), ItemDisplay::class.java)
        display.itemStack = createDropItemStack()
        display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
        display.billboard = Display.Billboard.FIXED
        display.isPersistent = false

        tickCustomFallingDripstone(display, -0.08, 0)
    }

    private fun tickCustomFallingDripstone(display: ItemDisplay, velocityY: Double, age: Int) {
        if (!display.isValid || age > 240) {
            display.remove()
            return
        }

        val current = display.location
        val nextVelocity = max(velocityY - 0.04, -1.5)
        val next = current.clone().add(0.0, nextVelocity, 0.0)

        if (!next.block.isPassable) {
            damageNearbyEntities(current, max(4.0, -nextVelocity * 12.0))
            playFallSound(current)
            playBreakSound(current)
            display.remove()
            return
        }

        display.teleport(next)

        FoliaUtils.runLater(1L) {
            FoliaUtils.runAtLocation(next) {
                tickCustomFallingDripstone(display, nextVelocity, age + 1)
            }
        }
    }

    private fun damageNearbyEntities(location: Location, amount: Double) {
        location.world
            ?.getNearbyEntities(location, 0.9, 1.2, 0.9)
            ?.filterIsInstance<LivingEntity>()
            ?.forEach { entity ->
                if (entity.noDamageTicks <= 0) {
                    entity.damage(amount)
                    entity.noDamageTicks = 10
                }
            }
    }

    private fun breakUnsupportedDripstone(world: World, pos: BlockPos) {
        val location = toBukkitLocation(world, pos)
        if (location != null) {
            forceDropCustomItem(location)
            playBreakSound(location)
        }

        setAir(world, pos)
    }

    private fun setAir(world: World, pos: BlockPos) {
        val bukkitWorld = Bukkit.getWorld(world.name()) ?: return
        bukkitWorld.getBlockAt(pos.x(), pos.y(), pos.z()).type = Material.AIR
    }

    private fun forceDropCustomItem(location: Location) {
        val world = location.world ?: return
        world.dropItemNaturally(location.clone().add(0.5, 0.25, 0.5), createDropItemStack())
    }

    private fun playBreakSound(location: Location) {
        location.world?.playSound(location, Sound.BLOCK_POINTED_DRIPSTONE_BREAK, 1.0f, 1.0f)
    }

    private fun playFallSound(location: Location) {
        location.world?.playSound(location, Sound.BLOCK_POINTED_DRIPSTONE_FALL, 1.0f, 1.0f)
    }

    private fun releaseTrident(trident: Trident, previousVelocity: Vector, brokenBlockLocation: Location) {
        val direction = if (previousVelocity.lengthSquared() > 0.0001) {
            previousVelocity.clone().normalize()
        } else {
            trident.location.direction.normalize()
        }

        val nextLocation = brokenBlockLocation.clone().add(0.5, 0.5, 0.5).add(direction.clone().multiply(0.65))

        FoliaUtils.runLater(1L) {
            FoliaUtils.runAtLocation(nextLocation) {
                if (!trident.isValid) return@runAtLocation

                trident.remove()
                nextLocation.world?.dropItemNaturally(nextLocation, ItemStack(Material.TRIDENT))
            }
        }
    }

    private fun createDropItemStack(): ItemStack {
        val customStack = createCraftEngineItem(blockDefinition.id().toString())
        if (customStack != null) return customStack

        return ItemStack(Material.POINTED_DRIPSTONE)
    }

    private fun createCraftEngineItem(id: String): ItemStack? {
        return if (id.length < 2) null else Optional.ofNullable<BukkitItemDefinition>(
            CraftEngineItems.byId(Key.of(id))
        )
            .map<ItemStack?> { itemDefinition -> itemDefinition.buildBukkitItem() }
            .orElse(null)
    }

    private fun damageEntity(nmsEntity: Any?, amount: Double, respectNoDamageTicks: Boolean = true) {
        val entity = nmsEntity.toBukkitEntity() as? LivingEntity ?: return

        if (respectNoDamageTicks && entity.noDamageTicks > 0) return

        if (!respectNoDamageTicks) {
            entity.noDamageTicks = 0
        }

        entity.damage(amount)
        entity.noDamageTicks = 10
    }

    private fun Any?.toBukkitEntity(): Entity? {
        if (this == null) return null

        return runCatching {
            javaClass.getMethod("getBukkitEntity").invoke(this) as? Entity
        }.getOrNull()
    }

    private fun updateColumn(world: World, pos: BlockPos) {
        if (world !is BukkitWorld) return

        val targets = linkedSetOf<BlockPos>()
        targets += pos

        listOf(PointedDripstoneVerticalDirection.up, PointedDripstoneVerticalDirection.down).forEach { direction ->
            var cursor = pos

            for (ignored in 0 until maxScan) {
                cursor = cursor.offset(0, direction.stepY, 0)
                val state = world.getBlock(cursor).customBlockState()

                if (state == null || !isSameBlock(state)) {
                    targets += cursor
                    break
                }

                targets += cursor
            }
        }

        targets.forEach { target ->
            val block = world.getBlock(target)
            val customState = block.customBlockState() ?: return@forEach

            if (!isSameBlock(customState)) return@forEach

            val updatedState = recalculateState(world, target, customState)
            setBlockState(world, target, updatedState)
        }
    }

    private fun setBlockState(world: World, pos: BlockPos, state: ImmutableBlockState) {
        val bukkitWorld = Bukkit.getWorld(world.name()) ?: return

        BukkitAdaptor.adapt(bukkitWorld).setBlockState(
            pos.x(),
            pos.y(),
            pos.z(),
            state,
            UpdateFlags.UPDATE_ALL
        )
    }

    private fun toBukkitLocation(world: World, pos: BlockPos): Location? {
        val bukkitWorld = Bukkit.getWorld(world.name()) ?: return null

        return Location(
            bukkitWorld,
            pos.x().toDouble(),
            pos.y().toDouble(),
            pos.z().toDouble()
        )
    }

    private fun isSameBlock(state: ImmutableBlockState): Boolean {
        return state.owner().value().id() == blockDefinition.id()
    }

    private fun isSameBlock(wrapper: BlockStateWrapper?): Boolean {
        return wrapper != null && wrapper.ownerId() == blockDefinition.id()
    }

    override fun canRandomlyTick(state: ImmutableBlockState?): Boolean {
        return state != null && isSameBlock(state)
    }

    companion object {
        val FACTORY = Factory()

        class Factory : BlockBehaviorFactory<PointedDripstoneBehavior> {
            override fun create(block: BlockDefinition, section: ConfigSection): PointedDripstoneBehavior {
                val thickness = block.getProperty("thickness")
                    ?: throw IllegalArgumentException("Missing 'thickness' property")
                val verticalDirection = block.getProperty("vertical_direction")
                    ?: throw IllegalArgumentException("Missing 'vertical_direction' property")
                val waterlogged = block.getProperty("waterlogged")

                @Suppress("UNCHECKED_CAST")
                return PointedDripstoneBehavior(
                    block,
                    thickness as Property<PointedDripstoneThickness>,
                    verticalDirection as Property<PointedDripstoneVerticalDirection>,
                    waterlogged as? Property<Boolean>,
                    maxScan = section.getInt("max-scan", 32),
                    maxGrowthLength = section.getInt("max-growth-length", 7),
                    growthChance = section.getInt("growth-chance", 10)
                )
            }
        }
    }
}