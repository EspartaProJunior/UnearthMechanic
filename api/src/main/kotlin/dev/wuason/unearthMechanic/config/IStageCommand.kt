package dev.wuason.unearthMechanic.config

interface IStageCommand {
    fun getCommand(): String
    fun isAsConsole(): Boolean
}