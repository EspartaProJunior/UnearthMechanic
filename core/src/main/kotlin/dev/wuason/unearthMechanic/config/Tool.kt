package dev.wuason.unearthMechanic.config

import dev.wuason.adapter.Adapter
import dev.wuason.adapter.AdapterData
import dev.wuason.unearthMechanic.UnearthMechanic
import dev.wuason.unearthMechanic.compatibilities.craftengine.CraftEnginePlugin
import java.util.*
import kotlin.jvm.optionals.getOrNull

class Tool(
    private val adapterData: AdapterData,
    private val size: Int,
    private val deep: Int,
    private val depth: Int,
    private val sound: Sound?,
    private val animation: Animation?,
    private val permission: String?,
    private val delay: Long,
    private val replaceOnBreak: String?,
    private val tintFurniture: String?
) : ITool {

    companion object {

        fun Tool(adapterData: AdapterData): Tool {
            return Tool(adapterData, 0, 0, 0, null, null, null, 0, null, null)
        }

        fun parseTool(tool: String): Tool? {
            return try {
                val split = tool.split(";")
                val adapterId = split[0].trim()

                val adapterData = Adapter.getAdapterData(adapterId).getOrNull()
                if (adapterData == null) {
                    val isCraftEngineTool = adapterId.startsWith("ce:")
                    val craftEngineReady = CraftEnginePlugin.isCraftEngineEnabled()

                    if (!isCraftEngineTool || craftEngineReady) {
                        UnearthMechanic.getInstance().logger.warning(
                            "Skipping invalid tool '$tool': adapter id '$adapterId' doesn't exist."
                        )
                    }

                    return null
                }

                if (split.size == 1) {
                    return Tool(adapterData, 0, 0, 0, null, null, null, 0, null, null)
                }

                var size = 0
                var deep = 0
                var depth = 0
                var sound: Sound? = null
                var anim: String? = null
                var permission: String? = null
                var delayAnim = 0L
                var delay = 0L
                var replaceOnBreak: String? = null
                var tintFurniture: String? = null

                split.drop(1).forEach { part ->
                    val x = part.split("=")
                    if (x.size != 2) {
                        UnearthMechanic.getInstance().logger.warning(
                            "Skipping invalid tool '$tool': invalid segment '$part'"
                        )
                        return null
                    }

                    when (x[0].lowercase(Locale.ENGLISH).trim()) {
                        "size" -> size = x[1].trim().toInt()
                        "deep" -> deep = x[1].trim().toInt()
                        "depth" -> depth = x[1].trim().toInt()
                        "sound" -> sound = Sound(x[1].trim(), 1.0f, 1.0f, 0)
                        "anim" -> anim = x[1].trim()
                        "permission" -> permission = x[1].trim()
                        "delayanim" -> delayAnim = x[1].trim().toLong()
                        "delay" -> delay = x[1].trim().toLong()
                        "replaceonbreak" -> replaceOnBreak = x[1].trim()
                        "tintfurniture" -> tintFurniture = x[1].trim()
                    }
                }

                if (depth > 0) {
                    if (size < 1) size = 1
                    if (deep < 1) deep = 1
                }
                if (size > 0) {
                    if (deep < 1) deep = 1
                    if (depth < 1) depth = 1
                }
                if (deep > 0) {
                    if (size < 1) size = 1
                    if (depth < 1) depth = 1
                }

                val animation = if (anim != null) {
                    Animation(if (delayAnim > 0) delayAnim else -1, anim)
                } else null

                Tool(
                    adapterData,
                    size,
                    deep,
                    depth,
                    sound,
                    animation,
                    permission,
                    delay,
                    replaceOnBreak,
                    tintFurniture
                )
            } catch (ex: Exception) {
                UnearthMechanic.getInstance().logger.warning(
                    "Skipping invalid tool '$tool': ${ex.message}"
                )
                null
            }
        }
    }

    override fun getAdapterData(): AdapterData = adapterData
    override fun getSize(): Int = size
    override fun getDeep(): Int = deep
    override fun getDepth(): Int = depth

    override fun toString(): String {
        val builder = StringBuilder()
        builder.append(adapterData.toString())
        if (size > 0) builder.append(";size=$size")
        if (deep > 0) builder.append(";deep=$deep")
        if (depth > 0) builder.append(";depth=$depth")
        if (sound != null) builder.append(";sound=$sound")
        if (animation != null) builder.append(";anim=$animation")
        if (permission != null) builder.append(";permission=$permission")
        if (delay > 0) builder.append(";delay=$delay")
        if (replaceOnBreak != null) builder.append(";replaceonbreak=$replaceOnBreak")
        if (tintFurniture != null) builder.append(";tintfurniture=$tintFurniture")
        return builder.toString()
    }

    override fun isMultiple(): Boolean = size > 0 || deep > 0 || depth > 0

    override fun equals(other: Any?): Boolean {
        if (other is String) return this.adapterData.toString() == other
        if (other is Tool) return this.adapterData == other.adapterData
        return super.equals(other)
    }

    override fun getSound(): ISound? = sound
    override fun getAnimation(): IAnimation? = animation
    override fun getToolPermission(): String? = permission
    override fun getDelay(): Long = delay
    override fun getReplaceOnBreak(): String? = replaceOnBreak
    override fun getTintFurniture(): String? = tintFurniture
}