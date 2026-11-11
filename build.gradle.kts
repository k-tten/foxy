
plugins {
    idea
    java
    kotlin("jvm") version "2.4.0"
    id("dev.kikugie.loom-back-compat")
}
group = "com.k_tten"
version = "${property("mod.version")}+${sc.current.version}"
project.base.archivesName = property("mod.id") as String

val requiredJava = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    sc.current.parsed >= "1.18" -> JavaVersion.VERSION_17
    sc.current.parsed >= "1.17" -> JavaVersion.VERSION_16
    else -> JavaVersion.VERSION_1_8
}
val minecraft = sc.current.version
repositories {
    mavenCentral()
}

dependencies {
    fun fapi(vararg modules: String) {
        for (module in modules) {
            modImplementation(fabricApi.module(module, property("deps.fabric_api") as String))
        }
    }

    minecraft("com.mojang:minecraft:${sc.current.version}")
    loomx.applyMojangMappings()
    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:1.13.12+kotlin.2.4.0")
    fapi("fabric-command-api-v2", "fabric-lifecycle-events-v1", "fabric-message-api-v1")
}

loom {
    fabricModJsonPath = rootProject.file("src/main/resources/fabric.mod.json") // Useful for interface injection

    runConfigs.all {
        ideConfigGenerated(true)
        runDir = "../../run" // Shares the run directory between versions
    }
}
java {
    withSourcesJar()
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava
}

kotlin {
    jvmToolchain(requiredJava.majorVersion.toInt())
}
tasks {
    processResources {
        inputs.property("id", project.property("mod.id"))
        inputs.property("name", project.property("mod.name"))
        inputs.property("version", project.property("mod.version"))
        inputs.property("minecraft", project.property("mod.mc_compat"))
        inputs.property("java_version", requiredJava.majorVersion)
        inputs.property("fabric_loader", project.property("deps.fabric_loader"))

        val props = mapOf(
            "id" to project.property("mod.id"),
            "name" to project.property("mod.name"),
            "version" to project.property("mod.version"),
            "minecraft" to project.property("mod.mc_compat"),
            "java_version" to requiredJava.majorVersion,
            "fabric_loader" to project.property("deps.fabric_loader")
        )

        filesMatching("fabric.mod.json") { expand(props) }
    }

    if (sc.eval(minecraft, "<=1.21.11")) {
        named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
            archiveClassifier.set("")
            destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
        }
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        dependsOn("build")
        from(
            if (sc.current.parsed < "26.1") named<org.gradle.jvm.tasks.Jar>("remapJar").map { it.archiveFile }
            else named<org.gradle.jvm.tasks.Jar>("jar").map { it.archiveFile }
        )
        into(rootProject.layout.buildDirectory.file("output/"))
    }
}