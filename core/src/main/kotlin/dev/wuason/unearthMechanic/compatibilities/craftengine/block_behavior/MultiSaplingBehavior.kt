package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior

import net.momirealms.craftengine.bukkit.api.BukkitAdaptor
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior
import net.momirealms.craftengine.bukkit.util.BlockStateUtils
import net.momirealms.craftengine.core.block.BlockDefinition
import net.momirealms.craftengine.core.block.ImmutableBlockState
import net.momirealms.craftengine.core.block.UpdateFlags
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory
import net.momirealms.craftengine.core.block.behavior.BonemealableBlock
import net.momirealms.craftengine.core.block.behavior.RandomTickBlock
import net.momirealms.craftengine.core.block.property.Property
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import net.momirealms.craftengine.core.util.Key
import net.momirealms.craftengine.core.world.BlockPos
import net.momirealms.craftengine.core.world.World
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Particle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import kotlin.collections.forEach
import kotlin.collections.get

class MultiSaplingBehavior(
    customBlock: BlockDefinition,
    private val stageProperty: Property<Int>?,
    private val patterns: List<SaplingPattern>,
    private val generators: List<SaplingGenerator>,
    private val growSpeed: Double,
    private val boneMealSuccessChance: Double,
    private val maxStage: Int,
    private val consumeSaplings: Boolean
) : BukkitBlockBehavior(customBlock), BonemealableBlock, RandomTickBlock {

    init {
        REGISTRY[customBlock.id()] = this
    }

    override fun randomTick(thisBlock: Any, args: Array<Any>) {
        if (ThreadLocalRandom.current().nextDouble() > growSpeed.coerceIn(0.0, 1.0)) return

        val rawState = args.getOrNull(0) ?: return
        val state = BlockStateUtils.getOptionalCustomBlockState(rawState).orElse(null) ?: return
        val (world, pos) = worldAndPosFromNmsArgs(args) ?: return

        if (!isSameBlock(state)) return
        advanceOrGrow(world, pos, state, fromBoneMeal = false)
    }

    fun tryBoneMeal(world: World, pos: BlockPos, state: ImmutableBlockState): Boolean {
        if (!isSameBlock(state)) return false
        if (ThreadLocalRandom.current().nextDouble() > boneMealSuccessChance.coerceIn(0.0, 1.0)) return false

        return boneMealPatternOrSingle(world, pos, state)
    }

    override fun isValidBonemealTarget(thisBlock: Any, args: Array<Any>): Boolean {
        return true
    }

    override fun isBonemealSuccess(thisBlock: Any, args: Array<Any>): Boolean {
        return ThreadLocalRandom.current().nextDouble() < boneMealSuccessChance.coerceIn(0.0, 1.0)
    }

    override fun performBonemeal(thisBlock: Any, args: Array<Any>) {
        val world = worldFromNms(args.getOrNull(0)) ?: return
        val pos = blockPosFromNms(args.getOrNull(2)) ?: return
        val rawState = args.getOrNull(3) ?: return
        val state = BlockStateUtils.getOptionalCustomBlockState(rawState).orElse(null) ?: return

        if (!isSameBlock(state)) return
        boneMealPatternOrSingle(world, pos, state)
    }

    private fun boneMealPatternOrSingle(world: World, pos: BlockPos, state: ImmutableBlockState): Boolean {
        val shapeMatch = findPatternMatch(world, pos, ignoreStage = true)
        val stage = stageProperty

        if (shapeMatch != null && stage != null) {
            val immature = shapeMatch.positions
                .mapNotNull { saplingPos -> world.getBlock(saplingPos).customBlockState()?.let { saplingPos to it } }
                .filter { (_, saplingState) -> readStage(saplingState) < maxStage }

            if (immature.isNotEmpty()) {
                immature.forEach { (saplingPos, saplingState) ->
                    setBlockState(world, saplingPos, saplingState.with(stage, maxStage))
                    spawnHappyParticles(world, saplingPos, enabled = true)
                }
                return true
            }
        }

        return advanceOrGrow(world, pos, state, fromBoneMeal = true)
    }

    private fun advanceOrGrow(
        world: World,
        pos: BlockPos,
        state: ImmutableBlockState,
        fromBoneMeal: Boolean
    ): Boolean {
        val currentStage = readStage(state)

        if (currentStage < maxStage) {
            stageProperty?.let { property ->
                setBlockState(world, pos, state.with(property, currentStage + 1))
                spawnHappyParticles(world, pos, fromBoneMeal)
            }
            return true
        }

        val match = findPatternMatch(world, pos, ignoreStage = false) ?: return false
        val generator = generators.randomOrNull() ?: return false

        if (consumeSaplings) {
            match.positions.forEach { saplingPos -> setAir(world, saplingPos) }
        }

        val target = match.origin.offset(generator.offsetX, generator.offsetY, generator.offsetZ)
        val generated = generator.generate(world, target)

        if (!generated && consumeSaplings) {
            val fallbackState = blockDefinition.variantProvider().states().firstOrNull() ?: return false
            match.positions.forEach { saplingPos -> setBlockState(world, saplingPos, fallbackState) }
        }

        spawnHappyParticles(world, pos, fromBoneMeal)
        return generated
    }

    private fun findPatternMatch(world: World, touchedPos: BlockPos, ignoreStage: Boolean): PatternMatch? {
        for (pattern in patterns) {
            for (anchorX in 0 until pattern.sizeX) {
                for (anchorY in 0 until pattern.sizeY) {
                    for (anchorZ in 0 until pattern.sizeZ) {
                        val origin = touchedPos.offset(-anchorX, -anchorY, -anchorZ)
                        val positions = pattern.positions(origin)

                        if (positions.all { pos -> isValidSapling(world, pos, pattern, ignoreStage) }) {
                            return PatternMatch(origin, positions)
                        }
                    }
                }
            }
        }

        return null
    }

    private fun isValidSapling(world: World, pos: BlockPos, pattern: SaplingPattern, ignoreStage: Boolean): Boolean {
        val state = world.getBlock(pos).customBlockState() ?: return false
        val id = state.owner().value().id()

        if (pattern.saplings.isNotEmpty() && id !in pattern.saplings) return false
        if (pattern.saplings.isEmpty() && id != blockDefinition.id()) return false

        if (ignoreStage) return true

        val stage = readStage(state)
        return stage >= pattern.minStage
    }

    private fun readStage(state: ImmutableBlockState): Int {
        return stageProperty?.let { property -> runCatching { state.get(property) }.getOrDefault(0) } ?: maxStage
    }

    private fun worldAndPosFromNmsArgs(args: Array<Any>, worldIndex: Int = 1, posIndex: Int = 2): Pair<World, BlockPos>? {
        val nmsWorld = args.getOrNull(worldIndex) ?: return null
        val nmsPos = args.getOrNull(posIndex) ?: return null

        val world = worldFromNms(nmsWorld) ?: return null
        val pos = blockPosFromNms(nmsPos) ?: return null

        return world to pos
    }

    private fun worldFromNms(nmsWorld: Any?): World? {
        if (nmsWorld == null) return null

        val craftWorld = Bukkit.getWorlds().firstOrNull { world ->
            runCatching { world.javaClass.getMethod("getHandle").invoke(world) == nmsWorld }.getOrDefault(false)
        } ?: return null

        return BukkitAdaptor.adapt(craftWorld)
    }

    private fun blockPosFromNms(nmsPos: Any?): BlockPos? {
        if (nmsPos == null) return null

        val x = runCatching { nmsPos.javaClass.getMethod("getX").invoke(nmsPos) as Int }.getOrNull() ?: return null
        val y = runCatching { nmsPos.javaClass.getMethod("getY").invoke(nmsPos) as Int }.getOrNull() ?: return null
        val z = runCatching { nmsPos.javaClass.getMethod("getZ").invoke(nmsPos) as Int }.getOrNull() ?: return null

        return BlockPos(x, y, z)
    }

    private fun setBlockState(world: World, pos: BlockPos, state: ImmutableBlockState) {
        val bukkitWorld = Bukkit.getWorld(world.name()) ?: return
        BukkitAdaptor.adapt(bukkitWorld).setBlockState(pos.x(), pos.y(), pos.z(), state, UpdateFlags.UPDATE_ALL)
    }

    private fun setAir(world: World, pos: BlockPos) {
        val bukkitWorld = Bukkit.getWorld(world.name()) ?: return
        bukkitWorld.getBlockAt(pos.x(), pos.y(), pos.z()).type = Material.AIR
    }

    private fun spawnHappyParticles(world: World, pos: BlockPos, enabled: Boolean) {
        if (!enabled) return

        val bukkitWorld = Bukkit.getWorld(world.name()) ?: return
        bukkitWorld.spawnParticle(
            Particle.HAPPY_VILLAGER,
            pos.x() + 0.5,
            pos.y() + 0.6,
            pos.z() + 0.5,
            8,
            0.25,
            0.25,
            0.25,
            0.0
        )
    }

    private fun isSameBlock(state: ImmutableBlockState): Boolean {
        return state.owner().value().id() == blockDefinition.id()
    }

    override fun canRandomlyTick(state: ImmutableBlockState?): Boolean {
        return state != null && isSameBlock(state)
    }

    data class SaplingPattern(
        val sizeX: Int,
        val sizeY: Int,
        val sizeZ: Int,
        val minStage: Int,
        val saplings: Set<Key>
    ) {
        fun positions(origin: BlockPos): List<BlockPos> {
            val result = ArrayList<BlockPos>(sizeX * sizeY * sizeZ)
            for (x in 0 until sizeX) {
                for (y in 0 until sizeY) {
                    for (z in 0 until sizeZ) {
                        result += origin.offset(x, y, z)
                    }
                }
            }
            return result
        }
    }

    data class PatternMatch(
        val origin: BlockPos,
        val positions: List<BlockPos>
    )

    data class SaplingGenerator(
        val type: String,
        val id: String?,
        val command: String?,
        val offsetX: Int,
        val offsetY: Int,
        val offsetZ: Int
    ) {
        fun generate(world: World, pos: BlockPos): Boolean {
            val bukkitWorld = Bukkit.getWorld(world.name()) ?: return false
            val dimension = bukkitWorld.key.toString()
            val x = pos.x()
            val y = pos.y()
            val z = pos.z()

            val resolvedCommand = when (type.lowercase()) {
                "feature" -> "execute in $dimension positioned $x $y $z run place feature ${id ?: return false}"
                "structure" -> "execute in $dimension positioned $x $y $z run place structure ${id ?: return false}"
                "command" -> command ?: return false
                else -> return false
            }.replace("{world}", bukkitWorld.name)
                .replace("{dimension}", dimension)
                .replace("{x}", x.toString())
                .replace("{y}", y.toString())
                .replace("{z}", z.toString())

            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolvedCommand)
        }
    }

    companion object {
        val FACTORY = Factory()
        private val REGISTRY = ConcurrentHashMap<Key, MultiSaplingBehavior>()

        fun byBlockId(blockId: Key): MultiSaplingBehavior? = REGISTRY[blockId]

        class Factory : BlockBehaviorFactory<MultiSaplingBehavior> {
            override fun create(block: BlockDefinition, section: ConfigSection): MultiSaplingBehavior {
                @Suppress("UNCHECKED_CAST")
                val stage = block.getProperty("stage") as? Property<Int>

                return MultiSaplingBehavior(
                    customBlock = block,
                    stageProperty = stage,
                    patterns = readPatterns(section, block.id()),
                    generators = readGenerators(section),
                    growSpeed = readDouble(section, "grow_speed", readDouble(section, "grow-speed", 0.05)),
                    boneMealSuccessChance = readDouble(section, "bone_meal_success_chance", readDouble(section, "bone-meal-success-chance", 0.45)),
                    maxStage = readInt(section, "max_stage", readInt(section, "max-stage", 1)),
                    consumeSaplings = readBoolean(section, "consume_saplings", readBoolean(section, "consume-saplings", true))
                )
            }

            private fun readPatterns(section: ConfigSection, fallbackSapling: Key): List<SaplingPattern> {
                val rawPatterns = readAny(section, "patterns") as? List<*> ?: return listOf(
                    SaplingPattern(
                        sizeX = readInt(section, "size_x", readInt(section, "size-x", 1)),
                        sizeY = readInt(section, "size_y", readInt(section, "size-y", 1)),
                        sizeZ = readInt(section, "size_z", readInt(section, "size-z", 1)),
                        minStage = readInt(section, "required_stage", readInt(section, "required-stage", 1)),
                        saplings = setOf(fallbackSapling)
                    )
                )

                return rawPatterns.mapNotNull { rawNode ->
                    val node = rawNode ?: return@mapNotNull null

                    SaplingPattern(
                        sizeX = readInt(node, "size_x", readInt(node, "size-x", 1)).coerceAtLeast(1),
                        sizeY = readInt(node, "size_y", readInt(node, "size-y", 1)).coerceAtLeast(1),
                        sizeZ = readInt(node, "size_z", readInt(node, "size-z", 1)).coerceAtLeast(1),
                        minStage = readInt(node, "required_stage", readInt(node, "required-stage", 1)),
                        saplings = readStringList(node, "saplings").map { Key.of(it) }.toSet()
                    )
                }
            }

            private fun readGenerators(section: ConfigSection): List<SaplingGenerator> {
                val rawGenerators = readAny(section, "generators") as? List<*>
                if (rawGenerators == null) {
                    val feature = readString(section, "feature", null)
                    val structure = readString(section, "structure", null)

                    return when {
                        feature != null -> listOf(SaplingGenerator("feature", feature, null, 0, 0, 0))
                        structure != null -> listOf(SaplingGenerator("structure", structure, null, 0, 0, 0))
                        else -> emptyList()
                    }
                }

                return rawGenerators.mapNotNull { rawNode ->
                    val node = rawNode ?: return@mapNotNull null

                    SaplingGenerator(
                        type = readString(node, "type", null) ?: return@mapNotNull null,
                        id = readString(node, "id", null),
                        command = readString(node, "command", null),
                        offsetX = readInt(node, "offset_x", readInt(node, "offset-x", 0)),
                        offsetY = readInt(node, "offset_y", readInt(node, "offset-y", 0)),
                        offsetZ = readInt(node, "offset_z", readInt(node, "offset-z", 0))
                    )
                }
            }

            private fun readAny(node: Any, key: String): Any? {
                if (node is Map<*, *>) return node[key]

                return runCatching {
                    node.javaClass.methods.firstOrNull { it.name == "get" && it.parameterCount == 1 }
                        ?.invoke(node, key)
                }.getOrNull()
            }

            private fun readString(node: Any, key: String, fallback: String?): String? {
                val value = invokeGetter(node, "getString", key) ?: readAny(node, key) ?: return fallback
                return value.toString()
            }

            private fun readStringList(node: Any, key: String): List<String> {
                val value = invokeGetter(node, "getStringList", key) ?: readAny(node, key) ?: return emptyList()
                return when (value) {
                    is List<*> -> value.map { it.toString() }
                    is String -> listOf(value)
                    else -> emptyList()
                }
            }

            private fun readInt(node: Any, key: String, fallback: Int): Int {
                val value = invokeGetter(node, "getInt", key, fallback) ?: readAny(node, key) ?: return fallback
                return when (value) {
                    is Number -> value.toInt()
                    is String -> value.toIntOrNull() ?: fallback
                    else -> fallback
                }
            }

            private fun readDouble(node: Any, key: String, fallback: Double): Double {
                val value = invokeGetter(node, "getDouble", key, fallback) ?: readAny(node, key) ?: return fallback
                return when (value) {
                    is Number -> value.toDouble()
                    is String -> value.toDoubleOrNull() ?: fallback
                    else -> fallback
                }
            }

            private fun readBoolean(node: Any, key: String, fallback: Boolean): Boolean {
                val value = invokeGetter(node, "getBoolean", key, fallback) ?: readAny(node, key) ?: return fallback
                return when (value) {
                    is Boolean -> value
                    is String -> value.toBooleanStrictOrNull() ?: fallback
                    else -> fallback
                }
            }

            private fun invokeGetter(node: Any, methodName: String, key: String, fallback: Any? = null): Any? {
                if (node is Map<*, *>) return null

                val methods = node.javaClass.methods.filter { it.name == methodName }
                for (method in methods) {
                    val value = runCatching {
                        when (method.parameterCount) {
                            1 -> method.invoke(node, key)
                            2 -> method.invoke(node, key, fallback)
                            else -> null
                        }
                    }.getOrNull()

                    if (value != null) return value
                }

                return null
            }
        }
    }
}