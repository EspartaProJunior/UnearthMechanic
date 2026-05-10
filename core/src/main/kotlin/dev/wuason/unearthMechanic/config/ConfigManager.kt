package dev.wuason.unearthMechanic.config

import dev.dejvokep.boostedyaml.YamlDocument
import dev.dejvokep.boostedyaml.block.implementation.Section
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings
import dev.wuason.adapter.Adapter
import dev.wuason.adapter.AdapterData
import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.compatibilities.craftengine.CraftEnginePlugin
import dev.wuason.unearthMechanic.utils.AdventureUtils
import java.io.File
import java.lang.reflect.Constructor
import java.util.Locale
import kotlin.jvm.optionals.getOrNull
import kotlin.let

class ConfigManager(private val core: UnearthMechanic) : IConfigManager {

    private val generics: HashMap<String, IGeneric> = HashMap()
    //private val genericsBaseItemId: HashMap<AdapterData, HashMap<AdapterData, IGeneric>> = HashMap()

    private val genericsBaseItemIdMode:
            HashMap<AdapterData, HashMap<AdapterData, HashMap<InteractionMode, MutableList<IGeneric>>>> = HashMap()

    override fun loadConfig() {
        generics.clear()
        //genericsBaseItemId.clear()
        genericsBaseItemIdMode.clear()

        loadConfig(GenericType.BLOCK)
        loadConfig(GenericType.FURNITURE)
        //core.logger.info("[UM-DBG] LOADED BASES: ${genericsBaseItemIdMode.keys}")
    }

    private fun getAllFilesRecursive(file: File): List<File> {
        val files = mutableListOf<File>()
        file.listFiles()?.forEach {
            if (it.isDirectory) {
                files.addAll(getAllFilesRecursive(it))
            } else {
                files.add(it)
            }
        }
        return files
    }

