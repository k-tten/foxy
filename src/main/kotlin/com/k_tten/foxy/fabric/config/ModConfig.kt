package com.k_tten.foxy.fabric.config

import com.k_tten.foxy.fabric.FoxyFabric
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

class ModConfig(private val path: Path) {
    var fruitDigging: Boolean = false
        private set
    var autostart: Boolean = false
        private set
    var zombieShootout: Boolean = false
        private set
    var catchAFish: Boolean = false
        private set
    var verbose: Boolean = true
        private set

    init {
        val properties = Properties()
        if (Files.exists(path)) {
            Files.newInputStream(path).use(properties::load)
            fruitDigging = loadBoolean(
                properties.getProperty("fruitDigging"),
                "fruitDigging",
                false
            )
            autostart = loadBoolean(properties.getProperty("autostart"), "autostart", false)
            zombieShootout = loadBoolean(
                properties.getProperty("zombieShootout"),
                "zombieShootout",
                false
            )
            catchAFish = loadBoolean(
                properties.getProperty("catchAFish"),
                "catchAFish",
                false
            )
            verbose = loadBoolean(properties.getProperty("verbose"), "verbose", true)
        } else {
            save()
        }
    }

    fun setFruitDigging(enabled: Boolean) {
        fruitDigging = enabled
        save()
    }

    fun setAutostart(enabled: Boolean) {
        autostart = enabled
        save()
    }

    fun setZombieShootout(enabled: Boolean) {
        zombieShootout = enabled
        save()
    }

    fun setCatchAFish(enabled: Boolean) {
        catchAFish = enabled
        save()
    }

    fun setVerbose(verbose: Boolean) {
        this.verbose = verbose
        save()
    }

    private fun loadBoolean(value: String?, key: String, default: Boolean): Boolean {
        if (value == null) return default
        return value.toBooleanStrictOrNull() ?: run {
            FoxyFabric.LOGGER.warn("Invalid $key value in $path; defaulting to $default")
            default
        }
    }

    private fun save() {
        Files.createDirectories(path.parent)
        val properties = Properties().apply {
            setProperty("fruitDigging", fruitDigging.toString())
            setProperty("autostart", autostart.toString())
            setProperty("zombieShootout", zombieShootout.toString())
            setProperty("catchAFish", catchAFish.toString())
            setProperty("verbose", verbose.toString())
        }
        Files.newOutputStream(path).use { properties.store(it, "Foxy configuration") }
    }
}
