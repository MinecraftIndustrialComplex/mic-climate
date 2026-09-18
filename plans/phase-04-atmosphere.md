# mic-climate — Phase 4 implementation plan: pollution → Project Atmosphere (experimental, default off)

Prerequisite: phase 0+1 built. Parent design `../PLAN.md` §5.7. Project Atmosphere facts below were
read from the 0.8.1.0 source at `~/Projects/WinddPhys/Project-Atmosphere` (third-party; custom
licence permits addons and forbids modification — **use only its public classes, never mixin into it**).

## Deliverable

When `pollution.mode = ATMOSPHERE`, Destroy's greenhouse/ozone warming is applied to Project
Atmosphere's live region temperature instead of being added inside the unified provider, so the
warming shows up in PA's own forecasts, HUDs and every consumer of `AtmoApi`. Default stays
`MODIFIER`. Builds with `--rerun-tasks`. No deploy.

## 1. Project Atmosphere facts (0.8.1.0)

- Live state per region: `modules.atmosphere.RegionAtmosphereState` — `getTemperature()`,
  `setTemperature(float)`, `adjustTemperature(float delta)`, `relaxTowardBase(float)`; registry
  `modules.atmosphere.AtmosphericStateRegistry` — `getState(RegionInstanceKey)`, `getStates()`,
  `getActiveStates()`; `util.RegionInstanceKey.from(BlockPos)` (default region size 2000 blocks).
- Tick path `modules.atmosphere.AtmosphericUpdateScheduler.tick(ServerLevel)`: active regions every
  20 ticks, passive every 100 (batched). Each update computes deltas toward a daylight/rain target
  derived from baselines captured at forecast init and applies `state.adjustTemperature(delta)`,
  then `state.relaxTowardBase(factor)` when `relaxFactor > 0`. The state is **not** rewritten from
  the forecast each tick; `fromForecast`/`initializeState` run only at region (re)generation.
- Consequence: an external `adjustTemperature(x)` persists but is eroded over time by
  `relaxTowardBase` (toward the region's `baseTemperature`, which has no public setter in 0.8.1.0)
  and by the delta controller pulling toward its target.
- `AtmoApi.getCurrentWeather(...)` returns `state.getTemperature()` when a state exists; there is
  **no public "pure forecast" read** once a state exists (`ForecastOrchestrator.getCurrentTemperature`
  also returns the live state first).
- `AtmoApi.registerWorldEffect(AtmosphereWorldEffect)` and `AtmosphereWeatherTickEvent` are
  consumer hooks (they receive a snapshot at sampled positions near players); there is no producer
  hook. `modules.temperature.core.TemperatureProvider` exists but has zero consumers (dead).

## 2. Design: a forcing controller with erosion compensation

Because PA relaxes external changes away, a one-shot `adjustTemperature(shift)` decays. Instead run
a small controller per active region, every `pollution.atmosphereIntervalTicks` (default 100):

```
desired  = DestroyPollutionShift.shift(level)             // same value MODIFIER mode uses, honours Destroy's switches
applied  = record.applied                                  // what we believe is still present (starts 0)
observedErosion = record.lastSetTemperature - state.getTemperature()   // how much PA moved it since our last write
                                                           // (positive when PA pulled it back down)
applied  = clamp(applied - observedErosion, 0, desired)    // assume erosion acted on our contribution first
delta    = desired - applied
if |delta| > 0.05: state.adjustTemperature(delta); applied = desired
record.lastSetTemperature = state.getTemperature(); record.applied = applied
```
This is intentionally conservative: it re-adds only what PA removed, never stacks, and returns to
zero when pollution clears (delta goes negative). Keep the record per `RegionInstanceKey` in
`atmosphere/PollutionAtmosphereEffect` (a `Map<RegionInstanceKey, Record>`, cleared on server stop
and when `AtmosphericStateRegistry.getState(key)` becomes null).

Caveat to document: the observed-erosion estimate cannot distinguish PA's natural weather drift
from erosion of our offset; in practice the controller keeps the region within a fraction of a degree
of `natural + desired` on a 100-tick cadence, but it is a heuristic. This is why the mode is
experimental and off by default. The clean fix is a base-temperature offset hook in Project
Atmosphere itself; note that as a possible upstream request to its author (its licence allows
addons, so the request is about a public setter, not a modification we ship).

Interaction with the unified provider: when `pollution.mode == ATMOSPHERE`, `UnifiedEnvironmentProvider`
must skip step 3 (adding the shift) — phase 1 already gates on the mode; verify. Destroy's
`getLocalTemperature` then receives PA's warmed value through Thermoo, so vats still warm.

## 3. Components

- `atmosphere/PollutionAtmosphereEffect` (only class importing `net.Gabou.*` besides
  `ProjectAtmosphereSource`): subscribes to NeoForge `ServerTickEvent.Post`; every interval, for
  each `ServerLevel` and each key in `AtmosphericStateRegistry.getActiveStates()` (active regions
  only; passive regions catch up when they become active), run the controller. Wrap in try/catch;
  disable itself with one ERROR log if PA throws repeatedly (>10 consecutive failures).
- `MicClimate`: `if (Compat.isLoaded("projectatmosphere") && Compat.isLoaded("destroy")) PollutionAtmosphereEffect.init();`
  — registration is unconditional on mode so the config can be flipped at runtime; the tick handler
  itself checks `ClimateConfig.pollutionMode() == ATMOSPHERE` and, when switching back to
  `MODIFIER`, removes any `applied` offset once (`adjustTemperature(-applied)`) before going idle.
- Config: `pollution.atmosphereIntervalTicks` (100, 20–1200). `pollution.mode` already exists.

## 4. Acceptance

- Build passes with `--rerun-tasks`; no PA import outside the two named classes; no mixin into PA.
- In-game checks for the report: with `pollution.mode = ATMOSPHERE` and greenhouse pollution raised
  (Destroy command), `/thermoo environment temperature ~ ~ ~ celsius`, a PG thermometer and PA's own
  temperature display all rise by about the same amount within a few intervals; clearing pollution
  brings them back; switching the config to `MODIFIER` removes the PA offset once and the Thermoo
  value stays warm through the provider instead; with Destroy's `temperatureAffected = false` nothing
  is applied in either mode.
