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
import dev.wuason.unearthMechanic.utils.Utils.Companion.toAdapter
import org.bukkit.Bukkit
import java.io.File
import java.lang.reflect.Constructor
import java.util.Locale
import kotlin.jvm.optionals.getOrNull
import kotlin.let

class ConfigManager(private val core: UnearthMechanic) : IConfigManager {

    private val generics: HashMap<String, IGeneric> = HashMap()
    private val genericsBaseItemId: HashMap<AdapterData, HashMap<AdapterData, IGeneric>> = HashMap()

    override fun loadConfig() {
        generics.clear()
        genericsBaseItemId.clear()
        loadConfig(GenericType.BLOCK)
        loadConfig(GenericType.FURNITURE)
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
                        var stageType: StageType = StageType.valueOf(type.name.uppercase(Locale.ENGLISH))
                        var itemStageId: String? = null
                        for (t in StageType.values()) {
                            sectionStage.getString("${t.getRoute()}_id")?.let {
                                stageType = t
                                itemStageId = it
                            }
                        }

                        val stageAdapterData: AdapterData? = itemStageId?.let { Adapter.getAdapterData(it).getOrNull() }

                        val remove: Boolean = sectionStage.getBoolean("remove", false)
                        val drops: List<Drop> = sectionStage.getStringList("drops", emptyList()).mapNotNull {
                            try {
                                val split = it.split(";")
                                val adapter = Adapter.getAdapterData(split[0]).getOrNull()

                                val adapterId = split[0]
                                if (adapter == null) {
                                    core.logger.warning("Skipping invalid drop '$it' in generic '$id': unknown adapter '$adapterId'")
                                    null
                                } else {
                                    Drop(adapter, split[1], split[2].toInt())
                                }
                            } catch (ex: Exception) {
                                core.logger.warning("Skipping invalid drop '$it' in generic '$id': ${ex.message}")
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
                                val adapter = Adapter.getAdapterData(split[0]).getOrNull()

                                val adapterId = split[0]
                                if (adapter == null) {
                                    core.logger.warning("Skipping invalid drop '$it' in generic '$id': unknown adapter '$adapterId'")
                                    null
                                } else {
                                    Item(adapter, split[1], split[2].toInt())
                                }
                            } catch (ex: Exception) {
                                core.logger.warning("Skipping invalid item '$it' in generic '$id': ${ex.message}")
                                null
                            }
                        }
                        val sounds: List<Sound> = sectionStage.getMapList("sounds", emptyList()).filter {
                            it.containsKey("sound") && it["sound"] is String && (it["sound"] as String).isNotBlank()
                        }.map {
                            val sound = it["sound"] as String
                            val volume = it.getOrDefault("volume", 1.0) as Number
                            val pitch = it.getOrDefault("pitch", 1.0) as Number
                            val delay = it.getOrDefault("delay", 0) as Number
                            Sound(sound, volume.toFloat(), pitch.toFloat(), delay.toLong())
                        }
                        val stage: Stage = stageType?.let {
                            stageType.getClazz().declaredConstructors[0].newInstance(
                                stages.size,
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
                                toolAnimDelay
                            ) as Stage
                        }?: Stage(
                            stages.size,
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
                            toolAnimDelay
                        )

                        stages.add(stage)

                        val sectionSequence: Section? = sectionStage.getSection("sequence")
                        if (sectionSequence != null && stage is Stage) {
                            val sequenceStages: MutableMap<Long, Stage> = mutableMapOf()

                            for (key in sectionSequence.getRoutesAsStrings(false)) {
                                val delay = key.toLongOrNull() ?: continue
                                val sectionSubStage = sectionSequence.getSection(key) ?: continue

                                var stageTypeSeq: StageType = StageType.valueOf(type.name.uppercase(Locale.ENGLISH))
                                var itemStageIdSeq: String? = null
                                for (t in StageType.values()) {
                                    sectionSubStage.getString("${t.getRoute()}_id")?.let {
                                        stageTypeSeq = t
                                        itemStageIdSeq = it
                                    }
                                }

                                val adapterDataSeq: AdapterData? = itemStageIdSeq?.let { Adapter.getAdapterData(it).getOrNull() }

                                val subStage: Stage = stageTypeSeq.getClazz().declaredConstructors[0].newInstance(
                                    stages.size,
                                    adapterDataSeq,
                                    emptyList<Drop>(),
                                    false, false, 0, 0, "", false, 0,
                                    emptyList<Item>(), false, emptyList<Sound>(), 0L, false
                                ) as Stage

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

                        val constructor: Constructor<*> = type.getClazz().declaredConstructors[0]

                        val baseStage: Stage = StageType.valueOf(type.name.uppercase(Locale.ENGLISH)).getClazz().declaredConstructors[0].newInstance(
                            -1,
                            if (baseItemId1.contains(";")) baseItemId1.substring(0, baseItemId1.indexOf(';')).toAdapter() else baseItemId1.toAdapter(),
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
                            false
                        ) as Stage

                        val generic: IGeneric = constructor.newInstance(cid, normalTools.toSet(), baseStage, stages, notProtected) as IGeneric

                        generics[generic.getId()] = generic

                        generic.getTools().forEach { tool: ITool -> putTool(generic.getBaseStage().getAdapterData()!!, tool.getAdapterData(), generic) }
                    }

                }

            }

        }

        AdventureUtils.sendMessagePluginConsole(core, "<aqua> ${type.getName()} loaded: <yellow>${generics.count { type.getClazz().isInstance(it.value) }}")
    }

    private fun putTool(baseAdapterData: AdapterData, tool: AdapterData, generic: IGeneric) {
        if (!genericsBaseItemId.containsKey(baseAdapterData)) {
            genericsBaseItemId[baseAdapterData] = HashMap()
        }
        genericsBaseItemId[baseAdapterData]?.set(tool, generic)
    }

    override fun validTool(baseAdapterData: AdapterData, tool: AdapterData): Boolean {
        return genericsBaseItemId.containsKey(baseAdapterData) && genericsBaseItemId[baseAdapterData]?.containsKey(tool) ?: false
    }

    override fun validBaseItemId(baseAdapterData: AdapterData): Boolean {
        return genericsBaseItemId.containsKey(baseAdapterData)
    }

    override fun getGeneric(baseAdapterData: AdapterData, tool: AdapterData): IGeneric? {
        if (!validTool(baseAdapterData, tool)) return null
        return genericsBaseItemId[baseAdapterData]?.get(tool)
    }

    override fun getGenerics(): HashMap<String, IGeneric> {
        return generics
    }

    override fun getGenericsBaseItemId(): HashMap<AdapterData, HashMap<AdapterData, IGeneric>> {
        return genericsBaseItemId
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