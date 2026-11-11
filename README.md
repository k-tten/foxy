# Foxy

A minimal Fabric mod starter project built with Kotlin and Stonecutter.

Use `/foxy set fruitdigging <true|false>` to enable or disable Fruit Digging,
`/foxy set autostart <true|false>` to automatically request another round
(off by default),
`/foxy set zombieshootout <true|false>` for Zombie Shootout, and
`/foxy set catchafish <true|false>` for Catch A Fish. Use
`/foxy set verbose <true|false>` to control Fruit Digging chat action messages.
Use `/foxy` (or `/foxy view`) to check the settings. They are saved in
`foxy.properties` in the Minecraft config directory.

When Fruit Digging is enabled, it listens for the Carnival Pirateman shovel
message and mines up to 15 blocks in the surrounding 7x7 area. The exact
`Fruit Digging` game message stops the sequence. Verbose is on by default;
when disabled, automation actions are not printed in chat.
With autostart enabled, after a round ends Foxy faces nearby Carnival Pirateman,
interacts, and selects the green clickable `Aye sure do!` option. Fruit Digging
then starts through its existing shovel-message trigger.
Zombie Shootout and Catch A Fish currently have configuration and registration
scaffolding only; their gameplay automation is not implemented yet.

Build a supported Minecraft version with `./gradlew :<version>:build` (or
`gradlew.bat :<version>:build` on Windows). Supported versions are listed in
`settings.gradle.kts`.
