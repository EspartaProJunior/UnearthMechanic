package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior

import dev.wuason.unearthMechanic.compatibilities.craftengine.types.WindowTile
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior
import net.momirealms.craftengine.bukkit.nms.FastNMS
import net.momirealms.craftengine.bukkit.util.BlockStateUtils
import net.momirealms.craftengine.bukkit.world.BukkitWorld
import net.momirealms.craftengine.core.block.BlockDefinition
import net.momirealms.craftengine.core.block.ImmutableBlockState
import net.momirealms.craftengine.core.block.UpdateFlags
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory
import net.momirealms.craftengine.core.block.properties.Property
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import net.momirealms.craftengine.core.registry.Holder
import net.momirealms.craftengine.core.world.BlockPos
import net.momirealms.craftengine.core.world.World
import net.momirealms.craftengine.core.world.context.BlockPlaceContext
import org.bukkit.Bukkit
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap

/**
 * “Window” connection using ONLY the “tile” property.
 * Values: single, up_left, up, up_right, left, middle, right, down_left, down, down_right
 */
class WindowConnectTileBehavior(
    customBlock: BlockDefinition,
    private val tileProperty: Property<WindowTile>
) : BukkitBlockBehavior(customBlock) {

    //private val log = Bukkit.getLogger()

    // ================== Debounce scheduler ==================
    companion object {
        private val pending = ConcurrentHashMap<String, Boolean>()
        private fun k(w: World, p: BlockPos) = "${w.name()}:${p.x()},${p.y()},${p.z()}"

        private val inBatch: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

        val FACTORY = Factory()
        class Factory : BlockBehaviorFactory<WindowConnectTileBehavior> {
            override fun create(block: BlockDefinition, section: ConfigSection): WindowConnectTileBehavior {
                val prop = block.getProperty("tile")
                    ?: throw IllegalArgumentException("Missing 'tile' property")
                @Suppress("UNCHECKED_CAST")
                val tileProperty = prop as Property<WindowTile>
                return WindowConnectTileBehavior(block, tileProperty)
            }
        }
    }

    // ================== Hooks ==================
    override fun updateShape(thisBlock: Any, args: Array<Any>): Any {
        if (inBatch.get() == true) return args[0]

        val world = args.getOrNull(3) as? World ?: return super.updateShape(thisBlock, args)
        val pos   = args.getOrNull(4) as? BlockPos ?: return super.updateShape(thisBlock, args)
        //log.info("[WindowTile] updateShape(): (${world.name()}:${pos.x()},${pos.y()},${pos.z()})")

        val optState = BlockStateUtils.getOptionalCustomBlockState(args[0]) ?: return super.updateShape(thisBlock, args)
        val state = optState.get()
        val newTile = calculateTile(world, pos, state)
        //log.info("[WindowTile] updateShape(): APPLY (${world.name()}:${pos.x()},${pos.y()},${pos.z()}) tile='$newTile'")

        return state.with(tileProperty, newTile)
            .customBlockState()
            .minecraftState()
    }

    override fun updateStateForPlacement(context: BlockPlaceContext, state: ImmutableBlockState): ImmutableBlockState {
        val world = context.level
        val pos   = context.clickedPos
        //log.info("[WindowTile] updateStateForPlacement(): (${world.name()}:${pos.x()},${pos.y()},${pos.z()}) stateIn=${customBlock.id().asString()}")
        val newTile = calculateTile(world, pos, state)
        //log.info("[WindowTile] updateStateForPlacement(): (${world.name()}:${pos.x()},${pos.y()},${pos.z()}) → tile='$newTile'")
        return state.with(tileProperty, newTile)
    }

    override fun onPlace(thisBlock: Any, args: Array<Any>) {
        if (inBatch.get() == true) { super.onPlace(thisBlock, args); return }
        //log.info("[WindowTile] onPlace(): args.size=${args.size}")
        val nmsWorld = args.getOrNull(1) ?: run { super.onPlace(thisBlock, args); return }
        val nmsPos   = args.getOrNull(2) ?: run { super.onPlace(thisBlock, args); return }

        val craftWorld = Bukkit.getWorlds().firstOrNull { w ->
            val handle = w.javaClass.getMethod("getHandle").invoke(w)
            handle == nmsWorld
        } ?: run { super.onPlace(thisBlock, args); return }

        val x = nmsPos.javaClass.getMethod("getX").invoke(nmsPos) as Int
        val y = nmsPos.javaClass.getMethod("getY").invoke(nmsPos) as Int
        val z = nmsPos.javaClass.getMethod("getZ").invoke(nmsPos) as Int

        val ceWorld = BukkitAdaptor.adapt(craftWorld)
        val cePos   = BlockPos(x, y, z)
        scheduleNeighborUpdate(ceWorld, cePos, 2L)
        super.onPlace(thisBlock, args)
    }

    /*override fun onRemove(thisBlock: Any, args: Array<Any>, superMethod: Callable<Any>) {
        if (inBatch.get() == true) { superMethod.call(); return }
        //log.info("[WindowTile] onRemove(): args.size=${args.size}")
        handleRemoval(thisBlock, args); superMethod.call()
    }*/

    override fun affectNeighborsAfterRemoval(thisBlock: Any, args: Array<Any>) {
        if (inBatch.get() == true) { super.affectNeighborsAfterRemoval(thisBlock, args); return }
        //log.info("[WindowTile] affectNeighborsAfterRemoval(): args.size=${args.size}")
        handleRemoval(thisBlock, args); super.affectNeighborsAfterRemoval(thisBlock, args)
    }

    private fun handleRemoval(thisBlock: Any, args: Array<Any>) {

        val nmsWorld = args.getOrNull(1) ?: return
        val nmsPos   = args.getOrNull(2) ?: return

        FastNMS.INSTANCE.`method$ScheduledTickAccess$scheduleBlockTick`(nmsWorld, nmsPos, thisBlock, 2)

        /*val craftWorld = Bukkit.getWorlds().firstOrNull { w ->
            val handle = w.javaClass.getMethod("getHandle").invoke(w)
            handle == nmsWorld
        } ?: return

        val x = nmsPos.javaClass.getMethod("getX").invoke(nmsPos) as Int
        val y = nmsPos.javaClass.getMethod("getY").invoke(nmsPos) as Int
        val z = nmsPos.javaClass.getMethod("getZ").invoke(nmsPos) as Int

        val ceWorld = BukkitAdaptors.adapt(craftWorld)
        val cePos   = BlockPos(x, y, z)
        scheduleNeighborUpdate(ceWorld, cePos, 2L)*/
    }

    private fun scheduleNeighborUpdate(world: World, pos: BlockPos, delay: Long) {
        if (inBatch.get() == true) return

        val key = k(world, pos)
        if (pending.putIfAbsent(key, true) != null) {
            //log.info("[WindowTile] scheduleNeighborUpdate(): SKIP dup for $key")
            return
        }
        val plugin = Bukkit.getPluginManager().getPlugin("UnearthMechanic") ?: return
        //log.info("[WindowTile] scheduleNeighborUpdate(): ($key) +${delay}t")
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            try { updateNeighbors(world, pos) } finally { pending.remove(key) }
        }, delay)

    }

    // ================== Connection logic ==================

    private fun calculateTile(world: World, pos: BlockPos, selfState: ImmutableBlockState? = null): WindowTile {
        val originState = selfState ?: world.getBlock(pos).customBlockState()
        val originId = ceStateId(originState) ?: return WindowTile.single
        val ori = ceOrientation(originState)

        val axis0 = normalizeAxis(propValue(originState, "axis"))
        val facing0 = normalizeFacing(propValue(originState, "facing"))

        fun isSameOriented(p: BlockPos): Boolean {
            val st = world.getBlock(p).customBlockState() ?: return false
            if (ceStateId(st) != originId) return false

            val ax = normalizeAxis(propValue(st, "axis"))
            val fc = normalizeFacing(propValue(st, "facing"))

            if (axis0 != null && ax != axis0) return false
            if (facing0 != null && fc != facing0) return false

            return true
        }

        fun inferUseAxisZ(p: BlockPos): Boolean? {
            val hasX = isSameOriented(p.offset( 1, 0, 0)) || isSameOriented(p.offset(-1, 0, 0))
            val hasZ = isSameOriented(p.offset( 0, 0, 1)) || isSameOriented(p.offset( 0, 0,-1))
            return when {
                hasZ && !hasX -> true
                hasX && !hasZ -> false
                else -> null
            }
        }

        val defaultUseAxisZ = when (ori.kind) {
            OrientationKind.FACING -> (ori.value == "east" || ori.value == "west")
            OrientationKind.AXIS -> when (ori.value) {
                "x" -> true
                "z" -> false
                "y" -> false
                else -> false
            }
            OrientationKind.NONE -> false
        }

        val hasFacing = (facing0 != null)
        val useAxisZ = if (hasFacing) (facing0 == "east" || facing0 == "west")
        else (inferUseAxisZ(pos) ?: defaultUseAxisZ)

        var neg = if (useAxisZ) pos.offset(0,0,-1) else pos.offset(-1,0,0)
        var posi= if (useAxisZ) pos.offset(0,0, 1) else pos.offset( 1,0,0)

        if (hasFacing) {
            val invertLR = if (!useAxisZ) (facing0 == "north") else (facing0 == "east")
            if (invertLR) {
                val tmp = neg; neg = posi; posi = tmp
            }
        }

        val left  = isSameOriented(neg)
        val right = isSameOriented(posi)

        val up = isSameOriented(pos.offset(0, 1, 0))
        val dn = isSameOriented(pos.offset(0,-1, 0))

        val family = when {
            up && dn  -> "middle"
            dn && !up -> "up"
            up && !dn -> "down"
            else      -> "none"
        }

        // local tag (L/R/C)
        var tag = when {
            !left && !right -> "C"
            !left && right  -> "L"
            left && !right  -> "R"
            else            -> "C"
        }

        val axisFlip = (!hasFacing && ori.kind == OrientationKind.AXIS && ori.value == "x")
        if (axisFlip) tag = when (tag) { "L" -> "R"; "R" -> "L"; else -> "C" }

        // investment only if there is facing
        val invert = when {
            facing0 == null -> false
            !useAxisZ -> (facing0 == "south")
            else      -> (facing0 == "west")
        }

        val baseFlipZ = useAxisZ
        val doFlip = invert xor axisFlip xor baseFlipZ
        if (doFlip) tag = when (tag) { "L" -> "R"; "R" -> "L"; else -> "C" }

        fun tileFrom(fam: String, t: String): WindowTile = when (fam) {
            "up"     -> when (t) { "L" -> WindowTile.up_left;   "R" -> WindowTile.up_right;   else -> WindowTile.up }
            "down"   -> when (t) { "L" -> WindowTile.down_left; "R" -> WindowTile.down_right; else -> WindowTile.down }
            "middle" -> when (t) { "L" -> WindowTile.left;      "R" -> WindowTile.right;      else -> WindowTile.middle }
            else     -> if (t == "C") WindowTile.single else WindowTile.single
        }

        val fam2 = if (family == "none") "middle" else family
        return tileFrom(fam2, tag)

        /*val n = isSameOriented(pos.offset(0, 0, -1)) // N = -Z
        val e = isSameOriented(pos.offset(1, 0,  0)) // E = +X
        val s = isSameOriented(pos.offset(0, 0,  1)) // S = +Z
        val w = isSameOriented(pos.offset(-1,0,  0)) // W = -X

        val hasH = e || w
        val hasV = n || s

        if (hasH) {
            val family = when {
                !n && !s -> "down"
                s && !n  -> "up"
                n && !s  -> "down"
                else     -> "middle"
            }
            return when {
                e && w -> when (family) { "up" -> WindowTile.up; "down" -> WindowTile.down; else -> WindowTile.middle }
                e && !w -> when (family) { "up" -> WindowTile.up_left; "down" -> WindowTile.down_left; else -> WindowTile.left }
                w && !e -> when (family) { "up" -> WindowTile.up_right; "down" -> WindowTile.down_right; else -> WindowTile.right }
                else -> WindowTile.single
            }
        }

        if (!hasH && hasV) {
            return when {
                s && !n -> WindowTile.down_left
                n && !s -> WindowTile.down_right
                else    -> WindowTile.middle
            }
        }

        return WindowTile.single*/
    }

    private fun isSame(world: World, pos: BlockPos): Boolean {
        val wrap = world.getBlockState(pos) ?: return false
        //val wrap = world.getBlockAt(pos).blockState() ?: return false
        val neighborStr = try { wrap.ownerId()?.toString() } catch (_: Throwable) { null }
        val selfStr     = try { blockDefinition.id().asString() } catch (_: Throwable) { blockDefinition.id().toString() }
        return neighborStr != null && neighborStr == selfStr
    }

    // ---------------- Robust recalculation with flood-fill ----------------

    private enum class OrientationKind { FACING, AXIS, NONE }
    private data class Orientation(val kind: OrientationKind, val value: String)

    private fun ceStateId(state: ImmutableBlockState?): String? {
        if (state == null) return null
        val ref = state.owner() as? Holder.Reference<BlockDefinition> ?: return null
        return try { ref.key().location().asString() } catch (_: Throwable) { null }
    }

    private fun ceFacing(state: ImmutableBlockState?, fallback: String = "south"): String {
        if (state == null) return fallback
        return try {
            state.propertyEntries().entries
                .firstOrNull { it.key.name() == "facing" }
                ?.value?.toString()?.lowercase() ?: fallback
        } catch (_: Throwable) { fallback }
    }

    private fun ceAxis(state: ImmutableBlockState?, fallback: String = "z"): String {
        if (state == null) return fallback
        return try {
            state.propertyEntries().entries
                .firstOrNull { it.key.name() == "axis" }
                ?.value?.toString()?.lowercase() ?: fallback
        } catch (_: Throwable) { fallback }
    }

    private fun propValue(state: ImmutableBlockState?, name: String): Any? {
        if (state == null) return null
        return try {
            state.propertyEntries().entries
                .firstOrNull { it.key.name().equals(name, ignoreCase = true) }
                ?.value
        } catch (_: Throwable) { null }
    }

    private fun normalizeAny(v: Any?): String? {
        if (v == null) return null
        val s = when (v) {
            is Enum<*> -> v.name
            else -> v.toString()
        }.trim().lowercase()

        // limpia wrappers típicos (Axis.X, minecraft:x, etc.)
        return s.substringAfterLast('.')
            .substringAfterLast(':')
            .trim()
    }

    private fun normalizeAxis(v: Any?): String? {
        val s = normalizeAny(v) ?: return null
        return when (s) {
            "x", "y", "z" -> s
            else -> null
        }
    }

    private fun normalizeFacing(v: Any?): String? {
        val s = normalizeAny(v) ?: return null
        return when (s) {
            "north","south","east","west","up","down" -> s
            else -> null
        }
    }

    private fun shouldInvertLR(axis0: String?, facing0: String?, useAxisZ: Boolean): Boolean {
        // If facing exists in the blockstate, we respect your original behavior:
        if (facing0 != null) {
            // runs in X => invert if north
            // runs in Z => invert if west
            return if (!useAxisZ) (facing0 == "south") else (facing0 == "west")
        }

        // If there is NO facing (only axis), we adjust according to how your models are:
        return when (axis0) {
            "x" -> true              // FIX: your axis=x models are mirrored (left/right)
            "y" -> useAxisZ          // FIX: axis=y fails only on the “Z-run” axis (west/east)
            else -> false            // z and others ok
        }
    }

    private fun ceOrientation(state: ImmutableBlockState?): Orientation {
        val facing = normalizeFacing(propValue(state, "facing"))
        if (facing != null) return Orientation(OrientationKind.FACING, facing)

        val axis = normalizeAxis(propValue(state, "axis"))
        if (axis != null) return Orientation(OrientationKind.AXIS, axis)

        return Orientation(OrientationKind.NONE, "")
    }

    private fun getTileNameFor(
        useAxisZ: Boolean,   // true if the run is north-south (facing NORTH or SOUTH)
        invertLR: Boolean,   // true if left/right needs to be reversed (EAST or WEST)
        idx: Int,            // index within the segment 0..len-1
        len: Int,            // length of the segment
        family: String       // "up" | "middle" | "down"
    ): String {
        val first = idx == 0
        val last  = idx == len - 1

        return when (family) {
            "up" -> when {
                first && last -> "single"
                first         -> if (invertLR) "up_right" else "up_left"
                last          -> if (invertLR) "up_left"  else "up_right"
                else          -> "up"
            }
            "down" -> when {
                first && last -> "single"
                first         -> if (invertLR) "down_right" else "down_left"
                last          -> if (invertLR) "down_left"  else "down_right"
                else          -> "down"
            }
            else -> when { // middle
                first && last -> "single"
                first         -> if (invertLR) "right" else "left"
                last          -> if (invertLR) "left"  else "right"
                else          -> "middle"
            }
        }
    }


    private fun updateNeighbors(world: World, origin: BlockPos) {
        if (world !is BukkitWorld) return
        val bWorld = org.bukkit.Bukkit.getWorld(world.name()) ?: return

        val originState = world.getBlock(origin).customBlockState() ?: return
        //val originState = world.getBlockAt(origin).customBlockState() ?: return //OLD API METHOD
        val originId = ceStateId(originState) ?: return
        /*val facing = ceFacing(originState, "south")

        // E/W run in Z, N/S run in X
        val useAxisZ = (facing == "east" || facing == "west")
        // Left/right inversions by orientation:
        val invertLR_X = (facing == "north") // for runs in X
        val invertLR_Z = (facing == "east")  // for runs in Z

        fun sameBlockId(st: ImmutableBlockState?): Boolean = ceStateId(st) == originId

        fun isSameAt(pos: BlockPos): Boolean {
            val st = world.getBlock(pos).customBlockState() ?: return false
            //val st = world.getBlockAt(pos).customBlockState() ?: return false
            return sameBlockId(st) && ceFacing(st, facing) == facing
        }*/

        val axis0 = normalizeAxis(propValue(originState, "axis"))
        val facing0 = normalizeFacing(propValue(originState, "facing")) // can come even if there is axis

        val ori = ceOrientation(originState)

        val axisFlip = (facing0 == null && ori.kind == OrientationKind.AXIS && ori.value == "x")

        fun sameBlockId(st: ImmutableBlockState?): Boolean = ceStateId(st) == originId

        fun isSameAt(pos: BlockPos): Boolean {
            val st = world.getBlock(pos).customBlockState() ?: return false
            if (ceStateId(st) != originId) return false

            val ax = normalizeAxis(propValue(st, "axis"))
            val fc = normalizeFacing(propValue(st, "facing"))

            if (axis0 != null && ax != axis0) return false
            if (facing0 != null && fc != facing0) return false
            return true
        }

        fun inferUseAxisZ(p: BlockPos): Boolean? {
            val hasX = isSameAt(p.offset( 1, 0, 0)) || isSameAt(p.offset(-1, 0, 0))
            val hasZ = isSameAt(p.offset( 0, 0, 1)) || isSameAt(p.offset( 0, 0,-1))
            return when {
                hasZ && !hasX -> true   // Z
                hasX && !hasZ -> false  // X
                else -> null            // ambiguous (single or rare form)
            }
        }

        // axis has no direction => we do not invert L/R

        //val invertLR_X = ((ori.kind == OrientationKind.FACING && ori.value == "north") xor axisFlip)
        //val invertLR_Z = ((ori.kind == OrientationKind.FACING && ori.value == "west")  xor axisFlip)

        val invertLR_X = (facing0 == "south")
        val invertLR_Z = (facing0 == "west")

        fun setTile(p: BlockPos, tile: WindowTile) {
            val state = world.getBlock(p).customBlockState() ?: return

            val newState = try { state.with(tileProperty, tile) } catch (_: Throwable) { return }
            BukkitAdaptor.adapt(bWorld).setBlockState(p.x(), p.y(), p.z(), newState, UpdateFlags.UPDATE_ALL)
            //BukkitAdaptors.adapt(bWorld).setBlockAt(p.x(), p.y(), p.z(), newState, UpdateOption.UPDATE_ALL.flags()) //OLD API METHOD
            // log: "[WindowTile] (${p.x()},${p.y()},${p.z()}) tile='${tile.name.lowercase()}' facing=${facing}"
        }

        fun familyFor(p: BlockPos): String {
            val up = isSameAt(p.offset(0, 1, 0))
            val dn = isSameAt(p.offset(0,-1, 0))
            return when {
                up && dn  -> "middle"
                dn && !up -> "up"    // top row (has block below)
                up && !dn -> "down"  // bottom row (has block above)
                else      -> "none"
            }
        }

        fun tileFromFamily(fam: String, tag: String): WindowTile = when (fam) {
            "up"     -> when (tag) { "L" -> WindowTile.up_left;   "R" -> WindowTile.up_right;   else -> WindowTile.up }
            "down"   -> when (tag) { "L" -> WindowTile.down_left; "R" -> WindowTile.down_right; else -> WindowTile.down }
            "middle" -> when (tag) { "L" -> WindowTile.left;      "R" -> WindowTile.right;      else -> WindowTile.middle }
            else     -> WindowTile.single
        }

        // Only within updateNeighbors, so as not to touch other parts
        fun getTileNameFor(invertLR: Boolean, idx: Int, len: Int, family: String): WindowTile {
            val first = idx == 0
            val last  = idx == len - 1
            fun lrTag(): String = when {
                len == 1     -> "C"
                first        -> if (invertLR) "R" else "L"
                last         -> if (invertLR) "L" else "R"
                else         -> "C"
            }
            val tag = lrTag()
            return tileFromFamily(if (family == "none") "down" else family, tag)
        }

        // Limit: 9x9 (radius 4) and ±2 in Y
        val maxHr = 16 //4 // 9 wide (4 on each side + center)
        val maxVy = 8 //2

        val comp = LinkedHashSet<BlockPos>()
        val q: ArrayDeque<BlockPos> = ArrayDeque()

        if (!isSameAt(origin)) return
        comp.add(origin); q.add(origin)

        val dirs = arrayOf(
            BlockPos( 1, 0, 0), BlockPos(-1, 0, 0),
            BlockPos( 0, 0, 1), BlockPos( 0, 0,-1),
            BlockPos( 0, 1, 0), BlockPos( 0,-1, 0)
        )
        val ox = origin.x(); val oy = origin.y(); val oz = origin.z()

        while (q.isNotEmpty()) {
            val p = q.removeFirst()
            for (d in dirs) {
                val n = p.offset(d.x(), d.y(), d.z())
                val hr = maxOf(kotlin.math.abs(n.x() - ox), kotlin.math.abs(n.z() - oz))
                val vy = kotlin.math.abs(n.y() - oy)
                if (hr > maxHr || vy > maxVy) continue
                if (!comp.contains(n) && isSameAt(n)) {
                    comp.add(n); q.add(n)
                }
            }
        }

        val defaultUseAxisZ: Boolean = when (ori.kind) {
            OrientationKind.FACING -> (ori.value == "east" || ori.value == "west")
            OrientationKind.AXIS -> when (ori.value) {
                "x" -> true   // pared “tipo east/west” => runs en Z
                "z" -> false  // pared “tipo north/south” => runs en X
                "y" -> false
                else -> false
            }
            OrientationKind.NONE -> false
        }

        val minX = comp.minOf { it.x() }
        val maxX = comp.maxOf { it.x() }
        val minZ = comp.minOf { it.z() }
        val maxZ = comp.maxOf { it.z() }

        val dx = maxX - minX
        val dz = maxZ - minZ

        val fallbackUseAxisZ =
            if (facing0 != null) (facing0 == "east" || facing0 == "west")
            else false

        val useAxisZ = when {
            dz > dx -> true
            dx > dz -> false
            else -> fallbackUseAxisZ
        }

        //val invertLR = shouldInvertLR(axis0, facing0, useAxisZ)
        val invertLR =
            if (facing0 != null) {
                if (!useAxisZ) (facing0 == "north") else (facing0 == "east")
            } else {
                // Axis Only
                shouldInvertLR(axis0, null, useAxisZ)
            }

        // Plan by rows (by Y)
        val rowsByY = comp.groupBy { it.y() }
        //val plan = ArrayList<Pair<BlockPos, String>>()
        val plan = mutableListOf<Pair<BlockPos, WindowTile>>()

        for ((y, row) in rowsByY) {
            if (!useAxisZ) {
                // NORTH/SOUTH → runs in X; reverse only if facing == NORTH
                val byZ = row.groupBy { it.z() }
                for ((z, col) in byZ) {
                    val xs = col.map { it.x() }.sorted()
                    var i = 0
                    while (i < xs.size) {
                        var j = i
                        while (j + 1 < xs.size && xs[j + 1] == xs[j] + 1) j++
                        val run = xs.subList(i, j + 1)
                        for ((idx, x) in run.withIndex()) {
                            val pos = BlockPos(x, y, z)
                            val fam = familyFor(pos)
                            //val tile = getTileNameFor(invertLR_X, idx, run.size, fam)
                            val tile = getTileNameFor(invertLR, idx, run.size, fam)
                            plan.add(pos to tile)
                        }
                        i = j + 1
                    }
                }
            } else {
                // EAST/WEST → runs in Z; reverse only if facing == WEST
                val byX = row.groupBy { it.x() }
                for ((x, col) in byX) {
                    val zs = col.map { it.z() }.sorted()
                    var i = 0
                    while (i < zs.size) {
                        var j = i
                        while (j + 1 < zs.size && zs[j + 1] == zs[j] + 1) j++
                        val run = zs.subList(i, j + 1)
                        for ((idx, z) in run.withIndex()) {
                            val pos = BlockPos(x, y, z)
                            val fam = familyFor(pos)
                            val tile = getTileNameFor(invertLR, idx, run.size, fam)
                            plan.add(pos to tile)
                        }
                        i = j + 1
                    }
                }
            }
        }

        if (comp.size == 1) {
            plan.clear()
            plan.add(origin to WindowTile.single)
        }

        inBatch.set(true)
        try {
            for ((p, t) in plan) setTile(p, t)
        } finally {
            inBatch.set(false)
        }
        //log.info("[WindowTile] updateNeighbors(): done facing=${facing} (${comp.size} bloques)")
    }

}