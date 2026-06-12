package dev.wuason.unearthMechanic.compatibilities.craftengine

import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field.FieldSource
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field.FieldType
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field.FireSettings
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field.IceSettings
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field.RedstoneFieldDataStore
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field.ResonatorOutputMode
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field.ResonatorSource
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field.SourceKey
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field.SourceKind
import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.redstone_field.toBukkitLocation
import dev.wuason.unearthMechanic.utils.FoliaUtils
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor
import net.momirealms.craftengine.core.block.BlockDefinition
import net.momirealms.craftengine.core.block.property.Property
import net.momirealms.craftengine.core.world.BlockPos
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

internal object RedstoneFieldManager : Listener {
    private val plugin: JavaPlugin by lazy {
        JavaPlugin.getProvidingPlugin(RedstoneFieldBehavior::class.java)
    }

    private val started = AtomicBoolean(false)
    private val maxConfiguredRadius = AtomicInteger(8)

    private val sources = ConcurrentHashMap<SourceKey, FieldSource>()
    private val resonators = ConcurrentHashMap<SourceKey, ResonatorSource>()
    private val sourceBuckets = ConcurrentHashMap<String, ConcurrentHashMap<Long, MutableSet<SourceKey>>>()
    private val resonatorBuckets = ConcurrentHashMap<String, ConcurrentHashMap<Long, MutableSet<SourceKey>>>()

    private val fieldDefinitions = ConcurrentHashMap<String, FieldBlockDefinition>()
    private val resonatorDefinitions = ConcurrentHashMap<String, ResonatorBlockDefinition>()
    private val blockIdCache = ConcurrentHashMap<Any, Set<String>>()
    private val stateIntGetterCache = ConcurrentHashMap<String, Property<Int>?>()

    private val resonatorExpiryTicks = ConcurrentHashMap<SourceKey, Long>()
    private val resonatorParticleTicks = ConcurrentHashMap<SourceKey, Long>()
    private val sourceSoundTicks = ConcurrentHashMap<SourceKey, Long>()

    fun ensureStarted(scanIntervalTicks: Long, maxRadius: Int) {
        maxConfiguredRadius.updateAndGet { old -> max(old, maxRadius.coerceAtLeast(1)) }

        if (!started.compareAndSet(false, true)) return

        RedstoneFieldDataStore.load()
        Bukkit.getPluginManager().registerEvents(this, plugin)

        FoliaUtils.runLater(40L) {
            restoreIndexedBlocks()
        }

        FoliaUtils.runTimer(
            delayTicks = scanIntervalTicks.coerceAtLeast(1L),
            periodTicks = scanIntervalTicks.coerceAtLeast(1L)
        ) {
            pulse()
        }

        FoliaUtils.runTimer(delayTicks = 100L, periodTicks = 100L) {
            RedstoneFieldDataStore.flushSaveNow()
        }
    }

    fun shutdown() {
        RedstoneFieldDataStore.close()
    }

    fun registerFieldDefinition(
        block: BlockDefinition,
        fieldType: FieldType,
        propertyName: String,
        maxRadius: Int,
        particleCount: Int,
        safeRadius: Double,
        iceSettings: IceSettings,
        fireSettings: FireSettings
    ) {
        val definition = FieldBlockDefinition(
            ids = blockDefinitionIds(block),
            fieldType = fieldType,
            propertyName = propertyName,
            maxRadius = maxRadius.coerceAtLeast(1),
            particleCount = particleCount.coerceAtLeast(0),
            safeRadius = safeRadius.coerceAtLeast(0.0),
            ice = iceSettings,
            fire = fireSettings
        )

        for (id in definition.ids) {
            fieldDefinitions[id] = definition
        }
    }

