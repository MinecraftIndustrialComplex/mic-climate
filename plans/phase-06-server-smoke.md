# mic-climate — Phase 6 implementation plan: real-pack dedicated-server smoke test

Goal: an agent-runnable script that installs the actual pack server-side from `pack/`, boots a
headless NeoForge server, verifies every `mic_climate` and `mic_destroy_electric` mixin applied,
drives a scripted scenario over RCON, and reports values — the integration test of the real 158-mod
server side. Items marked *(lookup)* come from the Sonnet lookup report appended below.

## 1. Debug probe command (in `mic_climate`)

`/mic_climate probe [<x> <y> <z>]` (permission 2, defaults to the caller's position or 0 64 0 from
console) printing one line per source, all present sources only:
```
thermoo    : 18.4 °C            (EnvironmentLookup → TEMPERATURE)
climate    : 18.4 °C  cache age 3t
powergrid  : 18.4 °C            (ThermalBehaviour.getAmbientTemperature)
destroy    : 291.55 K           (PollutionHelper.getLocalTemperature)   pollution shift +0.00
lso        : -0.38               (TemperatureUtil.getWorldTemperature)
crowns     : 291.6 K default    (DEFAULT_TEMPERATURE layer at pos, or "section not initialised")
atmosphere : 18.4 °C            (AtmoApi.getCurrentWeather) region (12, -3) applied offset +0.00
```
Plus `/mic_climate invalidate` (clears the Climate cache) and `/mic_climate pollution <0..1>` that
sets Destroy GREENHOUSE to that fraction of max (delegating to `PollutionHelper.setPollution`) so the
scenario does not depend on Destroy's own command syntax. Register via `RegisterCommandsEvent`; each
source line is produced by the corresponding bridge class behind `Compat.isLoaded`.

## 2. Server install (`mods/mic-climate/smoke/`)

`smoke/install.sh` (idempotent, everything under `smoke/server/`, gitignored):
1. `packwiz-installer-bootstrap.jar` *(lookup: C1 URL)* → `java -jar packwiz-installer-bootstrap.jar -g -s server <path or URL to pack.toml>`
   *(lookup: file path vs HTTP; if HTTP is required, serve `pack/` with `python3 -m http.server` for the install)*.
2. NeoForge server installer *(lookup: C2 URL/flags)* `--install-server smoke/server`; version = the
   pack's `pack.toml` `[versions] neoforge`.
3. `eula.txt` = `eula=true`; `server.properties`: `enable-rcon=true`, `rcon.port=25575`,
   `rcon.password=<random, written to smoke/rcon.pw>`, `online-mode=false`, `level-type=minecraft:flat`
   (fast), `spawn-protection=0`, `max-tick-time=-1`, `view-distance=4`, `simulation-distance=4`,
   `enable-command-block=true`; `user_jvm_args.txt` `-Xmx7G`.
4. Copy the pack's `config/` (packwiz-installer does this) — verify `legendarysurvivaloverhaul-common.toml` landed.

## 3. Scenario driver (`smoke/run.py`, Python 3, stdlib only)

- RCON client *(lookup: C3 — mcrcon in nixpkgs, else implement the packet format)*.
- Start `smoke/server/run.sh` as a subprocess, stream `logs/latest.log`; fail fast on
  `InjectionError|MixinTransformerError|MixinApplyError|Mixin apply failed` or a crash report; wait for
  `Done (` (timeout 15 min).
- Assert the mixin log lines: every entry in `mic_climate.mixins.json` and `mic_destroy_electric.mixins.json`
  applied (Mixin logs "Mixing X into Y" at DEBUG — enable `-Dmixin.debug.verbose=true` in JVM args
  for the smoke run).
- Scenario (all via RCON, parsing the probe output):
  1. `mic_climate probe 0 64 0` → all present sources agree: thermoo == climate == powergrid;
     destroy == climate + 273.15; record lso.
  2. `setblock 0 64 0 powergrid:thermometer[facing=north]` + neighbour air; after 120 ticks
     `data get block 0 64 0` → its temperature field ≈ climate *(field name from PG's `ThermometerBlockEntity.write`)*.
  3. `setblock 0 64 2 destroy:vat_controller`, `data get block` → temperature ≈ destroy K.
  4. `mic_climate pollution 1`, wait 60 ticks, `probe` → all rose by ≈ 20 × multiplier; `mic_climate pollution 0` → back.
  5. If PA present: `probe` `atmosphere` line equals thermoo; set config `pollution.mode = ATMOSPHERE`
     via `/mic_climate mode atmosphere` (add this subcommand; it flips the runtime config value),
     pollution 1, wait 2 intervals, `probe` → atmosphere ≈ thermoo ≈ baseline + shift, no double count;
     mode back to MODIFIER → PA offset removed.
  6. If CROWNS present: place `crowns:...` machine, wait, `probe` `crowns` line ≈ destroy K.
  7. `stop`; wait for exit; collect `logs/latest.log`, `logs/debug.log`, crash-reports.
- Output: `smoke/report.md` with each step's expected vs actual, and PASS/FAIL; exit code accordingly.

## 4. Acceptance

- `smoke/install.sh && smoke/run.py` passes on this machine from a clean `smoke/server/`; total wall
  time and peak RSS recorded; the report lists any server-side mod that had to be excluded to boot
  *(lookup: C4)* and why (those exclusions are findings for Ben, not silent fixes).
- Nothing in `pack/` is modified by the smoke test; the server lives entirely under `mods/mic-climate/smoke/server/`.

## Lookup results (2026-09-05) — these override the *(lookup)* markers above

- packwiz-installer bootstrap: `https://github.com/packwiz/packwiz-installer-bootstrap/releases/download/v0.0.3/packwiz-installer-bootstrap.jar`.
  Local path works (source handles http(s), `file:` URI and bare paths): `java -jar packwiz-installer-bootstrap.jar -g -s server /home/greencheetah/Projects/mic/pack/pack.toml`
  (run from `smoke/server/`; `-s server` installs `side = server|both`, drops the 25 client-only mods). Exit 1 on failure.
- NeoForge installer: `https://maven.neoforged.net/releases/net/neoforged/neoforge/<ver>/neoforge-<ver>-installer.jar --installServer <dir>`;
  **`<ver>` must be ≥ 21.1.238** (petrolpark 1.5.6 floor; thermoo-patches needs ≥ 21.1.233; pack.toml currently says 21.1.230 —
  a pack-level decision for Ben; the smoke test installs whatever `pack.toml` says and reports the mismatch if it refuses to boot).
  EULA: write `eula=true` to `eula.txt`. `server.properties` keys as in §2 plus `generate-structures=false`, `online-mode=false`.
- RCON client: `nix run nixpkgs#mcrcon -- -H 127.0.0.1 -P 25575 -p <pw> "<command>"` — no Python client needed.
- Watch-list among server-side mods: Distant Horizons (server LOD component), c2me, Sinytra Connector. Delisted-but-CDN-served: project-atmosphere, gabous-libs (packwiz-installer downloads by URL+hash, so fine).
