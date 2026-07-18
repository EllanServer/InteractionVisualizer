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

- Native Paper `ItemDisplay` and `TextDisplay` entities replace Armor Stands.
- Dropped-item visuals use shaded
  [Sparrow Heart](https://github.com/Xiao-MoMi/sparrow-heart) client-side vanilla
  `ITEM` entities. Empty Paper display anchors retain tracker and chunk lifecycle
  without enabling server item collision physics.
- One Paper API implementation replaces the old in-project per-version NMS modules.
  InteractionVisualizer uses Heart 0.72 only for the client-side item packet path
  on the supported 26.1/26.2 runtimes.
- Per-viewer anchor visibility uses Paper's `showEntity` / `hideEntity` API;
  tracker enter/leave events create and destroy the matching virtual `ITEM`.
- Static items bob and spin entirely client-side with no animation task; only
  active gravity motion needs per-viewer correction because Heart marks its
  fake items as no-gravity.
- Block-to-player pickup uses Minecraft's native take-item packet through one
  isolated reflection bridge. The client supplies the vanilla sound and
  three-tick live-target absorption; arbitrary location-to-location throws keep
  the existing custom motion path. No Sparrow fork or ProtocolLib is required.
- Display updates are revision-coalesced; there is no 5 ms packet scan loop.
- Player/chunk proximity queries use one allocation-light snapshot per world and
  server tick.
- Configuration is loaded with
  [Sparrow YAML](https://github.com/Xiao-MoMi/sparrow-yaml) and flattened into an
  immutable O(1)-lookup snapshot after every reload.
- Gradle replaces the former Maven multi-module build and verifies the same
  sources against both supported Paper API lines.

Other Sparrow modules were deliberately not added: metadata, reflection, NBT,
and Redis messaging do not serve this single-server rendering path.

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

## Performance rollout and diagnostics

New installations enable the bounded visibility restore, dropped-label spatial
culling, static packet items, static animation anchors, and coordinated block
updates. Existing explicit `false` values remain unchanged and produce one
migration reminder. Every path has its own rollback switch in `config.yml`;
event-driven block updates require a restart, while the other switches reload.

Use `/iv perf start <label>` and `/iv perf stop` around a stable sample window.
The resulting `IV_PERF` JSON includes viewer candidates/full reconciles,
dropped-item spatial and full-scan candidates, block queues, preference I/O and
SQL operations, packet operations, and anchor entity operations. Every plugin
disable also emits `IV_PERF_SHUTDOWN`; `totalRetained` should be zero in repeated
enable/disable leak tests.

## Optional integrations

- [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) 26.7.2: optional
  custom-item ID recognition. CraftEngine items can be selected by the third
  field of an item-label blacklist rule, and their display pose can be
  overridden in `material.yml` under `CustomItems`. It also supplies display
  lighting: InteractionVisualizer shares its light-block reference counts so
  CraftEngine furniture and IV displays do not remove each other's light, and
  performs no lighting task while idle. With `Settings.HideIfViewObstructed`,
  IV registers only its sent-chunk candidates in CraftEngine's entity-culling
  API for a second-stage ray-traced wall-occlusion check. CraftEngine is not
  bundled and the plugin behaves exactly as before when it is absent.
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
