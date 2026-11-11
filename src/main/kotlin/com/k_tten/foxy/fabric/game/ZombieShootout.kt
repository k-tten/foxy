package com.k_tten.foxy.fabric.game

import com.k_tten.foxy.fabric.config.ModConfig
import net.minecraft.core.BlockPos

class ZombieShootout(private val config: ModConfig) {
    fun register() = Unit

    private companion object {
        const val START_TRIGGER = "[NPC] Carnival Cowboy: I won't keep ya, then."
        const val END_TRIGGER = "                             Zombie Shootout"
        val CHECK_RANGE_START = BlockPos(-118, 71, 35)
        val CHECK_RANGE_END = BlockPos(-91, 70, 59)
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
    }
}
