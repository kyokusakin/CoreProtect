![CoreProtect](https://userfolio.com/uploads/coreprotect-banner-v19.png)

[![Artistic License 2.0](https://img.shields.io/github/license/PlayPro/CoreProtect?&logo=github)](LICENSE)
[![GitHub Workflows](https://github.com/PlayPro/CoreProtect/actions/workflows/build.yml/badge.svg)](https://github.com/PlayPro/CoreProtect/actions)
[![Netlify Status](https://img.shields.io/netlify/c1d26a0f-65c5-4e4b-95d7-e08af671ab67)](https://app.netlify.com/sites/coreprotect/deploys)
[![CodeFactor](https://www.codefactor.io/repository/github/playpro/coreprotect/badge)](https://www.codefactor.io/repository/github/playpro/coreprotect)
[![Join us on Discord](https://img.shields.io/discord/348680641560313868.svg?label=&logo=discord&logoColor=ffffff&color=7389D8&labelColor=6A7EC2)](https://discord.gg/b4DZ4jy)

CoreProtect
===========

CoreProtect is a blazing fast data logging and anti-griefing tool for Minecraft servers.

For a detailed description of the plugin, please visit [coreprotect.net](https://coreprotect.net).

| Quick Links |  |
| --- | --- |
| CoreProtect Discord: | [discord.gg/b4DZ4jy](https://discord.gg/b4DZ4jy) |
| CoreProtect Patreon: | [patreon.com/coreprotect](https://www.patreon.com/coreprotect) |
| CoreProtect Documentation: | [docs.coreprotect.net](https://docs.coreprotect.net) |
| Downloads for MC 1.14 - 1.21: | [coreprotect.net/latest](https://coreprotect.net/latest/) |
| Downloads for MC 1.8 - 1.12: | [coreprotect.net/legacy](https://coreprotect.net/legacy/) |

bStats
------
[![bStats Graph Data](https://bstats.org/signatures/bukkit/CoreProtect.svg)](https://bstats.org/plugin/bukkit/CoreProtect)

API
------
### [API Documentation](https://docs.coreprotect.net/api/)

### Dependency Information
Maven
```xml
<repository>
    <id>playpro-repo</id>
    <url>https://maven.playpro.com</url>
</repository>
```
```xml
<dependency>
    <groupId>net.coreprotect</groupId>
    <artifactId>coreprotect</artifactId>
    <version>23.1</version>
    <scope>provided</scope>
</dependency>
```

Contributing
------
CoreProtect is an open source project, and gladly accepts community contributions.

If you'd like to contribute, please read our contributing guidelines here: [CONTRIBUTING.md](CONTRIBUTING.md)

[![Contributor Covenant](https://img.shields.io/badge/Contributor%20Covenant-2.0-4baaaa.svg)](CONTRIBUTING.md#code-of-conduct) 
Fabric Rewrite (this branch)
------
This branch is a ground-up Fabric mod rewrite of CoreProtect, not a Bukkit/Paper plugin.
It is intentionally isolated from the legacy platform so the codebase can evolve independently.

Key differences from upstream:

- **Platform**: Fabric mod for Minecraft `1.21.11` / Java `21` — no Bukkit or Paper dependency.
- **Storage**: SQLite (default) and MySQL via `config/coreprotect-fabric/coreprotect-fabric.properties`; set `database.in-world=true` to place SQLite under the active world save folder.
- **Build**: `.\gradlew.bat build` → `build/libs/coreprotect-fabric-v0.1.0.jar`
- **Event hooks**: Fabric-native hooks covering block break/place, liquid flow, hoppers, pistons, explosions, entity interactions, item transactions, sign edits, WorldEdit (optional), and more — see source for full list.
- **Commands**: Full `/co` command surface including `lookup`, `rollback`, `restore`, `undo`, `purge`, `inspect`, `near`, `tp`, `migrate-db`, `network-debug`, and `consumer`.
- **Permissions**: LuckPerms via `fabric-permissions-api`; falls back to vanilla op levels.
- **API**: Fabric-native entry point at `net.coreprotect.fabric.api.CoreProtectFabric.getAPI()` plus legacy compatibility shims for `net.coreprotect.CoreProtect`, `net.coreprotect.CoreProtectAPI`, and `net.coreprotect.api.*`.
- **Networking**: `/co network-debug` and lookup result streaming over the `coreprotect:data` channel.
- **Undo state**: Persisted across reload/restart in `config/coreprotect-fabric/undo-sessions.bin`.
