package dev.wuason.unearthMechanic.config

import dev.dejvokep.boostedyaml.YamlDocument
import dev.dejvokep.boostedyaml.block.implementation.Section
import java.io.File
import java.util.Locale

/**
 * Loads and resolves reusable YAML templates before the normal UnearthMechanic
 * configuration parser sees an entry.
 *
 * This class deliberately owns all template-specific syntax so the existing
 * block, furniture, stage and tool parsers remain unaware of templates.
 */
class TemplateConfigManager(
    private val warn: (String) -> Unit
) {

    private val templates = linkedMapOf<String, TemplateDefinition>()

    /** Rebuilds the template registry from every configuration file. */
    fun load(documents: List<Pair<File, YamlDocument>>) {
        templates.clear()

        for ((file, document) in documents) {
            registerSection(document.getSection(TEMPLATES_KEY), file)
            registerSection(document.getSection("unearth.$TEMPLATES_KEY"), file)
        }
    }

    /**
     * Resolves an entry in memory and returns the same section populated with
     * plain configuration values. The source YAML file is never saved or changed.
     */
    fun resolve(section: Section, entryId: String, sourceFile: File): Section? {
        return try {
            val specialArguments = specialArguments(entryId)
            val resolved = resolveConfigNode(
                raw = sectionToMap(section),
                inheritedArguments = specialArguments,
                specialArguments = specialArguments,
                templateStack = emptyList()
            )

            section.clear()
            resolved.forEach { (key, value) -> section.set(key, value) }
            section
        } catch (exception: TemplateException) {
            warn(
                "[UnearthMechanic] Could not resolve templates for '$entryId' " +
                    "(${sourceFile.name}): ${exception.message}"
            )
            null
        }
    }

    private fun registerSection(section: Section?, file: File) {
        if (section == null) return

        for (templateId in section.getRoutesAsStrings(false)) {
            val templateSection = section.getSection(templateId)
            if (templateSection == null) {
                warn(
                    "[UnearthMechanic] Ignoring template '$templateId' in ${file.name}: " +
                        "its value must be a YAML section"
                )
                continue
            }

            val previous = templates.put(
                templateId,
                TemplateDefinition(sectionToMap(templateSection), file)
            )
            if (previous != null) {
                warn(
                    "[UnearthMechanic] Template '$templateId' from ${file.name} replaces " +
                        "the definition from ${previous.file.name}"
                )
            }
        }
    }

    private fun resolveConfigNode(
        raw: Map<String, Any?>,
        inheritedArguments: Map<String, Any?>,
        specialArguments: Map<String, Any?>,
        templateStack: List<String>
    ): LinkedHashMap<String, Any?> {
        val arguments = LinkedHashMap(inheritedArguments)
        val rawArguments = asStringMap(raw[ARGUMENTS_KEY])

        // LinkedHashMap preserves YAML order, so an argument can use an earlier one.
        for ((name, rawValue) in rawArguments) {
            arguments[name] = resolveArgument(rawValue, arguments)
        }
        // Reserved values always describe the concrete (non-template) entry.
        arguments.putAll(specialArguments)

        val result = linkedMapOf<String, Any?>()
        for (templateIdValue in templateReferences(raw[TEMPLATE_KEY], arguments)) {
            val templateId = templateIdValue.toString()
            if (templateId in templateStack) {
                val chain = (templateStack + templateId).joinToString(" -> ")
                throw TemplateException("circular template reference: $chain")
            }

            val definition = templates[templateId]
                ?: throw TemplateException("unknown template '$templateId'")
            val templateResult = resolveConfigNode(
                raw = definition.values,
                inheritedArguments = arguments,
                specialArguments = specialArguments,
                templateStack = templateStack + templateId
            )
            deepMerge(result, templateResult)
        }

        val localValues = linkedMapOf<String, Any?>()
        for ((key, value) in raw) {
            if (key !in CONTROL_KEYS) localValues[key] = deepCopy(value)
        }
        deepMerge(result, localValues)

        val merges = asStringMap(raw[MERGES_KEY])
        deepMerge(result, merges)

        val overrides = asStringMap(raw[OVERRIDES_KEY])
        applyOverrides(result, overrides)

        @Suppress("UNCHECKED_CAST")
        return resolveValue(
            value = result,
            arguments = arguments,
            specialArguments = specialArguments,
            templateStack = templateStack
        ) as LinkedHashMap<String, Any?>
    }

    private fun resolveValue(
        value: Any?,
        arguments: Map<String, Any?>,
        specialArguments: Map<String, Any?>,
        templateStack: List<String>
    ): Any? {
        return when (value) {
            is Section -> resolveValue(
                sectionToMap(value), arguments, specialArguments, templateStack
            )

            is Map<*, *> -> {
                val map = asStringMap(value)
                if (map.keys.any { it in CONTROL_KEYS }) {
                    resolveConfigNode(map, arguments, specialArguments, templateStack)
                } else {
                    val resolved = linkedMapOf<String, Any?>()
                    for ((key, child) in map) {
                        val resolvedKey = substituteString(key, arguments).toString()
                        resolved[resolvedKey] = resolveValue(
                            child, arguments, specialArguments, templateStack
                        )
                    }
                    resolved
                }
            }

            is List<*> -> value.map {
                resolveValue(it, arguments, specialArguments, templateStack)
            }

            is String -> substituteString(value, arguments)
            else -> value
        }
    }

    private fun resolveArgument(value: Any?, arguments: Map<String, Any?>): Any? {
        if (value !is Map<*, *>) return resolveSimpleValue(value, arguments)

        val map = asStringMap(value).toMutableMap()
        if (map.remove(SKIP_ARGUMENT_TYPE_KEY) != null) {
            return resolveSimpleValue(map, arguments)
        }

        val rawType = map[TYPE_KEY]?.toString()
        return when (rawType?.lowercase(Locale.ENGLISH)) {
            null -> resolveSimpleValue(map, arguments)
            CONDITION_TYPE -> {
                val condition = resolveSimpleValue(map[CONDITION_KEY], arguments)
                val branch = if (asBoolean(condition)) map[ON_TRUE_KEY] else map[ON_FALSE_KEY]
                resolveSimpleValue(branch, arguments)
            }

            WHEN_TYPE -> {
                val source = resolveSimpleValue(map[SOURCE_KEY], arguments)?.toString()
                val cases = asStringMap(map[WHEN_KEY])
                resolveSimpleValue(cases[source] ?: map[FALLBACK_KEY], arguments)
            }

            UPPERCASE_TYPE, LOWERCASE_TYPE -> {
                val text = resolveSimpleValue(map[VALUE_KEY], arguments)?.toString().orEmpty()
                val locale = map[LOCALE_KEY]?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(Locale::forLanguageTag)
                    ?: Locale.getDefault()
                if (map[TYPE_KEY].toString().equals(UPPERCASE_TYPE, true)) {
                    text.uppercase(locale)
                } else {
                    text.lowercase(locale)
                }
            }

            else -> throw TemplateException("unsupported argument type '$rawType'")
        }
    }

    private fun resolveSimpleValue(value: Any?, arguments: Map<String, Any?>): Any? {
        return when (value) {
            is String -> substituteString(value, arguments)
            is List<*> -> value.map { resolveSimpleValue(it, arguments) }
            is Map<*, *> -> asStringMap(value).mapValuesTo(linkedMapOf()) {
                resolveSimpleValue(it.value, arguments)
            }
            else -> value
        }
    }

    private fun templateReferences(value: Any?, arguments: Map<String, Any?>): List<Any?> {
        return when (value) {
            null -> emptyList()
            is List<*> -> value.map { resolveSimpleValue(it, arguments) }
            else -> listOf(resolveSimpleValue(value, arguments))
        }
    }

    private fun substituteString(input: String, arguments: Map<String, Any?>): Any? {
        val tokens = findPlaceholders(input)
        if (tokens.isEmpty()) return unescapeBraces(input)

        if (tokens.size == 1 && tokens[0].start == 0 && tokens[0].end == input.lastIndex) {
            val resolved = resolvePlaceholder(tokens[0].content, arguments)
            return if (resolved.found) resolved.value else input
        }

        val output = StringBuilder()
        var cursor = 0
        for (token in tokens) {
            output.append(input, cursor, token.start)
            val resolved = resolvePlaceholder(token.content, arguments)
            if (resolved.found) output.append(stringValue(resolved.value))
            else output.append(input, token.start, token.end + 1)
            cursor = token.end + 1
        }
        output.append(input, cursor, input.length)
        return unescapeBraces(output.toString())
    }

    private fun findPlaceholders(input: String): List<PlaceholderToken> {
        val result = mutableListOf<PlaceholderToken>()
        var index = 0
        while (index < input.length - 1) {
            if (input[index] == '\\' && index + 2 < input.length &&
                input[index + 1] == '$' && input[index + 2] == '{'
            ) {
                index += 3
                continue
            }
            if (input[index] != '$' || input[index + 1] != '{') {
                index++
                continue
            }

            var depth = 1
            var end = index + 2
            while (end < input.length && depth > 0) {
                when (input[end]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                end++
            }
            if (depth != 0) break

            result += PlaceholderToken(index, end - 1, input.substring(index + 2, end - 1))
            index = end
        }
        return result
    }

    private fun resolvePlaceholder(
        rawContent: String,
        arguments: Map<String, Any?>
    ): PlaceholderResult {
        val defaultSeparator = findDefaultSeparator(rawContent)
        var name = if (defaultSeparator >= 0) {
            rawContent.substring(0, defaultSeparator)
        } else {
            rawContent
        }
        val defaultValue = if (defaultSeparator >= 0) {
            rawContent.substring(defaultSeparator + 2)
        } else {
            null
        }

        val modifier = when {
            name.endsWith("^^") -> CaseModifier.UPPERCASE
            name.endsWith("^") -> CaseModifier.TITLE
            else -> CaseModifier.NONE
        }
        if (modifier == CaseModifier.UPPERCASE) name = name.dropLast(2)
        if (modifier == CaseModifier.TITLE) name = name.dropLast(1)

        val found = arguments.containsKey(name)
        var value = if (found) {
            arguments[name]
        } else if (defaultValue != null) {
            parseDefault(substituteString(defaultValue, arguments))
        } else {
            return PlaceholderResult(false, null)
        }

        value = when (modifier) {
            CaseModifier.NONE -> value
            CaseModifier.UPPERCASE -> stringValue(value).uppercase(Locale.getDefault())
            CaseModifier.TITLE -> stringValue(value)
                .split(Regex("[_\\s]+"))
                .filter(String::isNotEmpty)
                .joinToString(" ") { word ->
                    word.replaceFirstChar { character ->
                        if (character.isLowerCase()) character.titlecase(Locale.getDefault())
                        else character.toString()
                    }
                }
        }
        return PlaceholderResult(true, value)
    }

    private fun findDefaultSeparator(content: String): Int {
        var depth = 0
        for (index in 0 until content.length - 1) {
            when (content[index]) {
                '{' -> depth++
                '}' -> depth--
                ':' -> if (depth == 0 && content[index + 1] == '-') return index
            }
        }
        return -1
    }

    private fun parseDefault(value: Any?): Any? {
        if (value !is String) return value
        val trimmed = value.trim()
        if (trimmed.equals("null", true)) return null
        if (trimmed.equals("true", true)) return true
        if (trimmed.equals("false", true)) return false

        if (trimmed.length >= 2 &&
            ((trimmed.first() == '"' && trimmed.last() == '"') ||
                (trimmed.first() == '\'' && trimmed.last() == '\''))
        ) {
            return trimmed.substring(1, trimmed.lastIndex)
        }

        val number = trimmed.removeSuffix("d").removeSuffix("D")
            .removeSuffix("f").removeSuffix("F")
        number.toIntOrNull()?.let { return it }
        number.toLongOrNull()?.let { return it }
        number.toDoubleOrNull()?.let { return it }
        return trimmed
    }

    private fun deepMerge(target: MutableMap<String, Any?>, source: Map<String, Any?>) {
        for ((key, sourceValue) in source) {
            val targetValue = target[key]
            target[key] = when {
                targetValue is Map<*, *> && sourceValue is Map<*, *> -> {
                    val merged = asStringMap(targetValue)
                    deepMerge(merged, asStringMap(sourceValue))
                    merged
                }

                targetValue is List<*> && sourceValue is List<*> ->
                    targetValue.map(::deepCopy) + sourceValue.map(::deepCopy)

                else -> deepCopy(sourceValue)
            }
        }
    }

    private fun applyOverrides(target: MutableMap<String, Any?>, overrides: Map<String, Any?>) {
        for ((key, value) in overrides) target[key] = deepCopy(value)
    }

    private fun sectionToMap(section: Section): LinkedHashMap<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        for (key in section.getRoutesAsStrings(false)) {
            result[key] = normalize(section.get(key))
        }
        return result
    }

    private fun normalize(value: Any?): Any? {
        return when (value) {
            is Section -> sectionToMap(value)
            is Map<*, *> -> asStringMap(value)
            is List<*> -> value.map(::normalize)
            else -> value
        }
    }

    private fun asStringMap(value: Any?): LinkedHashMap<String, Any?> {
        if (value == null) return linkedMapOf()
        if (value is Section) return sectionToMap(value)
        if (value !is Map<*, *>) {
            throw TemplateException("expected a YAML section, found ${value::class.simpleName}")
        }

        val result = linkedMapOf<String, Any?>()
        for ((key, child) in value) result[key.toString()] = normalize(child)
        return result
    }

    private fun deepCopy(value: Any?): Any? {
        return when (value) {
            is Section -> sectionToMap(value)
            is Map<*, *> -> asStringMap(value).mapValuesTo(linkedMapOf()) { deepCopy(it.value) }
            is List<*> -> value.map(::deepCopy)
            else -> value
        }
    }

    private fun specialArguments(entryId: String): LinkedHashMap<String, Any?> {
        val hasNamespace = ':' in entryId
        val namespace = if (hasNamespace) entryId.substringBefore(':') else DEFAULT_NAMESPACE
        val id = if (hasNamespace) entryId.substringAfter(':') else entryId
        return linkedMapOf(NAMESPACE_ARGUMENT to namespace, ID_ARGUMENT to id)
    }

    private fun asBoolean(value: Any?): Boolean {
        return when (value) {
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            else -> value?.toString()?.equals("true", true) == true
        }
    }

    private fun stringValue(value: Any?): String = value?.toString() ?: "null"

    private fun unescapeBraces(value: String): String = value
        .replace("\\${'$'}{", "${'$'}{")
        .replace("\\{", "{")
        .replace("\\}", "}")

    private data class TemplateDefinition(
        val values: LinkedHashMap<String, Any?>,
        val file: File
    )

    private data class PlaceholderToken(
        val start: Int,
        val end: Int,
        val content: String
    )

    private data class PlaceholderResult(val found: Boolean, val value: Any?)

    private enum class CaseModifier { NONE, TITLE, UPPERCASE }

    private class TemplateException(message: String) : IllegalArgumentException(message)

    private companion object {
        const val DEFAULT_NAMESPACE = "unearth"
        const val TEMPLATES_KEY = "templates"
        const val TEMPLATE_KEY = "template"
        const val ARGUMENTS_KEY = "arguments"
        const val MERGES_KEY = "merges"
        const val OVERRIDES_KEY = "overrides"
        const val TYPE_KEY = "type"
        const val VALUE_KEY = "value"
        const val LOCALE_KEY = "locale"
        const val CONDITION_KEY = "condition"
        const val ON_TRUE_KEY = "on_true"
        const val ON_FALSE_KEY = "on_false"
        const val SOURCE_KEY = "source"
        const val WHEN_KEY = "when"
        const val FALLBACK_KEY = "fallback"
        const val SKIP_ARGUMENT_TYPE_KEY = "__skip_template_argument__"
        const val CONDITION_TYPE = "condition"
        const val WHEN_TYPE = "when"
        const val UPPERCASE_TYPE = "to_upper_case"
        const val LOWERCASE_TYPE = "to_lower_case"
        const val NAMESPACE_ARGUMENT = "__NAMESPACE__"
        const val ID_ARGUMENT = "__ID__"

        val CONTROL_KEYS = setOf(TEMPLATE_KEY, ARGUMENTS_KEY, MERGES_KEY, OVERRIDES_KEY)
    }
}
