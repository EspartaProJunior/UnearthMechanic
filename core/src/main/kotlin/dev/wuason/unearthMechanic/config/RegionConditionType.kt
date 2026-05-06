package dev.wuason.unearthMechanic.config

import java.util.Locale

enum class RegionConditionType {
    WHITELIST,
    BLACKLIST,
    ONLY_GLOBAL,
    DENY_GLOBAL;

    companion object {
        fun parse(raw: String?): RegionConditionType? {
            if (raw.isNullOrBlank()) return null

            return entries.firstOrNull {
                it.name.equals(
                    raw.trim().replace("-", "_"),
                    ignoreCase = true
                )
            }
        }

        fun validTypes(): String {
            return entries.joinToString(", ") {
                it.name.lowercase(Locale.ENGLISH)
            }
        }
    }
}

data class RegionCondition(
    val type: RegionConditionType,
    val regions: Set<String>
) {
    fun matches(currentRegions: Set<String>): Boolean {
        val normalizedCurrent = currentRegions
            .map { it.lowercase(Locale.ENGLISH) }
            .toSet()

        return when (type) {
            RegionConditionType.WHITELIST -> {
                regions.isNotEmpty() && normalizedCurrent.any { it in regions }
            }

            RegionConditionType.BLACKLIST -> {
                regions.isEmpty() || normalizedCurrent.none { it in regions }
            }

            RegionConditionType.ONLY_GLOBAL -> {
                normalizedCurrent.isEmpty()
            }

            RegionConditionType.DENY_GLOBAL -> {
                normalizedCurrent.isNotEmpty()
            }
        }
    }
}