# mic-climate — temperature connector for the MIC pack

Written 2026-09-04. Status: **in implementation (2026-09-05): phases 0–5 ALL BUILT; the phase-5 headless GameTest suite is green (16/16) on this machine, so the mixins are proven to bind and the four bridges to agree. Still NOT deployed to `pack/` and never run in a real client.** Mod id `mic_climate`. Lives at `mods/mic-climate`.

Per-phase implementation plans: `plans/phase-01-core.md`, `plans/phase-02-lso.md`, `plans/phase-03-crowns.md`, `plans/phase-04-atmosphere.md` (they override this file where they are more specific).

## 1. Goal

One temperature number per position that every temperature-aware mod in the pack reads, so a
transformer, a vat, a nuclear reactor's surroundings and the player's body all experience the
same winter, the same desert noon and the same polluted sky. No new API of our own: the shared
currency is **Thermoo**'s environment lookup, and this mod is the *connector* that feeds it and
that makes the other mods read it. Mods keep their own units and their own physics; only the
ambient input changes.

Non-goals: unifying Create's discrete heat levels (the pack's KubeJS already does that); changing
any mod's balance beyond making it climate-aware; touching Thermoo's or any bridged mod's source.

## 2. Why this shape (from the research in `mods/mic-destroy-electric/PLAN-pg-0.6.md`, Parts 3–4)

- Serene Seasons and Project Atmosphere are invisible to Destroy and Power Grid today: both read
  the static `Biome.getBaseTemperature()`.
- Thermoo is the only neutral, unit-aware, LGPL temperature library with a NeoForge 1.21.1 build,
  and its public API already has every hook needed; **zero Thermoo changes**.
- Every other mod here is all-rights-reserved or has no API, so a "register with us" library
  cannot work; a connector that reaches into each mod on its own terms can.
- Thermoo Patches already bridges Serene Seasons → Thermoo seasons; we depend on it instead of
  redoing that.

## 3. Reference facts (verified 2026-09-04)

Thermoo `1.21.1-neoforge` (last commit 2026-03-09; scratch clone under this session's scratchpad):
- `ThermooRegistries.ENVIRONMENT_PROVIDER_TYPE` is an open `Registry<EnvironmentProviderType<?>>`.
- `EnvironmentProvider`: `void buildCurrentComponents(Level, BlockPos, Holder<Biome>, DataComponentMap.Builder)`
  + `getType()`; types are `new EnvironmentProviderType<>(MapCodec)`.
- `EnvironmentDefinition` is a datapack registry entry: `biomes` (HolderSet), `exclude_biomes`,
  `provider` (holder), `priority` (default 1000; lookup sorts descending).
- Read: `EnvironmentLookup.getInstance().findEnvironmentComponents(Level, BlockPos)` →
  `DataComponentMap`; `EnvironmentComponentTypes.TEMPERATURE` is a `DataComponentType<TemperatureRecord>`; `TemperatureRecord(value, TemperatureUnit)`
  is the value class and `TemperatureRecordComponent` is the holder of its `CODEC` and `DEFAULT` (20 °C) — two classes, not a naming slip; `TemperatureUnit` has CELSIUS/KELVIN/FAHRENHEIT with converters.
- Seasons: `ThermooSeasonEvents.GET_CURRENT_SEASON` (Fabric-API event; Thermoo's NeoForge build
  requires `fabric_api`, i.e. forgified-fabric-api, which the pack already ships).
- Thermoo Patches NeoForge (2026-06-08): 18 files / ~870 lines; has the Serene Seasons bridge.

Project Atmosphere 0.8.1.0 (`~/Projects/WinddPhys/Project-Atmosphere`, third-party, custom
licence: addons allowed, no redistribution/modification):
- Read (server only): `AtmoApi.getInstance().getCurrentWeather(ServerLevel, BlockPos)` →
  `WeatherSnapshot(cloudCover, rainIntensity, temperatureC, windSpeedMps, windAngleRad, isStorming, isSnowing)`.
- Client mirror: `client.BiomeClientTemperatureCache.getTemperature(ResourceLocation biome, Level)`.
- Write: `AtmosphericStateRegistry.getState(RegionInstanceKey)` → `RegionAtmosphereState` with
  `getTemperature()`, `setTemperature(float)`, `adjustTemperature(float)`. `AtmoApi.registerWorldEffect(...)`.
- Vanilla→°C mapping used by PA itself: −0.5…2.0 → −20…+56 °C.

Destroy 0.4.1: `PollutionHelper.getLocalTemperature(Level, BlockPos)` (K) =
`LevelPollution.getOutdoorTemperature()` (289 K + greenhouse ≤20 K + ozone ≤4 K, honours
`enablePollution`/`temperatureAffected`) + `10 × biomeBase`. Consumers: vat Fourier cooling and
empty-vat reading, distillation tower, basin reactions. No override hook; mixin only.

Create: Power Grid 0.6.1: `ThermalBehaviour.getAmbientTemperature(Level, BlockPos)` (°C) =
`13.65 × biomeBase + 7.1`; called by `ThermalBehaviour.tick` (cached once on first tick),
`BaseWireEntity` (live), `LightBulbState` (cached `Float`, nulled to refresh), solar panels and
ceiling-tile solar (`ambientTemp` on first tick; bearing every tick), thermometer, circuit boards.

Legendary Survival Overhaul 2.4.7.2 (ARR): `TemperatureUtil.getWorldTemperature(Level, BlockPos)`;
world temperature = sum of `ModifierBase.getWorldInfluence(Player, Level, BlockPos)` over entries
of **LSO's own** `TemperatureModifierRegistry.MODIFIERS` DeferredRegister (a separate
DeferredRegister on the same key is ignored — verified in bytecode). Built-in modifiers and their
config switches (`legendarysurvivaloverhaul-common.toml`): biome (`"Biomes affect Temperature"`,
`"Biome Temperature Multiplier" = 18`), serene seasons (`"Serene Seasons Enabled"`), time
(`"Time Based Temperature Modifier" = 2`, shade), weather (rain −2, snow −6), altitude (−6),
wetness, freeze, blocks (datapack JSON, blockstate-only), huddling, sprint, on-fire. Precedent:
with TerraFirmaCraft installed LSO disables biome/time/season/altitude and uses TFC instead.

