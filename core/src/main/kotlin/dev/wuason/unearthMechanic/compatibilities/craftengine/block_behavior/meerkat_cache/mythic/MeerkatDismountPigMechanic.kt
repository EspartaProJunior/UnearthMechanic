package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.meerkat_cache.mythic

import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.meerkat_cache.MeerkatCacheGameplay
import dev.wuason.unearthMechanic.utils.FoliaUtils
import io.lumine.mythic.api.config.MythicLineConfig
import io.lumine.mythic.api.skills.INoTargetSkill
import io.lumine.mythic.api.skills.SkillMetadata
import io.lumine.mythic.api.skills.SkillResult
import io.lumine.mythic.bukkit.BukkitAdapter
import io.lumine.mythic.core.skills.SkillExecutor
import io.lumine.mythic.core.skills.SkillMechanic
import org.bukkit.entity.LivingEntity

@Suppress("DEPRECATION")
class MeerkatDismountPigMechanic(
    manager: SkillExecutor,
    line: String,
    mlc: MythicLineConfig
) : SkillMechanic(manager, line, mlc), INoTargetSkill {

    override fun cast(data: SkillMetadata): SkillResult {
        val caster = BukkitAdapter.adapt(data.caster.entity) as? LivingEntity
            ?: return SkillResult.INVALID_TARGET

        FoliaUtils.runAtEntity(caster) {
            MeerkatCacheGameplay.dismountVehicle(caster)
        }

        return SkillResult.SUCCESS
    }
}
