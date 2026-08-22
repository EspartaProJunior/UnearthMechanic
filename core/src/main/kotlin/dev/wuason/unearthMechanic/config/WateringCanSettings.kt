package dev.wuason.unearthMechanic.config

import java.util.Locale

data class WateringCanSettings(
    val cans: Set<String>,
    val requiredWater: Int,
    val consumeWater: Int,
    val consumeOn: WaterConsumeOn,
    val chargeMode: WaterChargeMode,
    val respectInfinite: Boolean,
    val creativeMode: WaterCreativeMode,
    val onFail: WateringCanFailureFeedback
) {
    fun acceptsCan(id: String): Boolean {
        return "*" in cans || cans.any { it.equals(id, ignoreCase = true) }
    }

    fun waterNeeded(): Int = maxOf(requiredWater, consumeWater)
}

data class WateringCanFailureFeedback(
    val actionbar: String?,
    val sound: String?
)

enum class WaterConsumeOn {
    SUCCESS;

    companion object {
        fun parse(raw: String?): WaterConsumeOn? = when (raw?.trim()?.lowercase(Locale.ENGLISH)) {
            "success" -> SUCCESS
            else -> null
        }
    }
}

enum class WaterChargeMode {
    ACTIVATION,
    TARGET;

    companion object {
        fun parse(raw: String?): WaterChargeMode? = when (raw?.trim()?.lowercase(Locale.ENGLISH)) {
            "activation" -> ACTIVATION
            "target" -> TARGET
            else -> null
        }
    }
}

enum class WaterCreativeMode {
    CONSUME,
    BYPASS;

    companion object {
        fun parse(raw: String?): WaterCreativeMode? = when (raw?.trim()?.lowercase(Locale.ENGLISH)) {
            "consume" -> CONSUME
            "bypass" -> BYPASS
            else -> null
        }
    }
}
