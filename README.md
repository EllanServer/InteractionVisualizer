# InteractionVisualizer — EllanServer Paper 26 fork

This fork is a Paper-only, performance-focused rewrite of
[LOOHP/InteractionVisualizer](https://github.com/LOOHP/InteractionVisualizer).
It visualizes crafting and functional blocks with native display entities while
preserving the familiar commands, preferences, and configuration layout.

## Supported server versions

- Paper **26.1.2** (primary target)
- Paper **26.2** (compile-verified compatibility target)
- Java **25**

Other Minecraft versions, Spigot, and Folia are intentionally unsupported. The
plugin checks the running Minecraft version during startup and disables itself
outside the supported range.

## Rewrite highlights

- Native Paper `ItemDisplay` and `TextDisplay` entities replace Armor Stands and
  raw metadata packets.
- One Paper API implementation replaces the old per-version NMS modules.
- Per-viewer visibility uses Paper's `showEntity` / `hideEntity` API.
- Display updates are revision-coalesced; there is no 5 ms packet scan loop.
- Player/chunk proximity queries use one allocation-light snapshot per world and
  server tick.
- Configuration is loaded with
  [Sparrow YAML](https://github.com/Xiao-MoMi/sparrow-yaml) and flattened into an
  immutable O(1)-lookup snapshot after every reload.
- Gradle replaces the former Maven multi-module build and verifies the same
  sources against both supported Paper API lines.

The other Sparrow modules were deliberately not added: metadata, reflection,
NBT, heart, and Redis messaging either duplicate Paper 26 APIs, reintroduce
version/NMS coupling, or do not serve this single-server rendering path.

## Building

```text
./gradlew clean check shadowJar
```

On Windows:

```text
gradlew.bat clean check shadowJar
```

The production plugin JAR is written to `build/libs/InteractionVisualizer-<version>.jar`.
`check` includes unit tests, the Paper 26.1.2 compilation, and a second compile
against Paper 26.2.

## Optional integrations

- [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) 26.7.2: optional
  custom-item ID recognition. CraftEngine items can be selected by the third
  field of an item-label blacklist rule, and their display pose can be
  overridden in `material.yml` under `CustomItems`. CraftEngine is not bundled
  and the plugin behaves exactly as before when it is absent.
- [LightAPI](https://www.spigotmc.org/resources/lightapi-fork.48247/)
- [OpenInv](https://dev.bukkit.org/projects/openinv)
- [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/)
- Essentials, SuperVanish, PremiumVanish, and CMI

Language resources remain available from
[InteractionVisualizerLanguages](https://github.com/LOOHP/InteractionVisualizerLanguages).

## License and upstream

This project remains licensed under GPL-3.0. Original project pages:

- [GitHub](https://github.com/LOOHP/InteractionVisualizer)
- [Modrinth](https://modrinth.com/plugin/interactionvisualizer)
- [Hangar](https://hangar.papermc.io/LOOHP/InteractionVisualizer)
- [SpigotMC](https://www.spigotmc.org/resources/77050/)
