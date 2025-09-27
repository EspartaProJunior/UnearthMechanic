package dev.wuason.unearthMechanic.config

import dev.wuason.libs.adapter.Adapter
import dev.wuason.libs.adapter.AdapterData
import java.util.*
import kotlin.jvm.optionals.getOrNull

class Tool(private val adapterData: AdapterData, private val size: Int, private val deep: Int, private val depth: Int, private val sound: Sound?, private val animation: Animation?, private val permission: String?, private val delay: Long, private val replaceOnBreak: String?, private val tintFurniture: String?): ITool {
    companion object {

        fun Tool(adapterData: AdapterData): Tool {
            return Tool(adapterData, 0, 0, 0, null, null, null, 0, null, null)
        }

        fun parseTool(tool: String): Tool {
            val split: List<String> = tool.split(";")
            if (split.size == 1) return Tool(Adapter.getAdapterData(split[0].trim()).getOrNull()?: throw NullPointerException("The adapter id: ${split[0].trim()} doesn't exists!"), 0, 0, 0, null, null, null, 0,null,null)
            var size: Int = 0
            var deep: Int = 0
            var depth: Int = 0
            var sound: Sound? = null
            var anim: String? = null
            var permission: String? = null
            var delayAnim: Long = 0
            var delay: Long = 0
            var replaceOnBreak: String? = null
            var tintFurniture: String? = null
            split.filter { split.indexOf(it) != 0 }.forEach {
                val x = it.split("=")
                if (x.size != 2) throw IllegalArgumentException("Invalid tool format")
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
            val animation: Animation? = if (anim != null) Animation(if (delayAnim > 0) delayAnim else -1, anim!!) else null
            return Tool(Adapter.getAdapterData(split[0].trim()).getOrNull()?: throw NullPointerException("The adapter id: ${split[0].trim()} doesn't exists!"), size, deep, depth, sound, animation, permission, delay, replaceOnBreak,tintFurniture)
        }
    }

    override fun getAdapterData(): AdapterData {
        return adapterData
    }

    override fun getSize(): Int {
        return size
    }

    override fun getDeep(): Int {
        return deep
    }

    override fun getDepth(): Int {
        return depth
    }

    override fun toString(): String {
        val builder: StringBuilder = StringBuilder()
        builder.append(adapterData.toString())
        if (size > 0) {
            builder.append(";size=${size}")
        }
        if (deep > 0) {
            builder.append(";deep=${deep}")
        }
        if (depth > 0) {
            builder.append(";depth=${depth}")
        }
        if (sound != null) {
            builder.append(";sound=${sound}")
        }
        if (animation != null) {
            builder.append(";anim=${animation}")
        }
        if (permission != null) {
            builder.append(";permission=${permission}")
        }
        if (delay > 0) {
            builder.append(";delay=${delay}")
        }
        if (replaceOnBreak != null) {
            builder.append(";replaceonbreak=${replaceOnBreak}")
        }
        if (tintFurniture != null) {
            builder.append(";tintfurniture=${tintFurniture}")
        }
        return builder.toString()
    }

    override fun isMultiple(): Boolean {
        return size > 0 || deep > 0 || depth > 0
    }

    override fun equals(other: Any?): Boolean {
        if (other is String) return this.adapterData.toString() == other
        if (other is Tool) return this.adapterData == other.adapterData
        return super.equals(other)
    }

    override fun getSound(): ISound? {
        return sound
    }

    override fun getAnimation(): IAnimation? {
        return animation
    }

    override fun getToolPermission(): String? {
        return permission
    }

    override fun getDelay(): Long {
        return delay
    }

    override fun getReplaceOnBreak(): String? {
        return replaceOnBreak
    }

    override fun getTintFurniture(): String? {
        return tintFurniture
    }

}