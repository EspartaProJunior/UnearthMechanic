package dev.wuason.unearthMechanic.config

import dev.wuason.adapter.AdapterData

class BlockStage(
    stage: Int,
    adapterData: AdapterData?,
    drops: List<Drop>,
    remove: Boolean,
    removeItemMainHand: Boolean,
    durabilityToRemove: Int,
    usagesIaToRemove: Int,
    permissionStage: String,
    onlyOneDrop: Boolean,
    reduceItemHand: Int,
    reduceItemInventory: Int,
    batchItemsWhenReducingInventory: Boolean,
    items: List<Item>,
    onlyOneItem: Boolean,
    sounds: List<Sound>,
    delay: Long,
    toolAnimDelay: Boolean,
    executeCommands: List<IStageCommand>,
    foodAdd: Int = 0,
    saturationAdd: Float = 0.0f
) : Stage(
    stage,
    adapterData,
    drops,
    remove,
    removeItemMainHand,
    durabilityToRemove,
    usagesIaToRemove,
    permissionStage,
    onlyOneDrop,
    reduceItemHand,
    reduceItemInventory,
    batchItemsWhenReducingInventory,
    items,
    onlyOneItem,
    sounds,
    delay,
    toolAnimDelay,
    executeCommands,
    foodAdd, saturationAdd
), IBlockStage