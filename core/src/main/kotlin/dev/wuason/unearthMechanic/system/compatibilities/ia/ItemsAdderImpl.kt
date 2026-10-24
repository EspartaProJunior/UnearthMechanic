package dev.wuason.unearthMechanic.system.compatibilities.ia

import dev.lone.itemsadder.api.CustomBlock
import dev.lone.itemsadder.api.CustomFurniture
import dev.lone.itemsadder.api.CustomStack
import dev.lone.itemsadder.api.Events.CustomBlockBreakEvent
import dev.lone.itemsadder.api.Events.CustomBlockInteractEvent
import dev.lone.itemsadder.api.Events.FurnitureBreakEvent
import dev.lone.itemsadder.api.Events.FurnitureInteractEvent
import dev.lone.itemsadder.api.Events.FurniturePlaceEvent
import dev.lone.itemsadder.api.Events.FurniturePlaceSuccessEvent
import dev.wuason.adapter.AdapterComp
import dev.wuason.adapter.AdapterData
import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.UnearthMechanicPlugin
import dev.wuason.unearthMechanic.config.*
import dev.wuason.unearthMechanic.system.ILiveTool
import dev.wuason.unearthMechanic.system.StageData
import dev.wuason.unearthMechanic.system.StageManager
import dev.wuason.unearthMechanic.system.compatibilities.ClientFurnitureInteraction
import dev.wuason.unearthMechanic.system.compatibilities.FurnitureTransitionLifecycle
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility
import dev.wuason.unearthMechanic.utils.DebugSettings
import dev.wuason.unearthMechanic.utils.FoliaUtils
import dev.wuason.unearthMechanic.utils.Utils
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.Collections
import java.util.UUID
import kotlin.collections.set

