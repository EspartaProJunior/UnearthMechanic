package dev.wuason.unearthMechanic.compatibilities.craftengine.types

import net.momirealms.craftengine.core.util.Key

object UneKeys {
    private const val NAMESPACE = "painter"

    val WINDOW_TILE_PROPERTY: Key = Key.of("$NAMESPACE:window_connect_tile")
    val COLUMN_POSITION_PROPERTY: Key = Key.of("$NAMESPACE:column_block")
    val SOFA_TILE_PROPERTY: Key = Key.of("$NAMESPACE:sofa_connect_tile")

    val FISH_TYPE_PROPERTY: Key = Key.of("$NAMESPACE:fish_type")

    val CURTAIN_YPOS_PROPERTY: Key = Key.of("$NAMESPACE:curtain_ypos")

    val COLUMN_BLOCK_BEHAVIOR: Key = Key.of("$NAMESPACE:column_block")
    val WINDOW_CONNECT_TILE_BEHAVIOR: Key = Key.of("$NAMESPACE:window_connect_tile")
    val SOFA_CONNECT_TILE_BEHAVIOR: Key = Key.of("$NAMESPACE:sofa_connect_tile")
    val FISH_TANK_BEHAVIOR: Key = Key.of("$NAMESPACE:fish_tank")
    val ASHES_MERGE_BEHAVIOR: Key = Key.of("$NAMESPACE:ashes_merge")
    val CURTAIN_BEHAVIOR: Key = Key.of("$NAMESPACE:curtain_block")
    val SHOWER_CURTAIN_BEHAVIOR: Key = Key.of("$NAMESPACE:shower_curtain_block")

    val SHIFT_PLACE_BLOCK_BEHAVIOR: Key = Key.of("$NAMESPACE:shift_place_block")

    val MINI_CUBES_PROPERTY: Key = Key.of("$NAMESPACE:mini_cubes")
    val MINI_CUBES_BEHAVIOR: Key = Key.of("$NAMESPACE:mini_cubes")
}
