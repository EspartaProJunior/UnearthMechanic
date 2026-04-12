package dev.wuason.unearthMechanic.utils

import java.util.Random;
import kotlin.math.pow
import kotlin.math.roundToInt

object MathUtils {
    @JvmStatic
    fun randomNumberString(numbers: String?): Int {
        if (numbers == null) return -1
        if (!numbers.contains("-")) return numbers.toInt()

        val nString = numbers.split("-")
        if (nString.size < 2) return nString[0].toInt()
        if (nString[0].isEmpty() || nString[1].isEmpty()) return 64

        val min = nString[0].toInt()
        val max = nString[1].toInt()
        return randomNumber(min, max)
    }

    @JvmStatic
    fun randomNumber(min: Int, max: Int): Int {
        return (min + kotlin.math.round(Math.random() * (max - min))).toInt()
    }

    @JvmStatic
    fun randomNumber(min: Int, max: Int, chance: Int): Int {
        val chanceDecimal = chance / 100.0
        val range = max - min
        return (min + range * (1 - Math.random().pow(chanceDecimal))).roundToInt()
    }

    @JvmStatic
    fun randomDouble(min: Double, max: Double): Double {
        return min + Math.random() * (max - min)
    }

    @JvmStatic
    fun randomDouble(min: Double, max: Double, chance: Int): Double {
        val chanceDecimal = chance / 100.0
        val range = max - min
        return min + range * (1 - Math.random().pow(chanceDecimal))
    }

    @JvmStatic
    fun chance(probability: Float): Boolean {
        if (probability < 0.0f || probability > 100.0f) {
            throw IllegalArgumentException("ERROR CHANCE!!!!!!!!!!")
        }
        val randomValue = Random().nextFloat() * 100.0f
        return randomValue < probability
    }
}