class ItemsAdderImpl(
    pluginName: String,
    private val core: UnearthMechanicPlugin,
    private val stageManager: StageManager,
    adapterComp: AdapterComp
) : ICompatibility(
    pluginName,
    adapterComp
), ClientFurnitureInteraction, FurnitureTransitionLifecycle {

    private val furnitureTransitions = FurnitureTransitionGate()
    fun installLoadedGeometry(plugin: org.bukkit.plugin.Plugin) {
        LoadedFurnitureGeometryListener(plugin) { entity, source -> rehydrateLoadedFurniture(entity, source) }.start()
    }
    fun rehydrateLoadedFurniture(entity: ItemFrame, source: String): Boolean = synchronized(furnitureTransitions) {
        // Acquisition uses this same monitor. Never enter an active manual transition.
        if (furnitureTransitions.current(entity.uniqueId) != null) return@synchronized false
        val location = entity.location
        val base = org.bukkit.Location(entity.world, location.blockX.toDouble(), location.blockY.toDouble(), location.blockZ.toDouble())
        if (stageManager.activeSequences.contains(base) || isRemoving(base)) return@synchronized false
        LoadedFurnitureGeometry.repair(entity, source)
    }

    private fun releaseFurnitureTransition(lease: FurnitureTransitionGate.Lease, reason: String) {
        val released = furnitureTransitions.release(lease)
        ItemsAdderHitboxCompat.released(released, lease)
        if (released) diagnostic {
            "TRANSITION_LOCK_RELEASED uuid=${lease.uuid} heldTicks=${Bukkit.getCurrentTick() - lease.startedTick} reason=$reason"
        }
    }

    override fun onFurnitureDispatchFinished(event: Event, hasDelay: Boolean) {
        val lease = furnitureTransitions.forEvent(event) ?: return
        if (lease.phase != FurnitureTransitionGate.Phase.DISPATCH) return
        if (hasDelay) furnitureTransitions.phase(lease, FurnitureTransitionGate.Phase.DELAY)
        else releaseFurnitureTransition(lease, "not_applicable_or_completed")
    }

    override fun onFurnitureDelayFinished(event: Event) {
        val lease = furnitureTransitions.recordForEvent(event) ?: return
        if (lease.stale) {
            furnitureTransitions.discardRecord(lease)
            return
        }
        if (lease.phase == FurnitureTransitionGate.Phase.DELAY || lease.phase == FurnitureTransitionGate.Phase.DISPATCH)
            releaseFurnitureTransition(lease, "delay_finished_without_application")
    }

    override fun onFurnitureDelayStarted(event: Event, maximumTicks: Long) {
        val lease = furnitureTransitions.forEvent(event) ?: return
        furnitureTransitions.phase(lease, FurnitureTransitionGate.Phase.DELAY)
        // Configured delay duration, plus the next tick boundary; not a player cooldown.
        try {
            FoliaUtils.runLater(maximumTicks + 1L) {
                try {
                    FoliaUtils.runAtLocation(lease.base) {
                        if (furnitureTransitions.owns(lease) && lease.phase == FurnitureTransitionGate.Phase.DELAY) {
                            try { stageManager.getDelays().remove(lease.base)?.cancel() }
                            finally { releaseFurnitureTransition(lease, "delay_deadline_or_external_cancellation") }
                        } else if (lease.stale) furnitureTransitions.discardRecord(lease)
                    }
                } catch (failure: Throwable) { releaseFurnitureTransition(lease, "delay_region_schedule_failed"); throw failure }
            }
        } catch (failure: Throwable) { releaseFurnitureTransition(lease, "delay_watchdog_schedule_failed"); throw failure }
    }

    override fun isFurnitureTransitionAlive(event: Event): Boolean =
        checkFurnitureTransitionOwnership(event, "unspecified")

    override fun checkFurnitureTransitionOwnership(event: Event, point: String): Boolean {
        if (event !is FurnitureInteractEvent) return true
        val entity = event.bukkitEntity
        val lease = furnitureTransitions.recordForEvent(event)
        val furniture = try { CustomFurniture.byAlreadySpawned(entity) } catch (_: Throwable) { null }
        val current = exactFurnitureIdentity(furniture, null)
        val reason = when {
            lease == null -> "lease_missing"
            lease.stale -> lease.staleReason ?: "invalidated"
            !furnitureTransitions.owns(lease) -> "lease_not_owner"
            !entity.isValid -> "entity_invalid"
            entity.isDead -> "entity_dead"
            lease.expectedSourceId != null && current != lease.expectedSourceId ->
                "source_changed expected=${lease.expectedSourceId} actual=${current ?: "null"}"
            else -> "owned"
        }
        val alive = reason == "owned"
        diagnostic {
            "DELAY_OWNERSHIP_CHECK point=$point uuid=${lease?.uuid ?: entity.uniqueId} " +
                    "sourceId=${lease?.expectedSourceId ?: "null"} generation=${lease?.generation ?: "null"} " +
                    "alive=$alive stale=${lease?.stale ?: true} reason=$reason " +
                    "phase=${lease?.phase ?: "null"} gate=${System.identityHashCode(furnitureTransitions)}"
        }
        if (!alive && lease != null && !lease.stale && furnitureTransitions.owns(lease))
            releaseFurnitureTransition(lease, reason)
        return alive
    }

    override fun onFurnitureSequenceClaim(uuid: UUID?, expectedSourceId: String?, point: String) {
        val expected = expectedSourceId?.takeIf { it.isNotBlank() }?.let(::normalizeFurnitureId)
        val lease = uuid?.let { furnitureTransitions.current(it) }
        diagnostic {
            "${if (point == "sequence_prelock") "SEQUENCE_PRELOCK" else "SEQUENCE_HOOK"}_LEASE_LOOKUP " +
                    "uuid=${uuid ?: "null"} expectedSourceId=${expected ?: "null"} " +
                    "found=${lease != null} actualSourceId=${lease?.expectedSourceId ?: "null"} " +
                    "phase=${lease?.phase ?: "null"} generation=${lease?.generation ?: "null"} " +
                    "gate=${System.identityHashCode(furnitureTransitions)}"
        }
        if (uuid == null || lease == null) return
        val invalidated = furnitureTransitions.invalidateForSequence(
            uuid, "superseded_by_sequence point=$point expectedSourceId=${expected ?: "null"}"
        ) ?: return
        ItemsAdderHitboxCompat.released(true, invalidated)
        diagnostic {
            "${if (point == "sequence_prelock") "SEQUENCE_PRELOCK" else "SEQUENCE_HOOK"}_INVALIDATED " +
                    "uuid=${invalidated.uuid} sourceId=${invalidated.expectedSourceId ?: "null"} " +
                    "generation=${invalidated.generation}"
        }
    }

    private fun settleFurnitureTransition(lease: FurnitureTransitionGate.Lease, entity: Entity, targetId: String, oldSettlingTicks: Long) {
        val settlingTicks = maxOf(oldSettlingTicks, FurnitureTransitionGate.settlingTicks(entity.boundingBox))
        if (!furnitureTransitions.phase(lease, FurnitureTransitionGate.Phase.SETTLING)) return
        diagnostic { "TRANSITION_REPLACE_COMPLETE uuid=${lease.uuid} targetId=$targetId settlingTicks=$settlingTicks" }
        try {
            // IA js.a schedules barrier rebuilding at +5 when both behaviours enable it.
            // Small hitboxes use +6; large ones also include IA's five-block timer batches.
            FoliaUtils.runLater(settlingTicks) {
                try {
                    FoliaUtils.runAtLocation(lease.base) {
                        if (furnitureTransitions.owns(lease)) {
                            var reason = "stability_validation_failed"
                            try {
                                val furniture = CustomFurniture.byAlreadySpawned(entity)
                                if (entity.uniqueId == lease.uuid && entity.isValid && !entity.isDead &&
                                    entity.location.block.location == lease.base &&
                                    furniture?.entity?.uniqueId == lease.uuid &&
                                    normalizeFurnitureId(furniture.namespacedID) == normalizeFurnitureId(targetId))
                                    reason = ItemsAdderHitboxCompat.stabilityReason(entity, targetId, lease.base)
                            } finally { releaseFurnitureTransition(lease, reason) }
                        }
                    }
                } catch (failure: Throwable) { releaseFurnitureTransition(lease, "settling_region_schedule_failed"); throw failure }
            }
        } catch (failure: Throwable) {
            releaseFurnitureTransition(lease, "settling_schedule_failed")
            throw failure
        }
    }

    override fun isClientFurnitureInteraction(event: Event): Boolean = event is FurnitureInteractEvent

    private val removedLocations = Collections.synchronizedSet(mutableSetOf<Location>())

    private val lastFurnitureInteractionTick =
        Collections.synchronizedMap(mutableMapOf<UUID, Int>())

    private fun claimFurnitureInteraction(player: Player): Boolean {
        val currentTick = Bukkit.getCurrentTick()

        synchronized(lastFurnitureInteractionTick) {
            if (lastFurnitureInteractionTick[player.uniqueId] == currentTick) {
                return false
            }

            lastFurnitureInteractionTick[player.uniqueId] = currentTick
            return true
        }
    }

    override fun isRemoving(location: Location): Boolean {
        return removedLocations.contains(location)
    }

    override fun setRemoving(location: Location) {
        removedLocations.add(location)
    }

    override fun clearRemoving(location: Location) {
        removedLocations.remove(location)
    }

    companion object {
        private val rotationMap = Collections.synchronizedMap(mutableMapOf<Location, Pair<Float, Float>>())
        val itemFrameRotationMap = Collections.synchronizedMap(mutableMapOf<Location, org.bukkit.Rotation>())

        private val furnitureBaseLocationByEntity = Collections.synchronizedMap(mutableMapOf<UUID, Location>())
    }

    fun removeStageData(location: Location){
        StageData.removeStageData(location)
    }

    fun isPossibleFurnitureEntity(entity: Entity): Boolean {
        return entity is ItemFrame || entity is ArmorStand || entity is ItemDisplay || entity is TextDisplay || entity is Interaction || entity is BlockDisplay
    }

    override fun getFurnitureUUID(location: Location): UUID? {
        val keyLoc = location.block.location
        val world = keyLoc.world ?: return null
        val center = keyLoc.clone().add(0.5, 0.5, 0.5)

        val entities = world.getNearbyEntities(center, 1.05, 1.25, 1.05)
            .sortedBy { it.location.distanceSquared(center) }

        for (entity in entities) {
            try {
                if (!isPossibleFurnitureEntity(entity)) continue
                if (!entity.isValid || entity.isDead) continue
                if (!isSameFurnitureArea(entity, keyLoc)) continue

                val furniture = CustomFurniture.byAlreadySpawned(entity)
                if (furniture != null) {
                    rememberFurnitureBase(entity, keyLoc)
                    return entity.uniqueId
                }
            } catch (_: Exception) {
                continue
            }
        }

        return null
    }

    override fun getFurnitureUUID(
        loc: Location,
        expectedAdapterId: String
    ): UUID? {
        val keyLoc = loc.block.location
        val world = keyLoc.world ?: return null

        val cleanExpectedId = expectedAdapterId
            .removePrefix("ia:")
            .removePrefix("itemsadder:")
            .substringBefore("[")

        val center = keyLoc.clone().add(0.5, 0.5, 0.5)

        val nearby = world.getNearbyEntities(center, 1.5, 1.5, 1.5)
            .sortedBy { it.location.distanceSquared(center) }

        var bestEntity: Entity? = null
        var bestDistance = Double.MAX_VALUE

        for (entity in nearby) {
            if (!isPossibleFurnitureEntity(entity)) continue
            if (!entity.isValid || entity.isDead) continue
            if (!isSameFurnitureArea(entity, keyLoc)) continue

            val furniture = try {
                CustomFurniture.byAlreadySpawned(entity)
            } catch (_: Throwable) {
                null
            } ?: continue

            if (!furniture.namespacedID.equals(cleanExpectedId, ignoreCase = true)) {
                continue
            }

            rememberFurnitureBase(entity, keyLoc)

            val entityBlockLoc = entity.location.block.location

            if (entityBlockLoc == keyLoc) {
                return entity.uniqueId
            }

            val distance = entity.location.distanceSquared(center)

            if (distance < bestDistance) {
                bestDistance = distance
                bestEntity = entity
            }
        }

        return bestEntity?.uniqueId
    }

    private fun rememberFurnitureBase(entity: Entity, baseLoc: Location) {
        furnitureBaseLocationByEntity[entity.uniqueId] = baseLoc.block.location
    }

    private fun forgetFurnitureBase(entity: Entity) {
        furnitureBaseLocationByEntity.remove(entity.uniqueId)
    }

    private fun isSameFurnitureArea(entity: Entity, keyLoc: Location): Boolean {
        val remembered = furnitureBaseLocationByEntity[entity.uniqueId]
        if (remembered != null) {
            return remembered.block.location == keyLoc.block.location
        }

        return entity.location.block.location == keyLoc.block.location
    }

    private fun warn(message: String) {
        try {
            val logger = runCatching { Bukkit.getLogger() }.getOrElse { java.util.logging.Logger.getLogger("Minecraft") }
            logger.warning(message)
        } catch (_: Throwable) { }
    }

    private inline fun debug(message: () -> String) {
        if (DebugSettings.isItemsAdderEnabled()) {
            Bukkit.getLogger().info("[UM][IA] ${message()}")
        }
    }

    private fun furnitureSnapshot(entity: Entity, id: String?): String =
        "uuid=${entity.uniqueId} id=$id type=${entity.type} location=${entity.location} " +
                "valid=${entity.isValid} dead=${entity.isDead}"

    // Debug work is guarded before tick reads, messages and snapshots.
    private inline fun diagnostic(prefix: String = "[UM-IA-DIAG]", message: () -> String) {
        if (!DebugSettings.isItemsAdderEnabled()) return
        try {
            val tick = runCatching { Bukkit.getCurrentTick() }.getOrNull()
            val nanoTime = System.nanoTime()
            val logger = runCatching { Bukkit.getLogger() }.getOrElse {
                java.util.logging.Logger.getLogger("Minecraft")
            }
            logger.info("$prefix ${message()} tick=${tick ?: "unavailable"} " +
                    "monoNs=$nanoTime thread=${Thread.currentThread().name}")
        } catch (_: Exception) {
            // A diagnostic failure must not select a different functional path.
        } catch (_: LinkageError) {
        }
    }

    private fun frameSnapshot(entity: Entity, prefix: String): String =
        (entity as? ItemFrame)?.let { "${prefix}Facing=${it.facing} ${prefix}ItemFrameRotation=${it.rotation}" } ?: ""

    private fun tryReplaceFurniture(entity: Entity, targetId: String, baseLoc: Location, manualInteraction: Boolean = false): Boolean {
        if (!entity.isValid || entity.isDead) {
            diagnostic { "REPLACE_SKIPPED reason=invalid_or_dead uuid=${entity.uniqueId} targetId=$targetId" }
            return false
        }
        val before = entity.location.clone()
        val uuid = entity.uniqueId
        val type = entity.type
        val frameRotation = (entity as? ItemFrame)?.rotation
        val frameFacing = (entity as? ItemFrame)?.facing
        val target = normalizeFurnitureId(targetId)
        var current: CustomFurniture? = null
        var attempted = false
        try {
            current = CustomFurniture.byAlreadySpawned(entity) ?: run {
                diagnostic { "REPLACE_SKIPPED reason=current_furniture_unresolved uuid=$uuid targetId=$target" }
                return false
            }
            debug { "replacement before ${furnitureSnapshot(entity, current.namespacedID)} target=$target" }
            val targetStack = CustomStack.getInstance(target) ?: run {
                diagnostic { "REPLACE_SKIPPED reason=target_not_registered uuid=$uuid targetId=$target" }
                return false
            }
            // Manual interactions delegate target behaviours to ItemsAdder's official API.
            // Keep the existing configuration preflight for the separate sequence route.
            fun gameplaySettings(stack: CustomStack): Map<String, Any>? {
                val item = stack.config.getConfigurationSection("items.${stack.id}") ?: return null
                return item.getValues(true).filterValues { it !is org.bukkit.configuration.ConfigurationSection }.filterKeys {
                    it != "resource" && !it.startsWith("resource.") &&
                            it !in setOf("name", "display_name", "lore")
                }
            }
            if (!manualInteraction) {
                val currentSettings = gameplaySettings(current) ?: run {
                    diagnostic { "REPLACE_SKIPPED reason=current_config_missing uuid=$uuid targetId=$target" }
                    return false
                }
                val targetSettings = gameplaySettings(targetStack)
                if (currentSettings != targetSettings) {
                    diagnostic {
                        val changedKeys = (currentSettings.keys + (targetSettings?.keys ?: emptySet()))
                            .filter { currentSettings[it] != targetSettings?.get(it) }
                        val reason = when {
                            targetSettings == null -> "target_config_missing"
                            changedKeys.any { it.contains("hitbox") || it.endsWith(".solid") || it.contains("barrier") } -> "hitbox_mismatch"
                            else -> "gameplay_mismatch"
                        }
                        "REPLACE_SKIPPED reason=$reason uuid=$uuid targetId=$target changedKeys=${changedKeys.joinToString(",")}"
                    }
                    debug { "replacement fallback: gameplay/hitbox settings differ target=$target" }
                    return false
                }
            }
            // byAlreadySpawned(Block) is a small spatial query, not an entity mapping.
            // Observe it without allowing lookup failures to destroy a valid replacement.
            val blockLookupBefore = if (DebugSettings.isItemsAdderEnabled()) runCatching {
                val found = CustomFurniture.byAlreadySpawned(baseLoc.block)
                found?.entity?.uniqueId to found?.namespacedID
            }.getOrNull() else null
            attempted = true
            diagnostic { "REPLACE_ATTEMPT uuid=$uuid currentId=${current?.namespacedID} targetId=$target " +
                    "entityType=$type yaw=${before.yaw} pitch=${before.pitch} location=$before " + frameSnapshot(entity, "old") }
            ItemsAdderHitboxCompat.before(entity, target, manualInteraction)
            current.replaceFurniture(target)
            ItemsAdderHitboxCompat.after(entity, target, manualInteraction)
            val resolved = CustomFurniture.byAlreadySpawned(entity)
            val after = entity.location
            val valid = entity.isValid && !entity.isDead && entity.uniqueId == uuid &&
                    entity.type == type &&
                    resolved?.entity?.uniqueId == uuid && resolved.namespacedID == target &&
                    after == before && (frameRotation == null || (entity as? ItemFrame)?.rotation == frameRotation) &&
                    (frameFacing == null || (entity as? ItemFrame)?.facing == frameFacing)
            debug { "replacement ${if (valid) "success" else "fallback"} " +
                    furnitureSnapshot(entity, resolved?.namespacedID) }
            if (valid) {
                diagnostic {
                    val lookupResult = runCatching {
                        val found = CustomFurniture.byAlreadySpawned(baseLoc.block)
                        found?.entity?.uniqueId to found?.namespacedID
                    }
                    val blockLookupAfter = lookupResult.getOrNull()
                    val changed = blockLookupBefore != null && blockLookupAfter != null &&
                            (blockLookupBefore != blockLookupAfter ||
                                    (blockLookupBefore.first == uuid && blockLookupAfter.second != target))
                    val reason = if (blockLookupBefore == null || blockLookupAfter == null)
                        "spatial_lookup_unavailable" else "non_authoritative_spatial_lookup"
                    "${if (changed) "BLOCK_MAPPING_CHANGED" else "BLOCK_MAPPING_OBSERVED"} " +
                            "accepted=true reason=$reason oldUuid=$uuid targetId=$target " +
                            "beforeLookupUuid=${blockLookupBefore?.first} beforeLookupId=${blockLookupBefore?.second} " +
                            "afterLookupUuid=${blockLookupAfter?.first} afterLookupId=${blockLookupAfter?.second} " +
                            "lookupException=${lookupResult.exceptionOrNull()?.javaClass?.name}"
                }
                diagnostic { "REPLACE_SUCCESS oldUuid=$uuid newUuid=${entity.uniqueId} uuid=${entity.uniqueId} targetId=$target " +
                        "currentId=${resolved?.namespacedID} entityType=${entity.type} " +
                        "yaw=${after.yaw} pitch=${after.pitch} location=$after " + frameSnapshot(entity, "new") }
                rememberFurnitureBase(entity, baseLoc)
                rotationMap.remove(baseLoc.block.location)
                itemFrameRotationMap.remove(baseLoc.block.location)
                return true
            }
            val failureReason = when {
                !entity.isValid -> "entity_invalid"
                entity.isDead -> "entity_dead"
                entity.uniqueId != uuid || resolved?.entity?.uniqueId != uuid && resolved != null -> "uuid_changed"
                resolved == null -> "unresolved_after_replace"
                resolved.namespacedID != target -> "target_id_mismatch"
                entity.type != type -> "entity_type_changed"
                after != before -> "location_changed"
                (entity as? ItemFrame)?.facing != frameFacing -> "facing_changed"
                (entity as? ItemFrame)?.rotation != frameRotation -> "itemframe_rotation_changed"
                else -> "validation_failed"
            }
            diagnostic { "REPLACE_FAILED reason=$failureReason uuid=$uuid targetId=$target " +
                    "valid=${entity.isValid} dead=${entity.isDead} actualUuid=${entity.uniqueId} " +
                    "entityType=${entity.type} expectedType=$type resolvedId=${resolved?.namespacedID} " +
                    "resolvedUuid=${resolved?.entity?.uniqueId} locationEqual=${after == before} " +
                    "oldYaw=${before.yaw} oldPitch=${before.pitch} yaw=${after.yaw} pitch=${after.pitch} " +
                    "oldLocation=$before location=$after frameRotation=$frameRotation " +
                    "actualFrameRotation=${(entity as? ItemFrame)?.rotation} oldFacing=$frameFacing " +
                    frameSnapshot(entity, "new") }
            diagnostic { "FALLBACK reason=$failureReason oldUuid=$uuid targetId=$target" }
        } catch (ex: Exception) {
            warn("[UM-IA] Replacement failed uuid=$uuid targetId=$target reason=${ex.javaClass.simpleName}")
            diagnostic { "${if (attempted) "REPLACE_FAILED" else "REPLACE_SKIPPED"} " +
                    "reason=replace_exception uuid=$uuid targetId=$target exception=${ex.javaClass.name} message=${ex.message}" }
            debug { "replacement fallback $ex" }
            diagnostic { "FALLBACK reason=replace_exception oldUuid=$uuid targetId=$target" }
        } catch (ex: LinkageError) {
            warn("[UM-IA] Replacement API linkage failed uuid=$uuid targetId=$target reason=${ex.javaClass.simpleName}")
            diagnostic { "${if (attempted) "REPLACE_FAILED" else "REPLACE_SKIPPED"} " +
                    "reason=api_linkage_error uuid=$uuid targetId=$target exception=${ex.javaClass.name} message=${ex.message}" }
            debug { "replacement fallback $ex" }
            diagnostic { "FALLBACK reason=api_linkage_error oldUuid=$uuid targetId=$target" }
        }
        if (!attempted) return false
        diagnostic { "FALLBACK_BEGIN phase=replacement_cleanup oldUuid=$uuid targetId=$target " +
                "oldYaw=${before.yaw} oldPitch=${before.pitch} oldLocation=$before" }
        // A failed call may have partially changed the wrapper or created another entity.
        // Remove through ItemsAdder before fallback spawn, including its barriers/hitboxes.
        val replacementEntity = current?.entity
        val wrappers = listOfNotNull(
            current,
            CustomFurniture.byAlreadySpawned(entity),
            replacementEntity?.let { CustomFurniture.byAlreadySpawned(it) }
        ).distinctBy { it.entity?.uniqueId }
        val knownEntities = (wrappers.mapNotNull { it.entity } + listOfNotNull(entity, replacementEntity))
            .distinctBy { it.uniqueId }
        for (wrapper in wrappers) wrapper.remove(false)
        for (knownEntity in knownEntities) {
            // Complete cleanup of every observed entity before allowing fallback spawn.
            if (knownEntity.isValid && !knownEntity.isDead) knownEntity.remove()
            forgetFurnitureBase(knownEntity)
        }
        rotationMap[baseLoc.block.location] = before.yaw to before.pitch
        if (frameRotation != null) itemFrameRotationMap[baseLoc.block.location] = frameRotation
        diagnostic { "FALLBACK_CLEANUP_DONE oldUuid=$uuid targetId=$target" }
        return false
    }

    override fun isValidUUID(loc: Location, expectedAdapterId: String?, expectedUuid: UUID?): Boolean {
        val keyLoc = loc.block.location
        val world = keyLoc.world ?: run {
            //debug("isValidUUID: world=null en $keyLoc")
            return false
        }

        val center = keyLoc.clone().add(0.5, 0.5, 0.5)
        val nearby = world.getNearbyEntities(center, 1.5, 1.5, 1.5)

        //debug("isValidUUID: loc=$keyLoc expectedUuid=$expectedUuid expectedId=$expectedAdapterId nearby=${nearby.size}")

        for (entity in nearby) {
            if (!isSameFurnitureArea(entity, keyLoc)) continue
            if (!isPossibleFurnitureEntity(entity) || !entity.isValid || entity.isDead) continue

            val furniture = try { CustomFurniture.byAlreadySpawned(entity) } catch (_: Throwable) { null } ?: continue

            //debug("isValidUUID: entity=${entity.type} uuid=${entity.uniqueId} id=${furniture.namespacedID}")

            if (expectedUuid != null) {
                if (entity.uniqueId == expectedUuid) {
                    //debug("isValidUUID: MATCH UUID")
                    return true
                }
                continue
            }

            if (expectedAdapterId != null &&
                furniture.namespacedID.equals(expectedAdapterId.removePrefix("ia:"), ignoreCase = true)
            ) {
                //debug("isValidUUID: MATCH adapterId")
                return true
            }
        }

        //debug("isValidUUID: FAIL loc=$keyLoc")
        return false
    }

    private fun getFurnitureFromEvent(event: Event): CustomFurniture? {
        return try {
            when (event) {
                is FurnitureInteractEvent -> {
                    event.furniture
                        ?: CustomFurniture.byAlreadySpawned(event.bukkitEntity)
                }

                is PlayerInteractEvent -> {
                    event.clickedBlock?.let {
                        CustomFurniture.byAlreadySpawned(it)
                    }
                }

                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun getFurnitureEntity(
        event: Event,
        furniture: CustomFurniture
    ): Entity? {
        return try {
            furniture.entity ?: when (event) {
                is FurnitureInteractEvent -> event.bukkitEntity
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun getFurnitureId(
        event: FurnitureInteractEvent,
        furniture: CustomFurniture?
    ): String? {
        val eventId = try {
            event.namespacedID
        } catch (_: Throwable) {
            null
        }

        /*
         * The wrapper may be null on the first access after a restart,
         * but namespacedID is normally already available in the event.
         */
        if (!eventId.isNullOrBlank()) {
            return eventId
        }

        return try {
            furniture?.namespacedID?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun normalizeFurnitureId(id: String): String {
        return id
            .removePrefix("ia:")
            .removePrefix("itemsadder:")
            .substringBefore("[")
    }

    /**
     * IA 4.0.18 can expose only the namespace through namespacedID while getId()
     * still contains the concrete item key. Ownership must distinguish stages
     * which share that namespace and preserve the same entity UUID.
     */
    private fun exactFurnitureIdentity(furniture: CustomFurniture?, fallback: String?): String? {
        val itemId = try { furniture?.id?.takeIf { it.isNotBlank() } } catch (_: Throwable) { null }
        val exposed = try { furniture?.namespacedID?.takeIf { it.isNotBlank() } } catch (_: Throwable) { null }
        if (itemId != null) {
            val namespace = when {
                exposed != null && ':' in exposed -> normalizeFurnitureId(exposed).substringBefore(':')
                exposed != null && exposed != itemId -> normalizeFurnitureId(exposed)
                fallback != null && ':' in normalizeFurnitureId(fallback) -> normalizeFurnitureId(fallback).substringBefore(':')
                else -> null
            }
            return if (namespace.isNullOrBlank()) itemId else "$namespace:$itemId"
        }
        return (exposed ?: fallback)?.takeIf { it.isNotBlank() }?.let(::normalizeFurnitureId)
    }

    private fun resolveFurnitureBaseLocation(
        event: Event,
        furnitureId: String,
        entity: Entity?
    ): Location {
        val eventLocation = when (event) {
            is FurnitureInteractEvent -> event.bukkitEntity.location
            is PlayerInteractEvent -> event.clickedBlock?.location
            else -> null
        }

        val entityLocation = entity?.location
        val cachedLocation = entity?.let {
            furnitureBaseLocationByEntity[it.uniqueId]
        }

        val origins = listOfNotNull(
            cachedLocation,
            entityLocation,
            eventLocation
        )

        val expectedId = normalizeFurnitureId(furnitureId)
        val candidates = LinkedHashSet<Location>()

        origins.forEach { origin ->
            val base = origin.block.location

            for (x in -2..2) {
                for (y in -2..2) {
                    for (z in -2..2) {
                        candidates.add(
                            base.clone().add(
                                x.toDouble(),
                                y.toDouble(),
                                z.toDouble()
                            ).block.location
                        )
                    }
                }
            }
        }

        val persistentLocation = candidates
            .asSequence()
            .filter { StageData.hasStageData(it) }
            .filter { location ->
                val stageData = StageData.fromLoc(location)
                    ?: return@filter false

                normalizeFurnitureId(
                    stageData.getActualAdapterData().id
                ).equals(expectedId, ignoreCase = true)
            }
            .minByOrNull { location ->
                val reference = entityLocation ?: eventLocation
                reference?.distanceSquared(location) ?: 0.0
            }

        val baseLocation = persistentLocation
            ?: cachedLocation?.block?.location
            ?: entityLocation?.block?.location
            ?: eventLocation?.block?.location
            ?: throw IllegalStateException(
                "Could not resolve ItemsAdder furniture location"
            )

        entity?.let {
            rememberFurnitureBase(it, baseLocation)
        }

        if (event is FurnitureInteractEvent) {
            rememberFurnitureBase(event.bukkitEntity, baseLocation)
        }

        return baseLocation
    }

    override fun isValidFurniture(loc: Location, expectedAdapterId: String?): Boolean {
        val keyLoc = loc.block.location
        val world = keyLoc.world ?: run {
            //debug("isValidFurniture: world=null en $keyLoc")
            return false
        }
        val cleanId = expectedAdapterId?.removePrefix("ia:")

        val center = keyLoc.clone().add(0.5, 0.5, 0.5)
        val nearby = world.getNearbyEntities(center, 1.5, 1.5, 1.5)

        //debug("isValidFurniture: loc=$keyLoc expected=$cleanId nearby=${nearby.size}")

        for (entity in nearby) {
            if (!isSameFurnitureArea(entity, keyLoc)) continue
            if (!isPossibleFurnitureEntity(entity) || !entity.isValid || entity.isDead) {
                //debug("isValidFurniture: ignorada entity=${entity.type} uuid=${entity.uniqueId} valid=${entity.isValid} dead=${entity.isDead}")
                continue
            }

            val furniture = CustomFurniture.byAlreadySpawned(entity)
            if(furniture == null){
                //debug("isValidFurniture: entity=${entity.type} uuid=${entity.uniqueId} no mapea a furniture")
                continue
            }

            //debug("isValidFurniture: encontrada entity=${entity.type} uuid=${entity.uniqueId} id=${furniture.namespacedID}")

            if (cleanId == null) return true
            if (furniture.namespacedID.equals(cleanId, ignoreCase = true)) {
                //debug("isValidFurniture: MATCH id=$cleanId")
                return true
            }
        }

        //debug("isValidFurniture: FAIL loc=$keyLoc expected=$cleanId")
        return false
    }

    override fun isValidBlock(loc: Location, expectedAdapterId: String?): Boolean {
        val cleanId = expectedAdapterId?.removePrefix("ia:") ?: return loc.block.type != Material.AIR

        val customBlock = try { CustomBlock.byAlreadyPlaced(loc.block) } catch (_: Throwable) { null }
        if (customBlock != null) {
            return customBlock.namespacedID.equals(cleanId, ignoreCase = true)
        }

        return false
    }

    override fun isValid(loc: Location, expectedAdapterId: String?): Boolean {
        return isValidFurniture(loc, expectedAdapterId) || isValidBlock(loc, expectedAdapterId)
    }

    override fun removeFurnitureByUUID(loc: Location, uuid: UUID?): Boolean {
        if (uuid == null) return false

        val keyLoc = loc.block.location
        val world = keyLoc.world ?: return false
        val center = keyLoc.clone().add(0.5, 0.5, 0.5)
        val nearby = world.getNearbyEntities(center, 1.5, 1.5, 1.5)

        for (entity in nearby) {
            if (!isSameFurnitureArea(entity, keyLoc)) continue
            if (!isPossibleFurnitureEntity(entity) || !entity.isValid || entity.isDead) continue
            if (entity.uniqueId != uuid) continue

            val furniture = try {
                CustomFurniture.byAlreadySpawned(entity)
            } catch (_: Throwable) {
                null
            } ?: continue

            rotationMap[keyLoc] = Pair(entity.location.yaw, entity.location.pitch)

            if (entity is ItemFrame) {
                itemFrameRotationMap[keyLoc] = entity.rotation
            }

            forgetFurnitureBase(entity)
            furniture.remove(false)
            return true
        }

        return false
    }

    @EventHandler
    fun onInteractBlock(event: CustomBlockInteractEvent) {
        if (event.action == Action.RIGHT_CLICK_BLOCK && event.hand == EquipmentSlot.HAND) {
            stageManager.interact(
                event.player,
                "ia:" + event.namespacedID,
                event.blockClicked.location,
                event,
                this
            )
        }
    }

    @EventHandler
    fun onInteractFurniture(event: FurnitureInteractEvent) {
        val furniture = try {
            event.furniture
                ?: CustomFurniture.byAlreadySpawned(event.bukkitEntity)
        } catch (_: Throwable) {
            null
        }

        val furnitureId = try {
            event.namespacedID?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        } ?: try {
            furniture?.namespacedID?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        } ?: return

        val entity = event.bukkitEntity

        val loc = resolveFurnitureBaseLocation(
            event = event,
            furnitureId = furnitureId,
            entity = entity
        )

        val existing = if (DebugSettings.isItemsAdderEnabled()) furnitureTransitions.current(entity.uniqueId) else null
        val exactSourceId = exactFurnitureIdentity(furniture, furnitureId)
        val lease = furnitureTransitions.acquire(
            entity.uniqueId, event, loc, Bukkit.getCurrentTick(), exactSourceId
        )
        if (lease == null) {
            event.isCancelled = true
            diagnostic { "TRANSITION_LOCK_REJECTED uuid=${entity.uniqueId} ageTicks=${Bukkit.getCurrentTick() - (existing?.startedTick ?: Bukkit.getCurrentTick())} reason=already_transitioning" }
            return
        }
        diagnostic { "TRANSITION_LOCK_ACQUIRED uuid=${lease.uuid} baseLocation=${lease.base}" }
        diagnostic {
            "MANUAL_LEASE_CREATED uuid=${lease.uuid} sourceId=${lease.expectedSourceId ?: "null"} " +
                    "event=${event.javaClass.name}@${System.identityHashCode(event)} generation=${lease.generation} " +
                    "phase=${lease.phase} gate=${System.identityHashCode(furnitureTransitions)}"
        }
        try {
            if (!entity.isValid || entity.isDead) {
                event.isCancelled = true
                releaseFurnitureTransition(lease, "event_entity_invalid_or_dead")
                return
            }
            if (stageManager.isTransitioning(loc)) {
                event.isCancelled = true
                releaseFurnitureTransition(lease, "stage_manager_transitioning")
                return
            }

            stageManager.interact(
                event.player,
                "ia:$furnitureId",
                loc,
                event,
                this
            )
            // StageManager normally owns this notification; also protects exceptional early exits.
            onFurnitureDispatchFinished(event, stageManager.getDelays().containsKey(loc.block.location))
        } catch (failure: Throwable) {
            releaseFurnitureTransition(lease, "dispatch_failed")
            throw failure
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBlockBreak(event: CustomBlockBreakEvent) {
        StageData.removeStageData(event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFurnitureBreakCommitted(event: FurnitureBreakEvent) {
        furnitureTransitions.current(event.bukkitEntity.uniqueId)?.let { releaseFurnitureTransition(it, "furniture_broken") }
        val loc = event.bukkitEntity.location.block.location
        removeStageData(loc)
        setRemoving(loc)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onFurnitureBreak(event: FurnitureBreakEvent) {
        val loc = event.bukkitEntity.location.block.location

        if (stageManager.isTransitioning(loc)) {
            event.isCancelled = true
            return
        }

        if (stageManager.activeSequences.contains(loc)) {
            stageManager.cancelSequence(this, loc)
            event.isCancelled = true
            return
        }

        //removeStageData(loc)
        //setRemoving(loc)

        if (!isRemoving(loc)) {
            if (!stageManager.activeSequences.contains(loc)) {
                clearRemoving(loc)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onFurniturePlaceSuccess(event: FurniturePlaceSuccessEvent) {
        val entity = event.bukkitEntity ?: run {
            diagnostic("[UM-IA-PLACE-DIAG]") { "PLACE_SUCCESS_SKIPPED reason=entity_missing" }
            return
        }
        val uuid = entity.uniqueId
        val valid = if (DebugSettings.isItemsAdderEnabled()) entity.isValid else false
        val dead = entity.isDead
        val entityLocation = entity.location
        val realBaseLocation = entityLocation.block.location
        val wasRemoving = isRemoving(realBaseLocation)
        diagnostic("[UM-IA-PLACE-DIAG]") {
            "PLACE_SUCCESS_RECEIVED uuid=$uuid id=${event.namespacedID} valid=$valid dead=$dead " +
                    "entityLocation=$entityLocation baseLocation=$realBaseLocation wasRemoving=$wasRemoving"
        }
        if (dead) {
            diagnostic("[UM-IA-PLACE-DIAG]") { "PLACE_SUCCESS_SKIPPED reason=entity_dead uuid=$uuid" }
            return
        }
        // ItemsAdder dispatches success inside the spawn consumer, before world insertion.
        // UUID and block base already exist; Bukkit validity is not required for this bookkeeping.
        rememberFurnitureBase(entity, realBaseLocation)
        if (wasRemoving) clearRemoving(realBaseLocation)
        diagnostic("[UM-IA-PLACE-DIAG]") {
            "PLACE_SUCCESS_APPLIED uuid=$uuid baseLocation=$realBaseLocation " +
                    "wasRemoving=$wasRemoving clearedRemoving=${wasRemoving && !isRemoving(realBaseLocation)}"
        }
    }

    private fun placeBlock(adapterId: String, location: Location?) {
        CustomBlock.place(adapterId.replace("ia:", ""), location)
    }

    private fun breakBlock(location: Location?) {
        CustomBlock.remove(location)
    }

    private fun breakFurniture(entity: Entity?, player: Player?) {
        CustomFurniture.remove(entity, false)
    }

    override fun handleStage(
        player: Player,
        itemAdapterData: AdapterData,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: IStage
    ) {
        when (stage) {
            is IBlockStage -> {
                FoliaUtils.runAtLocation(loc) {
                    handleBlockStage(player, itemAdapterData, event, loc, toolUsed, generic, stage)
                }
            }
            is IFurnitureStage -> {
                val keyLoc = loc.block.location
                val lease = if (event is FurnitureInteractEvent) furnitureTransitions.forEvent(event) ?: return else null
                lease?.let { furnitureTransitions.phase(it, FurnitureTransitionGate.Phase.SCHEDULED) }
                try {
                    FoliaUtils.runLater(2L) {
                        try {
                            FoliaUtils.runAtLocation(keyLoc) {
                                if (lease != null && !furnitureTransitions.owns(lease)) {
                                    furnitureTransitions.discardRecord(lease)
                                    return@runAtLocation
                                }
                                try {
                                    if (event is FurnitureInteractEvent && (!isFurnitureTransitionAlive(event) || !player.isOnline)) return@runAtLocation
                                    handleFurnitureStage(player, itemAdapterData, event, keyLoc, toolUsed, generic, stage)
                                } finally {
                                    if (lease != null && lease.phase != FurnitureTransitionGate.Phase.SETTLING)
                                        releaseFurnitureTransition(lease, "application_finished_or_aborted")
                                }
                            }
                        } catch (failure: Throwable) {
                            lease?.let { releaseFurnitureTransition(it, "application_region_schedule_failed") }
                            throw failure
                        }
                    }
                } catch (failure: Throwable) {
                    lease?.let { releaseFurnitureTransition(it, "application_schedule_failed") }
                    throw failure
                }
            }
        }
    }

    override fun handleSequenceStage(
        player: Player,
        itemAdapterData: AdapterData,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: IStage
    ) {
        if (stage is IBlockStage) {
            handleBlockStage(player, itemAdapterData, event, loc, toolUsed, generic, stage)
        } else if (stage is IFurnitureStage) {
            //Bukkit.getConsoleSender().sendMessage("[UM] handleFurnitureStage $loc → tick=${Bukkit.getCurrentTick()}")
            handleFurnitureStage(player, itemAdapterData, event, loc, toolUsed, generic, stage)
        }
    }

    private fun handleBlockStage(
        player: Player,
        itemAdapterData: AdapterData,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: IStage
    ) {
        placeBlock(itemAdapterData.id, loc)
    }

    // Never spawn before removing: old furniture removal can erase the new barriers.
    override fun placeNewFurnitureThenRemoveOld(
        loc: Location,
        currentAdapterId: String,
        targetAdapterId: String,
        oldUuid: UUID?
    ): UUID? {
        // Normal interactions keep their delayed handleStage path. Sequences already
        // execute this hook on the location scheduler and own StageData/UUID updates.
        if (!stageManager.activeSequences.contains(loc.block.location) || oldUuid == null) return null
        val entity = loc.world?.getEntity(oldUuid) ?: return null
        diagnostic {
            val location = entity.location
            "START route=sequence_hook currentId=$currentAdapterId targetId=$targetAdapterId " +
                    "uuid=${entity.uniqueId} entityType=${entity.type} yaw=${location.yaw} pitch=${location.pitch} location=$location"
        }
        val current = CustomFurniture.byAlreadySpawned(entity) ?: run {
            diagnostic { "REPLACE_SKIPPED route=sequence_hook reason=current_furniture_unresolved uuid=$oldUuid targetId=$targetAdapterId" }
            return null
        }
        if (current.namespacedID != normalizeFurnitureId(currentAdapterId)) {
            diagnostic { "REPLACE_SKIPPED route=sequence_hook reason=current_id_mismatch uuid=$oldUuid targetId=$targetAdapterId" }
            return null
        }
        onFurnitureSequenceClaim(oldUuid, currentAdapterId, "sequence_hook")
        return if (tryReplaceFurniture(entity, targetAdapterId, loc)) oldUuid else null
    }

    private fun handleFurnitureStage(
        player: Player,
        itemAdapterData: AdapterData,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: IStage
    ) {
        if (event is FurnitureInteractEvent) {
            diagnostic {
                val entity = event.bukkitEntity
                val location = entity.location
                "START route=interaction currentId=${event.namespacedID} targetId=${itemAdapterData.id} " +
                        "oldUuid=${entity.uniqueId} uuid=${entity.uniqueId} entityType=${entity.type} yaw=${location.yaw} " +
                        "pitch=${location.pitch} location=$location " + frameSnapshot(entity, "old")
            }
        }
        if(isRemoving(loc.block.location)){
            diagnostic { "REPLACE_SKIPPED reason=location_removing targetId=${itemAdapterData.id} location=$loc" }
            if(!stageManager.activeSequences.contains(loc.block.location)){
                clearRemoving(loc.block.location) }
            //Bukkit.getConsoleSender().sendMessage("[UM] Bloqueada recolocación definitiva en ${loc.block.location}")
            return
        }

        if (event is FurnitureInteractEvent) {

            val entityEvent: Entity = event.bukkitEntity
            if(!entityEvent.isValid || entityEvent.isDead) {
                diagnostic { "REPLACE_SKIPPED reason=event_entity_invalid uuid=${entityEvent.uniqueId} targetId=${itemAdapterData.id}" }
                //Bukkit.getConsoleSender().sendMessage(" NO ES VALIDO EL FURNITURE" +loc)
                return
            }

            val oldLocation = entityEvent.location.clone()
            val oldSettlingTicks = FurnitureTransitionGate.settlingTicks(entityEvent.boundingBox)
            val oldFrameRotation = (entityEvent as? ItemFrame)?.rotation
            val oldFrameFacing = (entityEvent as? ItemFrame)?.facing
            if (tryReplaceFurniture(entityEvent, itemAdapterData.id, loc, manualInteraction = true)) {
                furnitureTransitions.forEvent(event)?.let { settleFurnitureTransition(it, entityEvent, itemAdapterData.id, oldSettlingTicks) }
                return
            }

            //if(isRemoving(loc.block.location)) return
            if(isRemoving(loc.block.location)){
                diagnostic { "FALLBACK_ABORTED reason=location_removing oldUuid=${entityEvent.uniqueId} targetId=${itemAdapterData.id}" }
                if(!stageManager.activeSequences.contains(event.bukkitEntity.location.block.location)){
                    clearRemoving(event.bukkitEntity.location.block.location)
                }
                //Bukkit.getConsoleSender().sendMessage("Spawn cancelado en $loc - adapter ${itemAdapterData.id}")
                return
            }

            val currentId = "ia:" + event.namespacedID
            val hadCurrentObject = isValidFurniture(loc, currentId) || isValidBlock(loc, currentId)

            diagnostic { "FALLBACK_BEGIN phase=legacy_remove_spawn oldUuid=${entityEvent.uniqueId} " +
                    "currentId=$currentId targetId=${itemAdapterData.id} oldYaw=${oldLocation.yaw} " +
                    "oldPitch=${oldLocation.pitch} oldLocation=$oldLocation oldFacing=$oldFrameFacing " +
                    "oldItemFrameRotation=$oldFrameRotation hadCurrentObject=$hadCurrentObject" }


            if (hadCurrentObject) {
                (CustomFurniture.byAlreadySpawned(entityEvent) ?: event.furniture)?.remove(false)
                forgetFurnitureBase(entityEvent)
                event.bukkitEntity.remove()
                breakBlock(oldLocation)
            } else {
                breakBlock(oldLocation)
            }
            CustomFurniture.spawn(itemAdapterData.id, loc.block)?.let { customFurniture ->
                //Bukkit.getConsoleSender().sendMessage("[IA] spawn furniture at $loc - adapter ${itemAdapterData.id}")
                val entity: Entity = customFurniture.entity ?: run {
                    diagnostic { "SPAWN_RESULT result=entity_missing targetId=${itemAdapterData.id}" }
                    return
                }
                diagnostic {
                    val spawnLocation = entity.location
                    "SPAWN_RESULT route=interaction oldUuid=${entityEvent.uniqueId} newUuid=${entity.uniqueId} " +
                            "targetId=${itemAdapterData.id} entityType=${entity.type} " +
                            "spawnYaw=${spawnLocation.yaw} spawnPitch=${spawnLocation.pitch} " +
                            "targetYaw=${oldLocation.yaw} targetPitch=${oldLocation.pitch} location=$spawnLocation " +
                            frameSnapshot(entity, "spawn") + " targetFacing=$oldFrameFacing targetItemFrameRotation=$oldFrameRotation"
                }
                rememberFurnitureBase(entity, loc.block.location)

                debug { "fallback spawn ${furnitureSnapshot(entity, customFurniture.namespacedID)} " +
                        "spawnedYaw=${entity.location.yaw} targetYaw=${oldLocation.yaw} targetPitch=${oldLocation.pitch}" }
                entity.setRotation(oldLocation.yaw, oldLocation.pitch)

                rotationMap.remove(loc.block.location)
                itemFrameRotationMap.remove(loc.block.location)

                if (entityEvent is ItemFrame && entity is ItemFrame) {
                    entity.rotation = oldFrameRotation ?: entity.rotation
                }
                diagnostic {
                    val finalLocation = entity.location
                    "ROTATION_APPLIED route=interaction oldUuid=${entityEvent.uniqueId} newUuid=${entity.uniqueId} " +
                            "targetId=${itemAdapterData.id} finalYaw=${finalLocation.yaw} finalPitch=${finalLocation.pitch} " +
                            "location=$finalLocation " + frameSnapshot(entity, "final")
                }
                debug { "fallback after ${furnitureSnapshot(entity, customFurniture.namespacedID)}" }

            } ?: diagnostic { "SPAWN_RESULT result=null targetId=${itemAdapterData.id} oldUuid=${entityEvent.uniqueId}" }
            val entityLoc = event.bukkitEntity.location.block.location

            FoliaUtils.runLater(5L) {
                FoliaUtils.runAtLocation(entityLoc) {
                    if (!stageManager.activeSequences.contains(entityLoc)) {
                        clearRemoving(entityLoc)
                    }
                }
            }
        } else {
            // Sequence System
            val keyLoc = loc.block.location
            val rotation = rotationMap.remove(keyLoc)
            val cachedFrameRotation = itemFrameRotationMap.remove(keyLoc)

            if(isRemoving(loc.block.location)){
                if(!stageManager.activeSequences.contains(loc.block.location)){
                    clearRemoving(loc.block.location) }
                return
            }
            val keyBlock = keyLoc.block
            diagnostic { "FALLBACK_BEGIN route=sequence targetId=${itemAdapterData.id} " +
                    "oldYaw=${rotation?.first} oldPitch=${rotation?.second} location=$keyLoc" }
            CustomFurniture.spawn(itemAdapterData.id, keyBlock)?.let { customFurniture ->
                val entity: Entity = customFurniture.entity ?: run {
                    diagnostic { "SPAWN_RESULT route=sequence result=entity_missing targetId=${itemAdapterData.id}" }
                    return
                }
                diagnostic {
                    val spawnLocation = entity.location
                    "SPAWN_RESULT route=sequence newUuid=${entity.uniqueId} targetId=${itemAdapterData.id} " +
                            "entityType=${entity.type} spawnYaw=${spawnLocation.yaw} spawnPitch=${spawnLocation.pitch} " +
                            "targetYaw=${rotation?.first} targetPitch=${rotation?.second} location=$spawnLocation " +
                            frameSnapshot(entity, "spawn") + " targetFacing=unavailable targetItemFrameRotation=$cachedFrameRotation"
                }
                rememberFurnitureBase(entity, keyLoc)
                //Bukkit.getConsoleSender().sendMessage("[IA] spawn Sequence $loc - adapter ${itemAdapterData.id}")

                debug { "sequence fallback spawn ${furnitureSnapshot(entity, customFurniture.namespacedID)} targetRotation=$rotation" }
                if (rotation != null) {
                    entity.setRotation(rotation.first, rotation.second)
                } else diagnostic { "ROTATION_SKIPPED route=sequence reason=no_cached_rotation newUuid=${entity.uniqueId}" }
                if (cachedFrameRotation != null && entity is ItemFrame) {
                    entity.rotation = cachedFrameRotation
                }
                diagnostic {
                    val finalLocation = entity.location
                    "ROTATION_APPLIED route=sequence newUuid=${entity.uniqueId} targetId=${itemAdapterData.id} " +
                            "finalYaw=${finalLocation.yaw} finalPitch=${finalLocation.pitch} location=$finalLocation " +
                            frameSnapshot(entity, "final")
                }
                debug { "sequence fallback after ${furnitureSnapshot(entity, customFurniture.namespacedID)}" }
            } ?: diagnostic { "SPAWN_RESULT route=sequence result=null targetId=${itemAdapterData.id}" }
        }
    }

    private fun hardRemoveItemsAdderAt(loc: Location): Boolean {
        val keyLoc = loc.block.location
        var removed = false

        // CustomBlock
        try {
            val customBlock = CustomBlock.byAlreadyPlaced(keyLoc.block)
            if (customBlock != null) {
                CustomBlock.remove(keyLoc)
                removed = true
            }
        } catch (_: Throwable) {}

        // CustomFurniture
        val world = keyLoc.world ?: return removed
        val center = keyLoc.clone().add(0.5, 0.5, 0.5)

        for (entity in world.getNearbyEntities(center, 1.5, 1.5, 1.5)) {
            if (!isPossibleFurnitureEntity(entity) || !entity.isValid || entity.isDead) continue
            if (!isSameFurnitureArea(entity, keyLoc)) continue

            val furniture = try {
                CustomFurniture.byAlreadySpawned(entity)
            } catch (_: Throwable) {
                null
            } ?: continue

            rotationMap[keyLoc] = Pair(entity.location.yaw, entity.location.pitch)

            if (entity is ItemFrame) {
                itemFrameRotationMap[keyLoc] = entity.rotation
            }

            forgetFurnitureBase(entity)
            try {
                furniture.remove(false)
            } catch (_: Throwable) {
                entity.remove()
            }

            removed = true
        }

        if (removed) {
            StageData.removeStageData(keyLoc)
            setRemoving(keyLoc)

            FoliaUtils.runLater(2L) {
                FoliaUtils.runAtLocation(keyLoc) {
                    if (!stageManager.activeSequences.contains(keyLoc)) {
                        clearRemoving(keyLoc)
                    }
                }
            }
        }

        return removed
    }

    override fun handleCrossCompatibilityRemoveBeforeTarget(
        player: Player,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: IStage,
        targetCompatibility: ICompatibility
    ): Boolean {
        return hardRemoveItemsAdderAt(loc)
    }

    override fun handleRemove(
        player: Player,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: IStage
    ) {
        if (hardRemoveItemsAdderAt(loc)) return
        if (event is CustomBlockInteractEvent) {
            breakBlock(loc)
            return
        }
        if (event is FurnitureInteractEvent) {
            event.bukkitEntity?.let { entity ->
                if (isPossibleFurnitureEntity(entity)) {
                    val key = entity.location.block.location
                    rotationMap[key] = Pair(entity.location.yaw, entity.location.pitch)

                    if (entity is ItemFrame) {
                        itemFrameRotationMap[key] = entity.rotation
                    }
                    forgetFurnitureBase(entity)
                }
            }
            setRemoving(event.bukkitEntity.location.block.location)

            //Bukkit.getConsoleSender().sendMessage("[IA] Furniture removido en $loc")
            //val uuid = event.bukkitEntity.uniqueId
            removeStageData(event.bukkitEntity.location.block.location)
            event.furniture?.remove(false)
            return
        }

        // Sequence System
        val center = loc.clone().add(0.5, 0.5, 0.5)
        val nearby = loc.world.getNearbyEntities(center, 1.5, 1.5, 1.5)

        var removedAnyFurniture = false

        for (entity in nearby) {
            if (!isPossibleFurnitureEntity(entity) || !entity.isValid || entity.isDead) continue
            if (!isSameFurnitureArea(entity, loc.block.location)) continue

            val furniture = try {
                CustomFurniture.byAlreadySpawned(entity)
            } catch (_: Throwable) {
                null
            } ?: continue

            val key = loc.block.location

            rotationMap[key] = Pair(entity.location.yaw, entity.location.pitch)

            if (entity is ItemFrame) {
                itemFrameRotationMap[key] = entity.rotation
            }

            forgetFurnitureBase(entity)
            furniture.remove(false)
            removedAnyFurniture = true
        }

        if (!removedAnyFurniture && loc.block.type != org.bukkit.Material.AIR) {
            //debug("handleRemove[Sequence]: no furniture found, breakBlock fallback loc=${loc.block.location}")
            breakBlock(loc)
        }
    }

    override fun hashCode(
        player: Player,
        event: Event,
        loc: Location,
        toolUsed: ILiveTool,
        generic: IGeneric,
        stage: Int
    ): Int {
        if (event is CustomBlockInteractEvent) {
            val block: Block = event.blockClicked
            return Utils.calculateHashCode(
                block.location.hashCode(),
                block.hashCode(),
                block.type.hashCode(),
                block.blockData.hashCode(),
                block.state.hashCode()
            )
        }
        if (event is FurnitureInteractEvent) {
            val entity: Entity = event.bukkitEntity
            return Utils.calculateHashCode(
                entity.location.hashCode(),
                entity.hashCode(),
                entity.type.hashCode(),
                entity.uniqueId.hashCode(),
                entity.isDead.hashCode(),
                entity.facing.hashCode()
            )
        }
        return -1
    }

    override fun getItemHand(event: Event): ItemStack? {
        if (event is CustomBlockInteractEvent) {
            return event.item
        }
        return null
    }

    override fun getBlockFace(event: Event): BlockFace? {
        if (event is CustomBlockInteractEvent) {
            return event.blockFace
        }
        return null
    }

}