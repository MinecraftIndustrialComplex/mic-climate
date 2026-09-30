# Phase 8: Deep Time's climate as Project Atmosphere's base

Branch `pa-hook`. Phase 7 made machines and players read Deep Time's climate in Deep Time worlds,
with Project Atmosphere's weather on top. Project Atmosphere's own world effects still came from its
biome-built regions. This phase gives Project Atmosphere itself Deep Time's climate as its base, so
its snow, ice, rain-or-snow, clouds and crop stress follow the planet too.

## Why a mixin

Project Atmosphere's jar says "All Rights Reserved", and its public API cannot set a region's base
temperature (phase 7, "What Project Atmosphere allows"):

- the base is a `private final` field;
- it is set once, from a forecast built from biome base temperatures;
- written temperatures erode back toward Project Atmosphere's own target.

Ben was told this and chose "Mixin anyway" (2026-09-30). So mic-climate mixes into Project
Atmosphere, gated, optional, and failing safe. Project Atmosphere's jar is still never
redistributed: it is a `compileOnly` input from the gitignored `libs/`, and nothing of it is copied
into this repository or the mod's jar.

## What Project Atmosphere does (0.9.1.2, read from its bytecode)

Project Atmosphere has three layers of temperature.

**The forecast region** (`ForecastRegion`, one per 2000-block region):

- a 7-day week of daily minimum and maximum;
- built once from biome samples every 64 blocks at sea level;
- each sample goes through a per-biome table (`BiomeTempConfig`), at the season of the day it was
  built;
- persisted with the world.

**The live region state** (`RegionAtmosphereState`, one per forecast region):

- `baseTemperature`: `private final`, the forecast week's average;
- a daily curve and a day/night band derived from the forecast's first day;
- one global season offset, `SeasonalAtmosphericDrift.currentTemperatureOffsetC()`, from Serene
  Seasons: about +6 °C in summer and −8 °C in winter, the same in both hemispheres;
- that offset is added in four methods: `getEffectiveBaseTemperature()`,
  `getTargetTemperature(long)`, `getBaselineMinTemperature()` and `getBaselineMaxTemperature()`;
- the scheduler moves the live temperature across the band with sunlight, cools it with rain,
  restores it toward the target and relaxes it toward the effective base;
- it only runs while a player is in the overworld.

**The client cache** (`BiomeClientTemperatureCache`): one flat value per biome, averaged over the
forecast regions and sent at login.

What reads which:

| Project Atmosphere code | Reads | Decides |
|---|---|---|
| `BiomeFreezingMixin` (`Biome.shouldFreeze`, `shouldSnow`), `LocalizedPrecipitationBlockUpdater`, `ForecastSampling.canAccumulateSnow` | `LocalBiomeTemperatureResolver.getLocalBiomeTemperature` per block: the forecast blended with the biome's table range, weather terms, 6.5 °C/km from sea level | ice and snow layers (below 0 °C) |
| `SeasonHooksMixin` (Serene Seasons' `warmEnoughToRainSeasonal`), thermometer, seasonal trees, commands, `ForecastSampling` | `ForecastOrchestrator.getCurrentTemperature(ServerLevel, BlockPos, long)` per block: the forecast plus the global offset | rain or snow |
| `AtmoApi.getWeatherSnapshot` (other mods, the HUD sync, world effects) | the region's live temperature | the public reading and `isSnowing` |
| `CropStressManager.evaluate(ServerLevel, BlockPos)` | the region's live temperature | heat (> 35 °C) and cold (< 0 °C) stress |
| clouds (`CloudRegionEvolutionController`), `WeatherSampler`, seasonal drift | the region's live temperature and targets | cloud evolution, sampled weather |
| client rain/snow rendering | the client cache, per biome | what falls on screen |

## The hook

In a Deep Time world, with the hook active, Project Atmosphere's temperature is Deep Time's climate
plus Project Atmosphere's weather.

**Per region.** A region's seasonal base is Deep Time's monthly mean over the region at the date:

- the monthly means at sea level on a 5 × 5 lattice over the region, averaged (cached per region);
- read at the date mic-climate's `YearClock` gives (Serene Seasons to the tick);
- sea level because Project Atmosphere's region base is a sea-level figure.

It enters through the one call all four base methods share. `RegionAtmosphereStateMixin` wraps
`currentTemperatureOffsetC()` in those four methods and answers "Deep Time's regional mean now,
minus this region's own base" instead of the global offset. So:

- the effective base is Deep Time's seasonal mean for that region;
- the target keeps the forecast's day shape around it;
- the day/night band keeps its width around it;
- each region gets its own hemisphere's season and its own amplitude;
- Project Atmosphere's scheduler, sunlight, rain cooling and erosion run unchanged on top.

The `private final` base is never written. A region Project Atmosphere never simulated still holds
its temperature exactly equal to its base and can still be told apart.

**Per block.** Wherever Project Atmosphere asks about a block, the answer is:

```
T = DeepTime(pos, date) + clamp(live − effective base, ±deepTime.maxAnomaly)
```

- `DeepTime(pos, date)` is the monthly mean at the block, with Deep Time's own lapse rate at the
  block's height;
- the anomaly is the region's weather: its live temperature minus its (Deep Time) seasonal base;
- it is 0 for a region Project Atmosphere never simulated, and outside the overworld.

This is the number mic-climate's own provider builds (phase 7), without pollution in `MODIFIER`
mode. Four mixins apply it, each with `@ModifyReturnValue` or `@ModifyExpressionValue`, so Project
Atmosphere's original still runs first:

| Mixin | Target | Effect |
|---|---|---|
| `LocalBiomeTemperatureResolverMixin` | `getLocalBiomeTemperature` | ice and snow |
| `ForecastOrchestratorMixin` | `getCurrentTemperature(ServerLevel, BlockPos, long)` | rain or snow |
| `AtmoApiMixin` | `getWeatherSnapshot` | the public reading; `isSnowing` recomputed |
| `CropStressManagerMixin` | the temperature read in `evaluate` | crop stress |

Project Atmosphere's per-block weather terms in `LocalBiomeTemperatureResolver` (humidity,
pressure and wind cooling of up to about 2 °C) are replaced along with its biome blend. The
region's anomaly carries its rain cooling and day/night swing.

## Gating and failing safe

**At mixin time** (`atmosphere.ProjectAtmosphereMixinPlugin`), the hook's own mixin config
(`mic_climate.projectatmosphere.mixins.json`) applies a mixin only when:

1. Project Atmosphere is installed at a version in `ProjectAtmosphereVersions.KNOWN_RANGE`,
   `[0.9.1.2,0.9.2)`;
2. Deep Time is installed, or the JVM is a GameTest run;
3. `-Dmic_climate.projectAtmosphereBase=false` is not set;
4. every method it injects into, and every call it wraps, is in Project Atmosphere's bytecode with
   the expected descriptor.

Otherwise it logs one line and skips. `-Dmic_climate.projectAtmosphereBase.anyVersion=true` lifts
the version check; the method checks still run.

Behind that:

- the config is `"required": false` with `defaultRequire: 0`;
- every mixin is `@Pseudo`, and every injector is `require = 0`;
- no mixin shadows a field; the runtime side reaches Project Atmosphere only through public
  methods;
- every entry point catches any exception, logs it once, and hands Project Atmosphere's own value
  back.

**At runtime** (`atmosphere.ProjectAtmosphereBase`), the hook is active only when:

- `deepTime.enabled` and `deepTime.projectAtmosphereBase` are on (both default true);
- the level is the overworld of a Deep Time world with a simulated climate.

Otherwise every mixin returns Project Atmosphere's own value unchanged. Packs without Deep Time get
no bytecode changes at all.

**Threads.** Project Atmosphere calls its region getters from its worker pool too. The date is read
on the server thread once a tick and published. Deep Time's climate API is thread-safe.