    fun loadConfig(type: GenericType) {
        val base = File(core.dataFolder.path)
        base.mkdirs()
        val files: List<File> = getAllFilesRecursive(base).filter { it.name.endsWith(".yml") }
        for (file in files) {

            val config = YamlDocument.create(file, GeneralSettings.DEFAULT, LoaderSettings.DEFAULT, DumperSettings.DEFAULT, UpdaterSettings.DEFAULT)

            config.getSection("unearth.${type.getRoute()}")?.let { sectionGenerics ->

                for (key in sectionGenerics.getRoutesAsStrings(false)) {

                    val sectionGeneric: Section = sectionGenerics.getSection(key) ?: continue
                    val id: String = key
                    val basesItemId: ArrayList<String> = ArrayList()
                    val baseItemId = sectionGeneric.get("base")?: continue
                    if (baseItemId is String) {
                        basesItemId.add(baseItemId)
                    } else if (baseItemId is List<*>) {
                        basesItemId.addAll(baseItemId as List<String>)
                    }

                    val notProtected: Boolean = sectionGeneric.getBoolean("no_protect", false)

                    val rawMode = sectionGeneric.getString("mode", "INTERACT")
                    val interactionMode = InteractionMode.parse(rawMode)
                    if (interactionMode == null) {
                        core.logger.warning(
                            "[UnearthMechanic] Invalid mode '$rawMode' in generic '$id' (${file.name}). " +
                                    "Using INTERACT. Valid modes: ${InteractionMode.validModes()}"
                        )
                    }
                    val finalInteractionMode = interactionMode ?: InteractionMode.INTERACT

                    // Delay CraftEngine Loading
                    val toolStrings = sectionGeneric.getStringList("tool", listOf("mc:air"))

                    val normalTools = mutableSetOf<ITool>()

                    for (toolString in toolStrings) {
                        val adapterId = toolString.substringBefore(";").trim()

                        if (adapterId.startsWith("ce:") && !CraftEnginePlugin.isCraftEngineEnabled()) {
                            continue
                        }

                        Tool.parseTool(toolString)?.let { normalTools.add(it) }
                    }

                    val stages: MutableList<IStage> = mutableListOf()

                    val sectionStages: Section = sectionGeneric.getSection("transformation.stages") ?: continue

                    for (keyStage in sectionStages.getRoutesAsStrings(false)) {


                        val sectionStage: Section = sectionStages.getSection(keyStage) ?: continue

                        val stage = buildStageFromSection(
                            sectionStage = sectionStage,
                            defaultType = type,
                            genericId = id,
                            stageKey = keyStage,
                            stageIndex = stages.size
                        )

                        stages.add(stage)

                        val sectionSequence: Section? = sectionStage.getSection("sequence")
                        if (sectionSequence != null && stage is Stage) {
                            val sequenceStages: MutableMap<Long, Stage> = mutableMapOf()

                            for (key in sectionSequence.getRoutesAsStrings(false)) {
                                val delay = key.toLongOrNull() ?: continue
                                val sectionSubStage = sectionSequence.getSection(key) ?: continue

                                val subStage = buildStageFromSection(
                                    sectionStage = sectionSubStage,
                                    defaultType = type,
                                    genericId = id,
                                    stageKey = "$keyStage.sequence.$key",
                                    stageIndex = -1 // to tell them apart
                                )

                                sequenceStages[delay] = subStage
                            }

                            /*Bukkit.getConsoleSender().sendMessage("[UM] Cargando sequence en stage ${stage.getStage()} del id '$id' con ${sequenceStages.size} paso(s).")
                            sequenceStages.forEach { (delay, subStage) ->
                                Bukkit.getConsoleSender().sendMessage("[UM] - Paso con delay $delay ticks, ${subStage.getAdapterData()?.type}:${subStage.getAdapterData()?.id}")
                            }*/

                            stage.setSequenceStages(sequenceStages)
                        }

                    }

                    for ((i, baseItemId1) in basesItemId.withIndex()) {
                        var cid = getCorrectId(id, baseItemId1)
                        if (basesItemId.size > 1) {
                            if (cid.equals(id)) {
                                cid = "${id}_${i}"
                            }
                        }

                        val constructor: Constructor<*> = type.getClazz().declaredConstructors
                            .first { it.parameterCount == 6 }

                        constructor.isAccessible = true

                        val parsedBase = parseBlockStateId(baseItemId1.substringBefore(";"))

                        val adapterData = Adapter.getAdapterData(parsedBase.cleanId).getOrNull()
                        if (adapterData == null) {
                            //core.logger.warning("[UM-DBG] INVALID BASE: ${parsedBase.cleanId} raw=$baseItemId1")
                            continue
                        }

                        val baseStage: Stage = StageType.valueOf(type.name.uppercase(Locale.ENGLISH)).getClazz().declaredConstructors[0].newInstance(
                            -1,
                            adapterData,
                            listOf<Drop>(),
                            false,
                            false,
                            0,
                            0,
                            "",
                            false,
                            0,
                            listOf<Item>(),
                            false,
                            listOf<Sound>(),
                            0,
                            false,
                            emptyList<IStageCommand>()
                        ) as Stage

                        val generic: IGeneric = constructor.newInstance(cid, normalTools.toSet(),
                            baseStage, stages, notProtected,
                            finalInteractionMode) as IGeneric

                        baseStage.setExplicitBlockProperties(parsedBase.props)

                        generics[generic.getId()] = generic

                        generic.getTools().forEach { tool: ITool -> putTool(generic.getBaseStage().getAdapterData()!!, tool.getAdapterData(), generic) }
                    }

                }

            }

        }

        AdventureUtils.sendMessagePluginConsole(core, "<aqua> ${type.getName()} loaded: <yellow>${generics.count { type.getClazz().isInstance(it.value) }}")
    }

    private data class StageTargetResult(
        val stageType: StageType,
        val adapterData: AdapterData?,
        val randomOptions: List<RandomStageOption>
    )

