package dev.wuason.unearthMechanic.config

data class StageCommand(
    private val command: String,
    private val asConsole: Boolean
) : IStageCommand {

    override fun getCommand(): String = command

    override fun isAsConsole(): Boolean = asConsole
}