    fun registerResonatorDefinition(
        block: BlockDefinition,
        triggerTypes: Set<FieldType>,
        outputMode: ResonatorOutputMode,
        resonanceRadius: Int,
        particleCount: Int,
        safeRadius: Double,
        resonanceTicks: Long,
        iceSettings: IceSettings,
        fireSettings: FireSettings
    ) {
        val definition = ResonatorBlockDefinition(
            ids = blockDefinitionIds(block),
            triggerTypes = triggerTypes,
            outputMode = outputMode,
            radius = resonanceRadius.coerceAtLeast(1),
            particleCount = particleCount.coerceAtLeast(0),
            safeRadius = safeRadius.coerceAtLeast(0.0),
            resonanceTicks = resonanceTicks.coerceAtLeast(1L),
            ice = iceSettings,
            fire = fireSettings
        )

        for (id in definition.ids) {
            resonatorDefinitions[id] = definition
        }
    }

    fun rememberPlacedField(worldName: String, pos: BlockPos) {
        RedstoneFieldDataStore.addField(SourceKey(worldName, pos.x(), pos.y(), pos.z(), SourceKind.REDSTONE))
    }

    fun forgetPlacedField(worldName: String, pos: BlockPos) {
        RedstoneFieldDataStore.removeField(SourceKey(worldName, pos.x(), pos.y(), pos.z(), SourceKind.REDSTONE))
    }

    fun rememberPlacedResonator(worldName: String, pos: BlockPos) {
        RedstoneFieldDataStore.addResonator(SourceKey(worldName, pos.x(), pos.y(), pos.z(), SourceKind.RESONATOR))
    }

    fun forgetPlacedResonator(worldName: String, pos: BlockPos) {
        RedstoneFieldDataStore.removeResonator(SourceKey(worldName, pos.x(), pos.y(), pos.z(), SourceKind.RESONATOR))
    }

    fun activate(source: FieldSource) {
        if (sources[source.key] == source) return

        sources[source.key] = source
        addToBucket(sourceBuckets, source.key)
    }

    fun deactivate(key: SourceKey) {
        sources.remove(key)
        sourceSoundTicks.remove(key)
        removeFromBucket(sourceBuckets, key)
    }

    fun registerResonator(source: ResonatorSource) {
        if (resonators[source.key] == source) return

        resonators[source.key] = source
        addToBucket(resonatorBuckets, source.key)
    }

    fun unregisterResonator(key: SourceKey) {
        resonators.remove(key)
        resonatorExpiryTicks.remove(key)
        resonatorParticleTicks.remove(key)
        removeFromBucket(resonatorBuckets, key)
    }

    private fun pulse() {
        expireResonators()

        val slownessType = potionType("slowness")
        val slowFallingType = potionType("slow_falling")

        val sourceSnapshot = sources.values.toList()
        val activeSourcesByWorld = sourceSnapshot
            .groupingBy { it.key.world }
            .eachCount()

        for (source in sourceSnapshot) {
            val world = Bukkit.getWorld(source.key.world) ?: continue
            val center = Location(world, source.key.x + 0.5, source.key.y + 0.5, source.key.z + 0.5)
            val activeSourcesInWorld = activeSourcesByWorld[source.key.world] ?: 1
            val particleCount = scaledParticleCount(source.particleCount, activeSourcesInWorld)

            FoliaUtils.runAtLocation(center) {
                if (sources[source.key] != source) return@runAtLocation

                when (source.type) {
                    FieldType.ICE -> pulseIce(source, center, slownessType, slowFallingType, particleCount)
                    FieldType.FIRE -> pulseFire(source, center, particleCount)
                }

                if (source.key.kind == SourceKind.REDSTONE) {
                    playSourceSound(source, center)
                    triggerResonatorsInside(source, center, activeSourcesInWorld)
                }
            }
        }
    }

    private fun restoreIndexedBlocks() {
        if (fieldDefinitions.isEmpty() && resonatorDefinitions.isEmpty()) return

        for (key in RedstoneFieldDataStore.fields()) {
            restoreFieldAt(key)
        }

        for (key in RedstoneFieldDataStore.resonators()) {
            restoreResonatorAt(key)
        }
    }