    private fun parseRandomStageOptions(
        values: List<String>,
        genericId: String,
        stageKey: String
    ): List<RandomStageOption> {
        return values.mapNotNull { raw ->
            try {
                val split = raw.split(";")
                if (split.size < 2) {
                    core.logger.warning("Skipping invalid random stage entry '$raw' in generic '$genericId', stage '$stageKey': format must be 'adapterId;chance'")
                    return@mapNotNull null
                }

                val adapterId = split[0].trim()

                val rawChance = split[1].trim()

                if (!Regex("""^\d+(\.\d{1,2})?$""").matches(rawChance)) {
                    core.logger.warning(
                        "Skipping invalid random stage entry '$raw' in generic '$genericId', stage '$stageKey': chance supports up to 2 decimals"
                    )
                    return@mapNotNull null
                }
                val chance = rawChance.toDouble()

                if (chance <= 0.0) {
                    core.logger.warning("Skipping invalid random stage entry '$raw' in generic '$genericId', stage '$stageKey': chance must be > 0")
                    return@mapNotNull null
                }

                val adapter = Adapter.getAdapterData(adapterId).getOrNull()
                if (adapter == null) {
                    core.logger.warning("Skipping invalid random stage entry '$raw' in generic '$genericId', stage '$stageKey': unknown adapter '$adapterId'")
                    return@mapNotNull null
                }

                RandomStageOption(adapter, chance)
            } catch (ex: Exception) {
                //core.logger.warning("Skipping invalid random stage entry '$raw' in generic '$genericId', stage '$stageKey': ${ex.message}")
                null
            }
        }
    }

