package dev.wuason.unearthMechanic.config

import dev.wuason.adapter.AdapterData
import dev.wuason.unearthMechanic.system.PreviousBlockDataStore
import dev.wuason.unearthMechanic.system.PreviousBlockDataStore.PreviousBlockSnapshot
import org.bukkit.Location

object PreviousBlockMemory {

    fun save(loc: Location, adapterData: AdapterData, props: Map<String, String> = emptyMap()) {
        PreviousBlockDataStore.save(loc, adapterData, props)
    }

    fun get(loc: Location): PreviousBlockSnapshot? {
        return PreviousBlockDataStore.get(loc)
    }

    fun remove(loc: Location) {
        PreviousBlockDataStore.remove(loc)
    }
}