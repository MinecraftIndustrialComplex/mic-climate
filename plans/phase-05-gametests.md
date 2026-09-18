# mic-climate — Phase 5 implementation plan: headless GameTest suite

Goal: a `./gradlew runGameTestServer` task in `mods/mic-climate` that boots a headless NeoForge
server with every bridged mod on the runtime classpath, runs `@GameTest` methods asserting the
connector's behaviour, and exits non-zero on failure — so an agent can prove the mixins bind and
the values agree without a screen. Items marked *(lookup)* are filled from the Sonnet lookup report
appended below when it arrives; the builder must not guess them.

## 1. Build changes (`build.gradle`, `gradle.properties`)

- Add a run: `neoForge.runs { gameTestServer { type = "gameTestServer"; systemProperty "neoforge.enabledGameTestNamespaces", "mic_climate"; ... } }` *(lookup: exact MDG 2.0.74 syntax, task name, exit code on failure)*.
- Runtime classpath: today the bridged mods are `compileOnly`. Add a `localRuntime`/`runtimeOnly`
  configuration wired into the run (mic-destroy-electric already has `configurations { runtimeClasspath.extendsFrom localRuntime }`)
  containing the full graph *(lookup: table B)*: thermoo + forgified-fabric-api; thermoo-patches + YACL;
  power-grid + architectury-api + Create stack (create slim, ponder, flywheel, registrate — already
  `implementation`); destroy + petrolpark (local); legendary-survival-overhaul; project-atmosphere
  (local jar in `libs/`) + serene-seasons + serene-seasons-plus + simple-clouds + whatever they require;
  create-crowns + FormicAPI. Prefer `maven.modrinth:<slug>:<versionId>`; local files where Modrinth
  cannot serve. Keep `compileOnly` for API compilation exactly as it is — only the run's classpath grows.
- Also put `mic-destroy-electric`'s jar on the runtime classpath (`files(../mic-destroy-electric/build/libs/...)`)
  so the vat thermal behaviour and smog dimming are present, matching the pack.
- Memory: `-Xmx6G` for the gametest JVM (machine has 15 GB, ~7 free).
- The test sources live in `src/main/java/.../mic_climate/gametest/` behind a `@GameTestHolder`
  class, registered via `RegisterGameTestsEvent` *(lookup: A2)*; production jar may include them
  (they are inert without the gametest server) or exclude via a `src/gametest` source set if MDG
  makes that easy — builder's call, document it.

## 2. Tests (all server-side, no player needed)

Use an empty template *(lookup: A2 — `template = ""`/`EmptyTemplate`)* unless a structure is needed.
Each test lives in `MicClimateGameTests` with `@PrefixGameTestTemplate(false)` and a
`@GameTest(templateNamespace = "mic_climate", template = "...")` per method.

