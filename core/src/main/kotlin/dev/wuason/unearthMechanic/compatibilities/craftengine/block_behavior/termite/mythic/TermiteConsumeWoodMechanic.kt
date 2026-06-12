package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.termite.mythic

import dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.termite.TermiteGameplay
import dev.wuason.unearthMechanic.utils.FoliaUtils
import io.lumine.mythic.api.config.MythicLineConfig
import io.lumine.mythic.api.skills.INoTargetSkill
import io.lumine.mythic.api.skills.SkillMetadata
import io.lumine.mythic.api.skills.SkillResult
import io.lumine.mythic.bukkit.BukkitAdapter
import io.lumine.mythic.core.skills.SkillMechanic
import io.lumine.mythic.core.skills.SkillExecutor

@Suppress("DEPRECATION")
class TermiteConsumeWoodMechanic(
    manager: SkillExecutor,
    line: String,
    mlc: MythicLineConfig
) : SkillMechanic(manager, line, mlc), INoTargetSkill {

    private val radius = mlc.getInteger(arrayOf("radius", "r"), 6)
    private val consumeChance = mlc.getDouble(arrayOf("chance", "c"), 1.0)
    private val eatDistance = mlc.getDouble(arrayOf("eatdistance", "eat-distance", "d"), 1.65)

    override fun cast(data: SkillMetadata): SkillResult {
        val caster = BukkitAdapter.adapt(data.caster.entity) as? org.bukkit.entity.LivingEntity
            ?: return SkillResult.INVALID_TARGET

        FoliaUtils.runAtEntity(caster) {
            TermiteGameplay.consumeNearbyWood(caster, radius, consumeChance, eatDistance)
        }

        return SkillResult.SUCCESS
    }
}