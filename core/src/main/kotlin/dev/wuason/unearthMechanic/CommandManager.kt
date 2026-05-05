package dev.wuason.unearthMechanic

import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.BlockStateArgument
import dev.jorel.commandapi.arguments.IntegerArgument
import dev.jorel.commandapi.executors.CommandArguments
import dev.jorel.commandapi.executors.CommandExecutor
import dev.jorel.commandapi.executors.PlayerCommandExecutor
import dev.wuason.unearthMechanic.config.Furniture
import dev.wuason.unearthMechanic.system.StageData
import dev.wuason.unearthMechanic.utils.AdventureUtils
import dev.wuason.unearthMechanic.utils.Utils
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.command.CommandSender
import org.bukkit.util.RayTraceResult


class CommandManager(private val core: UnearthMechanic) : ICommandManager {

    fun loadCommands() {
        CommandAPICommand("unearthmechanic")
            .withPermission("unearthmechanic.main")
            .withAliases("unearth", "unearthm", "uth", "uthmechanic")
            .withSubcommands(
                CommandAPICommand("reload")
                    .withAliases("r")
                    .withPermission("unearthmechanic.reload")
                    .executes(CommandExecutor { commandSender: CommandSender?, commandArguments: CommandArguments? ->
                        val start = System.currentTimeMillis()

                        AdventureUtils.sendMessage(commandSender, "<red>[UnearthMechanic] Reloading...")
                        core.getConfigManager().loadConfig()

                        val end = System.currentTimeMillis()
                        val took = end - start

                        AdventureUtils.sendMessage(
                            commandSender,
                            "<green>[UnearthMechanic] Reload complete! <gray>(Took ${took}ms)"
                        )
                    }),
                CommandAPICommand("debug")
                    .withPermission("unearthmechanic.debug")
                    .withSubcommands(
                        CommandAPICommand("info")
                            .executes(CommandExecutor { commandSender: CommandSender?, commandArguments: CommandArguments? ->
                                AdventureUtils.sendMessage(commandSender, "<yellow>Debug Info:")
                                AdventureUtils.sendMessage(
                                    commandSender,
                                    "<yellow>Engine: <aqua> ${core.checkCompatibility() ?: "<red>None"}"
                                )
                                AdventureUtils.sendMessage(
                                    commandSender,
                                    "<yellow>Configs: <aqua>" + core.getConfigManager().getGenerics().size
                                )
                                AdventureUtils.sendMessage(
                                    commandSender,
                                    "<yellow>Configs block: <aqua>" + core.getConfigManager().getGenerics()
                                        .count { it.value is dev.wuason.unearthMechanic.config.Block })
                                AdventureUtils.sendMessage(
                                    commandSender,
                                    "<yellow>Configs furniture: <aqua>" + core.getConfigManager().getGenerics()
                                        .count { it.value is Furniture })
                                AdventureUtils.sendMessage(
                                    commandSender,
                                    "<yellow>Version: <aqua>" + core.description.version
                                )
                            })
                        ,
                        CommandAPICommand("block_info")
                            .executesPlayer(PlayerCommandExecutor { player, args ->
                                val block = player.rayTraceBlocks(10.0)?.hitBlock?: run {
                                    AdventureUtils.sendMessage(player, "<red>You need to look at a block")
                                    return@PlayerCommandExecutor
                                }
                                val stageData = StageData.fromBlock(block)?: run {
                                    AdventureUtils.sendMessage(player, "<red>This block doesn't have stage data")
                                    return@PlayerCommandExecutor
                                }
                                AdventureUtils.sendMessage(player, "<yellow>StageData Info:")
                                AdventureUtils.sendMessage(player, "<yellow>Stage actual: <aqua>" + stageData.getStage())
                                AdventureUtils.sendMessage(player, "<yellow>Generic id: <aqua>" + stageData.getGeneric().getId())
                                AdventureUtils.sendMessage(player, "<yellow>BaseItem id: <aqua>" + stageData.getGeneric().getBaseStage().getAdapterData().toString())
                                AdventureUtils.sendMessage(player, "<yellow>Actual item id: <aqua>" + stageData.getActualAdapterData().toString())
                                AdventureUtils.sendMessage(player, "<yellow>Loc: <aqua>" + stageData.getLocation())
                            }),
                        CommandAPICommand("test")
                            .withArguments(
                                BlockStateArgument("block"),
                                IntegerArgument("size"),
                                IntegerArgument("deep"),
                                IntegerArgument("depth")
                            )
                            .executesPlayer(PlayerCommandExecutor { player, args ->
                                val size: Int = args["size"] as Int
                                val deep: Int = args["deep"] as Int
                                val depth: Int = args["depth"] as Int
                                val blockData: BlockData = args["block"] as BlockData
                                val rayCast: RayTraceResult = player.rayTraceBlocks(10.0) ?: run {
                                    AdventureUtils.sendMessage(
                                        player,
                                        "<red>You need to look at a block in 10 blocks range"
                                    )
                                    return@PlayerCommandExecutor
                                }
                                val block: Block = rayCast.hitBlock ?: run {
                                    AdventureUtils.sendMessage(player, "<red>You need to look at a block")
                                    return@PlayerCommandExecutor
                                }
                                val blockFace: BlockFace = rayCast.hitBlockFace ?: run {
                                    AdventureUtils.sendMessage(player, "<red>You need to look at a block face")
                                    return@PlayerCommandExecutor
                                }
                                Utils.blockAround(block, size, deep, depth, player, blockFace).forEach {
                                    it.type = blockData.material
                                    it.blockData = blockData
                                }
                            })
                    )
            )
            .register()
    }

}