    private fun restoreFieldAt(key: SourceKey) {
        val world = Bukkit.getWorld(key.world) ?: return
        val location = Location(world, key.x.toDouble(), key.y.toDouble(), key.z.toDouble())

        FoliaUtils.runAtLocation(location) {
            val adaptedWorld = BukkitAdaptor.adapt(world)
            val pos = BlockPos(key.x, key.y, key.z)
            val state = adaptedWorld.getBlock(pos).customBlockState()

            if (state == null) {
                RedstoneFieldDataStore.removeField(key)
                deactivate(key)
                return@runAtLocation
            }

            val definition = definitionFor(state.owner().value(), fieldDefinitions)
            if (definition == null) {
                RedstoneFieldDataStore.removeField(key)
                deactivate(key)
                return@runAtLocation
            }

            restoreFieldSource(adaptedWorld, pos, state, definition)
        }
    }

    private fun restoreResonatorAt(key: SourceKey) {
        val world = Bukkit.getWorld(key.world) ?: return
        val location = Location(world, key.x.toDouble(), key.y.toDouble(), key.z.toDouble())

        FoliaUtils.runAtLocation(location) {
            val adaptedWorld = BukkitAdaptor.adapt(world)
            val pos = BlockPos(key.x, key.y, key.z)
            val state = adaptedWorld.getBlock(pos).customBlockState()

            if (state == null) {
                RedstoneFieldDataStore.removeResonator(key)
                unregisterResonator(key)
                return@runAtLocation
            }

            val definition = definitionFor(state.owner().value(), resonatorDefinitions)
            if (definition == null) {
                RedstoneFieldDataStore.removeResonator(key)
                unregisterResonator(key)
                return@runAtLocation
            }

            registerResonator(
                ResonatorSource(
                    key = key,
                    triggerTypes = definition.triggerTypes,
                    outputMode = definition.outputMode,
                    radius = definition.radius,
                    particleCount = definition.particleCount,
                    safeRadius = definition.safeRadius,
                    resonanceTicks = definition.resonanceTicks,
                    ice = definition.ice,
                    fire = definition.fire
                )
            )
        }
    }

    private fun restoreFieldSource(
        world: net.momirealms.craftengine.core.world.World,
        pos: BlockPos,
        state: Any,
        definition: FieldBlockDefinition
    ) {
        val location = toBukkitLocation(world, pos) ?: return
        val redstonePower = location.block.blockPower.coerceIn(0, 15)
        val savedPower = statePropertyValue(state, definition.propertyName) ?: 0
        val power = max(redstonePower, savedPower).coerceIn(0, 15)
        val key = SourceKey(world.name(), pos.x(), pos.y(), pos.z(), SourceKind.REDSTONE)

        if (power <= 0) {
            deactivate(key)
            return
        }

        activate(
            FieldSource(
                key = key,
                type = definition.fieldType,
                radius = scaledRadius(power, definition.maxRadius),
                particleCount = definition.particleCount,
                safeRadius = definition.safeRadius,
                ice = definition.ice,
                fire = definition.fire
            )
        )
    }

    private fun statePropertyValue(state: Any, propertyName: String): Int? {
        val property = stateIntGetterCache.computeIfAbsent(propertyName) {
            runCatching {
                val owner = state.javaClass.getMethod("owner").invoke(state)
                val ownerValue = owner.javaClass.getMethod("value").invoke(owner)

                @Suppress("UNCHECKED_CAST")
                ownerValue.javaClass.getMethod("getProperty", String::class.java)
                    .invoke(ownerValue, propertyName) as? Property<Int>
            }.getOrNull()
        } ?: return null

        return runCatching {
            state.javaClass.getMethod("get", Property::class.java).invoke(state, property) as? Int
        }.getOrNull()
    }

    private fun playSourceSound(source: FieldSource, center: Location) {
        val world = center.world ?: return
        val now = world.fullTime

        if (now < (sourceSoundTicks[source.key] ?: 0L)) return
        if (!hasNearbyViewer(center, 24.0)) return

        sourceSoundTicks[source.key] = now + 20L

        val sound = when (source.type) {
            FieldType.ICE -> Sound.BLOCK_AMETHYST_BLOCK_CHIME
            FieldType.FIRE -> Sound.BLOCK_RESPAWN_ANCHOR_CHARGE
        }

        world.playSound(center, sound, 0.45f, 0.65f)
    }