    private fun parseStageCommands(section: Section): List<IStageCommand> {
        return section.getMapList("execute_commands", emptyList()).mapNotNull { map ->
            try {
                val command = map["command"]?.toString()?.trim().orEmpty()
                if (command.isBlank()) return@mapNotNull null

                val asConsole = map["as_console"]?.toString()?.toBoolean() ?: true
                StageCommand(command, asConsole)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun buildStageFromSection(
        sectionStage: Section,
        defaultType: GenericType,
        genericId: String,
        stageKey: String,
        stageIndex: Int
    ): Stage {
        val targetResult = resolveStageTarget(sectionStage, defaultType, genericId, stageKey)
        val stageType: StageType = targetResult.stageType
        val stageAdapterData: AdapterData? = targetResult.adapterData
        val randomStageOptions: List<RandomStageOption> = targetResult.randomOptions

        val remove: Boolean = sectionStage.getBoolean("remove", false)

        val drops: List<Drop> = sectionStage.getStringList("drops", emptyList()).mapNotNull {
            try {
                val split = it.split(";")
                val adapterId = split[0].trim()
                val adapter = Adapter.getAdapterData(adapterId).getOrNull()

                if (adapter == null) {
                    //core.logger.warning("Skipping invalid drop '$it' in generic '$genericId', stage '$stageKey': unknown adapter '$adapterId'")
                    null
                } else {
                    Drop(adapter, split[1], split[2].toInt())
                }
            } catch (ex: Exception) {
                //core.logger.warning("Skipping invalid drop '$it' in generic '$genericId', stage '$stageKey': ${ex.message}")
                null
            }
        }

        val removeItemMainHand: Boolean = sectionStage.getBoolean("remove_item_main_hand", false)
        val durabilityToRemove = sectionStage.getInt("reduce_durability", 0)
        val usagesIaToRemove = sectionStage.getInt("reduce_usages_ia", 0)
        val permissionStage = sectionStage.getString("permission", "")
        val onlyOneDrop = sectionStage.getBoolean("only_one_drop", false)
        val onlyOneItem = sectionStage.getBoolean("only_one_add", false)
        val reduceItemMainHand: Int = sectionStage.getInt("reduce_item_main_hand", 0)
        val delay: Long = sectionStage.getLong("delay", 0)
        val toolAnimDelay = sectionStage.getBoolean("tool_anim_on_delay", false)

        val items: List<Item> = sectionStage.getStringList("items_add", emptyList()).mapNotNull {
            try {
                val split = it.split(";")
                val adapterId = split[0].trim()
                val adapter = Adapter.getAdapterData(adapterId).getOrNull()

                if (adapter == null) {
                    //core.logger.warning("Skipping invalid item '$it' in generic '$genericId', stage '$stageKey': unknown adapter '$adapterId'")
                    null
                } else {
                    Item(adapter, split[1], split[2].toInt())
                }
            } catch (ex: Exception) {
                //core.logger.warning("Skipping invalid item '$it' in generic '$genericId', stage '$stageKey': ${ex.message}")
                null
            }
        }

        val sounds: List<Sound> = sectionStage.getMapList("sounds", emptyList())
            .filter {
                it.containsKey("sound") && it["sound"] is String && (it["sound"] as String).isNotBlank()
            }.map {
                val sound = it["sound"] as String
                val volume = it.getOrDefault("volume", 1.0) as Number
                val pitch = it.getOrDefault("pitch", 1.0) as Number
                val delaySound = it.getOrDefault("delay", 0) as Number
                Sound(sound, volume.toFloat(), pitch.toFloat(), delaySound.toLong())
            }

        val executeCommands: List<IStageCommand> = parseStageCommands(sectionStage)
        val regionConditions: List<RegionCondition> =
            parseRegionConditions(sectionStage, genericId, stageKey)

        val constructor = stageType.getClazz().declaredConstructors[0]
        constructor.isAccessible = true

        val stage = constructor.newInstance(
            stageIndex,
            stageAdapterData,
            drops,
            remove,
            removeItemMainHand,
            durabilityToRemove,
            usagesIaToRemove,
            permissionStage,
            onlyOneDrop,
            reduceItemMainHand,
            items,
            onlyOneItem,
            sounds,
            delay,
            toolAnimDelay,
            executeCommands
        ) as Stage

        val rawTargetId = sectionStage.getString("${stageType.getRoute()}_id")
        if (!rawTargetId.isNullOrBlank()) {
            stage.setExplicitBlockProperties(parseBlockStateId(rawTargetId).props)
        }

        stage.setRememberPrevious(sectionStage.getBoolean("remember_previous", false))

        if (rawTargetId.equals("um:previous", ignoreCase = true)) {
            stage.setUsePrevious(true)
        }

        val fallbackRaw =
            sectionStage.getString("fallback_${stageType.getRoute()}_id")
                ?: sectionStage.getString("fallback_block_id")
        if (!fallbackRaw.isNullOrBlank()) {
            val parsedFallback = parseBlockStateId(fallbackRaw)

            stage.setFallbackAdapterData(
                Adapter.getAdapterData(parsedFallback.cleanId).getOrNull()
            )

            stage.setFallbackProperties(parsedFallback.props)
        }

        stage.setRandomStageOptions(randomStageOptions)
        stage.setRegionConditions(regionConditions)

        parseTimedSequenceInteraction(
            sectionStage = sectionStage,
            defaultType = defaultType,
            genericId = genericId,
            stageKey = stageKey
        )?.let {
            stage.setTimedSequenceInteraction(it)
        }

        return stage
    }

    private fun parseRegionConditions(
        section: Section,
        genericId: String,
        stageKey: String
    ): List<RegionCondition> {
        return section.getMapList("region-conditions", emptyList()).mapNotNull { map ->
            try {
                val rawType = map["type"]?.toString()
                val type = RegionConditionType.parse(rawType)

                if (type == null) {
                    core.logger.warning(
                        "Skipping invalid region-condition in generic '$genericId', stage '$stageKey': " +
                                "type='$rawType'. Valid types: ${RegionConditionType.validTypes()}"
                    )
                    return@mapNotNull null
                }

                val listRaw = map["list"]

                val regions = when (listRaw) {
                    is List<*> -> listRaw.mapNotNull { it?.toString()?.trim() }
                    is String -> listOf(listRaw.trim())
                    else -> emptyList()
                }
                    .filter { it.isNotBlank() }
                    .map { it.lowercase(Locale.ENGLISH) }
                    .toSet()

                if (
                    regions.isEmpty()
                    && type != RegionConditionType.ONLY_GLOBAL
                    && type != RegionConditionType.DENY_GLOBAL
                ) {
                    core.logger.warning(
                        "Skipping region-condition in generic '$genericId', stage '$stageKey': region list is empty"
                    )
                    return@mapNotNull null
                }

                RegionCondition(type, regions)
            } catch (ex: Exception) {
                core.logger.warning(
                    "Skipping invalid region-condition in generic '$genericId', stage '$stageKey': ${ex.message}"
                )
                null
            }
        }
    }

    private fun resolveStageTarget(
        sectionStage: Section,
        defaultType: GenericType,
        genericId: String,
        stageKey: String
    ): StageTargetResult {
        var stageType = StageType.valueOf(defaultType.name.uppercase(Locale.ENGLISH))
        var adapterData: AdapterData? = null
        var randomOptions: List<RandomStageOption> = emptyList()

        for (t in StageType.values()) {
            val fixedKey = "${t.getRoute()}_id"
            val randomKey = "${t.getRoute()}_random_id"

            val fixedValue = sectionStage.getString(fixedKey)
            val randomValue = sectionStage.getStringList(randomKey, emptyList())

            if (!fixedValue.isNullOrBlank()) {
                if (fixedValue.equals("um:previous", ignoreCase = true)) {
                    stageType = t
                    adapterData = null
                    break
                }

                stageType = t
                adapterData = Adapter.getAdapterData(parseBlockStateId(fixedValue).cleanId).getOrNull()
                break
            }

            if (!fixedValue.isNullOrBlank() && randomValue.isNotEmpty()) {
                core.logger.warning("Stage '$stageKey' in generic '$genericId' has both '$fixedKey' and '$randomKey'. Using '$randomKey'.")
            }

            if (randomValue.isNotEmpty()) {
                stageType = t
                randomOptions = parseRandomStageOptions(randomValue, genericId, stageKey)

                if (randomOptions.isEmpty()) {
                    core.logger.warning("Random stage '$stageKey' in generic '$genericId' has no valid entries.")
                }

                val total = randomOptions.sumOf { it.chance }
                if (kotlin.math.abs(total - 100.0) > 0.009) {
                    core.logger.warning("Random stage '$stageKey' in generic '$genericId' has total chance = $total (recommended: 100.00)")
                }

                adapterData = null
                break
            }

            if (!fixedValue.isNullOrBlank()) {
                stageType = t
                adapterData = Adapter.getAdapterData(parseBlockStateId(fixedValue).cleanId).getOrNull()
                break
            }
        }

        return StageTargetResult(stageType, adapterData, randomOptions)
    }

    private fun parseTimedSequenceInteraction(
        sectionStage: Section,
        defaultType: GenericType,
        genericId: String,
        stageKey: String
    ): TimedSequenceInteraction? {
        val timedSection = sectionStage.getSection("timed_interaction") ?: return null

        val collectWindowTicks = timedSection.getLong("collect_window", -1)
            .takeIf { it > 0 }
            ?: timedSection.getLong("take_window", -1).takeIf { it > 0 }
            ?: timedSection.getLong("success_window", -1).takeIf { it > 0 }
            ?: timedSection.getLong("window", -1).takeIf { it > 0 }
            ?: run {
                core.logger.warning(
                    "Skipping timed_interaction in generic '$genericId', stage '$stageKey': " +
                            "missing collect_window/take_window/success_window/window or value must be > 0"
                )
                return null
            }

        val outcomesSection = timedSection.getSection("outcomes")
        if (outcomesSection == null) {
            core.logger.warning(
                "Skipping timed_interaction in generic '$genericId', stage '$stageKey': missing outcomes section"
            )
            return null
        }

        val outcomes = linkedMapOf<String, TimedSequenceOutcome>()

        for (outcomeKey in outcomesSection.getRoutesAsStrings(false)) {
            val outcomeSection = outcomesSection.getSection(outcomeKey) ?: continue

            val successSection = outcomeSection.getSection("success")
            if (successSection == null) {
                core.logger.warning(
                    "Skipping timed_interaction outcome '$outcomeKey' in generic '$genericId', stage '$stageKey': missing success section"
                )
                continue
            }

            val tools = parseTimedOutcomeTools(
                section = outcomeSection,
                genericId = genericId,
                stageKey = "$stageKey.timed_interaction.outcomes.$outcomeKey"
            )

            val successStage = buildStageFromSection(
                sectionStage = successSection,
                defaultType = defaultType,
                genericId = genericId,
                stageKey = "$stageKey.timed_interaction.outcomes.$outcomeKey.success",
                stageIndex = -2
            )

            /*core.logger.info(
                "[UM-TIMED-CFG] outcome loaded " +
                        "generic=$genericId " +
                        "stage=$stageKey " +
                        "outcome=$outcomeKey " +
                        "tools=${tools.map { it.getAdapterData() }} " +
                        "fallback=${tools.isEmpty()} " +
                        "successAdapter=${successStage.getAdapterData()} " +
                        "successRemove=${successStage.isRemove()}"
            )*/

            outcomes[outcomeKey.lowercase(Locale.ENGLISH)] = TimedSequenceOutcome(
                id = outcomeKey.lowercase(Locale.ENGLISH),
                tools = tools,
                successStage = successStage
            )
        }

        val fallbackCount = outcomes.values.count { it.isFallback() }
        if (fallbackCount > 1) {
            core.logger.warning(
                "timed_interaction in generic '$genericId', stage '$stageKey' has $fallbackCount outcomes without tool. " +
                        "Only the first one will be used as fallback."
            )
        }

        if (outcomes.isEmpty()) {
            core.logger.warning(
                "Skipping timed_interaction in generic '$genericId', stage '$stageKey': no valid outcomes"
            )
            return null
        }

        /*core.logger.info(
            "[UM-TIMED-CFG] loaded timed_interaction " +
                    "generic=$genericId " +
                    "stage=$stageKey " +
                    "collectWindow=$collectWindowTicks " +
                    "outcomes=${outcomes.map { (id, outcome) ->
                        "$id tools=${outcome.tools.map { it.getAdapterData() }} fallback=${outcome.isFallback()} success=${outcome.successStage.getAdapterData()}"
                    }}"
        )*/

        return TimedSequenceInteraction(
            collectWindowTicks = collectWindowTicks,
            outcomes = outcomes
        )
    }

    private fun parseTimedOutcomeTools(
        section: Section,
        genericId: String,
        stageKey: String
    ): Set<ITool> {
        val rawTools = section.getStringList("tool", emptyList())
        if (rawTools.isEmpty()) return emptySet()

        val tools = linkedSetOf<ITool>()

        for (toolString in rawTools) {
            val adapterId = toolString.substringBefore(";").trim()

            if (adapterId.startsWith("ce:") && !CraftEnginePlugin.isCraftEngineEnabled()) {
                continue
            }

            Tool.parseTool(toolString)?.let { parsedTool ->
                if (parsedTool.getDelay() > 0) {
                    core.logger.warning(
                        "Timed interaction tool '$toolString' in '$stageKey' uses delay=${parsedTool.getDelay()}, " +
                                "but tool delay is ignored in timed_interaction outcomes."
                    )
                }

                tools.add(parsedTool)
            }
        }

        return tools
    }

    data class ParsedBlockId(
        val cleanId: String,
        val props: Map<String, String>
    )

    fun parseBlockStateId(raw: String): ParsedBlockId {
        val value = raw.trim()
        val start = value.indexOf('[')
        val end = value.lastIndexOf(']')

        if (start == -1 || end == -1 || end <= start) {
            return ParsedBlockId(value, emptyMap())
        }

        val cleanId = value.substring(0, start).trim()
        val propsRaw = value.substring(start + 1, end)

        val props = propsRaw.split(",")
            .mapNotNull {
                val split = it.split("=", limit = 2)
                if (split.size != 2) return@mapNotNull null
                split[0].trim().lowercase() to split[1].trim().lowercase()
            }
            .toMap()

        return ParsedBlockId(cleanId, props)
    }

    private fun putTool(
        baseAdapterData: AdapterData,
        tool: AdapterData,
        generic: IGeneric
    ) {
        genericsBaseItemIdMode
            .getOrPut(baseAdapterData) { HashMap() }
            .getOrPut(tool) { HashMap() }
            .getOrPut(generic.getInteractionMode()) { mutableListOf() }
            .add(generic)
    }

    override fun validTool(baseAdapterData: AdapterData, tool: AdapterData): Boolean {
        return genericsBaseItemIdMode[baseAdapterData]
            ?.get(tool)
            ?.values
            ?.any { it.isNotEmpty() }
            ?: false
    }

    fun validTool(
        baseAdapterData: AdapterData,
        tool: AdapterData,
        mode: InteractionMode,
        currentProps: Map<String, String> = emptyMap()
    ): Boolean {
        return getGeneric(baseAdapterData, tool, mode, currentProps) != null
    }

    override fun validBaseItemId(baseAdapterData: AdapterData): Boolean {
        return genericsBaseItemIdMode.containsKey(baseAdapterData)
    }

    override fun getGeneric(baseAdapterData: AdapterData, tool: AdapterData): IGeneric? {
        return genericsBaseItemIdMode[baseAdapterData]
            ?.get(tool)
            ?.values
            ?.firstOrNull { it.isNotEmpty() }
            ?.firstOrNull()
    }

    fun getGeneric(
        baseAdapterData: AdapterData,
        tool: AdapterData,
        mode: InteractionMode,
        currentProps: Map<String, String> = emptyMap()
    ): IGeneric? {
        val toolMap = genericsBaseItemIdMode[baseAdapterData]
        if (toolMap == null) {
            //core.logger.info("[UM-DBG-CFG] no base match base=$baseAdapterData availableBases=${genericsBaseItemIdMode.keys}")
            return null
        }

        val modeMap = toolMap[tool]
        if (modeMap == null) {
            //core.logger.info("[UM-DBG-CFG] no tool match base=$baseAdapterData tool=$tool availableTools=${toolMap.keys}")
            return null
        }

        val candidates = modeMap[mode]
        if (candidates == null) {
            //core.logger.info("[UM-DBG-CFG] no mode match base=$baseAdapterData tool=$tool mode=$mode availableModes=${modeMap.keys}")
            return null
        }

        //core.logger.info("[UM-DBG-CFG] candidates=${candidates.size} currentProps=$currentProps")

        candidates.forEach { generic ->
            val baseProps = (generic.getBaseStage() as? Stage)
                ?.getExplicitBlockProperties()
                ?: emptyMap()

            //core.logger.info("[UM-DBG-CFG] candidate id=${generic.getId()} base=${generic.getBaseStage().getAdapterData()} baseProps=$baseProps")
        }

        val exact = candidates.firstOrNull { generic ->
            val baseProps = (generic.getBaseStage() as? Stage)
                ?.getExplicitBlockProperties()
                ?: emptyMap()

            baseProps.isNotEmpty() && baseProps.all { (key, value) ->
                currentProps[key]?.equals(value, ignoreCase = true) == true
            }
        }

        if (exact != null) {
            //core.logger.info("[UM-DBG-CFG] exact match=${exact.getId()}")
            return exact
        }

        val fallback = candidates.firstOrNull { generic ->
            val baseProps = (generic.getBaseStage() as? Stage)
                ?.getExplicitBlockProperties()
                ?: emptyMap()

            baseProps.isEmpty()
        }

        //core.logger.info("[UM-DBG-CFG] fallback=${fallback?.getId() ?: "NULL"}")
        return fallback
    }

    override fun getGenerics(): HashMap<String, IGeneric> {
        return generics
    }

    override fun getGenericsBaseItemId(): HashMap<AdapterData, HashMap<AdapterData, IGeneric>> {
        val legacy = HashMap<AdapterData, HashMap<AdapterData, IGeneric>>()

        genericsBaseItemIdMode.forEach { (base, toolMap) ->
            toolMap.forEach { (tool, modeMap) ->
                val generic = modeMap.values.firstOrNull { it.isNotEmpty() }?.firstOrNull()
                if (generic != null) {
                    legacy.getOrPut(base) { HashMap() }[tool] = generic
                }
            }
        }

        return legacy
    }

    private fun getCorrectId(id: String, baseItemId: String): String {
        val split = baseItemId.split(";")
        return if (split.size >= 2 && split[1].isNotBlank()) "${id}_${split[1]}" else id
    }

    enum class GenericType(private val route: String, private val clazz: Class<out Generic>) {
        BLOCK("block", Block::class.java),
        FURNITURE("furniture", Furniture::class.java);

        fun getName(): String {
            return route.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
        }

        fun getRoute(): String {
            return route
        }

        fun getClazz(): Class<out Generic> {
            return clazz
        }
    }

    enum class StageType(private val route: String, private val clazz: Class<out Stage>) {
        BLOCK("block", BlockStage::class.java),
        FURNITURE("furniture", FurnitureStage::class.java);

        fun getName(): String {
            return route.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
        }

        fun getRoute(): String {
            return route
        }

        fun getClazz(): Class<out Stage> {
            return clazz
        }
    }
}