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
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import java.util.Base64
import java.util.Optional
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.sqrt

class CatchAFish(private val config: ModConfig) {
    private enum class AutostartStage {
        IDLE,
        WAIT_BEFORE_NPC,
        AIM_AT_NPC,
        WAIT_FOR_OPTION,
        WAIT_FOR_START
    }

    private enum class ApproachStage {
        WALK_TWO_BLOCKS,
        SNEAK_TO_EDGE,
        READY
    }

    private enum class Phase {
        CAST,
        WAIT_FOR_HIT,
        REEL
    }

    private data class Target(
        val stand: ArmorStand,
        val priority: Int
    )

    private data class TargetMotion(
        val position: Vec3,
        val sampledAt: Long,
        val velocity: Vec3
    )

    private data class AutostartRotation(
        val startYaw: Float,
        val startPitch: Float,
        val targetYaw: Float,
        val targetPitch: Float,
        val startedAt: Long
    )

    private var active = false
    private var autostartStage = AutostartStage.IDLE
    private var autostartNpc: Entity? = null
    private var autostartDeadline = 0L
    private var autostartRotation: AutostartRotation? = null
    private var autostartExpectedYaw = 0f
    private var autostartExpectedPitch = 0f
    private var autostartOptionWithoutClickLogged = false
    private var manualAutostart = false
    private var waitingForMouseLogged = false
    private var mouseControlAcquired = false
    private var phase = Phase.CAST
    private var activeTargetId: UUID? = null
    private var aimedTargetId: UUID? = null
    private var rotationStartedAt = 0L
    private var rotationStartYaw = 0f
    private var rotationStartPitch = 0f
    private var rotationComplete = false
    private var nextReelAt = 0L
    private var preparingCast = false
    private var nextPrepareClickAt = 0L
    private var castTargetPosition: Vec3? = null
    private var approachStage = ApproachStage.WALK_TWO_BLOCKS
    private var approachStartPosition: Vec3? = null
    private var approachYaw = 0f
    private var startupDeadline = 0L
    private var textDisplayAccessWarningLogged = false
    private val targetMotion = mutableMapOf<UUID, TargetMotion>()

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
        when (normalizeMessage(message)) {
            normalizeMessage(START_TRIGGER) -> {
                clearAutostart()
                start()
            }
            normalizeMessage(END_TRIGGER) -> {
                stop("End trigger received.")
                beginAutostart()
            }
        }
    }

    fun startAutostartManually(): Boolean {
        if (!config.catchAFish || active || autostartStage != AutostartStage.IDLE) return false
        beginAutostart(manual = true)
        return autostartStage != AutostartStage.IDLE
    }

    private fun beginAutostart(manual: Boolean = false) {
        if ((!manual && !config.autostart) || !config.catchAFish ||
            active || autostartStage != AutostartStage.IDLE
        ) return

        val client = Minecraft.getInstance()
        val player = client.player
        val level = client.level
        if (player == null || level == null) {
            chatLog("Autostart could not find an active player or world.")
            return
        }

        val npc = findCarnivalFisherman(level, player)
        if (npc == null) {
            chatLog("Autostart could not find Carnival Fisherman nearby.")
            return
        }

        try {
            closeChat(client)
        } catch (exception: ReflectiveOperationException) {
            FoxyFabric.LOGGER.error("Could not close chat before Catch A Fish autostart", exception)
            chatLog("Autostart failed: could not close chat.")
            return
        }

        autostartNpc = npc
        autostartExpectedYaw = player.yRot
        autostartExpectedPitch = player.xRot
        autostartStage = AutostartStage.WAIT_BEFORE_NPC
        autostartDeadline = System.nanoTime() + AUTOSTART_NPC_DELAY_NANOS
        autostartOptionWithoutClickLogged = false
        manualAutostart = manual
        chatLog("Autostart will interact with Carnival Fisherman in 3 seconds.")
    }

    private fun start() {
        if (!config.catchAFish) {
            chatLog("Start trigger ignored: Catch A Fish is disabled.")
            return
        }
        if (active) return

        val client = Minecraft.getInstance()
        if (client.player == null || client.level == null) {
            chatLog("Cannot start: player or world is unavailable.")
            return
        }
        try {
            closeChat(client)
        } catch (exception: ReflectiveOperationException) {
            FoxyFabric.LOGGER.error("Could not close chat before starting Catch A Fish", exception)
            chatLog("Could not close chat. Catch A Fish was not started.")
            return
        }

        active = true
        waitingForMouseLogged = false
        mouseControlAcquired = false
        activeTargetId = null
        resetAim()
        phase = Phase.CAST
        preparingCast = false
        nextReelAt = 0L
        castTargetPosition = null
        approachStage = ApproachStage.WALK_TWO_BLOCKS
        approachStartPosition = null
        startupDeadline = System.nanoTime() + STARTUP_DELAY_NANOS
        targetMotion.clear()
        chatLog("Start trigger received. Waiting 2 seconds before moving to the fishing edge.")
    }

    private fun stop(reason: String) {
        if (!active) return
        active = false
        releaseMovementKeys()
        startupDeadline = 0L
        activeTargetId = null
        targetMotion.clear()
        phase = Phase.CAST
        preparingCast = false
        nextReelAt = 0L
        castTargetPosition = null
        waitingForMouseLogged = false
        mouseControlAcquired = false
        approachStage = ApproachStage.WALK_TWO_BLOCKS
        approachStartPosition = null
        resetAim()
        chatLog("Catch A Fish stopped. $reason")
    }

    private fun tick(client: Minecraft) {
        if (autostartStage != AutostartStage.IDLE) tickAutostart(client)
        if (!active) return
        if (!config.catchAFish) {
            stop("Catch A Fish was disabled.")
            return
        }

        val player = client.player
        val level = client.level
        if (player == null || level == null) {
            stop("Player or world became unavailable.")
            return
        }

        if (startupDeadline != 0L) {
            if (System.nanoTime() < startupDeadline) return
            startupDeadline = 0L
        }

        if (!client.mouseHandler.isMouseGrabbed) {
            if (mouseControlAcquired) {
                stop("Mouse control was lost.")
                return
            }
            if (!waitingForMouseLogged) {
                chatLog("Waiting for mouse control. Click the game window to continue.")
                waitingForMouseLogged = true
            }
            return
        }
        if (waitingForMouseLogged) {
            chatLog("Mouse control acquired. Approaching the fishing edge.")
            waitingForMouseLogged = false
        }
        mouseControlAcquired = true

        if (approachStage != ApproachStage.READY) {
            approachToEdge(client, player, level)
            return
        }

        val entities = level.entitiesForRendering().toList()
        val stands = entities.filterIsInstance<ArmorStand>()
        val targets = findTargets(stands, player)
        val now = System.nanoTime()
        updateTargetMotion(targets, now)
        val targetById = targets.associateBy { it.stand.uuid }
        val currentId = activeTargetId
        if (currentId != null && currentId !in targetById) {
            chatLog("Target $currentId disappeared.")
            activeTargetId = null
            phase = Phase.CAST
            preparingCast = false
            castTargetPosition = null
            resetAim()
        }

        if (activeTargetId == null) {
            activeTargetId = targets.firstOrNull()?.stand?.uuid
            if (activeTargetId != null) {
                phase = Phase.CAST
                preparingCast = false
                castTargetPosition = null
                resetAim()
            }
        }
        val activeTarget = activeTargetId?.let(targetById::get)
        if (activeTarget == null) return

        val bobber = player.fishing
        if (phase == Phase.WAIT_FOR_HIT &&
            isBobberAttachedToFish(bobber, activeTarget.stand, entities)
        ) {
            phase = Phase.REEL
            nextReelAt = now
            castTargetPosition = null
            chatLog("Bobber attachment indicator found on ${describe(activeTarget)}. Reeling.")
        } else if (phase == Phase.WAIT_FOR_HIT &&
            castTargetPosition?.distanceTo(activeTarget.stand.position())
                ?.let { it > MISSED_CAST_MOVEMENT_DISTANCE } == true
        ) {
            phase = Phase.CAST
            castTargetPosition = null
            preparingCast = false
            resetAim()
            chatLog(
                "${describe(activeTarget)} moved more than 1 block after the cast. " +
                    "Retrying toward its updated position."
            )
        }

        val aimTarget = when (phase) {
            Phase.REEL -> targets.firstOrNull { it.stand.uuid != activeTarget.stand.uuid } ?: activeTarget
            Phase.CAST, Phase.WAIT_FOR_HIT -> activeTarget
        }
        val aimComplete = updateAim(player, aimTarget.stand, now)

        when (phase) {
            Phase.CAST -> {
                if (!aimComplete) return
                if (!hasFishingRod(player)) {
                    stop("No fishing rod is held.")
                    return
                }

                if (bobber != null) {
                    if (!preparingCast || now >= nextPrepareClickAt) {
                        clickUse()
                        preparingCast = true
                        nextPrepareClickAt = now + REEL_INTERVAL_NANOS
                        chatLog("Clearing the previous bobber before casting at ${describe(activeTarget)}.")
                    }
                    return
                }

                clickUse()
                preparingCast = false
                phase = Phase.WAIT_FOR_HIT
                castTargetPosition = activeTarget.stand.position()
                chatLog("Cast toward ${describe(activeTarget)}.")
            }
            Phase.WAIT_FOR_HIT -> Unit
            Phase.REEL -> {
                if (now >= nextReelAt) {
                    clickUse()
                    nextReelAt = now + REEL_INTERVAL_NANOS
                }
            }
        }
    }

    private fun tickAutostart(client: Minecraft) {
        if ((!config.autostart && !manualAutostart) || !config.catchAFish) {
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
                autostartStage == AutostartStage.WAIT_FOR_START) &&
            autostartDeadline != 0L && now >= autostartDeadline
        ) {
            val reason = if (autostartStage == AutostartStage.WAIT_FOR_START) {
                "Autostart timed out waiting for the Catch A Fish start message."
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
            AutostartStage.WAIT_FOR_START -> Unit
            AutostartStage.WAIT_BEFORE_NPC -> {
                if (now < autostartDeadline) return

                val npc = autostartNpc
                if (npc == null || !npc.isAlive) {
                    chatLog("Autostart stopped: Carnival Fisherman is no longer available.")
                    clearAutostart()
                    return
                }
                val direction = npc.boundingBox.center.subtract(player.eyePosition)
                val horizontalDistance = sqrt(direction.x * direction.x + direction.z * direction.z)
                val yaw = yawTo(direction.x, direction.z)
                val pitch = Math.toDegrees(-atan2(direction.y, horizontalDistance)).toFloat()
                beginAutostartRotation(player, yaw, pitch, now)
                autostartStage = AutostartStage.AIM_AT_NPC
                autostartDeadline = 0L
                chatLog("Autostart is aiming at Carnival Fisherman.")
            }
            AutostartStage.AIM_AT_NPC -> {
                val npc = autostartNpc
                if (npc == null || !npc.isAlive) {
                    chatLog("Autostart stopped: Carnival Fisherman is no longer available.")
                    clearAutostart()
                    return
                }
                if (!updateAutostartRotation(player, now)) return

                KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(RIGHT_MOUSE_BUTTON))
                autostartStage = AutostartStage.WAIT_FOR_OPTION
                autostartDeadline = now + AUTOSTART_PROMPT_TIMEOUT_NANOS
                chatLog("Right-clicked Carnival Fisherman. Waiting for the autostart option.")
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

        val viewChanged = client.mouseHandler.isMouseGrabbed &&
            (kotlin.math.abs(angleDifference(player.yRot, autostartExpectedYaw)) >
                AUTOSTART_LOOK_INPUT_THRESHOLD_DEGREES ||
                kotlin.math.abs(player.xRot - autostartExpectedPitch) >
                AUTOSTART_LOOK_INPUT_THRESHOLD_DEGREES)
        autostartExpectedYaw = player.yRot
        autostartExpectedPitch = player.xRot
        return viewChanged
    }

    private fun beginAutostartRotation(
        player: LocalPlayer,
        yaw: Float,
        pitch: Float,
        now: Long
    ) {
        val startYaw = player.yRot
        autostartRotation = AutostartRotation(
            startYaw = startYaw,
            startPitch = player.xRot,
            targetYaw = startYaw + angleDifference(yaw, startYaw),
            targetPitch = pitch,
            startedAt = now
        )
    }

    private fun updateAutostartRotation(player: LocalPlayer, now: Long): Boolean {
        val rotation = autostartRotation ?: return true
        val progress = ((now - rotation.startedAt).toDouble() /
            AUTOSTART_ROTATION_DURATION_NANOS).coerceIn(0.0, 1.0).toFloat()
        val easedProgress = progress * progress * (3f - 2f * progress)
        player.setYRot(lerp(rotation.startYaw, rotation.targetYaw, easedProgress))
        player.setXRot(lerp(rotation.startPitch, rotation.targetPitch, easedProgress))
        autostartExpectedYaw = player.yRot
        autostartExpectedPitch = player.xRot
        if (progress < 1f) return false
        autostartRotation = null
        return true
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
            autostartStage = AutostartStage.WAIT_FOR_START
            autostartDeadline = System.nanoTime() + AUTOSTART_START_TIMEOUT_NANOS
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
            FoxyFabric.LOGGER.error("Could not activate the Catch A Fish NPC chat option", exception)
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
        autostartRotation = null
        autostartOptionWithoutClickLogged = false
        manualAutostart = false
    }

    private fun findCarnivalFisherman(
        level: net.minecraft.client.multiplayer.ClientLevel,
        player: LocalPlayer
    ): Entity? =
        level.entitiesForRendering()
            .filter { normalizeMessage(it.displayName.string).contains(CARNIVAL_FISHERMAN_NAME, true) }
            .filter { player.distanceToSqr(it) <= NPC_SEARCH_RADIUS_SQUARED }
            .minByOrNull { player.distanceToSqr(it) }

    private fun approachToEdge(
        client: Minecraft,
        player: LocalPlayer,
        level: net.minecraft.client.multiplayer.ClientLevel
    ) {
        if (!player.onGround()) {
            releaseMovementKeys(client)
            return
        }

        val start = approachStartPosition
        if (start == null) {
            approachStartPosition = player.position()
            approachYaw = player.yRot
            chatLog("Walking forward about 2 blocks before sneaking to the edge.")
        }

        val initialPosition = approachStartPosition ?: return
        val forwardX = -kotlin.math.sin(Math.toRadians(approachYaw.toDouble()))
        val forwardZ = kotlin.math.cos(Math.toRadians(approachYaw.toDouble()))

        if (approachStage == ApproachStage.WALK_TWO_BLOCKS) {
            val walked = (player.x - initialPosition.x) * forwardX +
                (player.z - initialPosition.z) * forwardZ
            if (walked >= WALK_DISTANCE_BLOCKS) {
                approachStage = ApproachStage.SNEAK_TO_EDGE
                chatLog("Walked about 2 blocks. Sneaking toward the edge.")
            } else if (!hasSupportAt(
                    player,
                    level,
                    forwardX,
                    forwardZ,
                    player.boundingBox.xsize * 0.5 + WALK_EDGE_PROBE_MARGIN
                )
            ) {
                approachStage = ApproachStage.SNEAK_TO_EDGE
                chatLog("Approaching the edge. Switching to sneak for safety.")
            }
        }

        if (approachStage == ApproachStage.SNEAK_TO_EDGE &&
            !hasSupportAt(
                player,
                level,
                forwardX,
                forwardZ,
                player.boundingBox.xsize * 0.5 + SNEAK_EDGE_PROBE_MARGIN
            )
        ) {
            approachStage = ApproachStage.READY
            releaseMovementKeys(client)
            chatLog("Reached the edge while sneaking. Stopped and released sneak.")
            return
        }

        client.options.keyUp.setDown(true)
        client.options.keyShift.setDown(approachStage == ApproachStage.SNEAK_TO_EDGE)
    }

    private fun hasSupportAt(
        player: LocalPlayer,
        level: net.minecraft.client.multiplayer.ClientLevel,
        forwardX: Double,
        forwardZ: Double,
        forwardDistance: Double
    ): Boolean {
        val halfWidth = player.boundingBox.xsize * 0.5
        val rightX = forwardZ
        val rightZ = -forwardX
        val footY = player.y - FOOT_SUPPORT_PROBE_OFFSET
        val sideOffsets = listOf(-halfWidth * SUPPORT_SAMPLE_SPREAD, 0.0, halfWidth * SUPPORT_SAMPLE_SPREAD)
        return sideOffsets.all { sideOffset ->
            val x = player.x + forwardX * forwardDistance + rightX * sideOffset
            val z = player.z + forwardZ * forwardDistance + rightZ * sideOffset
            val supportPos = BlockPos.containing(x, footY, z)
            !level.getBlockState(supportPos).getCollisionShape(level, supportPos).isEmpty
        }
    }

    private fun releaseMovementKeys(client: Minecraft = Minecraft.getInstance()) {
        client.options.keyUp.setDown(false)
        client.options.keyShift.setDown(false)
    }

    private fun findTargets(stands: List<ArmorStand>, player: LocalPlayer): List<Target> =
        stands.asSequence()
            .filter { it.isAlive && isInRange(it.position()) && player.hasLineOfSight(it) }
            .mapNotNull { stand ->
                val encoded = stand.getItemBySlot(EquipmentSlot.HEAD)
                    .get(DataComponents.PROFILE)
                    ?.partialProfile()
                    ?.properties()
                    ?.get("textures")
                    ?.firstOrNull()
                    ?.value()
                    ?: return@mapNotNull null
                val textureHash = textureHash(encoded) ?: return@mapNotNull null
                val priority = when (textureHash) {
                    YELLOW_FISH_HASH -> YELLOW_PRIORITY
                    RED_FISH_HASH -> RED_PRIORITY
                    BLUE_FISH_HASH -> BLUE_PRIORITY
                    else -> return@mapNotNull null
                }
                Target(stand, priority)
            }
            .sortedWith(compareBy<Target> { it.priority }.thenBy { it.stand.uuid })
            .toList()

    private fun updateTargetMotion(targets: List<Target>, now: Long) {
        val presentIds = targets.mapTo(mutableSetOf()) { it.stand.uuid }
        targetMotion.keys.retainAll(presentIds)

        for (target in targets) {
            val stand = target.stand
            val position = stand.position()
            val previous = targetMotion[stand.uuid]
            val velocity = if (previous == null) {
                Vec3.ZERO
            } else {
                val elapsed = (now - previous.sampledAt).toDouble() / NANOS_PER_SECOND
                if (elapsed <= 0.0 || elapsed > MAX_MOTION_SAMPLE_GAP_SECONDS) {
                    Vec3.ZERO
                } else {
                    val measured = position.subtract(previous.position).scale(1.0 / elapsed)
                    limitTargetVelocity(
                        previous.velocity.scale(TARGET_VELOCITY_SMOOTHING)
                            .add(measured.scale(1.0 - TARGET_VELOCITY_SMOOTHING))
                    )
                }
            }
            targetMotion[stand.uuid] = TargetMotion(position, now, velocity)
        }
    }

    private fun limitTargetVelocity(velocity: Vec3): Vec3 {
        val speed = velocity.length()
        return if (speed > MAX_TARGET_SPEED_BLOCKS_PER_SECOND) {
            velocity.scale(MAX_TARGET_SPEED_BLOCKS_PER_SECOND / speed)
        } else {
            velocity
        }
    }

    private fun isBobberAttachedToFish(
        bobber: net.minecraft.world.entity.projectile.FishingHook?,
        stand: ArmorStand,
        entities: List<net.minecraft.world.entity.Entity>
    ): Boolean {
        if (bobber?.hookedIn === stand) return true

        val standName = stand.customName?.string.orEmpty()
        if (standName.isNotBlank()) return true

        return entities.asSequence()
            .filterIsInstance<Display.TextDisplay>()
            .filter { it.position().distanceToSqr(stand.position()) <= HIT_TEXT_RADIUS_SQUARED }
            .mapNotNull(::readTextDisplayText)
            .any { it.isNotBlank() && HIT_PROGRESS_TEXT_PATTERN.containsMatchIn(it) }
    }

    private fun readTextDisplayText(display: Display.TextDisplay): String? =
        try {
            val getter = display.javaClass.getDeclaredMethod("getText")
            if (!getter.trySetAccessible()) {
                if (!textDisplayAccessWarningLogged) {
                    FoxyFabric.LOGGER.warn("Could not access Catch A Fish progress text")
                    textDisplayAccessWarningLogged = true
                }
                return null
            }
            (getter.invoke(display) as? Component)?.string
        } catch (exception: ReflectiveOperationException) {
            if (!textDisplayAccessWarningLogged) {
                FoxyFabric.LOGGER.warn("Could not read Catch A Fish progress text", exception)
                textDisplayAccessWarningLogged = true
            }
            null
        }

    private fun textureHash(encodedProfile: String): String? =
        try {
            val profile = String(Base64.getDecoder().decode(encodedProfile), Charsets.UTF_8)
            TEXTURE_URL_PATTERN.find(profile)?.groupValues?.get(1)
        } catch (exception: IllegalArgumentException) {
            FoxyFabric.LOGGER.warn("Could not decode a Catch A Fish armor-stand texture profile", exception)
            null
        }

    private fun updateAim(player: LocalPlayer, stand: ArmorStand, now: Long): Boolean {
        val targetId = stand.uuid
        val aimPoint = aimPoint(player, stand)
        val direction = aimPoint.subtract(player.eyePosition)
        val horizontalDistance = sqrt(direction.x * direction.x + direction.z * direction.z)
        val targetYaw = yawTo(direction.x, direction.z)
        val targetPitch = Math.toDegrees(-atan2(direction.y, horizontalDistance)).toFloat()

        if (aimedTargetId != targetId ||
            (rotationComplete && (
                kotlin.math.abs(angleDifference(targetYaw, player.yRot)) > AIM_RETARGET_THRESHOLD_DEGREES ||
                    kotlin.math.abs(targetPitch - player.xRot) > AIM_RETARGET_THRESHOLD_DEGREES
                ))
        ) {
            aimedTargetId = targetId
            rotationStartedAt = now
            rotationStartYaw = player.yRot
            rotationStartPitch = player.xRot
            rotationComplete = false
        }

        val progress = ((now - rotationStartedAt).toDouble() /
            ROTATION_DURATION_NANOS).coerceIn(0.0, 1.0).toFloat()
        val easedProgress = progress * progress * (3f - 2f * progress)
        val finalYaw = rotationStartYaw + angleDifference(targetYaw, rotationStartYaw)
        player.setYRot(lerp(rotationStartYaw, finalYaw, easedProgress))
        player.setXRot(lerp(rotationStartPitch, targetPitch, easedProgress))
        if (progress < 1f) return false

        rotationComplete = true
        return true
    }

    private fun aimPoint(player: LocalPlayer, stand: ArmorStand): Vec3 {
        val headHeight = if (stand.isSmall) SMALL_ARMOR_STAND_HEAD_HEIGHT else ARMOR_STAND_HEAD_HEIGHT
        val distance = player.eyePosition.distanceTo(stand.position())
        val aboveHead = (AIM_ABOVE_HEAD_BASE + distance * AIM_ABOVE_HEAD_PER_BLOCK)
            .coerceAtMost(AIM_ABOVE_HEAD_MAX)
        val motion = targetMotion[stand.uuid]
        val travelTime = (distance / BOBBER_SPEED_BLOCKS_PER_SECOND + ROTATION_DURATION_SECONDS)
            .coerceAtMost(MAX_TARGET_PREDICTION_SECONDS)
        val predictedPosition = motion?.position?.add(motion.velocity.scale(travelTime))
            ?: stand.position()
        return Vec3(
            predictedPosition.x,
            predictedPosition.y + headHeight + aboveHead,
            predictedPosition.z
        )
    }

    private fun hasFishingRod(player: LocalPlayer): Boolean =
        player.mainHandItem.`is`(Items.FISHING_ROD) ||
            player.offhandItem.`is`(Items.FISHING_ROD)

    private fun clickUse() {
        KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(RIGHT_MOUSE_BUTTON))
    }

    private fun resetAim() {
        aimedTargetId = null
        rotationStartedAt = 0L
        rotationComplete = false
    }

    private fun describe(target: Target): String =
        "${fishColor(target.priority)} fish armor stand ${target.stand.uuid}"

    private fun fishColor(priority: Int): String = when (priority) {
        YELLOW_PRIORITY -> "yellow"
        RED_PRIORITY -> "red"
        BLUE_PRIORITY -> "blue"
        else -> "unknown"
    }

    private fun isInRange(position: Vec3): Boolean =
        position.x >= minOf(CHECK_RANGE_START.x, CHECK_RANGE_END.x) &&
            position.x <= maxOf(CHECK_RANGE_START.x, CHECK_RANGE_END.x) &&
            position.y >= minOf(CHECK_RANGE_START.y, CHECK_RANGE_END.y) &&
            position.y <= maxOf(CHECK_RANGE_START.y, CHECK_RANGE_END.y) &&
            position.z >= minOf(CHECK_RANGE_START.z, CHECK_RANGE_END.z) &&
            position.z <= maxOf(CHECK_RANGE_START.z, CHECK_RANGE_END.z)

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

    private fun yawTo(dx: Double, dz: Double): Float =
        Math.toDegrees(atan2(-dx, dz)).toFloat()

    private fun angleDifference(target: Float, current: Float): Float =
        ((target - current + 540f) % 360f) - 180f

    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress

    private companion object {
        const val BLUE_FISH_HASH = "a62ecb1c3fafb766b234591ad595ba196a578787844d27ae6a4c12bc19073297"
        const val RED_FISH_HASH = "92d59817c9264185e74387463f226bebfbb15843aa976ab1fc9821136eea39bf"
        const val YELLOW_FISH_HASH = "360f4f99c78adddeab276ebdf6ab9a0fcffabd49f0b543e1981ac7bd3d55b18a"
        const val START_TRIGGER = "[NPC] Carnival Fisherman: Well, here goes..."
        const val END_TRIGGER = "                               Catch a Fish"
        const val AUTOSTART_OPTION_TEXT = "You guessed it!"
        const val CARNIVAL_FISHERMAN_NAME = "Carnival Fisherman"
        const val NPC_SEARCH_RADIUS_SQUARED = 25.0
        const val AUTOSTART_NPC_DELAY_NANOS = 3_000_000_000L
        const val AUTOSTART_PROMPT_TIMEOUT_NANOS = 10_000_000_000L
        const val AUTOSTART_START_TIMEOUT_NANOS = 15_000_000_000L
        const val AUTOSTART_ROTATION_DURATION_NANOS = 200_000_000L
        const val AUTOSTART_LOOK_INPUT_THRESHOLD_DEGREES = 2.0f
        val AUTOSTART_OPTION_PATTERN = Regex(
            """\[\s*${Regex.escape(AUTOSTART_OPTION_TEXT)}\s*\]""",
            RegexOption.IGNORE_CASE
        )
        val CHECK_RANGE_START = BlockPos(-84, 66, 51)
        val CHECK_RANGE_END = BlockPos(-70, 70, 34)
        const val YELLOW_PRIORITY = 0
        const val BLUE_PRIORITY = 1
        const val RED_PRIORITY = 2
        const val RIGHT_MOUSE_BUTTON = 1
        const val ROTATION_DURATION_NANOS = 150_000_000L
        const val MISSED_CAST_MOVEMENT_DISTANCE = 1.0
        const val STARTUP_DELAY_NANOS = 2_000_000_000L
        const val REEL_INTERVAL_NANOS = 200_000_000L
        const val WALK_DISTANCE_BLOCKS = 2.5
        const val WALK_EDGE_PROBE_MARGIN = 0.45
        const val SNEAK_EDGE_PROBE_MARGIN = 0.02
        const val FOOT_SUPPORT_PROBE_OFFSET = 0.05
        const val SUPPORT_SAMPLE_SPREAD = 0.8
        const val ARMOR_STAND_HEAD_HEIGHT = 1.6
        const val SMALL_ARMOR_STAND_HEAD_HEIGHT = 0.9
        const val AIM_ABOVE_HEAD_BASE = 0.06
        const val AIM_ABOVE_HEAD_PER_BLOCK = 0.01
        const val AIM_ABOVE_HEAD_MAX = 0.35
        const val AIM_RETARGET_THRESHOLD_DEGREES = 0.35f
        const val BOBBER_SPEED_BLOCKS_PER_SECOND = 24.0
        const val MAX_TARGET_PREDICTION_SECONDS = 2.0
        const val MAX_TARGET_SPEED_BLOCKS_PER_SECOND = 12.0
        const val MAX_MOTION_SAMPLE_GAP_SECONDS = 1.0
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val TARGET_VELOCITY_SMOOTHING = 0.5
        const val ROTATION_DURATION_SECONDS = 0.15
        const val HIT_TEXT_RADIUS_SQUARED = 2.25
        const val LIGHT_GREEN_COLOR = 0x55FF55
        const val ORANGE_COLOR = 0xFFAA00
        val TEXTURE_URL_PATTERN = Regex(
            """"url"\s*:\s*"https?://textures\.minecraft\.net/texture/([a-fA-F0-9]+)""""
        )
        val COLOR_CODE_PATTERN = Regex("§[0-9A-FK-ORa-fk-or]")
        val WHITESPACE_PATTERN = Regex("\\s+")
        val HIT_PROGRESS_TEXT_PATTERN = Regex(
            """(?i)(progress|\d+\s*%|[-=|#█▏▎▍▌▋▊▉━─═]{2,})"""
        )
    }
}
