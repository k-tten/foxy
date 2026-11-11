package com.k_tten.foxy.fabric.game

import com.k_tten.foxy.fabric.FoxyFabric
import com.k_tten.foxy.fabric.config.ModConfig
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.block.Blocks
import java.util.Optional
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.random.Random

class FruitDigging(private val config: ModConfig) {
    private enum class Stage {
        IDLE,
        MOVE_TO_CENTER,
        AIM,
        MINE,
        WAIT_FOR_GROUND
    }

    private enum class AutostartStage {
        IDLE,
        WAIT_BEFORE_NPC,
        AIM_AT_NPC,
        WAIT_FOR_OPTION,
        WAIT_FOR_SHOVEL
    }

    private data class Rotation(
        val startYaw: Float,
        val startPitch: Float,
        val targetYaw: Float,
        val targetPitch: Float,
        val startedAt: Long
    )

    private val center = BlockPos(-109, 72, 22)
    private val blocks = linkedMapOf<BlockPos, Boolean>()
    private val blocksPresentAtStart = mutableMapOf<BlockPos, Boolean>()
    private val blockScores = mutableMapOf<BlockPos, Int>()
    private val blocksWithMineClue = mutableSetOf<BlockPos>()
    private var stage = Stage.IDLE
    private var autostartStage = AutostartStage.IDLE
    private var target: BlockPos? = null
    private var autostartNpc: Entity? = null
    private var autostartDeadline = 0L
    private var autostartOptionWithoutClickLogged = false
    private var autostartExpectedYaw = 0f
    private var autostartExpectedPitch = 0f
    private var autostartMouseX = 0.0
    private var autostartMouseY = 0.0
    private var lastMinedBlock: BlockPos? = null
    private var rotation: Rotation? = null
    private var movementRotationTarget: Float? = null
    private var centerMoveDelayStartedAt: Long? = null
    private var centerMoveStarted = false
    private var waitingForMouseLogged = false
    private var completedBlocks = 0
    private var manualAutostart = false

