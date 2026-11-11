package com.k_tten.foxy.fabric.game

import com.k_tten.foxy.fabric.FoxyFabric
import com.k_tten.foxy.fabric.config.ModConfig
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.util.ProblemReporter
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.storage.TagValueOutput
import net.minecraft.world.phys.Vec3
import java.util.UUID

class CatchAFish(private val config: ModConfig) {
    private var active = false
    private val reportedArmorStands = mutableMapOf<UUID, String>()

    fun register() {
        ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ ->
            handleMessage(message.string)
        }
        ClientReceiveMessageEvents.GAME.register { message, _ ->
            handleMessage(message.string)
        }
        ClientTickEvents.END_CLIENT_TICK.register(::tick)
    }

    private fun handleMessage(message: String) {
        when (normalizeMessage(message)) {
            normalizeMessage(START_TRIGGER) -> {
                if (!config.catchAFish) {
                    chatLog("Start trigger ignored: Catch A Fish is disabled.")
                    return
                }
                if (active) return

                active = true
                reportedArmorStands.clear()
                chatLog("Start trigger received. Scanning for armor stands.")
            }
            normalizeMessage(END_TRIGGER) -> {
                if (active) {
                    active = false
                    reportedArmorStands.clear()
                    chatLog("End trigger received. Catch A Fish scan stopped.")
                }
            }
        }
    }

    private fun tick(client: Minecraft) {
        if (!active) return
        if (!config.catchAFish) {
            active = false
            reportedArmorStands.clear()
            chatLog("Catch A Fish scan stopped because the feature was disabled.")
            return
        }

        val level = client.level ?: return
        val stands = level.entitiesForRendering()
            .filterIsInstance<ArmorStand>()
            .filter { isInRange(it.position()) }
        val presentIds = stands.mapTo(mutableSetOf()) { it.uuid }
        reportedArmorStands.keys.retainAll(presentIds)

        for (stand in stands) {
            val nbtOutput = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING)
            stand.saveWithoutId(nbtOutput)
            val nbt = nbtOutput.buildResult().toString()
            val heldItems = listOf(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)
                .joinToString { slot -> "${slot.name}=${stand.getItemBySlot(slot)}" }
            val wornItems = listOf(
                EquipmentSlot.HEAD,
                EquipmentSlot.CHEST,
                EquipmentSlot.LEGS,
                EquipmentSlot.FEET
            ).joinToString { slot -> "${slot.name}=${stand.getItemBySlot(slot)}" }
            val snapshot = "$nbt|$heldItems|$wornItems"
            if (reportedArmorStands.put(stand.uuid, snapshot) == snapshot) continue

            chatLog(
                "Armor stand ${stand.uuid} at ${stand.blockPosition()}:\n" +
                    "NBT: $nbt\nHeld items: $heldItems\nWorn items: $wornItems",
                force = true
            )
        }
    }

    private fun isInRange(position: Vec3): Boolean =
        position.x >= minOf(CHECK_RANGE_START.x, CHECK_RANGE_END.x) &&
            position.x <= maxOf(CHECK_RANGE_START.x, CHECK_RANGE_END.x) &&
            position.y >= minOf(CHECK_RANGE_START.y, CHECK_RANGE_END.y) &&
            position.y <= maxOf(CHECK_RANGE_START.y, CHECK_RANGE_END.y) &&
            position.z >= minOf(CHECK_RANGE_START.z, CHECK_RANGE_END.z) &&
            position.z <= maxOf(CHECK_RANGE_START.z, CHECK_RANGE_END.z)

    private fun chatLog(message: String, force: Boolean = false) {
        if (!force && !config.verbose) return

        val player = Minecraft.getInstance().player ?: return
        val component = Component.empty()
            .append(Component.literal("[").withStyle(ChatFormatting.LIGHT_PURPLE))
            .append(Component.literal("F").withStyle(ChatFormatting.RED))
            .append(Component.literal("o").withStyle(Style.EMPTY.withColor(LIGHT_GREEN_COLOR)))
            .append(Component.literal("x").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal("y").withStyle(ChatFormatting.AQUA))
            .append(Component.literal("] ").withStyle(Style.EMPTY.withColor(ORANGE_COLOR)))
            .append(message)
        try {
            val sendMessage = player.javaClass.methods.firstOrNull {
                it.name == "sendSystemMessage" && it.parameterCount == 1
            }
            if (sendMessage != null) {
                sendMessage.invoke(player, component)
                return
            }

            val displayMessage = player.javaClass.methods.firstOrNull {
                it.name == "displayClientMessage" && it.parameterCount == 2
            }
            if (displayMessage != null) {
                displayMessage.invoke(player, component, false)
                return
            }

            FoxyFabric.LOGGER.error("Could not find a chat message method for the current Minecraft version")
        } catch (exception: ReflectiveOperationException) {
            FoxyFabric.LOGGER.error("Could not send Catch A Fish status to chat", exception)
        }
    }

    private fun normalizeMessage(message: String): String =
        message.replace(COLOR_CODE_PATTERN, "")
            .trim()
            .replace(WHITESPACE_PATTERN, " ")

    private companion object {
        const val START_TRIGGER = "[NPC] Carnival Fisherman: Well, here goes..."
        const val END_TRIGGER = "                               Catch a Fish"
        val CHECK_RANGE_START = BlockPos(-84, 66, 51)
        val CHECK_RANGE_END = BlockPos(-70, 71, 34)
        const val LIGHT_GREEN_COLOR = 0x55FF55
        const val ORANGE_COLOR = 0xFFAA00
        val COLOR_CODE_PATTERN = Regex("§[0-9A-FK-ORa-fk-or]")
        val WHITESPACE_PATTERN = Regex("\\s+")
    }
}
