# Phase 2 evidence snapshot at `b93d990`

This snapshot separates formal evidence from smoke and local client measurements. It records what was actually
validated for Draft PR #3 before the dropped-label controls were changed to opt-in defaults.

## GitHub checks on the measured commit

- [Java 25 build](https://github.com/EllanServer/InteractionVisualizer/actions/runs/29264707559): passed.
- [A/B benchmark](https://github.com/EllanServer/InteractionVisualizer/actions/runs/29264708714): passed.
- [Packet ABBA](https://github.com/EllanServer/InteractionVisualizer/actions/runs/29264707795): passed as a four-run smoke check.
- [Runtime ABBA](https://github.com/EllanServer/InteractionVisualizer/actions/runs/29264710083): passed as a four-run smoke check.
- [Formal 12-run server A/B](https://github.com/EllanServer/InteractionVisualizer/actions/runs/29266067426): passed for the
  `legacy-text-component-cache / block-active` factor only.

The formal server run measured MSPT mean `5.0411 ms -> 3.8049 ms` (`23.85%` lower), MSPT p95
`7.4554 ms -> 5.0209 ms`, and MSPT p99 `10.2296 ms -> 7.2738 ms`. These numbers do not validate the
dropped-label visibility controls or every Phase 2 feature.

## Packet smoke boundary

The four-run static-spawn smoke observed Minecraft protocol events `7168 -> 2048` (`71.43%` lower) and Bukkit
entity spawns `1024 -> 0` for the packet-only candidate. It is useful regression evidence, but it is not the required
12-run packet gate and must not be presented as one.

## Local client `true-false-true` measurement

The final usable client comparison kept the same candidate JAR, world, client, fixed noon lighting, disabled mob
spawning, and one 512-item scene. Only `PacketOnlyStatic` changed. Each group contains six successful frame CSVs:

- B1 `PacketOnlyStatic=true`: six windows.
- A `PacketOnlyStatic=false`: six windows.
- B2 `PacketOnlyStatic=true`: six windows.

Aggregating B1+B2 against A produced:

| Metric | `false` | `true` | Observed change |
|---|---:|---:|---:|
| Present FPS | 1098.02 | 1310.50 | +19.42%; paired 95% CI +15.55% to +23.30% |
| Display FPS | 234.43 | 239.20 | Near the 240 Hz ceiling |
| 1% low FPS | 117.94 | 177.53 | Higher |
| Frames over 8.333 ms | 0.961% | 0.112% | 88.34% lower |

The per-file names, sizes, and SHA-256 values are preserved in
[`evidence/b93d990-frameview-manifest.csv`](evidence/b93d990-frameview-manifest.csv). The raw frame CSVs are not
committed to the repository, so this section is a durable result/provenance snapshot rather than self-contained raw
evidence. It does not replace the compatibility checklist or a formal packet run.

## Rollout boundary

`PacketOnlyStatic`, generic visibility limiting, and event-driven block updates remain disabled by default. The
dropped-label server-side distance culling and dedicated rate limiter must also remain opt-in until V2 capture,
multi-viewer, re-entry, pending cancellation, ghost, and production protocol-stack validation are complete.