Create: CROWNS (MIT, `1.21.1-dev`, 2026-07-20): per-block kelvin temperature field.
`PhysicsSaveManager.getDefaultTemperature(LevelChunkSection, Vec3i, BlockState)` seeds each cell
from `CROWNS.BIOME_TEMPERATURES.getValue(biome, 300f)` (datapack `data/crowns/float_map/biomes/temperatures.json`,
e.g. frozen ocean 273, desert 320), overridden by block/fluid tables; the solver's resilience term
pulls cells back toward that default layer (`TemperatureSolver.java:317-320`). `IHaveTemperature`
block entities conduct with neighbours. No API package.

## 4. Architecture

```
                 ┌───────────────── producers ─────────────────┐
 Project Atmosphere (°C, server)  Thermoo defaults + Thermoo Patches (seasons)
                 └──────────────┬──────────────────────────────┘
                                ▼
            mic_climate:unified EnvironmentProvider  (+ Destroy pollution delta)
                                ▼
                 Thermoo EnvironmentLookup  ── TemperatureRecord ──►  Climate.celsius(level, pos)
                                                                        │ (cached per chunk, 20 ticks)
        ┌──────────────┬──────────────┬──────────────┬─────────────────┘
        ▼              ▼              ▼              ▼
   Power Grid       Destroy          LSO           CROWNS
   getAmbient…   getLocalTemp…   world modifier   biome default layer
```

One producer, one currency, four consumers. Destroy's pollution enters once, at the producer.

### 4.1 Module layout

```
mods/mic-climate/
  build.gradle, gradle.properties, flake.nix        (copy from mic-destroy-electric; NeoForge 21.1.x, MDG 2.0.x)
  src/main/java/com/minecraftindustrialcomplex/mic_climate/
    MicClimate.java                 mod entry: registers provider type, config, LSO modifiers, PA push
    Climate.java                    static celsius(Level, BlockPos) / kelvin(...) over Thermoo + per-chunk cache
    Compat.java                     isLoaded(modid) helpers (LoadingModList / ModList)
    config/CClimate.java, ClimateConfigs.java   (catnip ConfigBase pattern as in mic-destroy-electric)
    provider/UnifiedEnvironmentProvider.java    Thermoo provider type mic_climate:unified
    provider/ProjectAtmosphereSource.java       server + client PA reads, gated on modid
    provider/DestroyPollutionShift.java         delta from LevelPollution (moved from mic-destroy-electric)
    lso/ClimateWorldModifier.java, lso/DeviceHeatModifier.java
    atmosphere/PollutionAtmosphereEffect.java   pollution → PA push (experimental)
    mixin/powergrid/ThermalBehaviourMixin, LightBulbStateMixin, SolarPanelBlockEntityMixin,
                    CeilingTileSolarBlockEntityMixin            (ambient source + cache refresh)
    mixin/destroy/PollutionHelperMixin
    mixin/crowns/PhysicsSaveManagerMixin (+ whatever the spike finds)
    mixin/MicClimateMixinPlugin.java    IMixinConfigPlugin: apply mixin/<mod>/* only if <mod> is loaded
  src/main/resources/
    mic_climate.mixins.json  (plugin = MicClimateMixinPlugin)
    data/mic_climate/thermoo/environment_definition/overworld.json   (priority 2000, #minecraft:is_overworld)
    data/mic_climate/thermoo/environment_provider/unified.json
  src/main/templates/META-INF/neoforge.mods.toml
```
Check Thermoo's actual datapack folder names for definitions/providers in its own resources before
writing the JSON (registry keys `ThermooRegistryKeys.ENVIRONMENT_DEFINITION` / `ENVIRONMENT_PROVIDER`).

### 4.2 Dependencies (`neoforge.mods.toml`)

| modId | type | note |
|---|---|---|
| thermoo | required `[4.8,)` | NeoForge build 4.8.1-neoforge; Modrinth project `thermoo` |
| fabric_api | required | forgified-fabric-api, Thermoo needs it; already in pack |
| thermoo_patches | optional, recommended | Serene Seasons → Thermoo seasons |
| powergrid, destroy, projectatmosphere, legendarysurvivaloverhaul, crowns | optional, `AFTER` | each bridge is gated |

Compile deps: Thermoo from Modrinth maven; PG/Destroy/LSO/CROWNS as compileOnly (Modrinth maven
or local jar file deps as mic-destroy-electric does); Project Atmosphere from CurseMaven or a
local jar (not redistributed, compileOnly only).

## 5. Components

### 5.1 `Climate` — the one read path

```java
public static float celsius(Level level, BlockPos pos)   // cached per (level, chunk) for cacheTicks (default 20)
public static float kelvin(Level level, BlockPos pos)    // celsius + 273.15
```
Implementation: `EnvironmentLookup.getInstance().findEnvironmentComponents(level, pos)
  .getOrDefault(EnvironmentComponentTypes.TEMPERATURE, TemperatureRecordComponent.DEFAULT)`
converted to Celsius via its `TemperatureUnit`. Works on both sides (Thermoo runs the lookup
client-side too). Cache: `WeakHashMap<Level, Long2ObjectMap<Sample>>` keyed by chunk, same style as
`PollutionTemperature` today, because Power Grid wires call the ambient lookup every tick.
Try/catch → 20 °C for early-load paths (ponder virtual levels, title-screen scenes).

### 5.2 `mic_climate:unified` Thermoo provider (the producer)

Codec fields: `base` (optional `Holder<EnvironmentProvider>` — a Thermoo built-in seasonal/biome
provider referenced from the datapack, used when Project Atmosphere is absent or on paths PA
cannot answer), `pollution` (bool, default true).

`buildCurrentComponents(level, pos, biome, builder)`:
1. If `config.source != THERMOO` and PA is loaded, `T = ProjectAtmosphereSource.celsius(level, pos)`
   (`PROJECT_ATMOSPHERE` with PA absent logs a warning once and behaves like `AUTO`): server →
   `AtmoApi.getCurrentWeather((ServerLevel) level, pos).temperatureC()`; client →
   `BiomeClientTemperatureCache.getTemperature(biomeId, level)`. Any exception or NaN → empty.
