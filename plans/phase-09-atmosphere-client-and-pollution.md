# Phase 9: Project Atmosphere's client table and Destroy's pollution

Branch `pa-client`, on top of phase 8 (merged into master as 0609007). Three changes, all at the
hook sites phase 8 added into Project Atmosphere:

1. **Destroy's pollution inside Project Atmosphere**, in every world.
2. **A per-player Deep Time client table**, and rain or snow on screen decided from it, in Deep
   Time worlds.
3. The Terralith biomes `#minecraft:is_overworld` misses are covered by the unified provider.

## 1. Pollution inside Project Atmosphere

Ben's call (2026-09-30): "Yes, in all worlds (Recommended)".

**Before.** `pollution.mode = ATMOSPHERE` pushed Destroy's warming into Project Atmosphere's region
states through its public API (`adjustTemperature`). Project Atmosphere pulled it back toward its own
target (60 % every 20 ticks when active), so the offset had to be topped up on a timer and never
held. The default, `MODIFIER`, left Project Atmosphere unwarmed.

**Now.** The warming (`DestroyPollutionShift`, Destroy's greenhouse and ozone model times
`pollution.multiplier`) is part of Project Atmosphere's own calculation:

- **Regions.** It is added to the season offset `RegionAtmosphereStateMixin` already wraps. Every
  region's seasonal base, targets and day/night band carry it, so the live temperature settles on
  it and nothing erodes it.
- **Per-block readings built from the forecast** (rain or snow, snow and freeze): it is added
  directly, because the forecast they sample does not carry it.
- **Per-block readings built from the live region** (the `AtmoApi` snapshot, crop stress): nothing
  is added. They carry it once the region has simulated.
- **Deep Time worlds:** the per-block value is Deep Time + weather anomaly + pollution. The anomaly
  is live minus seasonal base, which both carry the warming, so it cancels there.

It is on with Destroy installed and `pollution.projectAtmosphere = true` (default), in the overworld
of any world.

**Counted once.** The unified provider (machines, players, chemistry) still adds the warming itself,
except where Project Atmosphere's reading already carries it: when the region has simulated, or when
there is no region yet and the reading comes from the forecast. It decides this before reading,
because reading can create the region. The GameTest checks both states.

**The air warms at Project Atmosphere's pace.** The forecast-built readings and the region's
seasonal base move by the full shift at once. The live temperature, which the snapshot and the
unified value read once a region has simulated, follows as Project Atmosphere's scheduler pulls it
up:

- seconds in regions near a player;
- minutes elsewhere, about 85-90 % of the shift after 3600 ticks of passive updates;
- not at all while no player is online and nothing drives the simulation.

Before, `MODIFIER` added the warming to machines instantly. It is now counted once through the
air, so machines lag with it.

**Retired.** `PollutionAtmosphereEffect` is gone. `pollution.mode` and
`pollution.atmosphereIntervalTicks` still load, so existing files keep working. Both mode values now
behave as `MODIFIER`; `ATMOSPHERE` logs one warning.

**Gating.** The plugin now applies the five temperature mixins when Project Atmosphere 0.9.1.x and
either Deep Time or Destroy are installed. The fail-safe is unchanged: `@Pseudo`, `require = 0`,
the per-method bytecode check, and a fallback to Project Atmosphere's value on any exception.

## 2. The per-player Deep Time client table

Ben's call (2026-09-30): "Yes, per-player cache (Recommended)".

**What decides rain or snow on screen.** Phase 8 assumed Project Atmosphere's client table did. It
does not:

- Simple Clouds replaces vanilla's `renderSnowAndRain`.
- Its `WorldEffects` asks `Biome.getPrecipitationAt(pos)` for each column.
- Serene Seasons' client mixin answers that with `SeasonHooks.getPrecipitationAtSeasonal`, which
  uses the biome's vanilla temperature and one global season.
- Project Atmosphere's `BiomeClientTemperatureCache` feeds only its own client freeze and snow
  checks and mic-climate's client-side value.

So a Deep Time table alone would change nothing visible. The table goes to the client, and the
client's final answer is taken from it.

**The table** (`atmosphere.ProjectAtmosphereClientCache`):

- It covers every biome on the loaded surface around the player, every 32 blocks out to the view
  distance (at most 192 blocks).
- Each biome's value is the mean of Project Atmosphere's hooked temperature there: Deep Time +
  weather anomaly + pollution, the number the server decides rain or snow by.
- Other biomes get no entry, so the client keeps Serene Seasons' answer there, since Project Atmosphere's own
  per-biome averages mix both hemispheres.
- A marker entry, `mic_climate:deep_time_cache`, tells the client the table is Deep Time's.

**Sending.** The table travels in Project Atmosphere's own `BiomeDayTemperaturePacket`, which
replaces the client's table whole. It is sent:

- where Project Atmosphere sends its table: at login, and to everyone when the forecast is rebuilt
  (`ForecastGeneratorMixin`);
- again after 48 blocks of travel, or after a minute.

When the hook stops applying, the player gets Project Atmosphere's own table back, without the
marker.

**On screen** (`SeasonHooksPrecipitationMixin`). Serene Seasons' final answer is taken as it is,
except on a client holding a Deep Time table: there a biome that precipitates gets snow below 0 °C of
its table value and rain above. A biome Serene Seasons says does not precipitate (its tropical dry
season) still does not.

This is the one mixin into Serene Seasons. It touches only the client's final answer, after
whatever Serene Seasons, or a per-position season layer on top of it, decided.

**Switches.** `deepTime.projectAtmosphereClient` (default true) turns the table off on the server.
On a client, the same key makes it ignore a table. `-Dmic_climate.dev.precipitationLog=true` logs
the client's decisions (`[mic_climate-precip]`).

**Limit.** The table is per biome. Within one biome near the player, rain or snow is decided by that
biome's mean.

## 3. Terralith coverage

Terralith defines `deep_warm_ocean` and four `skylands_*` biomes outside `#minecraft:is_overworld`,
and Deep Time places `deep_warm_ocean`. The environment definition now selects
`#mic_climate:overworld`: the vanilla tag plus those five, as optional entries.

## Verification

See PLAN.md's build log.

## Credit

Project Atmosphere (CurseForge project 1258344) is by Gabou (xGabou), All Rights Reserved.
mic-climate mixes into it at runtime and never redistributes or modifies its jar. Ben is asking its
author for consent on Project Atmosphere's Discord.