    fun register() {
        ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ ->
            handleIncomingComponent(message)
        }
        ClientReceiveMessageEvents.GAME.register { message, _ ->
            handleIncomingComponent(message)
        }
        ClientTickEvents.END_CLIENT_TICK.register(::tick)
    }

    private fun handleIncomingComponent(message: Component) {
        handleAutostartOption(message)
        handleMessage(message.string)
    }

    private fun handleMessage(message: String) {
        if (message.startsWith(LOG_PREFIX)) return

        val normalizedMessage = normalizeMessage(message)
        val minesMatch = MINES_MESSAGE_PATTERN.find(normalizedMessage)
        if (minesMatch != null) {
            handleMinesMessage(minesMatch.groupValues[1].toInt())
            return
        }

        if (normalizedMessage == normalizeMessage(END_TRIGGER)) {
            stop("Fruit Digging message received.")
            beginAutostart()
            return
        }

        if (normalizedMessage != normalizeMessage(START_TRIGGER)) {
            if (normalizedMessage.contains("shovel", ignoreCase = true) ||
                normalizedMessage.contains("Pirateman", ignoreCase = true)
            ) {
                chatLog("Trigger text did not match: '${message.take(MAX_LOG_MESSAGE_LENGTH)}'")
            }
            return
        }
        if (!config.fruitDigging) {
            chatLog("Shovel message ignored: Fruit Digging is disabled. Use /foxy set fruitdigging true.")
            return
        }
        if (stage != Stage.IDLE) return

        clearAutostart()
        val client = Minecraft.getInstance()
        val level = client.level
        if (client.player == null || level == null) {
            FoxyFabric.LOGGER.warn("Received the shovel message without an active player or world")
            chatLog("Cannot start: no active player or world.")
            return
        }
        chatLog("Shovel message matched. Closing chat and checking the center block.")
        closeChat(client)

        blocks.clear()
        blockScores.clear()
        blocksWithMineClue.clear()
        for (x in -112..-106) {
            for (z in 19..25) {
                val block = BlockPos(x, 72, z)
                blocks[block] = false
                blocksPresentAtStart[block] = level.getBlockState(block).block == Blocks.SAND
                blockScores[block] = 0
            }
        }
        if (blocksPresentAtStart[center] != true) {
            FoxyFabric.LOGGER.warn("The center block at $center is not sand; skipping the digging sequence")
            chatLog("Cannot start: center block $center is not sand.")
            blocks.clear()
            blocksPresentAtStart.clear()
            return
        }
        target = center
        lastMinedBlock = null
        completedBlocks = 0
        rotation = null
        movementRotationTarget = null
        centerMoveDelayStartedAt = null
        centerMoveStarted = false
        waitingForMouseLogged = false
        stage = Stage.MOVE_TO_CENTER
        chatLog("Center block found. Waiting for mouse control, then 2 seconds before moving.")
    }

    private fun tick(client: Minecraft) {
        if (autostartStage != AutostartStage.IDLE) {
            tickAutostart(client)
            if (stage == Stage.IDLE) return
        }

        if (!config.fruitDigging) {
            stop()
            return
        }
        if (stage == Stage.IDLE) return

        val player = client.player
        val level = client.level
        if (player == null || level == null) {
            stop("Sequence stopped: player or world unavailable.")
            return
        }
        if (!client.mouseHandler.isMouseGrabbed) {
            if (stage == Stage.MOVE_TO_CENTER) {
                client.options.keyUp.setDown(false)
                client.options.keyJump.setDown(false)
                centerMoveDelayStartedAt = null
                if (!waitingForMouseLogged) {
                    chatLog("Waiting for mouse control. Click the game window to continue.")
                    waitingForMouseLogged = true
                }
                return
            }
            stop("Sequence stopped: mouse control was lost.")
            return
        }
        if (waitingForMouseLogged) {
            chatLog("Mouse control acquired. Starting the 2-second delay.")
            waitingForMouseLogged = false
        }

        markNewlyBrokenBlocks(level)
        if (stage == Stage.AIM || stage == Stage.MINE || stage == Stage.WAIT_FOR_GROUND) {
            val activeTarget = target
            if (activeTarget != null && level.getBlockState(activeTarget).block != Blocks.SAND) {
                client.options.keyAttack.setDown(false)
                val nextTarget = chooseUnbrokenBlock(level)
                target = nextTarget
                if (nextTarget == null) {
                    stop("Finished: no more sand blocks to mine.")
                } else if (stage == Stage.WAIT_FOR_GROUND || !player.onGround()) {
                    stage = Stage.WAIT_FOR_GROUND
                    chatLog("Target $activeTarget is no longer sand. Waiting to target $nextTarget.")
                } else {
                    beginAim(player, nextTarget)
                    stage = Stage.AIM
                    chatLog("Target $activeTarget is no longer sand. Retargeting $nextTarget.")
                }
                return
            }
        }

        when (stage) {
            Stage.IDLE -> Unit
            Stage.MOVE_TO_CENTER -> {
                val delayStartedAt = centerMoveDelayStartedAt ?: System.nanoTime().also {
                    centerMoveDelayStartedAt = it
                }
                if (System.nanoTime() - delayStartedAt < CENTER_MOVE_DELAY_NANOS) {
                    client.options.keyUp.setDown(false)
                    client.options.keyJump.setDown(false)
                    return
                }
                if (!centerMoveStarted) {
                    chatLog("Moving toward center block $center.")
                    centerMoveStarted = true
                }

                val destination = Vec3(center.x + 0.5, player.y, center.z + 0.5)
                val dx = destination.x - player.x
                val dz = destination.z - player.z
                if (dx * dx + dz * dz <= ARRIVAL_DISTANCE_SQUARED &&
                    player.y >= center.y + 1.0 - VERTICAL_ARRIVAL_TOLERANCE
                ) {
                    client.options.keyUp.setDown(false)
                    client.options.keyJump.setDown(false)
                    beginAim(player, center)
                    stage = Stage.AIM
                    chatLog("Reached center area. Aiming at $center.")
                } else {
                    val desiredYaw = yawTo(dx, dz)
                    val currentMovementTarget = movementRotationTarget
                    if (rotation == null &&
                        (currentMovementTarget == null ||
                            abs(angleDifference(desiredYaw, currentMovementTarget)) >
                            MOVEMENT_ROTATION_RETARGET_DEGREES)
                    ) {
                        movementRotationTarget = desiredYaw
                        beginRotation(player, desiredYaw, player.xRot)
                    }
                    updateRotation(player)
                    client.options.keyUp.setDown(true)
                    client.options.keyJump.setDown(
                        player.onGround() && player.y < center.y + 1.0 - VERTICAL_ARRIVAL_TOLERANCE
                    )
                }
            }
            Stage.AIM -> {
                client.options.keyUp.setDown(false)
                client.options.keyJump.setDown(false)
                if (updateRotation(player)) {
                    stage = Stage.MINE
                    chatLog("Mining target ${target ?: "unknown"}.")
                }
            }
            Stage.MINE -> {
                client.options.keyJump.setDown(false)
                val block = target ?: return
                client.options.keyAttack.setDown(true)
                if (blocks[block] == true) {
                    client.options.keyAttack.setDown(false)
                    chatLog("Mined $block ($completedBlocks).")
                    val nextTarget = chooseUnbrokenBlock(level)
                    target = nextTarget
                    if (nextTarget == null) {
                        stop("Finished: no more present blocks to mine.")
                    } else if (player.onGround()) {
                        beginAim(player, nextTarget)
                        stage = Stage.AIM
                        chatLog("Next target: $nextTarget.")
                    } else {
                        stage = Stage.WAIT_FOR_GROUND
                        chatLog("Waiting to land before targeting $nextTarget.")
                    }
                }
            }
            Stage.WAIT_FOR_GROUND -> {
                client.options.keyAttack.setDown(false)
                client.options.keyJump.setDown(false)
                if (player.onGround()) {
                    val nextTarget = target
                    if (nextTarget == null) {
                        stop("Sequence stopped: next target is unavailable.")
                    } else {
                        beginAim(player, nextTarget)
                        stage = Stage.AIM
                        chatLog("Landed. Aiming at $nextTarget.")
                    }
                }
            }
        }
    }

    fun startAutostartManually(): Boolean {
        if (!config.fruitDigging || stage != Stage.IDLE ||
            autostartStage != AutostartStage.IDLE
        ) return false
        beginAutostart(manual = true)
        return autostartStage != AutostartStage.IDLE
    }

    private fun beginAutostart(manual: Boolean = false) {
        if ((!manual && !config.autostart) || !config.fruitDigging ||
            autostartStage != AutostartStage.IDLE
        ) return

        val client = Minecraft.getInstance()
        val player = client.player
        val level = client.level
        if (player == null || level == null) {
            chatLog("Autostart could not find an active player or world.")
            return
        }

        val npc = findCarnivalPirateman(level, player)
        if (npc == null) {
            chatLog("Autostart could not find Carnival Pirateman nearby.")
            return
        }

        autostartExpectedYaw = player.yRot
        autostartExpectedPitch = player.xRot
        autostartMouseX = client.mouseHandler.xpos()
        autostartMouseY = client.mouseHandler.ypos()
        autostartNpc = npc
        autostartStage = AutostartStage.WAIT_BEFORE_NPC
        autostartDeadline = System.nanoTime() + AUTOSTART_NPC_DELAY_NANOS
        autostartOptionWithoutClickLogged = false
        manualAutostart = manual
        chatLog("Autostart will interact with Carnival Pirateman in 3 seconds.")
    }

    private fun tickAutostart(client: Minecraft) {
        if ((!config.autostart && !manualAutostart) || !config.fruitDigging) {
            clearAutostart()
            return
        }

        val player = client.player
        if (player == null) {
            chatLog("Autostart cancelled: player is unavailable.")
            clearAutostart()
            return
        }
        if (hasUserTakenControl(client, player)) {
            chatLog("Autostart cancelled because you moved or changed your view.")
            clearAutostart()
            return
        }

        val now = System.nanoTime()
        if ((autostartStage == AutostartStage.WAIT_FOR_OPTION ||
                autostartStage == AutostartStage.WAIT_FOR_SHOVEL) &&
            autostartDeadline != 0L && now >= autostartDeadline
        ) {
            val reason = if (autostartStage == AutostartStage.WAIT_FOR_SHOVEL) {
                "Autostart timed out waiting for the shovel message."
            } else {
                "Autostart timed out waiting for the NPC option."
            }
            chatLog(reason)
            clearAutostart()
            return
        }

        when (autostartStage) {
            AutostartStage.IDLE,
            AutostartStage.WAIT_FOR_OPTION,
            AutostartStage.WAIT_FOR_SHOVEL -> Unit
            AutostartStage.WAIT_BEFORE_NPC -> {
                if (now < autostartDeadline) return

                val npc = autostartNpc
                if (npc == null || !npc.isAlive) {
                    chatLog("Autostart stopped: Carnival Pirateman is no longer available.")
                    clearAutostart()
                    return
                }
                val direction = npc.boundingBox.center.subtract(player.eyePosition)
                val horizontalDistance = sqrt(direction.x * direction.x + direction.z * direction.z)
                val yaw = yawTo(direction.x, direction.z)
                val pitch = Math.toDegrees(-atan2(direction.y, horizontalDistance)).toFloat()
                beginRotation(player, yaw, pitch)
                autostartStage = AutostartStage.AIM_AT_NPC
                autostartDeadline = 0L
                chatLog("Autostart is aiming at Carnival Pirateman.")
            }
            AutostartStage.AIM_AT_NPC -> {
                val npc = autostartNpc
                if (npc == null || !npc.isAlive) {
                    chatLog("Autostart stopped: Carnival Pirateman is no longer available.")
                    clearAutostart()
                    return
                }
                val rotationComplete = updateRotation(player)
                autostartExpectedYaw = player.yRot
                autostartExpectedPitch = player.xRot
                if (!rotationComplete) return

                KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(RIGHT_MOUSE_BUTTON))
                autostartStage = AutostartStage.WAIT_FOR_OPTION
                autostartDeadline = now + AUTOSTART_PROMPT_TIMEOUT_NANOS
                chatLog("Right-clicked Carnival Pirateman. Waiting for the autostart option.")
            }
        }
    }

    private fun hasUserTakenControl(client: Minecraft, player: LocalPlayer): Boolean {
        val options = client.options
        if (options.keyUp.isDown || options.keyDown.isDown ||
            options.keyLeft.isDown || options.keyRight.isDown ||
            options.keyJump.isDown || options.keyShift.isDown ||
            options.keySprint.isDown
        ) {
            return true
        }

        if (kotlin.math.abs(angleDifference(player.yRot, autostartExpectedYaw)) >
            AUTOSTART_LOOK_INPUT_THRESHOLD_DEGREES ||
            kotlin.math.abs(player.xRot - autostartExpectedPitch) >
            AUTOSTART_LOOK_INPUT_THRESHOLD_DEGREES
        ) {
            return true
        }

        if (!client.mouseHandler.isMouseGrabbed) {
            val mouseX = client.mouseHandler.xpos()
            val mouseY = client.mouseHandler.ypos()
            val mouseMoved = kotlin.math.abs(mouseX - autostartMouseX) > AUTOSTART_MOUSE_MOVE_THRESHOLD ||
                kotlin.math.abs(mouseY - autostartMouseY) > AUTOSTART_MOUSE_MOVE_THRESHOLD
            autostartMouseX = mouseX
            autostartMouseY = mouseY
            if (mouseMoved) return true
        } else {
            autostartMouseX = client.mouseHandler.xpos()
            autostartMouseY = client.mouseHandler.ypos()
        }

        return false
    }

    private fun handleAutostartOption(message: Component) {
        if (autostartStage != AutostartStage.WAIT_FOR_OPTION) return

        val combinedText = StringBuilder()
        val spans = mutableListOf<Triple<Int, Int, ClickEvent?>>()
        message.visit({ style, segment ->
            val start = combinedText.length
            combinedText.append(segment)
            spans += Triple(start, combinedText.length, style.clickEvent)
            Optional.empty<Any>()
        }, Style.EMPTY)

        val optionMatch = AUTOSTART_OPTION_PATTERN.find(combinedText.toString()) ?: return
        val clickEvent = spans.firstNotNullOfOrNull { (start, end, event) ->
            if (event != null && start < optionMatch.range.last + 1 && end > optionMatch.range.first) {
                event
            } else {
                null
            }
        }
        if (clickEvent == null) {
            if (!autostartOptionWithoutClickLogged) {
                chatLog("Found the autostart option, but it has no clickable action.")
                autostartOptionWithoutClickLogged = true
            }
            return
        }

        if (runChatClickEvent(clickEvent)) {
            autostartStage = AutostartStage.WAIT_FOR_SHOVEL
            autostartDeadline = System.nanoTime() + AUTOSTART_SHOVEL_TIMEOUT_NANOS
            chatLog("Selected the '$AUTOSTART_OPTION_TEXT' option.")
        } else {
            clearAutostart()
        }
    }

    private fun runChatClickEvent(clickEvent: ClickEvent): Boolean {
        try {
            val handleClick = Screen::class.java.getDeclaredMethod(
                "defaultHandleGameClickEvent",
                ClickEvent::class.java,
                Minecraft::class.java,
                Screen::class.java
            )
            if (!handleClick.trySetAccessible()) {
                FoxyFabric.LOGGER.error("Could not access Minecraft's chat click-event handler")
                chatLog("Autostart could not activate the NPC option.")
                return false
            }

            val client = Minecraft.getInstance()
            handleClick.invoke(null, clickEvent, client, currentScreen(client))
            return true
        } catch (exception: ReflectiveOperationException) {
            FoxyFabric.LOGGER.error("Could not activate the Fruit Digging NPC chat option", exception)
            chatLog("Autostart failed while selecting the NPC option.")
            return false
        }
    }

    private fun currentScreen(client: Minecraft): Screen? {
        val gui = client.gui
        val screenAccessor = gui.javaClass.methods.firstOrNull {
            it.name == "screen" && it.parameterCount == 0
        }
        val screen = if (screenAccessor != null) {
            screenAccessor.invoke(gui)
        } else {
            client.javaClass.getField("screen").get(client)
        }
        return screen as? Screen
    }

    private fun clearAutostart() {
        autostartStage = AutostartStage.IDLE
        autostartNpc = null
        autostartDeadline = 0L
        autostartOptionWithoutClickLogged = false
        manualAutostart = false
        rotation = null
    }

    private fun findCarnivalPirateman(
        level: net.minecraft.client.multiplayer.ClientLevel,
        player: LocalPlayer
    ): Entity? =
        level.entitiesForRendering()
            .filter { normalizeMessage(it.displayName.string).contains(CARNIVAL_PIRATEMAN_NAME, true) }
            .filter { player.distanceToSqr(it) <= NPC_SEARCH_RADIUS_SQUARED }
            .minByOrNull { player.distanceToSqr(it) }

    private fun chooseUnbrokenBlock(level: net.minecraft.client.multiplayer.ClientLevel): BlockPos? {
        val remaining = blocks.filter { (block, broken) ->
            !broken && blocksPresentAtStart[block] == true &&
                level.getBlockState(block).block == Blocks.SAND
        }.keys.toList()
        if (remaining.isEmpty()) return null

        val highestScore = remaining.maxOf { blockScores[it] ?: 0 }
        val highestScoring = remaining.filter { (blockScores[it] ?: 0) == highestScore }
        return highestScoring[Random.nextInt(highestScoring.size)]
    }

    private fun markNewlyBrokenBlocks(level: net.minecraft.client.multiplayer.ClientLevel) {
        for ((block, broken) in blocks) {
            if (!broken && blocksPresentAtStart[block] == true &&
                level.getBlockState(block).block == Blocks.SANDSTONE
            ) {
                blocks[block] = true
                completedBlocks++
                if (block == target && block !in blocksWithMineClue) lastMinedBlock = block
                chatLog("Detected mined block $block: ${level.getBlockState(block).block}.")
            }
        }
    }

    private fun handleMinesMessage(minesNearby: Int) {
        if (stage == Stage.IDLE) return

        val minedBlock = lastMinedBlock ?: target?.takeIf { stage == Stage.MINE }
        if (minedBlock == null) {
            chatLog("Received a mine clue, but could not identify the tile just mined.")
            return
        }
        if (!blocksWithMineClue.add(minedBlock)) return
        lastMinedBlock = null

        val neighbors = listOf(
            BlockPos(minedBlock.x - 1, minedBlock.y, minedBlock.z),
            BlockPos(minedBlock.x + 1, minedBlock.y, minedBlock.z),
            BlockPos(minedBlock.x, minedBlock.y, minedBlock.z - 1),
            BlockPos(minedBlock.x, minedBlock.y, minedBlock.z + 1)
        )
        for (neighbor in neighbors) {
            blockScores[neighbor] = (blockScores[neighbor] ?: 0) - minesNearby
        }
        chatLog("Mine clue for $minedBlock: penalized its 4 adjacent blocks by $minesNearby each.")
    }

    private fun closeChat(client: Minecraft) {
        val gui = client.gui
        val screenAccessor = gui.javaClass.methods.firstOrNull {
            it.name == "screen" && it.parameterCount == 0
        }
        val currentScreen = if (screenAccessor != null) {
            screenAccessor.invoke(gui)
        } else {
            client.javaClass.getField("screen").get(client)
        }
        if (currentScreen !is ChatScreen) return

        val screenOwner = if (screenAccessor != null) gui else client
        screenOwner.javaClass.getMethod("setScreen", Screen::class.java).invoke(screenOwner, null)
    }

    private fun beginAim(player: LocalPlayer, block: BlockPos) {
        movementRotationTarget = null
        val eyePosition = player.eyePosition
        val destination = Vec3(
            eyePosition.x.coerceIn(block.x + TOP_FACE_EDGE_MARGIN, block.x + 1.0 - TOP_FACE_EDGE_MARGIN),
            block.y + 1.0,
            eyePosition.z.coerceIn(block.z + TOP_FACE_EDGE_MARGIN, block.z + 1.0 - TOP_FACE_EDGE_MARGIN)
        )
        val direction = destination.subtract(player.eyePosition)
        val horizontalDistance = kotlin.math.sqrt(direction.x * direction.x + direction.z * direction.z)
        val yaw = yawTo(direction.x, direction.z)
        val pitch = Math.toDegrees(-kotlin.math.atan2(direction.y, horizontalDistance)).toFloat()
        beginRotation(player, yaw, pitch)
    }

    private fun beginRotation(player: LocalPlayer, yaw: Float, pitch: Float) {
        val startYaw = player.yRot
        rotation = Rotation(
            startYaw = startYaw,
            startPitch = player.xRot,
            targetYaw = startYaw + angleDifference(yaw, startYaw),
            targetPitch = pitch,
            startedAt = System.nanoTime()
        )
    }

    private fun updateRotation(player: LocalPlayer): Boolean {
        val activeRotation = rotation ?: return true
        val progress = ((System.nanoTime() - activeRotation.startedAt).toDouble() /
            ROTATION_DURATION_NANOS).coerceIn(0.0, 1.0).toFloat()
        val easedProgress = progress * progress * (3f - 2f * progress)
        player.setYRot(lerp(activeRotation.startYaw, activeRotation.targetYaw, easedProgress))
        player.setXRot(lerp(activeRotation.startPitch, activeRotation.targetPitch, easedProgress))
        if (progress < 1f) return false
        rotation = null
        return true
    }

    private fun stop(reason: String = "Sequence stopped.") {
        if (stage == Stage.IDLE) return

        chatLog(reason)
        val client = Minecraft.getInstance()
        client.options.keyAttack.setDown(false)
        client.options.keyUp.setDown(false)
        client.options.keyJump.setDown(false)
        stage = Stage.IDLE
        target = null
        rotation = null
        movementRotationTarget = null
        centerMoveDelayStartedAt = null
        centerMoveStarted = false
        waitingForMouseLogged = false
        blocks.clear()
        blocksPresentAtStart.clear()
        blockScores.clear()
        blocksWithMineClue.clear()
        lastMinedBlock = null
        completedBlocks = 0
    }

    private fun chatLog(message: String) {
        if (!config.verbose) return

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
            val systemMessageMethod = player.javaClass.methods.firstOrNull {
                it.name == "sendSystemMessage" && it.parameterCount == 1
            }
            if (systemMessageMethod != null) {
                systemMessageMethod.invoke(player, component)
                return
            }

            val clientMessageMethod = player.javaClass.methods.firstOrNull {
                it.name == "displayClientMessage" && it.parameterCount == 2
            }
            if (clientMessageMethod != null) {
                clientMessageMethod.invoke(player, component, false)
                return
            }

            FoxyFabric.LOGGER.error("Could not find a chat message method for the current Minecraft version")
        } catch (exception: ReflectiveOperationException) {
            FoxyFabric.LOGGER.error("Could not send an automation log message to chat", exception)
        }
    }

    private fun normalizeMessage(message: String): String =
        message.replace(COLOR_CODE_PATTERN, "")
            .trim()
            .replace(WHITESPACE_PATTERN, " ")

    private fun yawTo(dx: Double, dz: Double): Float =
        Math.toDegrees(kotlin.math.atan2(-dx, dz)).toFloat()

    private fun angleDifference(target: Float, current: Float): Float =
        ((target - current + 540f) % 360f) - 180f

    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress

    private companion object {
        const val START_TRIGGER = "[NPC] Carnival Pirateman: Here's yer shovel, then."
        const val END_TRIGGER = "                               Fruit Digging"
        const val AUTOSTART_OPTION_TEXT = "Aye sure do!"
        const val RIGHT_MOUSE_BUTTON = 1
        val AUTOSTART_OPTION_PATTERN = Regex(
            """\[\s*${Regex.escape(AUTOSTART_OPTION_TEXT)}\s*\]""",
            RegexOption.IGNORE_CASE
        )
        const val CARNIVAL_PIRATEMAN_NAME = "Carnival Pirateman"
        const val NPC_SEARCH_RADIUS_SQUARED = 25.0
        const val AUTOSTART_NPC_DELAY_NANOS = 3_000_000_000L
        const val AUTOSTART_PROMPT_TIMEOUT_NANOS = 10_000_000_000L
        const val AUTOSTART_SHOVEL_TIMEOUT_NANOS = 15_000_000_000L
        const val AUTOSTART_LOOK_INPUT_THRESHOLD_DEGREES = 0.25f
        const val AUTOSTART_MOUSE_MOVE_THRESHOLD = 0.5
        val MINES_MESSAGE_PATTERN = Regex(
            """MINES!\s+There\s+(?:is|are)\s+(\d+)\s+bombs?\s+hidden\s+nearby\.?""",
            RegexOption.IGNORE_CASE
        )
        const val ARRIVAL_DISTANCE_SQUARED = 0.04
        const val VERTICAL_ARRIVAL_TOLERANCE = 0.1
        const val MOVEMENT_ROTATION_RETARGET_DEGREES = 5f
        const val TOP_FACE_EDGE_MARGIN = 0.05
        const val CENTER_MOVE_DELAY_NANOS = 2_000_000_000L
        const val MAX_LOG_MESSAGE_LENGTH = 120
        const val LOG_PREFIX = "[Foxy]"
        const val LIGHT_GREEN_COLOR = 0x55FF55
        const val ORANGE_COLOR = 0xFFAA00
        val COLOR_CODE_PATTERN = Regex("§[0-9A-FK-ORa-fk-or]")
        val WHITESPACE_PATTERN = Regex("\\s+")
        const val ROTATION_DURATION_NANOS = 200_000_000L
    }
}
