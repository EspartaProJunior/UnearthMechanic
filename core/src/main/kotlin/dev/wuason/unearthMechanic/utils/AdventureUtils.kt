package dev.wuason.unearthMechanic.utils

import dev.wuason.unearthMechanic.UnearthMechanic
import me.clip.placeholderapi.PlaceholderAPI
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentIteratorType
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import java.util.Locale

object AdventureUtils {
    @JvmField
    var PREFIX: String = "<dark_gray>[<gold>\$NAME<dark_gray>][<gold>\$MECHANIC<dark_gray>] -> <white>"

    @JvmStatic
    fun consoleMessage(text: String?) {
        if (text == null) return
        val mm = MiniMessage.miniMessage()
        Bukkit.getConsoleSender().sendMessage(mm.deserialize(text))
    }

    @JvmStatic
    fun playerMessage(text: String?, player: Player?) {
        if (text == null || player == null) return

        val mm = MiniMessage.miniMessage()
        var parsedText = text

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            parsedText = PlaceholderAPI.setPlaceholders(player, parsedText)
        }

        player.sendMessage(mm.deserialize(parsedText))
    }

    @JvmStatic
    @JvmOverloads
    fun deserializeJson(text: String?, player: Player? = null): String? {
        var parsedText = text ?: return null

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            parsedText = PlaceholderAPI.setPlaceholders(player, parsedText)
        }

        return GsonComponentSerializer.gson().serialize(deserialize(parsedText))
    }

    @JvmStatic
    fun sendMessage(sender: CommandSender?, text: String?) {
        if (text == null || sender == null) return

        val mm = MiniMessage.miniMessage()

        when (sender) {
            is Player -> {
                var parsedText = text
                if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                    parsedText = PlaceholderAPI.setPlaceholders(sender, parsedText)
                }
                sender.sendMessage(mm.deserialize(parsedText))
            }

            is ConsoleCommandSender -> {
                Bukkit.getConsoleSender().sendMessage(mm.deserialize(text))
            }
        }
    }

    @JvmStatic
    fun deserialize(text: String): Component {
        return MiniMessage.miniMessage().deserialize(text)
    }

    @JvmStatic
    fun deserialize(list: List<String>): List<Component> {
        return list.map(::deserialize)
    }

    @JvmStatic
    @JvmOverloads
    fun deserializeLegacy(text: String?, player: Player? = null): String? {
        var parsedText = text ?: return null

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            parsedText = PlaceholderAPI.setPlaceholders(player, parsedText)
        }

        return LegacyComponentSerializer.builder()
            .hexColors()
            .build()
            .serialize(deserialize(parsedText))
    }

    @JvmStatic
    @JvmOverloads
    fun deserializeLegacyList(listText: List<String>, player: Player? = null): List<String> {
        var parsedList = listText

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            parsedList = PlaceholderAPI.setPlaceholders(player, parsedList)
        }

        val serializer = LegacyComponentSerializer.builder()
            .hexColors()
            .build()

        return parsedList.map { serializer.serialize(deserialize(it)) }
    }

    @JvmStatic
    fun sendMessagePluginConsole(addon: JavaPlugin, message: String) {
        consoleMessage(
            PREFIX
                .replace("\$NAME", UnearthMechanic.getInstance().description.name)
                .replace("\$MECHANIC", (addon as Plugin).description.name) + message
        )
    }

    @JvmStatic
    fun sendMessagePluginConsole(message: String) {
        consoleMessage(
            PREFIX
                .replace("\$NAME", UnearthMechanic.getInstance().description.name)
                .replace("\$MECHANIC", "CORE") + message
        )
    }

    @JvmStatic
    fun removeTextAllComponents(component: Component, placeholder: String): Component {
        if (placeholder.isEmpty()) return component

        val replaces = convertToTextComponentReplace(component)
        val toReplace = mutableListOf<TextComponentReplace>()
        val replaced = mutableListOf<TextComponentReplace>()

        for (replace in replaces) {
            if (replace.char == placeholder[toReplace.size]) {
                toReplace.add(replace)

                if (toReplace.size == placeholder.length) {
                    replaced.addAll(toReplace)
                    toReplace.clear()
                }
            } else if (toReplace.isNotEmpty()) {
                toReplace.clear()
            }
        }

        for (i in replaced.indices.reversed()) {
            replaced[i].removeCharByIndex()
        }

        val componentIterator = component.iterator(ComponentIteratorType.DEPTH_FIRST)
        var newComponent = Component.empty()

        while (componentIterator.hasNext()) {
            var current = componentIterator.next()

            for (replace in replaced) {
                if (replace.component.unmodifiedParent == current) {
                    current = replace.component.parent
                }
            }

            newComponent = newComponent.append(current.children(emptyList()))
        }

        return newComponent
    }

    @JvmStatic
    fun convertToTextComponentReplace(component: Component): List<TextComponentReplace> {
        val replaces = mutableListOf<TextComponentReplace>()

        component.iterator(ComponentIteratorType.DEPTH_FIRST).forEachRemaining { current ->
            if (current is TextComponent) {
                val parent = ComponentParent(current)
                val chars = current.content().toCharArray()

                for (i in chars.indices) {
                    replaces.add(TextComponentReplace(parent, chars[i], i))
                }
            }
        }

        return replaces
    }

    @JvmStatic
    fun getAllTextFromComponent(component: Component): String {
        return PlainTextComponentSerializer.plainText().serialize(component)
    }

    @JvmStatic
    fun containsText(component: Component, text: String): Boolean {
        return getAllTextFromComponent(component).contains(text)
    }

    @JvmStatic
    fun containsTextIgnoreCase(component: Component, text: String): Boolean {
        return getAllTextFromComponent(component)
            .uppercase(Locale.ENGLISH)
            .contains(text.uppercase(Locale.ENGLISH))
    }

    class ComponentParent(var parent: TextComponent) {
        val unmodifiedParent: TextComponent = parent
    }

    class TextComponentReplace(
        val component: ComponentParent,
        val char: Char,
        val charIndex: Int
    ) {
        fun removeCharByIndex() {
            val builder = StringBuilder()
            builder.append(component.parent.content())
            builder.deleteCharAt(charIndex)
            component.parent = component.parent.content(builder.toString())
        }
    }
}