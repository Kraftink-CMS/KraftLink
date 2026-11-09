# KraftLink

KraftLink connects your game server or proxy to KraftInk. It reports server status and online players and runs commands sent from the CMS.

[Installation guide](https://kraftink.com/en/documentation/kraftlink) · [Releases](https://github.com/kraftink-cms/KraftLink/releases) · [Discord](https://discord.kraftink.com)

## Install KraftLink

Download the universal `KraftLink.jar` or choose the dedicated variant for your platform. Check the release notes for
platform support. Stop the server and install one KraftLink JAR per instance.

| Platform                        | Dedicated JAR                    | Installation folder |
| ------------------------------- | -------------------------------- | ------------------- |
| Bukkit, Spigot, Paper and Folia | `KraftLink-Bukkit-VERSION.jar`   | `plugins/`          |
| Velocity                        | `KraftLink-Velocity-VERSION.jar` | `plugins/`          |
| Hytale                          | `KraftLink-Hytale-VERSION.jar`   | `mods/`             |
| Fabric                          | `KraftLink-Fabric-VERSION.jar`   | `mods/`             |
| Forge                           | `KraftLink-Forge-VERSION.jar`    | `mods/`             |
| NeoForge                        | `KraftLink-NeoForge-VERSION.jar` | `mods/`             |
| Sponge                          | `KraftLink-Sponge-VERSION.jar`   | `mods/plugins/`     |
| Nukkit                          | `KraftLink-Nukkit-VERSION.jar`   | `plugins/`          |

The Bukkit variant supports Minecraft from 1.8.8 and targets Java 8. The Hytale variant is built against server 0.7.4.
Fabric, Forge and NeoForge target Minecraft Java 26.1.2 and require Java 25. Fabric also requires Fabric API. Sponge
targets SpongeAPI 17 on Minecraft Java 1.21.10. The Nukkit variant targets Cloudburst Nukkit API 1.1 and connects to a
Minecraft Bedrock server. See the [installation guide](https://kraftink.com/en/documentation/kraftlink) for platform
requirements.

On SpongeVanilla and SpongeForge, put either JAR in `mods/plugins/`. Use the dedicated Sponge JAR on SpongeNeo so that
the NeoForge loader discovers KraftLink as a Sponge plugin.

On Nukkit, the universal JAR loads the plugin and may log a warning while looking for a Bedrock resource pack in the
Hytale manifest. Use the dedicated Nukkit JAR to avoid that warning.

1. In KraftInk, open **Servers → Game servers**, add a KraftLink connection and generate a connection token.
2. Restart the server. Set `cms_url` to your KraftInk site's HTTPS address and enter the connection `token` in KraftLink's generated `config.json`. To run commands, list the allowed command names in `allowed_command_roots`.
3. Run `kraftlink reload`, then use `kraftlink status` to check that the connection is `connected`.

## Build from source

Build with JDK 25, Maven and the Gradle wrapper.

```shell
mvn clean package
bash mods/gradlew -p mods assemble
```

On Windows, use `mods\gradlew.bat -p mods assemble` for the second command. The builds write `KraftLink.jar` and the dedicated variants to `target/`.
