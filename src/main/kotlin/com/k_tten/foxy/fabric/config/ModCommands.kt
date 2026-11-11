package com.k_tten.foxy.fabric.config

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.network.chat.Component

object ModCommands {
    fun register(
        dispatcher: CommandDispatcher<FabricClientCommandSource>,
        config: ModConfig
    ) {
        dispatcher.register(
            LiteralArgumentBuilder.literal<FabricClientCommandSource>("foxy")
                .executes { context ->
                    sendStatus(context.source, config)
                    1
                }
                .then(
                    LiteralArgumentBuilder.literal<FabricClientCommandSource>("view")
                        .executes { context ->
                            sendStatus(context.source, config)
                            1
                        }
                )
                .then(
                    LiteralArgumentBuilder.literal<FabricClientCommandSource>("set")
                        .then(
                            LiteralArgumentBuilder.literal<FabricClientCommandSource>("fruitdigging")
                                .then(
                                    RequiredArgumentBuilder.argument<FabricClientCommandSource, Boolean>(
                                        "value",
                                        BoolArgumentType.bool()
                                    )
                                        .executes { context ->
                                            val enabled = BoolArgumentType.getBool(context, "value")
                                            config.setFruitDigging(enabled)
                                            context.source.sendFeedback(
                                                Component.literal("Fruit Digging: $enabled")
                                            )
                                            1
                                        }
                                )
                        )
                        .then(
                            LiteralArgumentBuilder.literal<FabricClientCommandSource>("autostart")
                                .then(
                                    RequiredArgumentBuilder.argument<FabricClientCommandSource, Boolean>(
                                        "value",
                                        BoolArgumentType.bool()
                                    )
                                        .executes { context ->
                                            val enabled = BoolArgumentType.getBool(context, "value")
                                            config.setAutostart(enabled)
                                            context.source.sendFeedback(
                                                Component.literal("Fruit Digging autostart: $enabled")
                                            )
                                            1
                                        }
                                )
                        )
                        .then(
                            LiteralArgumentBuilder.literal<FabricClientCommandSource>("zombieshootout")
                                .then(
                                    RequiredArgumentBuilder.argument<FabricClientCommandSource, Boolean>(
                                        "value",
                                        BoolArgumentType.bool()
                                    )
                                        .executes { context ->
                                            val enabled = BoolArgumentType.getBool(context, "value")
                                            config.setZombieShootout(enabled)
                                            context.source.sendFeedback(
                                                Component.literal("Zombie Shootout: $enabled")
                                            )
                                            1
                                        }
                                )
                        )
                        .then(
                            LiteralArgumentBuilder.literal<FabricClientCommandSource>("catchafish")
                                .then(
                                    RequiredArgumentBuilder.argument<FabricClientCommandSource, Boolean>(
                                        "value",
                                        BoolArgumentType.bool()
                                    )
                                        .executes { context ->
                                            val enabled = BoolArgumentType.getBool(context, "value")
                                            config.setCatchAFish(enabled)
                                            context.source.sendFeedback(
                                                Component.literal("Catch A Fish: $enabled")
                                            )
                                            1
                                        }
                                )
                        )
                        .then(
                            LiteralArgumentBuilder.literal<FabricClientCommandSource>("verbose")
                                .then(
                                    RequiredArgumentBuilder.argument<FabricClientCommandSource, Boolean>(
                                        "value",
                                        BoolArgumentType.bool()
                                    )
                                        .executes { context ->
                                            val verbose = BoolArgumentType.getBool(context, "value")
                                            config.setVerbose(verbose)
                                            context.source.sendFeedback(
                                                Component.literal("Fruit Digging verbose: $verbose")
                                            )
                                            1
                                        }
                                )
                        )
                )
        )
    }

    private fun sendStatus(source: FabricClientCommandSource, config: ModConfig) {
        source.sendFeedback(
            Component.literal(
                "Fruit Digging: ${config.fruitDigging}, autostart: ${config.autostart}, " +
                    "Zombie Shootout: ${config.zombieShootout}, " +
                    "Catch A Fish: ${config.catchAFish}, verbose: ${config.verbose}"
            )
        )
    }
}
