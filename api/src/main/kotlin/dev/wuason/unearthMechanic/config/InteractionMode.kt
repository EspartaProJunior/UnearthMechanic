package dev.wuason.unearthMechanic.config

enum class InteractionMode {
    INTERACT,
    INTERACT_SHIFT;

    fun matches(isSneaking: Boolean): Boolean {
        return when (this) {
            INTERACT -> !isSneaking
            INTERACT_SHIFT -> isSneaking
        }
    }

    companion object {

        fun parse(raw: String?): InteractionMode? {
            if (raw == null) return INTERACT

            return entries.firstOrNull {
                it.name.equals(raw.trim(), ignoreCase = true)
            }
        }

        fun validModes(): String {
            return entries.joinToString(", ") { it.name }
        }

        fun fromSneaking(isSneaking: Boolean): InteractionMode {
            return if (isSneaking) INTERACT_SHIFT else INTERACT
        }
    }
}