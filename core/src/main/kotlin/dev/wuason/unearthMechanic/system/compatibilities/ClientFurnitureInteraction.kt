package dev.wuason.unearthMechanic.system.compatibilities

import org.bukkit.event.Event

/** Lets a compatibility identify furniture input without exposing its optional API to features. */
interface ClientFurnitureInteraction {
    fun isClientFurnitureInteraction(event: Event): Boolean
}