package dev.wuason.unearthMechanic.compatibilities.craftengine.block_behavior.termite

import io.lumine.mythic.bukkit.MythicBukkit
import org.bukkit.Location
import org.bukkit.entity.LivingEntity

object MythicTermites {
    const val TERMITE_MOB_ID = "Termite"
    const val TERMITE_TAG = "um_termite"

    fun spawn(location: Location, colonyKey: String? = null): LivingEntity? {
        val entity = MythicBukkit.inst()
            .apiHelper
            .spawnMythicMob(TERMITE_MOB_ID, location) as? LivingEntity
            ?: return null

        entity.addScoreboardTag(TERMITE_TAG)
        if (colonyKey != null) entity.addScoreboardTag("um_termite_colony:$colonyKey")
        return entity
    }

    fun isTermite(entity: org.bukkit.entity.Entity): Boolean {
        if (entity.scoreboardTags.contains(TERMITE_TAG)) return true
        val activeMob = MythicBukkit.inst().mobManager.getActiveMob(entity.uniqueId).orElse(null)
        return activeMob?.mobType == TERMITE_MOB_ID
    }

    fun playWoodCuttingAnimation(entity: LivingEntity) {
        MythicBukkit.inst().apiHelper.castSkill(entity, "TermiteWoodCuttingAnimation")
    }
}