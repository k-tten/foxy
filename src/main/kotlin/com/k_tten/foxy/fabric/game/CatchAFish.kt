package com.k_tten.foxy.fabric.game

import com.k_tten.foxy.fabric.config.ModConfig
import net.minecraft.core.BlockPos

class CatchAFish(private val config: ModConfig) {
    fun register() = Unit

    private companion object {
        const val START_TRIGGER = "[NPC] Carnival Fisherman: Good luck, friend!"
        const val END_TRIGGER = "                               Catch a Fish"
        val CHECK_RANGE_START = BlockPos(-84, 66, 51)
        val CHECK_RANGE_END = BlockPos(-70, 71, 34)
    }
}