| test | steps | assertion |
|---|---|---|
| `providerIsSelected` | at the test's absolute pos, call `EnvironmentLookup.getInstance().findEnvironmentComponents(level, pos)` | map has `EnvironmentComponentTypes.TEMPERATURE`; value equals `Climate.celsius(level, pos)` within 0.01 (Thermoo attaches definitions at `SERVER_STARTED`, which has happened by the time tests run) |
| `powerGridAmbientFollowsClimate` | `ThermalBehaviour.getAmbientTemperature(level, pos)` | equals `Climate.celsius` within 0.01 — proves the PG mixin bound |
| `destroyLocalTemperatureFollowsClimate` | `PollutionHelper.getLocalTemperature(level, pos)` | equals `Climate.kelvin` within 0.01 — proves the Destroy mixin bound |
| `pollutionWarmsOnce` | read baseline T; `PollutionHelper.setPollution(level, GREENHOUSE, max)`; `Climate.invalidate(level)`; wait ≥ 25 ticks (shift cache) | Thermoo/PG/Destroy all rose by the same amount (≈ 20 K × multiplier); restore pollution to 0 afterwards |
| `thermalDeviceCacheRefreshes` | place a PG device with a `ThermalBehaviour` (e.g. `powergrid:basin_heater` unpowered) at a relative pos; raise pollution; run 120 ticks | the device's `cachedAmbientTemperature` (read via an accessor mixin or reflection) moved to the new ambient — proves the tick refresh inject |
| `lsoWorldTemperatureIncludesClimate` | `TemperatureUtil.getWorldTemperature(level, pos)` with and without `ClimateConfig.lso.enabled` (toggle via the config value or a test hook) | difference equals `(Climate.celsius − neutral) × unitsPerDegree` within 0.05 |
| `lsoDeviceHeatFromHotBehaviour` | place `powergrid:basin_heater`, set its `ThermalBehaviour.setTemperature(1600)` via `BlockEntityBehaviour.get` | `TemperatureUtil.getWorldTemperature` at 1 block ≈ +0.63 more than at 12 blocks |
| `vatThermalBehaviourVisible` (needs mic-destroy-electric on classpath) | place `destroy:vat_controller`; `BlockEntityBehaviour.get(be, ThermalBehaviour.TYPE)` | non-null and `getTemperature()` finite |
| `crownsDefaultLayerSeedsFromClimate` (only if CROWNS + FormicAPI resolve) | place a CROWNS machine block so the section becomes "near dynamic"; wait for `PhysicsWorldData.initialise` (poll up to 200 ticks) | the `DEFAULT_TEMPERATURE` layer value at an air cell ≈ `Climate.kelvin` (read via CROWNS' public getters found in phase 3) |
| `atmosphereModeSkipsProviderShift` (only if PA resolves) | set `pollution.mode = ATMOSPHERE`, raise pollution, wait 2 intervals | Thermoo value equals PA's `AtmoApi.getCurrentWeather(...).temperatureC()` within 0.5 (no double count); switching back to MODIFIER within the same test removes the PA offset once |

Config toggling in tests: prefer a small `ClimateConfig.forTest(...)` override hook guarded by a
system property (`mic_climate.gametest=true`) rather than editing TOML at runtime.

## 3. Runner

`mods/mic-climate/gametest.sh`: `nix develop -c ./gradlew runGameTestServer --console=plain 2>&1 | tee build/gametest.log`
then grep the summary lines *(lookup: what the gametest server prints on pass/fail)* and exit accordingly.
Document expected runtime and the log lines an agent should grep (`Mixin apply`, `InjectionError`,
`GameTest` summary).

## 4. Acceptance

- `gametest.sh` exits 0 with all tests green on this machine; a deliberately broken assertion exits non-zero.
- The mixin load log shows all `mic_climate` mixins applied and none skipped (all optional mods present).
- Report: runtime dependency table actually used (coordinates), test list with results, run time,
  memory, and anything that could not be tested headlessly.

## Lookup results (2026-09-05) — these override the *(lookup)* markers above

**Run config (MDG 2.0.74):** no `gameTestServer()` shorthand; declare
```gradle
neoForge { runs { gameTestServer { type = "gameTestServer"; property 'neoforge.enabledGameTestNamespaces', 'mic_climate' } } }
```
Task `runGameTestServer`; exit code = number of required failed tests (0 on success). No extra program args.

**Registration API (verified in the NeoForge 21.1.230 sources jar):** class annotation
`net.neoforged.neoforge.gametest.GameTestHolder(String templateNamespace)`, `PrefixGameTestTemplate(boolean)`,
mod-bus `RegisterGameTestsEvent.register(Class)`; test methods use vanilla `net.minecraft.gametest.framework.GameTest`
(fields `timeoutTicks`, `batch`, `required`, `templateNamespace`, `template`, `setupTicks`, `attempts`...).
**An empty `template` is NOT allowed** with this path — `StructureUtils.prepareTestStructure` throws
`IllegalStateException("Missing test structure")`; NeoForge's separate `testframework` with `@EmptyTemplate` is not
in the 21.1.230 jar. So ship one tiny structure `data/mic_climate/structure/empty_5x5x5.nbt` (a 5×5×5 air box on a
1-thick floor — generate it with `StructureTemplate` in a one-off, or hand-write the NBT with a small script; vanilla
structure NBT is documented) and reference it from every test. `GameTestHelper`: `setBlock(BlockPos, BlockState)`,
`<T extends BlockEntity> T getBlockEntity(BlockPos)` (relative), `absolutePos`, `succeed()`, `fail(String)`,
`succeedWhen(Runnable)`, `runAtTickTime(long, Runnable)`.

**NeoForge version:** bump `neo_version` in `gradle.properties` to **21.1.238** or later (21.1.249 is the newest
verified on maven.neoforged.net) — petrolpark 1.5.6 requires `[21.1.238,)` and thermoo-patches `[21.1.233,)`; the
runtime would refuse to load at 21.1.230. Keep `neo_version_range` compatible.

**Runtime classpath (all verified Modrinth version ids; `maven.modrinth:<slug>:<id>` unless noted):**
| mod | coordinate | needs |
|---|---|---|
| thermoo | `thermoo:T0vIbHHv` | fabric_api |
| forgified-fabric-api | `forgified-fabric-api:V9WdDUTx` | — |
| thermoo-patches | `thermoo-patches:KYaqnBiE` | yacl, thermoo |
| yacl | `yacl:7TVdVtxF` | — |
| power-grid | `power-grid:ip4gJrgx` | architectury, create |
| architectury-api | `architectury-api:1IiqEQGl` (byte-identical to the pack's CurseForge pin) | — |
| create (full) | `create:UjX6dr61` — use the FULL jar at runtime, not the `slim` compile artifact | ponder, flywheel, registrate (already `implementation`) |
| destroy | `files("../Destroy/a/b/c/build/libs/destroy-1.21.1-0.4.1.jar")` | create, petrolpark |
| petrolpark | `petrolpark:OpfPig4I` (or the flatDir 1.5.6 jar; identical) | neoforge ≥21.1.238 |
| legendary-survival-overhaul | `legendary-survival-overhaul:AZzEduN3` | — |
| project-atmosphere | `files("libs/NeoForge-projectatmosphere-0.8.1.0.jar")` (project delisted; CDN still serves) | sereneseasons, sereneseasonsplus, simpleclouds |
| serene-seasons | `serene-seasons:B6nTjh4b` | glitchcore |
| glitchcore | pack pin (read `pack/mods/glitchcore.pw.toml` for the id) | — |
| serene-seasons-plus | `serene-seasons-plus:nYao5aRK` | sereneseasons, gaboulibs |
| gabous-libs | delisted on Modrinth; download the CDN URL from `pack/mods/gabous-libs.pw.toml` into `libs/` (compile-time no, runtime `files(...)`) | — |
| simple-clouds | `simple-clouds:qpinHX2C` (crackerslib is jar-in-jar) | — |
| create-crowns | `create-crowns:oXIJWFli` | create, formicapi |
| formicapi | `formicapi:KFLSWnM1` (2.4.2) | — |
| mic_destroy_electric | `files("../mic-destroy-electric/build/libs/mic_destroy_electric-1.21.1-1.0.0.jar")` | — |
Registrate has no mods.toml (plain library). Put all of these on a `localRuntime` configuration that the
`gameTestServer` run's classpath extends; keep the existing `compileOnly` lines untouched.

**Ben's addition (2026-09-05): real-client headless run via mc-runtime-test.** After the server suite is green, add a
second job using `headlesshq/mc-runtime-test` (MIT; supports NeoForge 1.21–1.21.11; runs the real modded client under
HeadlessMC + Xvfb, joins a singleplayer world and runs registered GameTests). Study its README for the local (non-GitHub-
Actions) invocation and the `-Dmc-runtime-test.*` properties; wrap it in `gametest-client.sh` using `nix shell nixpkgs#xvfb-run`.
Client-side tests to add there: the client `Climate` read returns the Thermoo default (documents the known limitation),
and a PG thermometer item/block renders without exception. If mc-runtime-test cannot run locally on NixOS in a
reasonable time, report what blocked it and leave the script + tests in place for CI.
