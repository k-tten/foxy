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
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RedstoneLampBlock
import net.minecraft.world.phys.Vec3
import java.util.Optional
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.sqrt

class ZombieShootout(private val config: ModConfig) {
    private enum class AutoStartStage {
        IDLE,
        WAIT_BEFORE_MOVE,
        MOVE_TO_START,
        AIM_AT_NPC,
        WAIT_FOR_OPTION,
        WAIT_FOR_START
    }

    private data class Rotation(
        val startYaw: Float,
        val startPitch: Float,
        val startedAt: Long,
        val shotDeadline: Long
    )

    private data class ZombieMotion(
        val position: Vec3,
        val sampledAt: Long,
        val velocity: Vec3
    ) {
        val speed: Double
            get() = velocity.length()
    }

    private data class Target(
        val zombie: Zombie?,
        val lamp: BlockPos?,
        val priority: Int,
        val retryOrder: Int
    ) {
        val key: String
            get() = zombie?.uuid?.let { "z:$it" } ?: "l:$lamp"
    }

    private var active = false
    private var waitingForMouseLogged = false
    private var mouseControlAcquired = false
    private var hadTargets = false
    private var autoStartStage = AutoStartStage.IDLE
    private var autoStartNpc: Entity? = null
    private var autoStartDeadline = 0L
    private var autoStartExpectedYaw = 0f
    private var autoStartExpectedPitch = 0f
    private var autoStartMovementYaw: Float? = null
    private var autoStartOptionWithoutClickLogged = false
    private var manualAutostart = false
    private var rotation: Rotation? = null
    private var aimedTargetKey: String? = null
    private val zombieMotion = mutableMapOf<UUID, ZombieMotion>()
    private val zombieRetryAt = mutableMapOf<UUID, Long>()
    private val retryFirst = linkedSetOf<UUID>()
    private val litLamps = linkedSetOf<BlockPos>()
    private val lampRetryAt = mutableMapOf<BlockPos, Long>()

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
        val normalized = normalizeMessage(message)
        when (normalized) {
            normalizeMessage(END_TRIGGER) -> {
                stop("End trigger received.")
                beginAutostart()
            }
            normalizeMessage(START_TRIGGER) -> startFromTrigger()
        }
    }

    private fun startFromTrigger() {
        if (!config.zombieShootout) {
            chatLog("Start trigger ignored: Zombie Shootout is disabled.")
            return
        }

        if (autoStartStage != AutoStartStage.IDLE) {
            clearAutostart()
        }
        start()
    }

    fun startAutostartManually(): Boolean {
        if (!config.zombieShootout || active ||
            autoStartStage != AutoStartStage.IDLE
        ) return false
        beginAutostart(manual = true)
        return autoStartStage != AutoStartStage.IDLE
    }

    private fun beginAutostart(manual: Boolean = false) {
        if ((!manual && !config.autostart) || !config.zombieShootout ||
            autoStartStage != AutoStartStage.IDLE
        ) return

        val client = Minecraft.getInstance()
        val player = client.player
        if (player == null || client.level == null) {
            chatLog("Autostart could not find an active player or world.")
            return
        }

        try {
            closeChat(client)
        } catch (exception: ReflectiveOperationException) {
            FoxyFabric.LOGGER.error("Could not close chat before Zombie Shootout autostart", exception)
            chatLog("Autostart failed: could not close chat.")
            return
        }

        autoStartExpectedYaw = player.yRot
        autoStartExpectedPitch = player.xRot
        autoStartNpc = null
        autoStartMovementYaw = null
        autoStartOptionWithoutClickLogged = false
        manualAutostart = manual
        autoStartStage = AutoStartStage.WAIT_BEFORE_MOVE
        autoStartDeadline = System.nanoTime() + AUTOSTART_MOVE_DELAY_NANOS
        chatLog("Autostart will move to $AUTOSTART_POSITION in 3 seconds.")
    }

    private fun start() {
        if (active) return

        val client = Minecraft.getInstance()
        if (client.player == null || client.level == null) {
            FoxyFabric.LOGGER.warn("Received the Zombie Shootout trigger without an active player or world")
            chatLog("Cannot start: player or world is unavailable.")
            return
        }

        try {
            closeChat(client)
        } catch (exception: ReflectiveOperationException) {
            FoxyFabric.LOGGER.error("Could not close chat before starting Zombie Shootout", exception)
            chatLog("Could not close chat. Zombie Shootout was not started.")
            return
        }
        active = true
        waitingForMouseLogged = false
        mouseControlAcquired = false
        hadTargets = false
        rotation = null
        aimedTargetKey = null
        zombieMotion.clear()
        zombieRetryAt.clear()
        retryFirst.clear()
        litLamps.clear()
        lampRetryAt.clear()
        chatLog("Start trigger received. Zombie Shootout started.")
    }

    private fun stop(reason: String) {
        if (!active) return

        active = false
        rotation = null
        aimedTargetKey = null
        waitingForMouseLogged = false
        mouseControlAcquired = false
        hadTargets = false
        zombieMotion.clear()
        zombieRetryAt.clear()
        retryFirst.clear()
        litLamps.clear()
        lampRetryAt.clear()
        chatLog("Zombie Shootout stopped. $reason")
    }

    private fun tick(client: Minecraft) {
        if (autoStartStage != AutoStartStage.IDLE) {
            tickAutostart(client)
            if (!active) return
        }

        if (!active) return
        if (!config.zombieShootout) {
            stop("Zombie Shootout was disabled.")
            return
        }

        val player = client.player
        val level = client.level
        if (player == null || level == null) {
            stop("Player or world became unavailable.")
            return
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
            chatLog("Mouse control acquired. Starting Zombie Shootout.")
            waitingForMouseLogged = false
        }
        mouseControlAcquired = true

        val now = System.nanoTime()
        val zombies = level.entitiesForRendering()
            .filterIsInstance<Zombie>()
            .filter { it.isAlive && isInRange(it.position()) }
        updateZombieMotion(zombies, now)
        updateRetries(zombies, now)

        val targets = buildTargets(client, player, zombies, now)
        val target = aimedTargetKey?.let { currentKey ->
            targets.firstOrNull { it.key == currentKey }
        } ?: targets.firstOrNull()
        if (target == null) {
            if (hadTargets) {
                chatLog("No eligible zombies or lit lamps remain in the scan area.")
            }
            hadTargets = false
            rotation = null
            aimedTargetKey = null
            return
        }
        hadTargets = true

        if (aimedTargetKey != target.key) {
            aimedTargetKey = target.key
            beginRotation(player, now)
            chatLog("Aiming at ${describeTarget(target)}.")
        }

        val activeRotation = rotation ?: return
        val timeUntilShotSeconds =
            (activeRotation.shotDeadline - now).coerceAtLeast(0L).toDouble() / NANOS_PER_SECOND
        val aimPoint = targetPoint(player, target, timeUntilShotSeconds)
        val direction = aimPoint.subtract(player.eyePosition)
        val horizontalDistance = sqrt(direction.x * direction.x + direction.z * direction.z)
        val yaw = yawTo(direction.x, direction.z)
        val pitch = Math.toDegrees(-atan2(direction.y, horizontalDistance)).toFloat()

        updateRotation(player, activeRotation, yaw, pitch, now)
        if (now < activeRotation.shotDeadline) return

        fireAt(target, now)
        aimedTargetKey = null
        rotation = null
    }

    private fun tickAutostart(client: Minecraft) {
        if ((!config.autostart && !manualAutostart) || !config.zombieShootout) {
            clearAutostart()
            return
        }

        val player = client.player
        val level = client.level
        if (player == null || level == null) {
            chatLog("Autostart cancelled: player or world is unavailable.")
            clearAutostart()
            return
        }
        if (hasUserTakenControl(client, player)) {
            chatLog("Autostart cancelled because you moved or changed your view.")
            clearAutostart()
            return
        }

        val now = System.nanoTime()
        if ((autoStartStage == AutoStartStage.WAIT_FOR_OPTION ||
                autoStartStage == AutoStartStage.WAIT_FOR_START) &&
            autoStartDeadline != 0L && now >= autoStartDeadline
        ) {
            val reason = if (autoStartStage == AutoStartStage.WAIT_FOR_START) {
                "Autostart timed out waiting for the Cowboy start message."
            } else {
                "Autostart timed out waiting for the NPC option."
            }
            chatLog(reason)
            clearAutostart()
            return
        }

        when (autoStartStage) {
            AutoStartStage.IDLE,
            AutoStartStage.WAIT_FOR_OPTION,
            AutoStartStage.WAIT_FOR_START -> Unit
            AutoStartStage.WAIT_BEFORE_MOVE -> {
                if (now >= autoStartDeadline) {
                    autoStartDeadline = 0L
                    autoStartStage = AutoStartStage.MOVE_TO_START
                    chatLog("Moving to the Cowboy interaction point $AUTOSTART_POSITION.")
                }
            }
            AutoStartStage.MOVE_TO_START -> moveToAutostartPosition(client, player, level, now)
            AutoStartStage.AIM_AT_NPC -> {
                val npc = autoStartNpc
                if (npc == null || !npc.isAlive) {
                    chatLog("Autostart stopped: Carnival Cowboy is no longer available.")
                    clearAutostart()
                    return
                }
                if (!client.mouseHandler.isMouseGrabbed) return

                val direction = npc.boundingBox.center.subtract(player.eyePosition)
                val horizontalDistance = sqrt(direction.x * direction.x + direction.z * direction.z)
                val yaw = yawTo(direction.x, direction.z)
                val pitch = Math.toDegrees(-atan2(direction.y, horizontalDistance)).toFloat()
                if (!updateAutostartRotation(player, yaw, pitch, now)) return

                KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(RIGHT_MOUSE_BUTTON))
                autoStartStage = AutoStartStage.WAIT_FOR_OPTION
                autoStartDeadline = now + AUTOSTART_PROMPT_TIMEOUT_NANOS
                chatLog("Right-clicked Carnival Cowboy. Waiting for the autostart option.")
            }
        }
    }

    private fun moveToAutostartPosition(
        client: Minecraft,
        player: LocalPlayer,
        level: net.minecraft.client.multiplayer.ClientLevel,
        now: Long
    ) {
        if (!client.mouseHandler.isMouseGrabbed) {
            client.options.keyUp.setDown(false)
            client.options.keyJump.setDown(false)
            if (!waitingForMouseLogged) {
                chatLog("Waiting for mouse control. Click the game window to continue.")
                waitingForMouseLogged = true
            }
            return
        }
        if (waitingForMouseLogged) {
            chatLog("Mouse control acquired. Walking to the Cowboy interaction point.")
            waitingForMouseLogged = false
        }
        mouseControlAcquired = true

        val dx = AUTOSTART_POSITION.x + 0.5 - player.x
        val dz = AUTOSTART_POSITION.z + 0.5 - player.z
        if (dx * dx + dz * dz <= AUTOSTART_ARRIVAL_DISTANCE_SQUARED &&
            player.y >= AUTOSTART_POSITION.y + 1.0 - AUTOSTART_VERTICAL_TOLERANCE
        ) {
            client.options.keyUp.setDown(false)
            client.options.keyJump.setDown(false)
            val npc = findCarnivalCowboy(level, player)
            if (npc == null) {
                chatLog("Autostart could not find Carnival Cowboy nearby.")
                clearAutostart()
                return
            }
            autoStartNpc = npc
            autoStartStage = AutoStartStage.AIM_AT_NPC
            autoStartMovementYaw = null
            rotation = null
            chatLog("Reached $AUTOSTART_POSITION. Aiming at Carnival Cowboy.")
            return
        }

        val desiredYaw = yawTo(dx, dz)
        val movementYaw = autoStartMovementYaw
        if (rotation == null &&
            (movementYaw == null ||
                kotlin.math.abs(angleDifference(desiredYaw, movementYaw)) >
                MOVEMENT_ROTATION_RETARGET_DEGREES)
        ) {
            autoStartMovementYaw = desiredYaw
            beginAutostartRotation(player, now)
        }
        updateAutostartRotation(player, desiredYaw, player.xRot, now)
        autoStartExpectedYaw = player.yRot
        autoStartExpectedPitch = player.xRot
        client.options.keyUp.setDown(true)
        client.options.keyJump.setDown(
            player.onGround() &&
                player.y < AUTOSTART_POSITION.y + 1.0 - AUTOSTART_VERTICAL_TOLERANCE
        )
    }

    private fun hasUserTakenControl(client: Minecraft, player: LocalPlayer): Boolean {
        val moving = autoStartStage == AutoStartStage.MOVE_TO_START
        val options = client.options
        if ((!moving && (options.keyUp.isDown || options.keyJump.isDown)) ||
            options.keyDown.isDown || options.keyLeft.isDown || options.keyRight.isDown ||
            options.keyShift.isDown || options.keySprint.isDown
        ) {
            return true
        }

        val viewChanged = client.mouseHandler.isMouseGrabbed &&
            (kotlin.math.abs(angleDifference(player.yRot, autoStartExpectedYaw)) >
                AUTOSTART_LOOK_INPUT_THRESHOLD_DEGREES ||
                kotlin.math.abs(player.xRot - autoStartExpectedPitch) >
                AUTOSTART_LOOK_INPUT_THRESHOLD_DEGREES)
        autoStartExpectedYaw = player.yRot
        autoStartExpectedPitch = player.xRot
        return viewChanged
    }

    private fun beginAutostartRotation(player: LocalPlayer, now: Long) {
        rotation = Rotation(
            startYaw = player.yRot,
            startPitch = player.xRot,
            startedAt = now,
            shotDeadline = now + AUTOSTART_ROTATION_DURATION_NANOS
        )
    }

    private fun updateAutostartRotation(
        player: LocalPlayer,
        yaw: Float,
        pitch: Float,
        now: Long
    ): Boolean {
        val activeRotation = rotation ?: run {
            beginAutostartRotation(player, now)
            return false
        }
        val progress = ((now - activeRotation.startedAt).toDouble() /
            AUTOSTART_ROTATION_DURATION_NANOS).coerceIn(0.0, 1.0).toFloat()
        val easedProgress = progress * progress * (3f - 2f * progress)
        val targetYaw = activeRotation.startYaw +
            angleDifference(yaw, activeRotation.startYaw)
        player.setYRot(lerp(activeRotation.startYaw, targetYaw, easedProgress))
        player.setXRot(lerp(activeRotation.startPitch, pitch, easedProgress))
        autoStartExpectedYaw = player.yRot
        autoStartExpectedPitch = player.xRot
        if (progress < 1f) return false
        rotation = null
        return true
    }

    private fun handleAutostartOption(message: Component) {
        if (autoStartStage != AutoStartStage.WAIT_FOR_OPTION) return

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
            if (!autoStartOptionWithoutClickLogged) {
                chatLog("Found the autostart option, but it has no clickable action.")
                autoStartOptionWithoutClickLogged = true
            }
            return
        }

        if (runChatClickEvent(clickEvent)) {
            autoStartStage = AutoStartStage.WAIT_FOR_START
            autoStartDeadline = System.nanoTime() + AUTOSTART_START_TIMEOUT_NANOS
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
            FoxyFabric.LOGGER.error("Could not activate the Zombie Shootout NPC option", exception)
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

    private fun findCarnivalCowboy(
        level: net.minecraft.client.multiplayer.ClientLevel,
        player: LocalPlayer
    ): Entity? =
        level.entitiesForRendering()
            .filter { normalizeMessage(it.displayName.string).contains(CARNIVAL_COWBOY_NAME, true) }
            .filter { player.distanceToSqr(it) <= NPC_SEARCH_RADIUS_SQUARED }
            .minByOrNull { player.distanceToSqr(it) }

    private fun clearAutostart() {
        Minecraft.getInstance().options.keyUp.setDown(false)
        Minecraft.getInstance().options.keyJump.setDown(false)
        autoStartStage = AutoStartStage.IDLE
        autoStartNpc = null
        autoStartDeadline = 0L
        autoStartMovementYaw = null
        autoStartOptionWithoutClickLogged = false
        manualAutostart = false
        rotation = null
        waitingForMouseLogged = false
        mouseControlAcquired = false
    }

    private fun buildTargets(
        client: Minecraft,
        player: LocalPlayer,
        zombies: List<Zombie>,
        now: Long
    ): List<Target> {
        val retryOrder = retryFirst.withIndex().associate { it.value to it.index }
        val targets = mutableListOf<Target>()

        for (zombie in zombies) {
            val retryIndex = retryOrder[zombie.uuid]
            if (retryIndex == null && zombie.uuid in zombieRetryAt) continue

            val helmet = zombie.getItemBySlot(EquipmentSlot.HEAD).item
            val priority = when {
                helmet == Items.DIAMOND_HELMET -> DIAMOND_HELMET_PRIORITY
                zombie.isBaby -> BABY_ZOMBIE_PRIORITY
                helmet == Items.GOLDEN_HELMET -> GOLD_HELMET_PRIORITY
                helmet == Items.IRON_HELMET -> IRON_HELMET_PRIORITY
                helmet == Items.LEATHER_HELMET -> LEATHER_HELMET_PRIORITY
                else -> continue
            }
            targets += Target(zombie, null, priority, retryIndex ?: Int.MAX_VALUE)
        }

        for (lamp in REDSTONE_LAMP_POSITIONS) {
            val state = client.level?.getBlockState(lamp) ?: continue
            val isLit = state.block == Blocks.REDSTONE_LAMP &&
                state.getValue(RedstoneLampBlock.LIT)
            if (!isLit) {
                if (litLamps.remove(lamp)) {
                    chatLog("Redstone lamp at $lamp turned off.")
                }
                lampRetryAt.remove(lamp)
            } else {
                if (litLamps.add(lamp)) {
                    chatLog("Redstone lamp at $lamp turned on.")
                }
            }
        }

        for (lamp in litLamps) {
            if ((lampRetryAt[lamp] ?: 0L) <= now) {
                targets += Target(null, lamp, LIT_LAMP_PRIORITY, Int.MAX_VALUE)
            }
        }

        return targets.sortedWith(
            compareBy<Target> { it.retryOrder }
                .thenBy { it.priority }
                .thenBy { targetDistanceSquared(player, it) }
        )
    }

    private fun updateZombieMotion(zombies: List<Zombie>, now: Long) {
        val presentIds = zombies.mapTo(mutableSetOf()) { it.uuid }
        zombieMotion.keys.retainAll(presentIds)
        zombieRetryAt.keys.retainAll(presentIds)
        retryFirst.retainAll(presentIds)

        for (zombie in zombies) {
            val position = zombie.position()
            val previous = zombieMotion[zombie.uuid]
            val velocity = if (previous == null) {
                Vec3.ZERO
            } else {
                val elapsedSeconds = (now - previous.sampledAt).toDouble() / NANOS_PER_SECOND
                if (elapsedSeconds <= 0.0 || elapsedSeconds > MAX_SAMPLE_GAP_SECONDS) {
                    Vec3.ZERO
                } else {
                    val measured = position.subtract(previous.position).scale(1.0 / elapsedSeconds)
                    val smoothed = previous.velocity.scale(VELOCITY_SMOOTHING)
                        .add(measured.scale(1.0 - VELOCITY_SMOOTHING))
                    limitVelocity(smoothed)
                }
            }
            zombieMotion[zombie.uuid] = ZombieMotion(position, now, velocity)
        }
    }

    private fun updateRetries(zombies: List<Zombie>, now: Long) {
        val presentIds = zombies.mapTo(mutableSetOf()) { it.uuid }
        val maturedRetries = zombieRetryAt.filterValues { it <= now }.keys.toList()
        for (uuid in maturedRetries) {
            zombieRetryAt.remove(uuid)
            if (uuid in presentIds && retryFirst.add(uuid)) {
                chatLog("Zombie $uuid survived the shot; retrying it at highest priority.")
            }
        }
    }

    private fun targetPoint(player: LocalPlayer, target: Target, aimDelaySeconds: Double): Vec3 {
        val zombie = target.zombie
        if (zombie != null) {
            val motion = zombieMotion[zombie.uuid]
            val center = zombie.boundingBox.center
            val eyePosition = player.eyePosition
            val distance = eyePosition.distanceTo(center)
            val predictionTime =
                aimDelaySeconds + distance / PROJECTILE_SPEED_BLOCKS_PER_SECOND
            val predictedOffset = if (motion != null && motion.speed > 0.0) {
                motion.velocity.normalize().scale(motion.speed * predictionTime)
            } else {
                Vec3.ZERO
            }
            return Vec3(
                center.x + predictedOffset.x,
                zombie.boundingBox.maxY + predictedOffset.y + ZOMBIE_AIM_ABOVE_HEAD,
                center.z + predictedOffset.z
            )
        }

        val lamp = target.lamp ?: return player.eyePosition
        return Vec3(lamp.x + 0.5, lamp.y + 1.0 + LAMP_AIM_OFFSET, lamp.z + 0.5)
    }

    private fun fireAt(target: Target, now: Long) {
        KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(RIGHT_MOUSE_BUTTON))
        val zombie = target.zombie
        if (zombie != null) {
            val speed = zombieMotion[zombie.uuid]?.speed ?: 0.0
            chatLog(
                "Right-clicked ${describeTarget(target)} " +
                    "(estimated speed ${"%.1f".format(java.util.Locale.ROOT, speed)} blocks/s)."
            )
            retryFirst.remove(zombie.uuid)
            zombieRetryAt[zombie.uuid] = now + ZOMBIE_RETRY_DELAY_NANOS
        } else {
            target.lamp?.let {
                chatLog("Right-clicked redstone lamp at $it.")
                lampRetryAt[it] = now + LAMP_RETRY_DELAY_NANOS
            }
        }
    }

    private fun describeTarget(target: Target): String {
        val zombie = target.zombie ?: return "redstone lamp at ${target.lamp}"
        val helmet = zombie.getItemBySlot(EquipmentSlot.HEAD).item
        val label = when {
            helmet == Items.DIAMOND_HELMET -> "diamond-helmet zombie"
            zombie.isBaby -> "baby zombie"
            helmet == Items.GOLDEN_HELMET -> "gold-helmet zombie"
            helmet == Items.IRON_HELMET -> "iron-helmet zombie"
            helmet == Items.LEATHER_HELMET -> "leather-helmet zombie"
            else -> "zombie"
        }
        return "$label ${zombie.uuid}"
    }

    private fun targetDistanceSquared(player: LocalPlayer, target: Target): Double {
        val point = target.zombie?.boundingBox?.center
            ?: target.lamp?.let { Vec3(it.x + 0.5, it.y + 0.5, it.z + 0.5) }
            ?: return Double.MAX_VALUE
        return player.eyePosition.distanceToSqr(point)
    }

    private fun isInRange(position: Vec3): Boolean =
        position.x >= minOf(CHECK_RANGE_START.x, CHECK_RANGE_END.x) &&
            position.x <= maxOf(CHECK_RANGE_START.x, CHECK_RANGE_END.x) &&
            position.y >= minOf(CHECK_RANGE_START.y, CHECK_RANGE_END.y) &&
            position.y <= maxOf(CHECK_RANGE_START.y, CHECK_RANGE_END.y) &&
            position.z >= minOf(CHECK_RANGE_START.z, CHECK_RANGE_END.z) &&
            position.z <= maxOf(CHECK_RANGE_START.z, CHECK_RANGE_END.z)

    private fun limitVelocity(velocity: Vec3): Vec3 {
        val speed = velocity.length()
        return if (speed > MAX_ZOMBIE_SPEED_BLOCKS_PER_SECOND) {
            velocity.scale(MAX_ZOMBIE_SPEED_BLOCKS_PER_SECOND / speed)
        } else {
            velocity
        }
    }

    private fun beginRotation(player: LocalPlayer, now: Long) {
        rotation = Rotation(
            startYaw = player.yRot,
            startPitch = player.xRot,
            startedAt = now,
            shotDeadline = now + MAX_SHOT_DELAY_NANOS
        )
    }

    private fun updateRotation(
        player: LocalPlayer,
        activeRotation: Rotation,
        targetYaw: Float,
        targetPitch: Float,
        now: Long
    ) {
        val progress = ((now - activeRotation.startedAt).toDouble() /
            ROTATION_DURATION_NANOS).coerceIn(0.0, 1.0).toFloat()
        val easedProgress = progress * progress * (3f - 2f * progress)
        val finalYaw = activeRotation.startYaw +
            angleDifference(targetYaw, activeRotation.startYaw)
        player.setYRot(lerp(activeRotation.startYaw, finalYaw, easedProgress))
        player.setXRot(lerp(activeRotation.startPitch, targetPitch, easedProgress))
    }

    private fun normalizeMessage(message: String): String =
        message.replace(COLOR_CODE_PATTERN, "")
            .trim()
            .replace(WHITESPACE_PATTERN, " ")

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
            FoxyFabric.LOGGER.error("Could not send Zombie Shootout status to chat", exception)
        }
    }

    private fun yawTo(dx: Double, dz: Double): Float =
        Math.toDegrees(atan2(-dx, dz)).toFloat()

    private fun angleDifference(target: Float, current: Float): Float =
        ((target - current + 540f) % 360f) - 180f

    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress

    private companion object {
        const val LIGHT_GREEN_COLOR = 0x55FF55
        const val ORANGE_COLOR = 0xFFAA00
        const val START_TRIGGER = "[NPC] Carnival Cowboy: I won't keep ya, then."
        const val END_TRIGGER = "                             Zombie Shootout"
        val CHECK_RANGE_START = BlockPos(-119, 74, 35)
        val CHECK_RANGE_END = BlockPos(-91, 70, 62)
        val REDSTONE_LAMP_POSITIONS = listOf(
            BlockPos(-118, 76, 39),
            BlockPos(-119, 77, 42),
            BlockPos(-119, 75, 45),
            BlockPos(-118, 76, 49),
            BlockPos(-117, 76, 52),
            BlockPos(-115, 77, 55),
            BlockPos(-112, 76, 58),
            BlockPos(-109, 75, 60),
            BlockPos(-106, 77, 61),
            BlockPos(-102, 75, 62),
            BlockPos(-99, 77, 62),
            BlockPos(-96, 76, 61)
        )
        val COLOR_CODE_PATTERN = Regex("§[0-9A-FK-ORa-fk-or]")
        val WHITESPACE_PATTERN = Regex("\\s+")
        const val DIAMOND_HELMET_PRIORITY = 0
        const val BABY_ZOMBIE_PRIORITY = 1
        const val LIT_LAMP_PRIORITY = 2
        const val GOLD_HELMET_PRIORITY = 3
        const val IRON_HELMET_PRIORITY = 4
        const val LEATHER_HELMET_PRIORITY = 5
        const val RIGHT_MOUSE_BUTTON = 1
        const val AUTOSTART_OPTION_TEXT = "Sure thing, partner!"
        const val CARNIVAL_COWBOY_NAME = "Carnival Cowboy"
        val AUTOSTART_POSITION = BlockPos(-103, 69, 40)
        val AUTOSTART_OPTION_PATTERN = Regex(
            """\[\s*${Regex.escape(AUTOSTART_OPTION_TEXT)}\s*\]""",
            RegexOption.IGNORE_CASE
        )
        const val NPC_SEARCH_RADIUS_SQUARED = 25.0
        const val AUTOSTART_MOVE_DELAY_NANOS = 3_000_000_000L
        const val AUTOSTART_PROMPT_TIMEOUT_NANOS = 10_000_000_000L
        const val AUTOSTART_START_TIMEOUT_NANOS = 15_000_000_000L
        const val AUTOSTART_ROTATION_DURATION_NANOS = 200_000_000L
        const val AUTOSTART_ARRIVAL_DISTANCE_SQUARED = 0.04
        const val AUTOSTART_VERTICAL_TOLERANCE = 0.1
        const val AUTOSTART_LOOK_INPUT_THRESHOLD_DEGREES = 2.0f
        const val MOVEMENT_ROTATION_RETARGET_DEGREES = 5f
        const val ZOMBIE_RETRY_DELAY_NANOS = 400_000_000L
        const val LAMP_RETRY_DELAY_NANOS = 400_000_000L
        const val PROJECTILE_SPEED_BLOCKS_PER_SECOND = 40.0
        const val MAX_ZOMBIE_SPEED_BLOCKS_PER_SECOND = 12.0
        const val MAX_SAMPLE_GAP_SECONDS = 1.0
        const val VELOCITY_SMOOTHING = 0.5
        const val ZOMBIE_AIM_ABOVE_HEAD = 0.2
        const val LAMP_AIM_OFFSET = 0.2
        const val MAX_SHOT_DELAY_NANOS = 100_000_000L
        const val ROTATION_DURATION_NANOS = 100_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
