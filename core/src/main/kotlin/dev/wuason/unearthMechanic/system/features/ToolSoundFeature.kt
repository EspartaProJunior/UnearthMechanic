package dev.wuason.unearthMechanic.system.features

import dev.wuason.unearthMechanic.config.IGeneric
import dev.wuason.unearthMechanic.config.IStage
import dev.wuason.unearthMechanic.system.ILiveTool
import dev.wuason.unearthMechanic.system.compatibilities.ICompatibility
import org.bukkit.Location
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.event.Event

class ToolSoundFeature: AbstractFeature() {

    override fun onPreApply(
        p: Player,
        comp: ICompatibility,
        event: Event,
        loc: Location,
        liveTool: ILiveTool,
        iStage: IStage,
        iGeneric: IGeneric
    ) {
        liveTool.getITool().getSound()?.let { sound ->
            p.getWorld().playSound(
                loc,
                sound.soundId,
                SoundCategory.BLOCKS,
                sound.volume,
                sound.pitch
            )
        }
    }
}