2. Else delegate to `base.buildCurrentComponents(...)` and read back the temperature it set (if
   the builder cannot be read back, build the base into a temporary `DataComponentMap` first).
3. If Destroy is loaded and `pollution` and `config.pollution.mode == MODIFIER`:
   `T += DestroyPollutionShift.shift(level)` (= `getOutdoorTemperature() − 289` × multiplier,
   cached 20 ticks per level, 0 on any exception — this is today's `PollutionTemperature`).
4. `builder.set(EnvironmentComponentTypes.TEMPERATURE, new TemperatureRecord(T, CELSIUS))`.
   Leave humidity to the base provider (or set from PA's snapshot when available — optional).

Datapack: one `EnvironmentDefinition` for `#minecraft:is_overworld` at priority 2000 pointing at
`mic_climate:unified` with `base` = Thermoo's temperate-seasonal provider (check its id in
Thermoo's own data). Nether/End keep Thermoo's defaults. No `neoforge:conditions` guard is needed on the definition:
thermoo is a required dependency, so the datapack registry always exists.

### 5.3 Power Grid consumer

`@ModifyReturnValue(method = "getAmbientTemperature")` on `ThermalBehaviour` → `Climate.celsius(level, pos)`
(config `powergrid.enabled`). Plus the cache-refresh injects that exist today in
mic-destroy-electric: `ThermalBehaviour.tick` HEAD re-sampling the shadowed
`cachedAmbientTemperature` every 100 ticks; `LightBulbState.tick` HEAD nulling `Float cachedAmbientTemperature`;
`SolarPanelBlockEntity`/`CeilingTileSolarBlockEntity` `electricalTick` HEAD re-sampling `ambientTemp`
(the bearing already re-samples every tick). Note the two intervals: `Climate` caches the value per chunk for
`cacheTicks` (20), but a PG device only re-reads it every 100 ticks, so device freshness is bounded by the latter. Effect: every device, wire, bulb, panel, the
thermometer block and circuit boards follow PA weather and seasons.

### 5.4 Destroy consumer

`@ModifyReturnValue(method = "getLocalTemperature")` on `PollutionHelper` → `Climate.kelvin(level, pos)`
(config `destroy.enabled`). Full replacement: the pollution term is already inside the unified
value (§5.2 step 3), so nothing is counted twice; Destroy's `enablePollution`/`temperatureAffected`
configs are honoured because the shift is derived from `getOutdoorTemperature()`. Effect: vats cool
toward the real ambient, empty vats read it, distillation and basin reactions follow seasons.

### 5.5 Legendary Survival Overhaul consumer

Two `ModifierBase` entries registered **on LSO's own** `TemperatureModifierRegistry.MODIFIERS`
from `MicClimate`'s constructor (LSO's class must be initialised first: declare `AFTER` ordering;
entries land under LSO's namespace, e.g. `legendarysurvivaloverhaul:mic_climate_world` — acceptable,
document it). Gate the whole class behind `Compat.isLoaded("legendarysurvivaloverhaul")` and keep
LSO imports out of `MicClimate` itself (load via reflection-free indirection: a separate
`LsoBridge` class only touched when loaded).

- `ClimateWorldModifier.getWorldInfluence(player, level, pos)` =
  `(Climate.celsius(level, pos) − lso.neutralCelsius) × lso.unitsPerDegree`; defaults
  `neutralCelsius = 20`, `unitsPerDegree = 0.24` (LSO's own biome modifier spans 0…18 units over
  PA's −20…56 °C, i.e. ≈0.24 units/°C). Sample the same 9-point neighbourhood LSO's biome
  modifier uses if the single-point value looks too jumpy at biome borders.
- `DeviceHeatModifier`: within LSO's influence distance, for each block entity carrying
  `ThermalBehaviour.TYPE` (PG devices; Destroy vats via mic-destroy-electric feature 4) add
  `max(0, (T − 22) × deviceHeat.tempScalar/100)` blended to 0 over `(T − 22) × deviceHeat.rangeScalar/100`
  blocks — Power Grid's own dormant Cold Sweat formula (`ElectricBlockTemp`), defaults 0.04 and 0.5.
  Reuse LSO's block-scan radius/shape and cache per player for a few ticks; skip when `T ≤ 22`.
- **Pack config change (not code):** with PA driving, zero LSO's overlapping built-ins so seasons,
  time of day and weather are not counted twice: `"Biomes affect Temperature" = false`,
  `"Serene Seasons Enabled" = false`, `"Time Based Temperature Modifier" = 0`,
  `"Rain Temperature Modifier" = 0`, `"Snow Temperature Modifier" = 0`. Keep altitude, shade,
  wetness, freeze, blocks, huddling, sprint, on-fire. Ship as `pack/config/legendarysurvivaloverhaul-common.toml`.
  Existing LSO block JSONs in `pack/kubejs/data/*/legendarysurvivaloverhaul/temperature/blocks/`
  stay (blockstate heat for burners/furnaces); the basin heater JSON becomes redundant once
  `DeviceHeatModifier` exists — remove it then to avoid double heat.

### 5.6 CROWNS consumer (phase 3, after a source spike)

Target: the biome default layer. `@ModifyExpressionValue` on the
`CROWNS.BIOME_TEMPERATURES.getValue(biome, 300f)` call in `PhysicsSaveManager.getDefaultTemperature`
returning `Climate.kelvin(level, pos)`. Two things the spike must settle: (a) that method receives a
`LevelChunkSection`, not a `Level` — find the caller that has the level (or capture it in a
thread-local set by the caller) and the absolute position; (b) the default layer is computed at
chunk init and the solver only relaxes toward it, so seasons will not move it — find where CROWNS
regenerates/stores the default layer and add a periodic refresh (e.g. once per in-game hour per
loaded section) or a refresh on Thermoo season change. Budget 2–4 h; if (b) has no clean seam,
ship (a) only and document that CROWNS' field follows the climate at chunk (re)load.

### 5.7 Pollution → Project Atmosphere (phase 4, experimental, default off)

Config `pollution.mode = MODIFIER | ATMOSPHERE`. In `ATMOSPHERE` mode the unified provider stops
adding the Destroy delta (§5.2 step 3) and a server-tick handler (every 100 ticks) applies
`state.setTemperature(forecastTemperature + shift)` per loaded `RegionAtmosphereState`, where
`forecastTemperature` is the value PA would have produced unmodified and `shift` is the same
`DestroyPollutionShift.shift(level)` used in MODIFIER mode, so Destroy's `enablePollution`/`temperatureAffected`
switches are honoured in both modes (they zero the shift at its source). Verify in PA source how
`RegionAtmosphereState` is refreshed from the forecast each tick (`fromForecast`, any
`relaxTowardBase`) before choosing `set` vs `adjust`; `adjust` accumulates and must not be called
per tick with the same delta. Uses only PA's public API (its licence permits addons, forbids
modification; no mixins into PA anywhere in this mod).

## 6. Migration out of mic-destroy-electric

Move, do not duplicate: `content/PollutionTemperature`, `mixin/ThermalBehaviourMixin`,
`mixin/LightBulbStateMixin`, the `electricalTick` ambient-refresh injects in
`SolarPanelBlockEntityMixin` and `CeilingTileSolarBlockEntityMixin` (keep their smog-dimming
`@ModifyReturnValue`s — that is a Destroy×PG feature), and config `pollution.temperatureMultiplier`
(→ `mic_climate` `pollution.multiplier`). `mic-destroy-electric` keeps dynamo, blacklight, basin
heater→vat, redstone converter, smog→solar, vat thermal behaviour, and gains an optional
`mic_climate` dependency in its mods.toml (documentation only; the two mods' mixins touch different
methods, so load order between them does not matter and mic_climate declares no dependency back). Alternative if standalone use matters: keep them in
mic-destroy-electric behind a mixin-plugin check that disables them when `mic_climate` is present.
Recommendation: move; the pack always ships both.

## 7. Config (`mic_climate-common.toml`)

| key | default | meaning |
|---|---|---|
| `source` | `AUTO` | `AUTO` (PA if loaded, else Thermoo base), `PROJECT_ATMOSPHERE`, `THERMOO` |
| `cacheTicks` | 20 | per-chunk cache of the unified value |
| `pollution.mode` | `MODIFIER` | `MODIFIER` adds Destroy's delta in the provider; `ATMOSPHERE` pushes it into PA (§5.7) |
| `pollution.multiplier` | 1.0 | scales Destroy's greenhouse/ozone delta (was mic-destroy-electric's) |
| `powergrid.enabled` / `destroy.enabled` / `lso.enabled` / `crowns.enabled` | true | per-bridge switches |
| `lso.neutralCelsius`, `lso.unitsPerDegree` | 20, 0.24 | world modifier mapping |
| `lso.deviceHeat.enabled`, `.tempScalar`, `.rangeScalar` | true, 0.04, 0.5 | device proximity heat |
| `deepTime.enabled`, `.weatherAnomaly`, `.maxAnomaly` | true, true, 20 | phase 7: Deep Time's climate as the base |
| `deepTime.projectAtmosphereBase` | true | phase 8: Deep Time's climate as Project Atmosphere's base |
| `deepTime.hemisphereSeasons`, `.fullSeasonLatitude` | true, 45 | phase 9: seasons by latitude on Deep Time planets |