    private fun triggerResonatorsInside(source: FieldSource, center: Location, activeSourcesInWorld: Int) {
        val world = center.world ?: return
        val radius = source.radius.toDouble()
        val worldBuckets = resonatorBuckets[source.key.world] ?: return
        val chunkRadius = ceil(radius / 16.0).toInt() + 1
        val baseChunkX = source.key.x shr 4
        val baseChunkZ = source.key.z shr 4

        for (cx in baseChunkX - chunkRadius..baseChunkX + chunkRadius) {
            for (cz in baseChunkZ - chunkRadius..baseChunkZ + chunkRadius) {
                val keys = worldBuckets[chunkKey(cx, cz)] ?: continue

                for (key in keys) {
                    val resonator = resonators[key] ?: continue
                    if (source.type !in resonator.triggerTypes) continue

                    val location = Location(world, key.x + 0.5, key.y + 0.5, key.z + 0.5)
                    if (!isInsideCube(location, center, radius)) continue

                    activateResonator(resonator, location, source.type, activeSourcesInWorld)
                }
            }
        }
    }

    private fun activateResonator(
        resonator: ResonatorSource,
        location: Location,
        parentType: FieldType,
        activeSourcesInWorld: Int
    ) {
        val now = location.world?.fullTime ?: 0L
        val outputType = when (resonator.outputMode) {
            ResonatorOutputMode.INHERIT -> parentType
            ResonatorOutputMode.ICE -> FieldType.ICE
            ResonatorOutputMode.FIRE -> FieldType.FIRE
        }

        resonatorExpiryTicks[resonator.key] = now + resonator.resonanceTicks

        activate(
            FieldSource(
                key = resonator.key,
                type = outputType,
                radius = resonator.radius,
                particleCount = resonator.particleCount,
                safeRadius = resonator.safeRadius,
                ice = resonator.ice,
                fire = resonator.fire
            )
        )

        if (now >= (resonatorParticleTicks[resonator.key] ?: 0L)) {
            resonatorParticleTicks[resonator.key] = now + 10L
            spawnResonatorParticles(
                location,
                outputType,
                scaledParticleCount(resonator.particleCount, activeSourcesInWorld)
            )
        }
    }

    private fun expireResonators() {
        for ((key, expiresAt) in resonatorExpiryTicks.entries) {
            val world = Bukkit.getWorld(key.world) ?: continue

            if (world.fullTime >= expiresAt) {
                resonatorExpiryTicks.remove(key)
                resonatorParticleTicks.remove(key)
                deactivate(key)
            }
        }
    }

    private fun pulseIce(
        source: FieldSource,
        center: Location,
        slownessType: PotionEffectType?,
        slowFallingType: PotionEffectType?,
        particleCount: Int
    ) {
        val world = center.world ?: return
        val radius = source.radius.toDouble()

        world.getNearbyEntities(center, radius, radius, radius)
            .asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { isInsideCube(it.location, center, radius) }
            .forEach { entity ->
                FoliaUtils.runAtEntity(entity) {
                    if (!entity.isValid) return@runAtEntity
                    if (!isInsideCube(entity.location, center, radius)) return@runAtEntity

                    val leatherPieces = if (entity is Player) leatherArmorPieces(entity) else 0
                    val slownessAmplifier = reducedIceAmplifier(source.ice.slownessAmplifier, source.ice, leatherPieces)
                    val slowFallingAmplifier = reducedIceAmplifier(source.ice.slowFallingAmplifier, source.ice, leatherPieces)

                    if (slownessAmplifier >= 0) {
                        slownessType?.let {
                            entity.addPotionEffect(PotionEffect(it, 40, slownessAmplifier, true, false, true))
                        }
                    }

                    if (slowFallingAmplifier >= 0) {
                        slowFallingType?.let {
                            entity.addPotionEffect(PotionEffect(it, 40, slowFallingAmplifier, true, false, true))
                        }
                    }

                    if (entity is Player && entity.location.distanceSquared(center) <= source.safeRadius * source.safeRadius) {
                        return@runAtEntity
                    }

                    val freezeTicks = reducedFreezeTicks(entity.maxFreezeTicks, source.ice, leatherPieces)
                    if (freezeTicks > 0) {
                        entity.freezeTicks = max(entity.freezeTicks, freezeTicks)
                    }
                }
            }

        spawnIceParticles(center, radius, particleCount)
    }

