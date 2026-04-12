package dev.wuason.unearthMechanic.utils

import org.bukkit.Bukkit

object VersionDetector {

    private var serverVersion: ServerVersion? = null
    private val VERSIONING_PATTERN = Regex("^(\\d+(?:\\.\\d+)*)")

    enum class ServerVersion(val versionNumber: Int) {
        v1_18_2(2),
        v1_19(3),
        v1_19_1(4),
        v1_19_2(5),
        v1_19_3(6),
        v1_19_4(7),
        v1_20(8),
        v1_20_1(9),
        v1_20_2(10),
        v1_20_3(11),
        v1_20_4(12),
        v1_20_5(13),
        v1_20_6(14),
        v1_21(15),
        v1_21_1(16),
        v1_21_2(17),
        v1_21_3(18),
        v1_21_4(19),
        v1_21_5(20),
        v1_21_6(21),
        v1_21_7(22),
        v1_21_8(23),
        v1_21_9(24),
        v1_21_10(25),
        v1_21_11(26),
        v26_1(27),
        v26_1_1(28),
        v26_1_2(29),
        UNSUPPORTED(-1);

        fun getVersionName(): String {
            return name.replace("v", "").replace("_", ".")
        }

        fun isAtLeast(otherVersion: ServerVersion): Boolean {
            return versionNumber >= otherVersion.versionNumber
        }

        fun isLessThan(otherVersion: ServerVersion): Boolean {
            return versionNumber < otherVersion.versionNumber
        }

        companion object {
            @JvmStatic
            fun fromString(version: String): ServerVersion {
                return try {
                    valueOf("v" + version.replace(".", "_"))
                } catch (_: IllegalArgumentException) {
                    UNSUPPORTED
                }
            }

            @JvmStatic
            fun fromVersionNumber(i: Int): ServerVersion {
                return entries.firstOrNull { it.versionNumber == i } ?: UNSUPPORTED
            }
        }
    }

    @JvmStatic
    fun getServerVersion(): ServerVersion {
        if (serverVersion == null) {
            val bukkitVersion = Bukkit.getBukkitVersion()
            val versionName = VERSIONING_PATTERN.find(bukkitVersion)?.groupValues?.get(1)
                ?: bukkitVersion.split("-")[0]
            serverVersion = ServerVersion.fromString(versionName)
        }
        return serverVersion!!
    }
}