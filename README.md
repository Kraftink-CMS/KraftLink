# KraftLink

KraftLink connects your game server or proxy to KraftInk. It reports server status and online players and runs commands sent from the CMS.

[Installation guide](https://kraftink.com/en/documentation/kraftlink) · [Releases](https://github.com/kraftink-cms/KraftLink/releases) · [Discord](https://discord.kraftink.com)

## Install KraftLink

Download `KraftLink.jar` or the variant for your platform. Stop the server and install one KraftLink JAR per instance.

| Platform                        | Dedicated JAR                    | Installation folder |
| ------------------------------- | -------------------------------- | ------------------- |
| Bukkit, Spigot, Paper and Folia | `KraftLink-Bukkit-VERSION.jar`   | `plugins/`          |
| Velocity                        | `KraftLink-Velocity-VERSION.jar` | `plugins/`          |
| Hytale                          | `KraftLink-Hytale-VERSION.jar`   | `mods/`             |

The Bukkit variant supports Minecraft from 1.8.8 and targets Java 8. The Hytale variant is built against server 0.7.4. Use the Java version required by your server or proxy.

1. In KraftInk, open **Servers → Game servers**, add a KraftLink connection and generate a connection token.
2. Restart the server. Set `cms_url` to your KraftInk site's HTTPS address and enter the connection `token` in KraftLink's generated `config.json`. To run commands, list the allowed command names in `allowed_command_roots`.
3. Run `kraftlink reload`, then use `kraftlink status` to check that the connection is `connected`.

## Build from source

Build with JDK 25 and Maven.

```shell
mvn clean package
```

The build writes `KraftLink.jar` and the three dedicated variants to `target/`.
