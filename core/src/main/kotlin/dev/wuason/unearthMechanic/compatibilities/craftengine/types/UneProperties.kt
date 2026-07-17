package dev.wuason.unearthMechanic.compatibilities.craftengine.types

import net.momirealms.craftengine.core.block.property.EnumProperty
import net.momirealms.craftengine.core.block.property.IntegerProperty
import net.momirealms.craftengine.core.block.property.Properties

object UneProperties {

    fun registerAll() {
        // Register enum properties so that CraftEngine can use them in configs
        Properties.register(UneKeys.WINDOW_TILE_PROPERTY, EnumProperty.factory(WindowTile::class.java))
        Properties.register(UneKeys.COLUMN_POSITION_PROPERTY, EnumProperty.factory(ColumnPosition::class.java))
        Properties.register(UneKeys.SOFA_TILE_PROPERTY, EnumProperty.factory(SofaTile::class.java))

        Properties.register(UneKeys.FISH_TYPE_PROPERTY, EnumProperty.factory(FishType::class.java))
        registerCurtainYPos()

        registerMiniCubes()

        Properties.register(UneKeys.POINTED_DRIPSTONE_THICKNESS_PROPERTY, EnumProperty.factory(PointedDripstoneThickness::class.java))
        Properties.register(UneKeys.POINTED_DRIPSTONE_VERTICAL_DIRECTION_PROPERTY, EnumProperty.factory(PointedDripstoneVerticalDirection::class.java))
        Properties.register(UneKeys.AMETHYST_FACING_PROPERTY, EnumProperty.factory(AmethystFacing::class.java))
        Properties.register(UneKeys.BRITTLE_ICE_PROPERTY, EnumProperty.factory(BrittleIceStage::class.java))
        Properties.register(UneKeys.REDSTONE_POWER_PROPERTY, IntegerProperty.FACTORY)
        Properties.register(UneKeys.WALL_CONNECTION_PROPERTY, EnumProperty.factory(WallConnection::class.java))
        Properties.register(UneKeys.WALL_POS_PROPERTY, EnumProperty.factory(WallPos::class.java))
        Properties.register(UneKeys.WALL_SHAPE_PROPERTY, EnumProperty.factory(WallShape::class.java))
        Properties.register(UneKeys.WALL_YPOS_PROPERTY, EnumProperty.factory(WallYPos::class.java))

        Properties.register(UneKeys.TERMITE_NEST_STAGE_PROPERTY, EnumProperty.factory(TermiteNestStage::class.java))
        Properties.register(UneKeys.HOLLOW_LOG_STAGE_PROPERTY, EnumProperty.factory(HollowLogStage::class.java))
        Properties.register(UneKeys.TERMITE_COMPOSTER_STAGE_PROPERTY, EnumProperty.factory(TermiteComposterStage::class.java))

        registerTrialBlockProperties()
    }

    fun registerCurtainYPos() {
        Properties.register(UneKeys.CURTAIN_YPOS_PROPERTY, EnumProperty.factory(CurtainYPos::class.java))
    }

    fun registerMiniCubes() {
        Properties.register(UneKeys.MINI_CUBES_PROPERTY, EnumProperty.factory(MiniCubeMaskState::class.java))
    }

    fun registerTrialBlockProperties() {
        Properties.register(UneKeys.VAULT_STAGE_PROPERTY, EnumProperty.factory(VaultStage::class.java)
        )
        Properties.register(UneKeys.TRIAL_SPAWNER_STAGE_PROPERTY, EnumProperty.factory(TrialSpawnerStage::class.java)
        )
    }
}