    private fun pulseFire(source: FieldSource, center: Location, particleCount: Int) {
        val world = center.world ?: return
        val radius = source.radius.toDouble()

        world.getNearbyEntities(center, radius, radius, radius)
            .asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { isInsideCube(it.location, center, radius) }
            .forEach { entity ->
                FoliaUtils.runAtEntity(entity) {
                    if (!entity.isValid) return@runAtEntity
                    if (!isInsideCube(entity.location, center, radius)) return@runAtEntity

                    val isPlayerNearCore = entity is Player &&
                            entity.location.distanceSquared(center) <= source.safeRadius * source.safeRadius

                    if (!isPlayerNearCore) {
                        entity.fireTicks = max(entity.fireTicks, source.fire.fireTicks)
                    }
                }
            }

        if (source.fire.igniteBlocks) {
            tryIgniteBlocks(source, center)
        }

        spawnFireParticles(center, radius, particleCount)
    }

    private fun tryIgniteBlocks(source: FieldSource, center: Location) {
        val world = center.world ?: return
        val random = ThreadLocalRandom.current()
        val radius = source.radius

        repeat(source.fire.blockIgniteAttempts.coerceAtLeast(0)) {
            val x = center.blockX + random.nextInt(-radius, radius + 1)
            val y = center.blockY + random.nextInt(-radius, radius + 1)
            val z = center.blockZ + random.nextInt(-radius, radius + 1)
            val location = Location(world, x.toDouble(), y.toDouble(), z.toDouble())

            if (location.distanceSquared(center) <= source.safeRadius * source.safeRadius) return@repeat

            FoliaUtils.runAtLocation(location) {
                val block = world.getBlockAt(x, y, z)
                val below = world.getBlockAt(x, y - 1, z)

                if (block.type.isAir && below.type.isSolid) {
                    block.type = Material.FIRE
                }
            }
        }
    }

    private fun spawnIceParticles(center: Location, radius: Double, count: Int) {
        if (count <= 0 || !hasNearbyViewer(center, radius + 24.0)) return

        val world = center.world ?: return
        val random = ThreadLocalRandom.current()
        val half = radius
        val edgePoints = (radius * count).toInt().coerceIn(16, 90)
        val innerPoints = (radius * radius * count / 2.0).toInt().coerceIn(24, 180)

        repeat(edgePoints) { index ->
            val t = -half + (2.0 * half * index / edgePoints)
            spawnParticle(world, Particle.SOUL_FIRE_FLAME, center.x + t, center.y - half, center.z - half)
            spawnParticle(world, Particle.SOUL_FIRE_FLAME, center.x + t, center.y - half, center.z + half)
            spawnParticle(world, Particle.SOUL_FIRE_FLAME, center.x - half, center.y - half, center.z + t)
            spawnParticle(world, Particle.SOUL_FIRE_FLAME, center.x + half, center.y - half, center.z + t)
        }

        repeat(innerPoints) {
            world.spawnParticle(
                Particle.SNOWFLAKE,
                center.x + random.nextDouble(-half, half),
                center.y + random.nextDouble(-half, half),
                center.z + random.nextDouble(-half, half),
                1,
                0.12,
                0.12,
                0.12,
                0.0
            )
        }
    }

    private fun spawnFireParticles(center: Location, radius: Double, count: Int) {
        if (count <= 0 || !hasNearbyViewer(center, radius + 24.0)) return

        val world = center.world ?: return
        val random = ThreadLocalRandom.current()
        val half = radius
        val points = (radius * count).toInt().coerceIn(24, 160)

        repeat(points) {
            world.spawnParticle(
                Particle.FLAME,
                center.x + random.nextDouble(-half, half),
                center.y + random.nextDouble(-half, half),
                center.z + random.nextDouble(-half, half),
                1,
                0.08,
                0.08,
                0.08,
                0.0
            )
        }
    }