## 8. Licensing

- Thermoo LGPL-3.0: depending on and linking it is fine; no source changes.
- Project Atmosphere (by Gabou/xGabou; its jar declares "All Rights Reserved"): no redistribution
  or modified builds; credit it in the pack listing and in this mod's docs. Its public API only,
  with one exception Ben chose on 2026-09-30 ("Mixin anyway"): the optional Deep Time base hook
  (phase 8, `plans/phase-08-atmosphere-base.md`) mixes into five of its classes at runtime, in its
  own config, only for Project Atmosphere versions it was checked against and only with Deep Time
  installed. Its jar stays a `compileOnly` input in the gitignored `libs/`. Phase 9's hemisphere
  seasons (Ben's "Patch PA per position", 2026-09-30) add three more mixins there under the same gate.
- Serene Seasons (Glitchfiend) and Serene Seasons Plus (Gabou), both "All Rights Reserved": phase 9
  (`plans/phase-09-hemisphere-seasons.md`, Ben's decisions of 2026-09-30) mixes into five Serene
  Seasons classes and one Serene Seasons Plus class at runtime, in their own config, only for
  versions they were checked against and only with Deep Time installed, and uses Serene Seasons'
  resolver-override hook for colours. Nothing of either is copied or redistributed; the pinned jars
  are `compileOnly` inputs in the gitignored `libs/`.
- LSO all-rights-reserved: we compile against its `api`/`registry` classes (intended for
  integrations, and Project Atmosphere does the same). Do not ship any LSO code. Ask the author if
  publishing beyond the pack.
- Destroy, CROWNS MIT; Power Grid — check its licence file before publishing.
- Our mod: MIT like the other mic addons.

## 9. Build, order of work, verification

Phases (each ends with a working jar in `pack/mods/`):
0. Skeleton + `Climate` + unified provider + datapack definition. Test: Thermoo's `/thermoo environment`
   command (see `api/command/EnvironmentCommand`) shows PA's temperature at the player.
1. Power Grid + Destroy consumers, migration from mic-destroy-electric (§6). Test: PG thermometer in
   air == empty vat reading (K−273.15) == Thermoo command; changes with PA weather and at night;
   pollution still warms both.
2. LSO modifiers + pack config. Test: LSO HUD tracks PA temperature; standing at a seething basin
   heater or a hot vat warms the player; no double season effect (compare with `"Serene Seasons Enabled"` toggled).
3. CROWNS spike then bridge. Test: fresh chunk in a snowy biome initialises near the unified kelvin.
4. Pollution → PA mode. Test: greenhouse pollution raises PA's reported temperature; provider no longer adds it.
5. Optional upstream: offer the Project Atmosphere provider (§5.2 minus pollution) to Thermoo
   Patches as a self-contained module mirroring its Serene Seasons patch (~100 lines + JSON).

Build/deploy exactly as mic-destroy-electric: `nix develop -c ./gradlew build -x test --offline`,
copy to `pack/mods/`, `cd pack && nix develop -c packwiz refresh`. All mixins in one config with
`defaultRequire: 1` and the plugin gating; the first launch is the real test of every injection.
Test from a fresh export of the pack (the 2.1.1 instance lacks Project Atmosphere).

## 10. Risks and open questions

- Thermoo's 1.21.1-neoforge branch is parked; if a blocking bug appears we may need to build our
  own Thermoo jar from that branch (LGPL allows it). Mitigation: keep all Thermoo use behind `Climate`.
- PA is server-only; client reads go through its per-biome day cache, so client-side ambient can
  lag the server by PA's sync interval. Acceptable for tooltips; the server value drives physics.
- Thermoo lookup cost on hot paths (wires per tick) — the chunk cache is mandatory, not optional.
- LSO's registration timing (must land on its DeferredRegister before `RegisterEvent`) — verify
  once in a dev run; fallback is swapping `TemperatureUtil.internal` with a delegating wrapper.
- CROWNS default-layer refresh may have no clean seam (§5.6).
- Double counting is the failure mode everywhere: pollution once (provider), seasons once
  (Thermoo/PA, LSO built-ins off), device heat once (DeviceHeatModifier replaces the basin heater JSON).


## Build log

- **2026-09-04 phase 0+1 built.** Both `mic_climate` 0.1.0 and the trimmed `mic-destroy-electric` pass
  `--rerun-tasks`. Deviations: Project Atmosphere's Modrinth project (`qIWoLcKJ`) now 404s, so the
  compileOnly dependency is the pack's own pinned jar at `mods/mic-climate/libs/NeoForge-projectatmosphere-0.8.1.0.jar`
  (sha512 matches `pack/mods/project-atmosphere.pw.toml`; never bundled). Provider type is registered via
  NeoForge `RegisterEvent` (Thermoo's own mechanism). Known limitation: Thermoo attaches environment
  definitions to biomes on the server at `SERVER_STARTED` only, so client-side `Climate` reads return the
  20 °C default — server physics unaffected; client-only tooltips may show 20 °C. Nothing deployed.
- **2026-09-04 phase 2 (LSO) built.** Two modifiers registered on LSO's own DeferredRegister from the mod
  constructor (timing verified in NeoForge bytecode; no `TemperatureUtil.internal` fallback needed). LSO calls
  `getWorldInfluence` with a null player, so the device-heat cache is keyed per position. Scan radius follows
  LSO's `Config.Baked.tempInfluenceMaximumDist`. `pack/config/legendarysurvivaloverhaul-common.toml` written
  (5 values changed; packwiz ships `config/`, needs `packwiz refresh`). **Follow-up:** once device heat is
  confirmed in game, delete `pack/kubejs/data/powergrid/legendarysurvivaloverhaul/temperature/blocks/basin_heater.json`
  — it now double-counts the basin heater.
- **2026-09-04 phase 3 (CROWNS) built** against the Modrinth 2.2.5 jar. Corrections vs the spec: the biome
  table's `getValue` belongs to FormicAPI (`Lcom/rae/formicapi/content/data/managers/FloatMapDataLoader;getValue(Ljava/lang/Object;F)F`,
  `ordinal = 0` — three calls share the descriptor); `scheduleInitialisation` is called by CROWNS from the SERVER
  thread unsynchronised, so the bridge only sets a volatile flag on `ServerTickEvent` and schedules at the HEAD of
  `initialise` on the physics thread; only `loadedSections ∩ nearDynamic` is scheduled (else `initialise` evicts the
  section); refreshed sections are marked `setNeedTicking` at RETURN so the solver carries cells to the new default;
  `TEMPERATURE` is never rescheduled. **If Ben adds a CROWNS version other than 2.2.5, re-javap the FormicAPI path.**
- **2026-09-05 phase 4 (pollution → Project Atmosphere) built.** `atmosphere/PollutionAtmosphereEffect` runs an
  erosion-compensating controller on `ServerTickEvent.Post`; `pollution.mode = ATMOSPHERE` is now real and the
  provider's "behaves as MODIFIER" warning is gone (it survives only for the case where PA is absent). Deviations
  from `plans/phase-04-atmosphere.md`, both forced by what the 0.8.1.0 source actually does: (1) `AtmosphericStateRegistry`
  is **global** — `STATES`/`ACTIVE` are static and `RegionInstanceKey` is `(regionX, regionZ, regionSize)` with no
  dimension, and PA's `EventHandler` ticks nothing outside `Level.OVERWORLD` — so the controller keeps one map and
  runs one pass against `server.overworld()`, not one pass per `ServerLevel` (that would apply the same offset once
  per dimension). (2) The spec's `clamp(applied - erosion, 0, desired)` cannot unwind: with pollution cleared
  `desired` is 0, so the clamp zeroes the belief and the negative delta never fires. Ceiling is `max(previousApplied,
  desired)` instead. Also: `applied` is credited with the state's *actual* movement, so PA's `clampTemperature`
  ceiling cannot leave a phantom offset. **Erosion is much stronger than §5.7 assumed:** the dominant term is not
  `relaxTowardBase` (`relaxFactor` 0.0005) but `AtmosphericUpdateScheduler`'s delta controller, whose ACTIVE mode
  blend of 0.6 pulls 60% of the gap toward its target every 20 ticks. At the spec's default
  `atmosphereIntervalTicks = 100` the offset is ~1% of `desired` by the time the next pass restores it, i.e. a
  sawtooth; at 20 it oscillates between 100% and 40%. Default left at 100 per the spec, with the trade-off written
  into the config comment — **open decision: change the default to 20.** Nothing deployed.
- **2026-09-05 phase 5 (headless GameTest suite) built and green.** `./gametest.sh` boots a dedicated NeoForge
  21.1.249 server with the pack's temperature graph on a `localRuntime` configuration and runs 16 `@GameTest`s in
  ~60 s / ~2.0 GB peak RSS; **all 16 pass**, and the debug run shows all seven `mic_climate` mixins applied with no
  `InjectionError` and nothing skipped. `neo_version` had to go 21.1.230 → **21.1.249** (petrolpark 1.5.6 needs
  `[21.1.238,)`, thermoo-patches `[21.1.233,)`); `neo_version_range` is now `[21.1.238,)`, so **the pack's own
  NeoForge pin has to move too — Ben's call.** Deviations from `plans/phase-05-gametests.md`: an empty template is
  indeed rejected, so `data/mic_climate/structure/empty_5x5x5.nbt` is generated by `tools/make_test_structure.py`
  (plain-Python NBT, no Minecraft needed); NeoForge discovers `@GameTestHolder` classes from mod scan data by
  itself, so the `RegisterGameTestsEvent` listener only *reports* what is present and every optional test guards
  itself with `GameTests.skipWithout` while the optional-mod calls live in bridge classes; and because gametests in
  one batch run simultaneously, every test that writes `ClimateConfig.Test`, level pollution or PA state gets a
  `batch` of its own. `thermalDeviceCacheRefreshes` moves the ambient with the new
  `ClimateConfig.Test.forcedCelsius` hook rather than with pollution, so it tests the 100-tick device re-read and
  nothing else; `pollutionWarmsOnce` is where the pollution path is proved end to end (Thermoo, Power Grid and
  Destroy all rose by exactly Destroy's own +20 K, once). LSO's `getWorldTemperature` rounds to one decimal, so
  the two LSO assertions carry a 0.1 tolerance.
- **2026-09-05 finding: Project Atmosphere 0.8.1.0 and Serene Seasons Plus 5.1.2 cannot load together.**
  PA's `ServerLevelSnowStormMixin` declares `implements com.Gabou.sereneseasonsplus.features.snowstorm.ISnowStormLevel`;
  SSP dropped that class in 5.0 (4.2.3 still ships it; 5.1.1 and 5.1.2 do not — the equivalent moved to Gabou's Libs
  as `net.Gabou.gaboulibs.util.ISnowStormLevel`, a different name). The reference is hard, `projectatmosphere.mixins.json`
  is `"required": true` with `defaultRequire: 1`, so the mixin applies, `ServerLevel` gains a non-existent interface
  and the JVM dies during `net.minecraft.server.Bootstrap` with `Cannot find class com/Gabou/sereneseasonsplus/...`.
  **This is not a headless-server artefact — an integrated server loads `ServerLevel` too, so the pack as pinned
  (`pack/mods/project-atmosphere.pw.toml` 0.8.1.0 + `pack/mods/serene-seasons-plus.pw.toml` 5.1.2) cannot load a
  world.** The gametest runtime therefore ships the pack's SSP and leaves PA out by default;
  `./gametest.sh -PwithAtmosphere` swaps SSP down to 4.2.3 (plus Better Days, which SSP 4.2.3 calls unguarded on
  every level tick) and **all 16 tests pass with Project Atmosphere driving**, which is the evidence that the pair
  is the only problem. Nothing in `pack/` was changed.
- **2026-09-05 phase 5, Ben's client run: `gametest-client.sh` written, not yet green.** HeadlessMC 2.10.0 +
  mc-runtime-test 4.5.1, driven by `./gametest-client.sh` (`-lwjgl`) or `GAMETEST_CLIENT_XVFB=1 ./gametest-client.sh`
  (real GL under Xvfb + Mesa; `nix build`-resolved store paths on `LD_LIBRARY_PATH`, since `nix shell` does not set
  it). Two client tests exist (`ClientGameTests`): the documented client-side `Climate` fallback to 20 °C, and a Power
  Grid thermometer being placeable in a rendered world. **What works:** the script installs NeoForge 21.1.249 into
  `~/.minecraft`, stages the same 19-mod set through a new `collectClientMods` Gradle task, boots the real modded
  client, loads every resource pack, and under Xvfb creates and joins a singleplayer world, then exits 0 — with no
  error from `mic_climate` anywhere. **What does not:** mc-runtime-test never reaches its GameTest stage (no
  `/test runall`, no `[gametest]` lines, and `-DMcRuntimeGameTestMinExpectedGameTests=1` does not fail the run
  either), so the two client tests have never executed. Open question is whether the `-D` properties reach the game
  JVM through HeadlessMC's `--jvm` at all. Three client-only findings came out of getting that far, all now encoded
  in `build.gradle`: Destroy's client setup needs **JEI** on the classpath (hard `mezz.jei.api` reference in a
  deferred task) so JEI is a `gametestClientOnly` dependency; **Simple Clouds** without Project Atmosphere throws
  `"Not initialized"` from `CloudSpawningDataManager.getInstance()` during `ServerLevel` construction and kills world
  creation (a dedicated server never reaches that path); and **Serene Seasons Plus** opens a modal `PerformanceWarning`
  screen that mc-runtime-test cannot get past even with `McRuntimeGameTestCloseAnyScreen`. Both of the latter are
  Project Atmosphere dependencies only, so both now live behind `-PwithAtmosphere`.
- **2026-09-05 phase 5 (gametests) built and GREEN: 16/16** (`./gametest.sh`, ~35 s, ~2 GB; `-PwithAtmosphere` adds PA
  with SSP 4.2.3; `-PgametestDebugLog` prints mixin application — all 7 mic_climate mixins apply). `neo_version` 21.1.249.
  Runtime set in `build.gradle` (`localRuntime`); Create full jar replaces `slim` at runtime. Client run
  (`gametest-client.sh`, HeadlessMC + mc-runtime-test) boots the real modded client under Xvfb and joins a world but
  never reaches the GameTest stage (open: `-D` properties through HeadlessMC `--jvm`). Client findings: JEI required
  by Destroy client setup; Simple Clouds without PA crashes world creation; SSP opens a modal PerformanceWarning.
- **PACK BLOCKER (2026-09-05) — RESOLVED the same day.** Project Atmosphere 0.8.1.0's `ServerLevelSnowStormMixin`
  implemented `com.Gabou.sereneseasonsplus.features.snowstorm.ISnowStormLevel`, which Serene Seasons Plus removed in 5.0,
  so the pack's PA 0.8.1.0 + SSP 5.1.2 pins could not load a `ServerLevel` at all. **Fixed by repinning the pack**
  (Ben, 2026-09-05) to **Project Atmosphere 0.9.1.2** (CurseForge project 1258344 file 8541023) and **Gabou's Libs 1.9**
  (project 1367332 file 8774265), keeping SSP at 5.1.2: 0.9.1.2's mixin implements
  `net.Gabou.gaboulibs.util.ISnowStormLevel`, which Gabou's Libs 1.9 ships, and PA now declares
  `sereneseasonsplus [5.1.1,)`. No downgrade needed and none applied. `mic-climate` now compiles against
  `libs/NeoForge-projectatmosphere-0.9.1.2.jar` (byte-identical to the pack's pin, sha1 `a8e2c9b3…`); every PA symbol
  the mod uses — `AtmoApi.getInstance().getCurrentWeather`, `WeatherSnapshot.temperatureC`,
  `BiomeClientTemperatureCache.getTemperature`, `AtmosphericStateRegistry.getActiveStates`/`getState`,
  `RegionAtmosphereState.getTemperature`/`adjustTemperature`, `RegionInstanceKey.from`/`regionX`/`regionZ` — is
  unchanged in 0.9.1.2, so no source adaptation was needed. `./gametest.sh -PwithAtmosphere` now runs the pack's real
  trio (PA 0.9.1.2 + SSP 5.1.2 + Gabou's Libs 1.9, plus Simple Clouds 0.7.3 which PA hard-requires; Better Days is gone,
  it was only there for SSP 4.2.3) and is **16/16 green**.
- **2026-09-05 phase 6 (real-pack dedicated-server smoke test) built and GREEN: 20/20.** `smoke/install.sh` installs the
  pack server-side from the local `pack/pack.toml` (packwiz-installer-bootstrap v0.0.3, `-g -s server`) into
  `smoke/server/` on NeoForge 21.1.249 — **164 mod jars, nothing excluded, nothing under `pack/` written** — and
  `smoke/run.py` boots it, drives the scenario over `mcrcon` and writes `smoke/report.md`. **87–114 s to `Done (`,
  peak RSS 3.4 GB against `-Xmx6G`.** All five `mic_climate` mixins that have a target applied and all ten of
  `mic_destroy_electric`'s did (verified by name from `-Dmixin.debug.verbose=true` output, not merely inferred from a
  successful boot). New in the mod: `/mic_climate probe|invalidate|pollution|mode` (`command/`, one bridge class per
  optional mod behind `Compat.isLoaded`, registered on `RegisterCommandsEvent`), `Climate.uncachedCelsius`/`cacheAge`,
  and a session-scoped `pollution.mode` override so the mode can be flipped from a console without editing the toml.
  **Two findings for Ben:** (1) **Create: CROWNS is not in the pack** — no `create-crowns` and no `formicapi` jar — so
  the phase-3 bridge and its two `mixin/crowns/*` mixins are correctly gated off and are exercised only by the gametest
  suite, never by the pack; (2) the pack index already ships `mods/mic_climate-1.21.1-0.1.0.jar`, i.e. this mod **is**
  deployed to `pack/` despite earlier entries here saying "nothing deployed" — the smoke test installs it and then
  copies the freshly built jar over it. Also noted: `ThermometerBlockEntity` persists only `Max` (its highest reading),
  not a live temperature, so the thermometer step asserts against `Max` and records rather than asserts when the
  ambient is at or below 0 °C.
- **2026-09-05 pack repinned (Ben's decision):** Project Atmosphere 0.9.1.2 (CurseForge project 1258344 / file 8541023; its
  mixin implements `net.Gabou.gaboulibs.util.ISnowStormLevel`; hard deps gaboulibs ≥1.8.1, simpleclouds ≥0.7.3, optional SSP ≥5.1.1)
  and Gabou's Libs 1.9 (CurseForge project 1367332 / file 8774265); SSP stays 5.1.2. Both projects are delisted from Modrinth,
  so their update tracking is CurseForge now. Phase 6 agent is re-verifying mic-climate against the 0.9.1.2 API before the smoke run.
- **2026-09-30 phase 8 (Deep Time's climate as Project Atmosphere's own base) built on branch `pa-hook`** — Ben's
  "Mixin anyway" after being told Project Atmosphere is All Rights Reserved and its API cannot set a base. Design,
  gating and limits: `plans/phase-08-atmosphere-base.md`. Five `@Pseudo` mixins in their own config
  (`mic_climate.projectatmosphere.mixins.json`, `"required": false`, `defaultRequire: 0`), applied by
  `atmosphere.ProjectAtmosphereMixinPlugin` only to Project Atmosphere `[0.9.1.2,0.9.2)`, only with Deep Time
  installed (or in a GameTest run), and only when every target method and wrapped call is in its jar (read straight
  from the mod file: ModLauncher's mixin service refuses untransformed bytecode, which the first run proved by
  skipping all five with one log line each and the GameTests failing, as designed). Runtime switch
  `deepTime.projectAtmosphereBase` (default true), active only in a Deep Time overworld with a climate.
  - **GameTests** (Mac, `tools/remote/mic-climate.sh`): `./gametest.sh` 22/22 (the three new tests skip),
    `-PwithAtmosphere` 22/22 with 5/5 hook targets bound: with a −30 °C / +40 °C stand-in climate Project Atmosphere's
    snapshot, rain-or-snow and snow/freeze temperatures read it exactly, it lays snow and freezes water only at −30,
    flags cold then heat crop stress, and the region's seasonal base is the stand-in with the forecast's day shape and
    band width kept; outside Deep Time, or switched off, its numbers are its own bit for bit.
  - **Pack smoke** (`smoke/install.sh` + `run.py`, the real pack from Deep Time's snapshot, 166 mods, no Deep Time):
    **20/20**; the plugin logs "not applied: Deep Time is not installed". It first failed 0/1 with master's jar too: a
    peer's server on the Mac held Simple Voice Chat's UDP 24454, and the smoke server's JVM shut down right after its
    voice chat failed to bind. Passing run: voice chat moved to 24464 in the smoke server's config.
  - **Real Deep Time planet** (earthlike_quick_16k, scattered continents, 500 Myr; Deep Time master ba8019a), hook off
    then on in one world via `/mic_climate atmosphere base off|on`. Four of P1-25's five sites are sea on today's
    planet (Af, BWk, Dfc and EF all probe as ocean; ET is `terralith:cold_shrubland`), so a second pass scanned
    `/deeptime climate` on a grid and picked land sites. Midwinter noon, Project Atmosphere driven 3000 ticks each way,
    °C:

    | Site | Deep Time | PA off: AtmoApi / rain-or-snow / snow-freeze | PA on (all three) | on, midnight |
    |---|---|---|---|---|
    | Af, bamboo jungle, 260 m (−7168, 0) | 25.2 | −0.4 / −3.0 / 16.6 | 27.1 | 23.6 |
    | BWk, badlands, 800 m, south (512, 1024) | 23.2 | −6.7 / −7.4 / 3.1 (lays snow) | 23.8 (no snow) | 22.9 |
    | Dfc, spruce taiga, 220 m (−4608, −2304) | −3.5 | −3.4 / −4.2 / −13.8 | −2.9 | −4.3 |
    | ET, snowy badlands, 460 m (−8192, −3328) | −21.8 | 0.5 / −1.0 / 2.0 | −20.4 | −22.8 |
    | EF, ice spikes, 20 m (−6144, −3584) | −21.1 | −0.9 / −2.9 / −35.6 | −19.6 | −22.7 |

    With the hook on, all three of Project Atmosphere's readings equal mic-climate's unified value (Deep Time + its
    weather anomaly) to the hundredth, in every phase. Region seasonal bases (Deep Time's, sea level): Af 22.5, BWk
    23.8 (southern summer), Dfc −11.0, ET −12.0, EF −10.4, against Project Atmosphere's own −2.4, −7.4, −3.9, −0.8
    and −2.4 (its biome table has almost none of these Terralith/Deep Time biomes). On the first, fresh-world pass
    (undriven, so no anomaly), Project Atmosphere's own reading was 0.2–4.3 °C at all five P1-25 coordinates in both
    seasons, and its rain-or-snow temperature over the equatorial sea was −5.7 °C in northern winter (snow in the
    tropics); with the hook both equalled Deep Time (26.4 there), and the southern ice-cap sea read −6.8 in northern
    summer and +4.7 in northern winter, opposite to the north.
  - Finding, not this phase's: at `terralith:deep_warm_ocean` (which Deep Time places) the unified provider does not
    answer and Thermoo returns its default 20.00 °C (the Af coordinate on the first pass), most likely because that
    biome is not in `#minecraft:is_overworld`, the tag `thermoo/environment/overworld.json` selects.
- **2026-09-30 phase 9 (seasons by latitude on Deep Time planets) built on branch `seasons`** — Ben's decisions
  (Deep Time decisions log, 2026-09-30): code in mic-climate, "full latitude damping", Project Atmosphere patched
  per position, Serene Seasons Plus snow patched. Design, hooks, gating and limits:
  `plans/phase-09-hemisphere-seasons.md`. Needs Deep Time's climate API 2 (branch `climate-latitude-api`, not
  merged: `DeepTimeClimate.circumferenceBlocks(Level)` on both sides, `latitudeDeg`).
  - **GameTests** (Mac, `tools/remote/mic-climate.sh`): `./gametest.sh` 28/28 (7 skip: no Project Atmosphere or
    Serene Seasons Plus, 2 client-only) and `-PwithAtmosphere` 28/28. Serene Seasons is now the pack's own pin
    (10.1.0.3 from CurseForge, `libs/`) at compile and test time; the mixins bind 4/4 (Serene Seasons, server),
    1/1 (Serene Seasons Plus), 2/2 (Project Atmosphere, server).
  - **Real Deep Time planet** (MIC server, earthlike_quick_16k, scattered continents, 500 Myr, C = 16384; Deep Time
    `climate-latitude-api`; Deep Time's `tools/review/seasons-probe.sh`): a grid of 32 places at 45 N, 15 N, the
    equator and 45 S; most are sea, the land ones are below. The Mixin export shows every server injector landed
    in its target (`review/out/seasons/injections.txt` in Deep Time). Northern midwinter / northern midsummer:

    | Site | Lat., strength | Serene Seasons here (decisions) | SS temperature (level's) | Snow/ice (< 0.15) | Wheat | Melt | PA region season, sunlight | PA snow/freeze °C |
    |---|---|---|---|---|---|---|---|---|
    | Dfb, `terralith:yellowstone` (−3072, −2048) | 45.0 N, 1.00 | Mid Winter / Mid Summer | −0.50 (−0.50) / 0.25 (0.25) | yes / no | no / yes | 0 / 25 % | winter ×0.73 / summer ×1.08 | −9.6 / 16.1 |
    | Cfa, `terralith:birch_taiga` (−7168, −683) | 15.0 N, 0.26 | Late Spring / Mid Summer | 0.03 (−0.50) / 0.22 (0.22) | yes¹ / no | no / yes | 12.5 / 25 % | spring ×0.95 / summer ×1.08 | 18.3 / 25.0 |
    | Af, `bamboo_jungle` (−7168, 0) | 0.0, 0.00 | Mid Summer / Mid Summer | 0.95 (0.95)² / 0.95 | no / no | yes / yes | 25 / 25 % | summer ×1.08 / summer ×1.08 | 25.2 / 25.2 |
    | sea, `terralith:deep_warm_ocean` (−5120, 0) | 0.0, 0.00 | Mid Summer / Mid Summer | 0.50 (−0.30) / 0.50 (0.50) | no / no | yes / yes | 25 / 25 % | summer / summer | 26.3 / 26.6 |
    | Cfb, `old_growth_birch_forest` (−3072, 2048) | 45.0 S, 1.00 | Mid Summer / Mid Winter | 0.60 (−0.20) / −0.20 (0.60) | no / yes | yes / no | 25 / 0 % | summer ×1.08 / winter ×0.73 | 16.1 / 3.0 |
    | same, `/mic_climate seasons off`, midwinter | — | Mid Winter | −0.20 | yes | no | 0 % | winter ×0.73 | 16.1 |

    ¹ Terralith's birch taiga is a cold biome to Serene Seasons (0.22 even in its summer), so it stays under 0.15 at
    15° in winter though faded from −0.50 to 0.03; the biome, not the season. ² Serene Seasons' tropical biomes take
    no temperature shift. The equator is seasonless (Mid Summer all year, wheat all year, no winter cooling in either
    season); the south is inverted in every column; at 15 N the season is faded (Late Spring in midwinter). Project
    Atmosphere's level-wide season stayed winter / summer throughout. Its snow/freeze temperature is Deep Time's
    (phase 8), which already had each hemisphere's season.
