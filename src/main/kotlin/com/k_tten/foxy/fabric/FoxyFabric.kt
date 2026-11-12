package com.k_tten.foxy.fabric

import com.k_tten.foxy.fabric.config.ModConfig
import com.k_tten.foxy.fabric.config.ModCommands
import com.k_tten.foxy.fabric.game.CatchAFish
import com.k_tten.foxy.fabric.game.FruitDigging
import com.k_tten.foxy.fabric.game.ZombieShootout
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.api.ModInitializer
import net.fabricmc.loader.api.FabricLoader
import org.apache.logging.log4j.LogManager

class FoxyFabric : ModInitializer {
    companion object {
        val LOGGER = LogManager.getLogger(FoxyFabric::class.java)
    }

    lateinit var config: ModConfig
        private set

    override fun onInitialize() {
        config = ModConfig(FabricLoader.getInstance().configDir.resolve("foxy.properties"))
        val catchAFish = CatchAFish(config)
        val fruitDigging = FruitDigging(config)
        val zombieShootout = ZombieShootout(config)
        catchAFish.register()
        fruitDigging.register()
        zombieShootout.register()
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            ModCommands.register(dispatcher, config, catchAFish, fruitDigging, zombieShootout)
        }
        LOGGER.info("Initializing Foxy")
    }
}