    private fun spawnResonatorParticles(center: Location, type: FieldType, count: Int) {
        if (count <= 0 || !hasNearbyViewer(center, 24.0)) return

        val world = center.world ?: return
        val random = ThreadLocalRandom.current()
        val particle = if (type == FieldType.ICE) Particle.SNOWFLAKE else Particle.FLAME
        val sound = if (type == FieldType.ICE) Sound.BLOCK_AMETHYST_BLOCK_CHIME else Sound.BLOCK_RESPAWN_ANCHOR_CHARGE

        repeat(count.coerceIn(8, 48)) {
            world.spawnParticle(
                Particle.SCULK_SOUL,
                center.x + random.nextDouble(-0.65, 0.65),
                center.y + random.nextDouble(-0.15, 0.95),
                center.z + random.nextDouble(-0.65, 0.65),
                1,
                0.03,
                0.06,
                0.03,
                0.0
            )

            world.spawnParticle(
                particle,
                center.x + random.nextDouble(-0.45, 0.45),
                center.y + random.nextDouble(0.05, 0.95),
                center.z + random.nextDouble(-0.45, 0.45),
                1,
                0.03,
                0.05,
                0.03,
                0.0
            )
        }

        world.playSound(center, sound, 0.65f, 0.55f)
    }

    private fun fieldAt(location: Location): FieldSource? {
        val worldName = location.world?.name ?: return null
        val worldBuckets = sourceBuckets[worldName] ?: return null
        val chunkRadius = ceil(maxConfiguredRadius.get() / 16.0).toInt() + 1
        val baseChunkX = location.blockX shr 4
        val baseChunkZ = location.blockZ shr 4

        for (cx in baseChunkX - chunkRadius..baseChunkX + chunkRadius) {
            for (cz in baseChunkZ - chunkRadius..baseChunkZ + chunkRadius) {
                val keys = worldBuckets[chunkKey(cx, cz)] ?: continue

                for (key in keys) {
                    val source = sources[key] ?: continue
                    val center = Location(location.world, key.x + 0.5, key.y + 0.5, key.z + 0.5)

                    if (isInsideCube(location, center, source.radius.toDouble())) {
                        return source
                    }
                }
            }
        }

        return null
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockGrow(event: BlockGrowEvent) {
        if (fieldAt(event.block.location)?.type == FieldType.ICE) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockSpread(event: BlockSpreadEvent) {
        if (fieldAt(event.block.location)?.type == FieldType.ICE) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockForm(event: BlockFormEvent) {
        if (fieldAt(event.block.location)?.type == FieldType.ICE) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockFade(event: BlockFadeEvent) {
        if (fieldAt(event.block.location)?.type == FieldType.ICE) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onStructureGrow(event: StructureGrowEvent) {
        if (fieldAt(event.location)?.type == FieldType.ICE) event.isCancelled = true
    }

    private fun <T> definitionFor(definition: Any, definitions: Map<String, T>): T? {
        return blockDefinitionIds(definition).firstNotNullOfOrNull { definitions[it] }
    }

    private fun blockDefinitionIds(definition: Any): Set<String> {
        return blockIdCache.computeIfAbsent(definition) {
            val ids = linkedSetOf<String>()

            for (methodName in listOf("id", "key", "getId", "getKey")) {
                val value = runCatching {
                    definition.javaClass.methods
                        .firstOrNull { it.name == methodName && it.parameterCount == 0 }
                        ?.invoke(definition)
                }.getOrNull()

                if (value != null) ids.add(value.toString())
            }

            ids.add(definition.toString())
            ids
        }
    }

    private fun addToBucket(
        buckets: ConcurrentHashMap<String, ConcurrentHashMap<Long, MutableSet<SourceKey>>>,
        key: SourceKey
    ) {
        buckets.computeIfAbsent(key.world) { ConcurrentHashMap() }
            .computeIfAbsent(chunkKey(key.x shr 4, key.z shr 4)) { ConcurrentHashMap.newKeySet() }
            .add(key)
    }

    private fun removeFromBucket(
        buckets: ConcurrentHashMap<String, ConcurrentHashMap<Long, MutableSet<SourceKey>>>,
        key: SourceKey
    ) {
        buckets[key.world]?.get(chunkKey(key.x shr 4, key.z shr 4))?.remove(key)
    }

    private fun hasNearbyViewer(center: Location, radius: Double): Boolean {
        val world = center.world ?: return false
        val radiusSquared = radius * radius

        return world.players.any { player ->
            player.isValid && player.location.distanceSquared(center) <= radiusSquared
        }
    }

    private fun leatherArmorPieces(player: Player): Int {
        val equipment = player.equipment ?: return 0
        var pieces = 0

        if (equipment.helmet?.type == Material.LEATHER_HELMET) pieces++
        if (equipment.chestplate?.type == Material.LEATHER_CHESTPLATE) pieces++
        if (equipment.leggings?.type == Material.LEATHER_LEGGINGS) pieces++
        if (equipment.boots?.type == Material.LEATHER_BOOTS) pieces++

        return pieces
    }

    private fun reducedIceAmplifier(baseAmplifier: Int, settings: IceSettings, leatherPieces: Int): Int {
        return baseAmplifier - leatherPieces * settings.leatherArmorEffectReduction.coerceAtLeast(0)
    }

    private fun reducedFreezeTicks(maxFreezeTicks: Int, settings: IceSettings, leatherPieces: Int): Int {
        if (settings.fullLeatherPreventsFreeze && leatherPieces >= 4) return 0
        return (maxFreezeTicks * ((4 - leatherPieces.coerceIn(0, 4)) / 4.0)).toInt()
    }

    private fun scaledParticleCount(baseCount: Int, activeSourcesInWorld: Int): Int {
        if (baseCount <= 0) return 0

        val multiplier = when {
            activeSourcesInWorld <= 4 -> 1.0
            activeSourcesInWorld <= 8 -> 0.75
            activeSourcesInWorld <= 16 -> 0.5
            activeSourcesInWorld <= 32 -> 0.35
            else -> 0.2
        }

        return (baseCount * multiplier).toInt().coerceAtLeast(1)
    }

    private fun scaledRadius(power: Int, maxRadius: Int): Int {
        if (power <= 0) return 0
        return ceil(power.coerceIn(1, 15) * maxRadius.coerceAtLeast(1) / 15.0).toInt()
    }

    private fun isInsideCube(location: Location, center: Location, radius: Double): Boolean {
        return location.world == center.world &&
                abs(location.x - center.x) <= radius &&
                abs(location.y - center.y) <= radius &&
                abs(location.z - center.z) <= radius
    }

    private fun spawnParticle(world: org.bukkit.World, particle: Particle, x: Double, y: Double, z: Double) {
        world.spawnParticle(particle, x, y, z, 1, 0.015, 0.02, 0.015, 0.0)
    }

    private fun potionType(name: String): PotionEffectType? {
        return PotionEffectType.getByKey(NamespacedKey.minecraft(name.lowercase()))
            ?: PotionEffectType.getByName(name.uppercase())
    }

    private fun chunkKey(chunkX: Int, chunkZ: Int): Long {
        return (chunkX.toLong() shl 32) xor (chunkZ.toLong() and 0xffffffffL)
    }

    private data class FieldBlockDefinition(
        val ids: Set<String>,
        val fieldType: FieldType,
        val propertyName: String,
        val maxRadius: Int,
        val particleCount: Int,
        val safeRadius: Double,
        val ice: IceSettings,
        val fire: FireSettings
    )

    private data class ResonatorBlockDefinition(
        val ids: Set<String>,
        val triggerTypes: Set<FieldType>,
        val outputMode: ResonatorOutputMode,
        val radius: Int,
        val particleCount: Int,
        val safeRadius: Double,
        val resonanceTicks: Long,
        val ice: IceSettings,
        val fire: FireSettings
    )
}