**Marker.** Each mixin adds the marker interface `ProjectAtmosphereHooked` to its target, so the
probe and the GameTests can tell which ones bound.

## Config and commands

- `deepTime.projectAtmosphereBase` (default true).
- `/mic_climate atmosphere base [on|off|config]` reads the switch or sets it for the session.
- `/mic_climate probe` has a `pa-base` line:
  - the value is Project Atmosphere's snow/freeze temperature at the block;
  - whether the hook is active, and why not;
  - how many of the five targets it bound;
  - the region's seasonal base with and without it, and its live value;
  - the rain-or-snow temperature;
  - whether Project Atmosphere would lay snow at the block.

## Known limits

**The client still renders from Project Atmosphere's per-biome cache.** That cache is one flat
value per biome, sent at login, averaged over regions in both hemispheres. It cannot be made right
per hemisphere without syncing Deep Time's climate to clients. So falling rain or snow on screen
can disagree with the server's ice and snow layers in mid-latitude biomes. The extremes (ice caps,
tropics) agree, because Deep Time picks those biomes from its climate.

**Serene Seasons' own effects and Project Atmosphere's seasonal humidity, pressure and
cloud-water modifiers stay global** (northern). Only temperature is per hemisphere. (Phase 9,
`plans/phase-09-hemisphere-seasons.md`, makes both follow latitude on Deep Time planets.)

**A region's live temperature can lag its base, and then its anomaly is large until Project
Atmosphere simulates it again.** The cap (`deepTime.maxAnomaly`) bounds it. Three ways it happens:

- a region created before the hook, or restored from a save made without it, starts from Project
  Atmosphere's biome-derived temperature;
- a region saved while the hook was on reads Deep Time-based after the hook is switched off;
- the date jumps (`/season set`, or a long headless stretch) while nobody is online to simulate.

The scheduler pulls the region onto its base within a few updates: seconds near a player, a couple
of minutes for the rest. The planet probe's second pass saw the third case: it reused a world whose
regions were saved in midwinter, then read them in midsummer undriven.

**Project Atmosphere's forecast regions are untouched.** Their persisted biome-built weeks remain
in the save. Three things still read them directly:

- the no-state fallback of `getCurrentTemperature(RegionInstanceKey, long)`;
- sandstorm selection;
- the client cache.

**Crop stress stays region-level for humidity.** Only its temperature is per block.

## Tests

`AtmosphereBaseGameTests`, with Project Atmosphere on the classpath (`-PwithAtmosphere`):

- **`atmosphereBaseHooksBind`**: all five mixins applied. This fails, rather than skips, on a
  Project Atmosphere inside the known range.
- **`atmosphereBaseIsInvisibleOutsideDeepTime`**: with the switch on in a world Deep Time did not
  generate, the region's seasonal base is Project Atmosphere's base plus its global offset, and its
  snapshot is the region's live value, both bit for bit.
- **`atmosphereBaseFollowsItsClimate`**: with a stand-in climate
  (`ClimateConfig.Test.projectAtmosphereTestClimate`) of −30 °C and then +40 °C, Project
  Atmosphere's own entry points all read the stand-in plus the region's anomaly:
  - its snapshot, its rain-or-snow and snow/freeze temperatures;
  - `Biome.shouldSnow` and `shouldFreeze` (on placed water) lay snow and freeze water only at
    −30 °C;
  - crop stress flags cold, then heat;
  - the region's seasonal base is the stand-in, with the forecast's day shape and band width kept;
  - switching off, or clearing the stand-in, gives Project Atmosphere its own numbers back.

Without Project Atmosphere they skip. A Project Atmosphere outside the known range skips too.

On a real Deep Time planet, Deep Time's `tools/review/p1-25-probe.sh` sites were probed with the
hook off and on (see PLAN.md's build log).

## Credit

Project Atmosphere (CurseForge project 1258344) is by Gabou (xGabou), All Rights Reserved.
mic-climate reads its public API and, for this optional hook, mixes into five of its classes at
runtime. It never redistributes or modifies Project Atmosphere's jar.
