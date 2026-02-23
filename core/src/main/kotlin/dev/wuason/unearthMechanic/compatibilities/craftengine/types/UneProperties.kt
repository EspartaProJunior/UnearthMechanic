package dev.wuason.unearthMechanic.compatibilities.craftengine.types

import net.momirealms.craftengine.core.block.properties.EnumProperty
import net.momirealms.craftengine.core.block.properties.Properties
import net.momirealms.craftengine.core.util.Key

object UneProperties {
    val WINDOW_TILE_KEY: Key = Key.of("painter:window_connect_tile")
    val COLUMN_POSITION_KEY: Key = Key.of("painter:column_block")
    val SOFA_TILE_KEY: Key = Key.of("painter:sofa_connect_tile")

    val FISH_TYPE_KEY: Key = Key.of("painter:fish_type")

    fun registerAll() {
        // Register enum properties so that CraftEngine can use them in configs
        Properties.register(WINDOW_TILE_KEY, EnumProperty.factory(WindowTile::class.java))
        Properties.register(COLUMN_POSITION_KEY, EnumProperty.factory(ColumnPosition::class.java))
        Properties.register(SOFA_TILE_KEY, EnumProperty.factory(SofaTile::class.java))

        Properties.register(FISH_TYPE_KEY, EnumProperty.factory(FishType::class.java))
    }
}