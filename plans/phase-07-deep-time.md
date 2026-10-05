# Phase 7: Deep Time worlds

Branch `deep-time`. Deep Time (`deeptime`, a separate mod) generates a world from a simulated planet
and publishes the planet's climate through a public API
(`com.minecraftindustrialcomplex.deeptime.api.climate`, `DeepTimeClimate.API_VERSION` 1). In a Deep
Time world, the unified temperature now starts from that climate rather than from the biome.

## Why

A Deep Time biome is only a coarse band picked from the simulated climate. Every source this mod had
before starts from the biome's base temperature:

- the fallback maps it linearly onto −20 to +56 °C;
- Project Atmosphere maps it through a per-biome table.

That drops most of what the planet knows:

- an ice-cap plateau at −45 °C reads as a snowy biome, about −20 °C;
- the southern hemisphere gets northern seasons;
- a mountain reads its valley's temperature;
- Project Atmosphere's table has no entry for Terralith's biomes, so they count as 0 °C.

## What Project Atmosphere allows (0.9.1.2, read from its jar)

**No way to supply or override its base temperature.**

- `RegionAtmosphereState`'s base is a `final` field.
- The base is fixed when the region is created, from a 7-day forecast generated from biome base
  temperatures sampled over the region.
- It has no provider, registry or event.
- `setTemperature` and `adjustTemperature` exist, but the scheduler pulls the value back toward its own
  target within a few updates. This is the same erosion as `pollution.mode = ATMOSPHERE`.

**Its regions do not fit a Deep Time planet.**

- A region is 2000 × 2000 blocks, about a quarter of a 16k planet's pole-to-pole height.
- Its region keys have no dimension.
- It applies one global season offset to both hemispheres.

**It only simulates while players are in the overworld.** Headless servers never update its regions.

**Readable:** the live regional temperature and the region's effective base (base + season offset),
through public classes this mod already uses for pollution. Nothing is mixed into it.

## The coupling

In a Deep Time world with a simulated climate, the temperature is:

```
T = DeepTime(pos, date) + clamp(PA.live − PA.effectiveBase, ±deepTime.maxAnomaly) [+ pollution]
```

**`DeepTime(pos, date)`** is Deep Time's monthly mean at the block:

- It is the simulated lapse rate applied at the block's height.
- It is read at the date `YearClock` gives:
  - Serene Seasons' cycle to the tick, with Early Spring as the start of March;
  - otherwise Thermoo's season, as its middle month;
  - otherwise the annual mean.
- North and south of the equator get opposite seasons, because Deep Time's months do.

**The anomaly** is Project Atmosphere's weather:

- the day/night swing between its baselines;
- rain and cloud cooling;
- the day-to-day forecast;
- advection.

Its own season offset cancels out, so seasons are not counted twice. A region it created but never
simulated (the live value still exactly its base) has no anomaly.

**Pollution:**

- in `MODIFIER` mode it is added as before;
- in `ATMOSPHERE` mode it arrives through the anomaly, because it moves the live value and not the base;
- without the anomaly it is added directly.

Worlds Deep Time did not generate take the old path unchanged, and so does every world without Deep
Time. A GameTest compares `deepTime.enabled` on and off.

## Config (`deepTime` section)

- `enabled` (default true).
- `weatherAnomaly` (default true).
- `maxAnomaly` (default 20 °C).

## Known limits

(Phase 8, `plans/phase-08-atmosphere-base.md`, lifts most of the first limit below: an optional
mixin gives Project Atmosphere Deep Time's climate as its own base.)

**Project Atmosphere's own visible weather still comes from its biome-derived regions:**

- snow and freeze decisions;
- clouds;
- crop stress.

Its snow and freeze resolver blends the region with the local biome's range plus a height lapse. Deep
Time picks those biomes from its climate, so snow follows the planet's cold places, but not in its
exact numbers. Machines and players read Deep Time's numbers.

(Phase 9, `plans/phase-09-hemisphere-seasons.md`, lifts the next limit on Deep Time planets.)

**Serene Seasons' own effects stay global** (northern). Examples are leaf colours, crop seasons and its
snowfall shift, so a southern summer looks like winter in those mods while this mod reports summer
temperatures.

**The client reads Project Atmosphere's per-biome client cache as before.** Deep Time's climate lives on
the server.

## Tests

- **`DeepTimeGameTests`:**
  - the calendar mapping;
  - the anomaly cap;
  - other worlds unchanged.
- **The coupling on a real Deep Time planet** is checked from Deep Time's side. Its planet server runs
  with this branch's jar, `/mic_climate probe` against `/deeptime climate`.
- **`/mic_climate atmosphere drive <ticks>`:**
  - it runs Project Atmosphere's scheduler on a headless server;
  - it exists only with `-Dmic_climate.driveAtmosphere=true`;
  - it calls the same public entry points Project Atmosphere's level tick calls when a player is
    online.
