package dev.wuason.unearthMechanic.compatibilities.craftengine.types

import net.momirealms.craftengine.core.block.property.EnumProperty
import net.momirealms.craftengine.core.block.property.Properties

object UneProperties {

    fun registerAll() {
        // Register enum properties so that CraftEngine can use them in configs
        Properties.register(UneKeys.WINDOW_TILE_PROPERTY, EnumProperty.factory(WindowTile::class.java))
        Properties.register(UneKeys.COLUMN_POSITION_PROPERTY, EnumProperty.factory(ColumnPosition::class.java))
        Properties.register(UneKeys.SOFA_TILE_PROPERTY, EnumProperty.factory(SofaTile::class.java))

        Properties.register(UneKeys.FISH_TYPE_PROPERTY, EnumProperty.factory(FishType::class.java))
        registerCurtainYPos()
    }

    fun registerCurtainYPos() {
        Properties.register(UneKeys.CURTAIN_YPOS_PROPERTY, EnumProperty.factory(CurtainYPos::class.java))
    }
}