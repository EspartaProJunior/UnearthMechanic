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

    val POINTED_DRIPSTONE_THICKNESS_PROPERTY: Key = Key.of("$NAMESPACE:pointed_dripstone_thickness")
    val POINTED_DRIPSTONE_VERTICAL_DIRECTION_PROPERTY: Key = Key.of("$NAMESPACE:pointed_dripstone_vertical_direction")
    val POINTED_DRIPSTONE_BEHAVIOR: Key = Key.of("$NAMESPACE:pointed_dripstone")

    val AMETHYST_FACING_PROPERTY: Key = Key.of("$NAMESPACE:amethyst_facing")
    val AMETHYST_CRYSTAL_BEHAVIOR: Key = Key.of("$NAMESPACE:amethyst_crystal")
    val BUDDING_AMETHYST_BEHAVIOR: Key = Key.of("$NAMESPACE:budding_amethyst")

    val BRITTLE_ICE_PROPERTY: Key = Key.of("$NAMESPACE:brittle_ice_stage")
    val BRITTLE_ICE_BEHAVIOR: Key = Key.of("$NAMESPACE:brittle_ice")

    val MULTIFACE_ATTACHED_BLOCK_BEHAVIOR: Key = Key.of("$NAMESPACE:multiface_attached_block")

    val REDSTONE_POWER_PROPERTY: Key = Key.of("$NAMESPACE:redstone_power")
    val TIMED_REDSTONE_RELAY_BEHAVIOR: Key = Key.of("$NAMESPACE:timed_redstone_relay")

    val REDSTONE_FIELD_BEHAVIOR: Key = Key.of("$NAMESPACE:redstone_field")

    val WALL_CONNECTION_PROPERTY: Key = Key.of("$NAMESPACE:wall_connection")
    val WALL_BLOCK_BEHAVIOR: Key = Key.of("$NAMESPACE:wall_block")

    val REDSTONE_FIELD_RESONATOR_BEHAVIOR: Key = Key.of("$NAMESPACE:redstone_field_resonator")

    val TERMITE_NEST_BEHAVIOR: Key = Key.of("$NAMESPACE:termite_nest")
    val TERMITE_HOLLOW_LOG_BEHAVIOR: Key = Key.of("$NAMESPACE:termite_hollow_log")
    val TERMITE_COMPOSTER_BEHAVIOR: Key = Key.of("$NAMESPACE:termite_composter")

    val TERMITE_NEST_STAGE_PROPERTY: Key = Key.of("$NAMESPACE:termite_nest_stage")
    val HOLLOW_LOG_STAGE_PROPERTY: Key = Key.of("$NAMESPACE:hollow_log_stage")
    val TERMITE_COMPOSTER_STAGE_PROPERTY: Key = Key.of("$NAMESPACE:termite_composter_stage")

    val TERMITE_BUCKET_ITEM_BEHAVIOR: Key = Key.of("$NAMESPACE:termite_bucket")

    val MEERKAT_CACHE_SAND_BEHAVIOR: Key = Key.of("$NAMESPACE:meerkat_cache_sand")

    val BLOCK_SPEED_ITEM_BEHAVIOR: Key = Key.of("$NAMESPACE:block_speed")

    val CROP_ACCELERATOR_ITEM_BEHAVIOR: Key = Key.of("$NAMESPACE:crop_accelerator")

    val FROZEN_TOTEM_OF_UNDYING_ITEM_BEHAVIOR: Key = Key.of("$NAMESPACE:frozen_totem_of_undying")

    val MULTI_SAPLING_BEHAVIOR: Key = Key.of("$NAMESPACE:multi_sapling")

    val TRIAL_SPAWNER_BEHAVIOR: Key = Key.of("$NAMESPACE:trial_spawner")

    val VAULT_BEHAVIOR: Key = Key.of("$NAMESPACE:vault")

    val VAULT_STAGE_PROPERTY: Key = Key.of("$NAMESPACE:vault_stage")
    val TRIAL_SPAWNER_STAGE_PROPERTY: Key = Key.of("$NAMESPACE:trial_spawner_stage")
}
