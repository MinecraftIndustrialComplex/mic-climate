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

**How the warming reaches machines: hybrid** (Ben, 2026-09-30, "Hybrid (Recommended)"). The
forecast-built readings and a region's seasonal base move by the full shift at once. The live
temperature follows as Project Atmosphere's scheduler pulls it up: seconds near a player, minutes
elsewhere, not at all while nothing drives the simulation. For machines, players and chemistry
(the unified value), `ProjectAtmosphereBase.pollutionCorrection` decides how the warming arrives:

- **Where Project Atmosphere is simulating the region:** at its own pace, through its live
  temperature. The unified value reads the air.
- **Everywhere else:** at once. The unified value is Project Atmosphere's reading plus the shift,
  less what the region's live temperature already holds.

The total is the same either way, and the warming is counted once.

**"Simulating" means:** the region got an ACTIVE update from Project Atmosphere's scheduler within
the last 60 ticks (`SIMULATING_TICKS`). ACTIVE is the 20-tick pass over regions within 1000 blocks
of a player. Everything else counts as not simulating:

- a region never created (the reading comes from the forecast, to which the hook adds the shift);
- a region never simulated (its live temperature is still exactly its base);
- a region with only PASSIVE updates (the 100-tick, 35 %-strength batch over the rest of the
  world, which takes minutes to follow a change);
- a region with no updates since the last player left.

Why ACTIVE updates within 60 ticks: that is the distinction Ben drew, near a player against remote
factories with no player driving them. A recency test alone would count passively updated remote
regions as simulated and leave them at the slow passive pace. Active-set membership alone goes stale
when the last player leaves, because Project Atmosphere stops rebuilding the set. Sixty ticks is
three active passes, so one late callback does not flip a region.

**What the live temperature holds.** `onScheduledUpdate`, fed by a mixin around the
`adjustTemperature` call in `AtmosphericUpdateScheduler.applyDeltas`, moves a per-region estimate
toward the current shift by the fraction each update moves the region toward a step in its
targets. The fractions come from Project Atmosphere's constants:

- ACTIVE: 1 × (0.6 blend + 0.04 restore) + 0.0012 relax = 0.64;
- PASSIVE: 0.35 × (0.45 + 0.04) + 0.00035 = 0.17;
- plus its guard for a deviation over 6 °C.

A state Project Atmosphere replaces starts again from nothing. A region first seen after a restart,
already simulated, is taken to hold the current shift. The seasonal drift's slow
`adjustTemperature` every 200 ticks is not counted, which makes the estimate slightly low.

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
