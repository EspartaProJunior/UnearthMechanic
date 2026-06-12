package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior

import dev.wuason.unearthMechanic.utils.FoliaUtils
import dev.wuason.unearthMechanic.compatibilities.craftengine.types.BrittleIceStage
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior
import net.momirealms.craftengine.core.block.BlockDefinition
import net.momirealms.craftengine.core.block.ImmutableBlockState
import net.momirealms.craftengine.core.block.UpdateFlags
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory
import net.momirealms.craftengine.core.block.property.Property
import net.momirealms.craftengine.core.plugin.config.ConfigSection
import net.momirealms.craftengine.core.registry.Holder
import net.momirealms.craftengine.core.world.BlockPos
import net.momirealms.craftengine.core.world.context.BlockPlaceContext
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BrittleIceBehavior(
    customBlock: BlockDefinition,
    private val stageProperty: Property<BrittleIceStage>,
    private val breakDelayTicks: Long,
    private val crackedDelayTicks: Long,
    private val middleDelayTicks: Long,
    private val brokenDelayTicks: Long,
    private val instantBreakFallDistance: Float,
    private val instantBreakRadius: Int,
    private val crackSound: Sound,
    private val breakSound: Sound,
    private val particle: Particle,
    private val stageSoundVolume: Float
) : BukkitBlockBehavior(customBlock) {

    private val customBlockId = customBlock.id().asString()

    companion object {
        private val breaking = ConcurrentHashMap<String, UUID>()

        val FACTORY = Factory()

        class Factory : BlockBehaviorFactory<BrittleIceBehavior> {
            override fun create(block: BlockDefinition, section: ConfigSection): BrittleIceBehavior {
                val prop = block.getProperty("stage")
                    ?: throw IllegalArgumentException("Missing 'stage' property")

                @Suppress("UNCHECKED_CAST")
                val stageProperty = prop as Property<BrittleIceStage>

                val breakDelay = section.getLong("break-delay-ticks", 60L)

                return BrittleIceBehavior(
                    customBlock = block,
                    stageProperty = stageProperty,
                    breakDelayTicks = breakDelay,
                    crackedDelayTicks = section.getLong("cracked-delay-ticks", breakDelay / 3),
                    middleDelayTicks = section.getLong("middle-delay-ticks", (breakDelay * 2) / 3),
                    brokenDelayTicks = section.getLong("broken-delay-ticks", (breakDelay - 8L).coerceAtLeast(1L)),
                    instantBreakFallDistance = section.getDouble("instant-break-fall-distance", 3.0).toFloat(),
                    instantBreakRadius = section.getLong("instant-break-radius", 1L).toInt().coerceIn(0, 4),
                    crackSound = sound(section.getString("crack-sound", "minecraft:block.glass.hit")),
                    breakSound = sound(section.getString("break-sound", "minecraft:block.glass.break")),
                    particle = particle(section.getString("particle", "minecraft:snowflake")),
                    stageSoundVolume = section.getDouble("stage-sound-volume", 0.18).toFloat()
                )
            }

            private fun sound(raw: String): Sound {
                val key = raw.substringAfter(':').uppercase().replace('.', '_')
                return runCatching { Sound.valueOf(key) }.getOrDefault(Sound.BLOCK_GLASS_BREAK)
            }

            private fun particle(raw: String): Particle {
                val key = raw.substringAfter(':').uppercase().replace('.', '_')
                return runCatching { Particle.valueOf(key) }.getOrDefault(Particle.SNOWFLAKE)
            }
        }
    }

    override fun updateStateForPlacement(context: BlockPlaceContext, state: ImmutableBlockState): ImmutableBlockState {
        return state.with(stageProperty, BrittleIceStage.normal)
    }

    override fun stepOn(thisBlock: Any, args: Array<Any>) {
        val level = args.getOrNull(0) ?: return super.stepOn(thisBlock, args)
        val pos = args.getOrNull(1) ?: return super.stepOn(thisBlock, args)
        val entity = args.getOrNull(3)

        if (!shouldTrigger(entity)) {
            super.stepOn(thisBlock, args)
            return
        }

        val location = toBukkitLocation(level, pos) ?: return super.stepOn(thisBlock, args)
        startBreaking(location)

        super.stepOn(thisBlock, args)
    }

    override fun fallOn(thisBlock: Any, args: Array<Any>) {
        val level = args.getOrNull(0) ?: return super.fallOn(thisBlock, args)
        val pos = args.getOrNull(2) ?: return super.fallOn(thisBlock, args)
        val entity = args.getOrNull(3)
        val fallDistance = (args.getOrNull(4) as? Number)?.toFloat() ?: 0f

        if (isPlayer(entity) && fallDistance >= instantBreakFallDistance) {
            val location = toBukkitLocation(level, pos) ?: return super.fallOn(thisBlock, args)
            breakArea(location)
        }

        super.fallOn(thisBlock, args)
    }

    private fun shouldTrigger(nmsEntity: Any?): Boolean {
        val bukkitEntity = bukkitEntity(nmsEntity)
        return bukkitEntity is Player || bukkitEntity is LivingEntity
    }

    private fun isPlayer(nmsEntity: Any?): Boolean {
        return bukkitEntity(nmsEntity) is Player
    }

    private fun bukkitEntity(nmsEntity: Any?): Any? {
        if (nmsEntity == null) return null
        return runCatching {
            nmsEntity.javaClass.getMethod("getBukkitEntity").invoke(nmsEntity)
        }.getOrNull()
    }

    private fun startBreaking(location: Location) {
        val key = location.key()
        if (breaking.putIfAbsent(key, UUID.randomUUID()) != null) return

        scheduleStage(location, key, BrittleIceStage.cracked, crackedDelayTicks)
        scheduleStage(location, key, BrittleIceStage.middle, middleDelayTicks)
        scheduleStage(location, key, BrittleIceStage.broken, brokenDelayTicks)

        FoliaUtils.runLater(breakDelayTicks) {
            FoliaUtils.runAtLocation(location) {
                try {
                    breakNow(location)
                } finally {
                    breaking.remove(key)
                }
            }
        }
    }

    private fun breakNow(location: Location) {
        val block = location.block
        if (block.type == Material.AIR || !isSameCustomBlock(location)) return

        breaking.remove(location.key())
        setStage(location, BrittleIceStage.broken)

        val center = location.clone().add(0.5, 0.5, 0.5)
        location.world?.playSound(center, breakSound, 1.0f, 1.15f)
        block.setType(Material.AIR, false)
        location.world?.spawnParticle(particle, center, 32, 0.35, 0.22, 0.35, 0.02)
    }

    private fun breakArea(center: Location) {
        val world = center.world ?: return
        val radius = instantBreakRadius

        for (xOffset in -radius..radius) {
            for (zOffset in -radius..radius) {
                val location = Location(
                    world,
                    (center.blockX + xOffset).toDouble(),
                    center.blockY.toDouble(),
                    (center.blockZ + zOffset).toDouble()
                )

                FoliaUtils.runAtLocation(location) {
                    breakNow(location)
                }
            }
        }
    }

    private fun scheduleStage(location: Location, key: String, stage: BrittleIceStage, delayTicks: Long) {
        FoliaUtils.runLater(delayTicks.coerceAtLeast(1L)) {
            FoliaUtils.runAtLocation(location) {
                if (!breaking.containsKey(key) || location.block.type == Material.AIR || !isSameCustomBlock(location)) {
                    breaking.remove(key)
                    return@runAtLocation
                }

                if (!setStage(location, stage)) return@runAtLocation

                val center = location.clone().add(0.5, 0.85, 0.5)
                val pitch = when (stage) {
                    BrittleIceStage.cracked -> 0.8f
                    BrittleIceStage.middle -> 1.0f
                    BrittleIceStage.broken -> 1.2f
                    BrittleIceStage.normal -> 0.8f
                }

                location.world?.playSound(center, crackSound, stageSoundVolume, pitch)
                location.world?.spawnParticle(particle, center, 10, 0.32, 0.05, 0.32, 0.0)
            }
        }
    }

    private fun toBukkitLocation(nmsWorld: Any, nmsPos: Any): Location? {
        val world = findBukkitWorld(nmsWorld) ?: return null
        val x = intMethod(nmsPos, "getX") ?: return null
        val y = intMethod(nmsPos, "getY") ?: return null
        val z = intMethod(nmsPos, "getZ") ?: return null
        return Location(world, x.toDouble(), y.toDouble(), z.toDouble())
    }

    private fun findBukkitWorld(nmsWorld: Any): World? {
        return Bukkit.getWorlds().firstOrNull { world ->
            runCatching {
                val handle = world.javaClass.getMethod("getHandle").invoke(world)
                handle == nmsWorld
            }.getOrDefault(false)
        }
    }

    private fun intMethod(target: Any, method: String): Int? {
        return runCatching { target.javaClass.getMethod(method).invoke(target) as Int }.getOrNull()
    }

    private fun isSameCustomBlock(location: Location): Boolean {
        return getCustomState(location)?.let { ceStateId(it) == customBlockId } == true
    }

    private fun setStage(location: Location, stage: BrittleIceStage): Boolean {
        val world = location.world ?: return false
        val state = getCustomState(location) ?: return false
        if (ceStateId(state) != customBlockId) return false

        val newState = runCatching { state.with(stageProperty, stage) }.getOrNull() ?: return false

        BukkitAdaptor.adapt(world).setBlockState(
            location.blockX,
            location.blockY,
            location.blockZ,
            newState,
            UpdateFlags.UPDATE_ALL
        )
        return true
    }

    private fun getCustomState(location: Location): ImmutableBlockState? {
        val world = location.world ?: return null
        return BukkitAdaptor.adapt(world)
            .getBlock(BlockPos(location.blockX, location.blockY, location.blockZ))
            .customBlockState()
    }

    private fun ceStateId(state: ImmutableBlockState?): String? {
        if (state == null) return null
        val ref = state.owner() as? Holder.Reference<BlockDefinition> ?: return null
        return runCatching { ref.key().location().asString() }.getOrNull()
    }

    private fun Location.key(): String {
        return "${world?.uid}:${blockX},${blockY},${blockZ}"
    }
}