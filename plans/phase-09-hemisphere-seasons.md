# Phase 9: seasons by latitude on Deep Time planets

Branch `seasons`. Serene Seasons has one season per world, the northern one. On a Deep Time planet,
which spans both hemispheres and the tropics, that made the south's leaves, crops, snow and melt
follow the northern calendar, and the tropics have winters. Phases 7 and 8 already made the
temperature follow each hemisphere (Deep Time's monthly climate). This phase makes Serene Seasons,
Serene Seasons Plus and Project Atmosphere's own seasonal effects follow latitude too.

Research: `Docs/deeptime-hemisphere-seasons.md` (every Serene Seasons read site, its colour override
hook, the Serene Seasons Plus and Project Atmosphere interactions). Ben's decisions (2026-09-30,
Deep Time's decisions log):

- the code goes in mic-climate;
- **"Full latitude damping"**: the southern hemisphere runs half a year shifted, and season strength
  fades smoothly toward the equator, reaching no seasons at the equator;
- Project Atmosphere: **"Patch PA per position"**;
- Serene Seasons Plus snow: **"Patch it"**.

## The model (`seasons.LatitudeSeasons`)

Latitude is Deep Time's, φ = −z · 360° / C, with C the planet's circumference.

- **The south is half a year out.** South of the equator the calendar is shifted by half a cycle:
  exactly six of Serene Seasons' twelve sub-seasons and three of its six tropical seasons. The local
  boundaries therefore fall on the global ones; Serene Seasons' season-change events, its client
  re-mesh and mic-climate's calendar (Early Spring = 1 March) all stay valid.
- **Seasons fade toward the equator.** The season's strength is a smoothstep of |φ|: 0 at the
  equator, 1 at and beyond `deepTime.fullSeasonLatitude` (default 45°), half strength at half that
  latitude. At the equator the strength is 0, so the half-year jump across it is invisible.
- **"No seasons" is Mid Summer**, Serene Seasons' own neutral season: white grass and foliage
  overlays (the biome's own colour), vanilla's birch colour, no biome temperature adjustment by
  default, and its tropical biomes grow the summer crops all year.

A faded season is pulled toward Mid Summer two ways:

- **Blended** (continuous quantities): grass, foliage and birch colours and the biome temperature are
  the hemisphere's value blended with the Mid Summer value by the strength. They vary smoothly with
  latitude, so a snow line follows the biomes' temperatures, not a line of constant z.
- **Discrete** (decisions that need one sub-season): the hemisphere's sub-season's distance from Mid
  Summer (−6..5 sub-seasons) is scaled by the strength and rounded. Used for crop fertility, the
  melt rate, the season sensor, Serene Seasons Plus's snow policy and Project Atmosphere's regional
  season. With the default 45°, Mid Winter is seen as:

  | Latitude | Strength | Discrete sub-season in the level's Mid Winter |
  |---|---|---|
  | ≥ 45° N | 1.00 | Mid Winter |
  | 35° N | 0.87 | Late Winter |
  | 30° N | 0.74 | Early Spring |
  | 22.5° N | 0.50 | Mid Spring |
  | 15° N | 0.26 | Late Spring |
  | 11.25° N | 0.16 | Early Summer |
  | < 7.8° | < 0.08 | Mid Summer |
  | 45° S | 1.00 | Mid Summer |

## Where it hooks

All positions are in hand at every decision point, so every hook reads the latitude of the block,
chunk or region it is deciding for.

### Serene Seasons (`mic_climate.seasons.mixins.json`, `mixin/seasons/sereneseasons`)

| Mixin / hook | Target (10.1.0.3) | Technique | Covers |
|---|---|---|---|
| `SeasonHooksMixin` | `SeasonHooks.getBiomeTemperature(Level, Holder, BlockPos)`, its call to `getBiomeTemperatureInSeason` | `@WrapOperation`: call it with the hemisphere's sub-season, and again with Mid Summer, then blend | snow and ice placement, rain or snow (server and client, per column per frame), `isRainingAt`, the melt test, Serene Seasons Plus's snow test |
| `ModFertilityMixin` | `ModFertility.isCropFertile(String, Level, BlockPos)`, its `SeasonHelper.getSeasonState` | `@WrapOperation`: the discrete local state | crop growth, out-of-season behaviour, bonemeal (server and client) |
| `RandomUpdateHandlerMixin` | `RandomUpdateHandler.onWorldTick` | `@ModifyExpressionValue` on `meltChance()`/`meltRolls()` (the loop runs at the largest of any sub-season), `@WrapOperation` on `meltInChunk` (a chunk's first roll runs its own rolls at its own chance, the loop's other rolls for it are skipped), `@Inject` at HEAD (reset) | melting in the south during the northern winter, and none in the tropics' "winter" |
| `SeasonSensorBlockMixin` | `SeasonSensorBlock.updatePower(Level, BlockPos)`, its `getSeasonState` | `@WrapOperation`: the discrete local state | the season sensor's redstone output |
| `ModClientMixin` (client) | `ModClient.lambda$registerBlockColors$0` (the birch leaf colour handler) | `@WrapOperation` on its `getSeasonState` (the hemisphere's state), `@ModifyReturnValue` (blend toward vanilla's birch colour) | birch leaves |
| colour override (client, `seasons.client.SeasonsClient`) | `SeasonColorHandlers.registerResolverOverride(GRASS / FOLIAGE, …)` | **no mixin**: Serene Seasons' own extension point. Recolour with its `applySeasonal*Colouring` for the hemisphere's (tropical) season, blend toward the biome's own colour | grass and foliage |

Not patched: Serene Seasons' own season (`SeasonSavedData`), its season-change events, its weather
frequency (level-wide rain), the calendar item, `hasPrecipitationSeasonal` (tropical dry season, no
position), and Thermoo Patches' / InControl's / LSO's level-wide reads.

### Serene Seasons Plus (`mixin/seasons/sereneseasonsplus`)

`SnowAccumulationPolicyMixin`: `@ModifyVariable` on the sub-season parameter of
`SnowAccumulationPolicy.evaluateChunk`, its per-chunk decision to lay storm snow, melt it or leave
it. Serene Seasons Plus passes its cached global sub-season in from its chunk tick
(`SnowChunkWeatherLogic.run`) and its load reconciler (`SnowChunkLoadReconciler`), and from its
re-check of queued work; all three now judge each chunk by its middle's discrete local sub-season. Its
cold test already reads Serene Seasons' patched temperature.

### Project Atmosphere (`mic_climate.projectatmosphere.mixins.json`, beside the phase-8 hook)

Project Atmosphere takes its season from Serene Seasons through one delegate and uses it level-wide.
The exception is its seasonal drift, which asks the delegate once per region, with the region's
position, for the humidity, pressure and cloud-water targets it drifts the region toward; the
delegate ignored that position.

| Mixin | Target (0.9.1.2) | Effect |
|---|---|---|
| `SereneSeasonsSeasonDelegateMixin` | `SereneSeasonsSeasonDelegate.snapshot(Level, BlockPos)` and `moistureStage(Level, BlockPos)`, their `getSeasonState` and `usesTropicalSeasons` | asked with a position, the delegate reads the discrete local state; the tropical wet/dry stage is dropped where the strength is below 0.5 (about 22.5°) |
| `AtmosphericUpdateSchedulerMixin` | `buildStateView`, its `getBiomeSunlightMultiplier()` | each region's day heating uses its own season's sunlight multiplier (the level's multiplier is rescaled by local / level, read through Project Atmosphere's own private modifier by reflection; on failure it stays level-wide) |
| `ClientTickHandlerMixin` (client) | `ClientTickHandler.getCurrentSeason(ClientLevel, BlockPos)` | the wind's falling leaves follow the local season |

Temperature is deliberately not touched here: the phase-8 hook already replaces Project Atmosphere's
global season offset per region with Deep Time's monthly means, which carry each hemisphere's
season, and the tropics' small one. The rest of Project Atmosphere's level-wide season reads either
feed a temperature that hook replaces (forecast generation, its local resolver's biome table) or are
triggers at boundaries the hemispheres share (season-change regeneration), or commands. Its seasonal
trees (Dynamic Trees) and aurora factor are inert in the pack (neither mod is installed) and are not
patched.

## The latitude on both sides (Deep Time climate API 2)

Deep Time's climate API was server-side only, and the client needs the latitude for colours. Deep
Time branch `climate-latitude-api` (not merged) adds, at `DeepTimeClimate.API_VERSION` 2:

- `static int DeepTimeClimate.circumferenceBlocks(Level)`: the planet's C on either side (the
  generator's on the server; on a client the `WorldInfoPayload` the server sends at login, respawn
  and dimension change), 0 for anything else. Allocation-free.
- `static double DeepTimeClimate.latitudeDeg(double z, int circumference)`: −z · 360 / C.
- `ClientPlanetState.circumference(Level)`, which resolves the payload's dimension once.

mic-climate reads it through `provider.DeepTimeSource.circumferenceBlocks` (still the only class that
imports Deep Time). A Deep Time without API 2 answers `NoSuchMethodError`, logged once; the seasons
then stay global everywhere, as before.

## Gating and failing safe

**At mixin time** (`seasons.SeasonsMixinPlugin`, and `atmosphere.ProjectAtmosphereMixinPlugin` for
the three Project Atmosphere mixins):

1. `-Dmic_climate.hemisphereSeasons=false` is not set;
2. Deep Time is installed, or the JVM is a GameTest run;
3. the target mod is inside its checked range: Serene Seasons `[10.1.0.3,10.1.1)`, Serene Seasons Plus
   `[5.1.2,5.2)`, Project Atmosphere `[0.9.1.2,0.9.2)` (`-Dmic_climate.hemisphereSeasons.anyVersion=true`
   lifts the first two);
4. every method the mixin injects into, and every call it wraps, is in the mod's bytecode (read
   straight from its jar).

Otherwise one log line and that part keeps its own season. Behind that: `"required": false`,
`defaultRequire: 0`, `@Pseudo`, `require = 0` on every injector, and every entry point hands the
mod's own value back on any exception. **Without Deep Time nothing is applied at all.**

**At runtime** (`seasons.PlanetLatitude`): only with `deepTime.enabled` and
`deepTime.hemisphereSeasons` (default true) on, and only on a level whose circumference is positive
(a Deep Time planet). North of the full-season latitude the level's season is the local one and every
hook passes Serene Seasons' own value through. Off a planet every hook returns exactly what the
patched code computed.

**Cost.** The latitude is a couple of volatile reads, cached config values and one multiplication;
nothing allocates on the colour and precipitation paths (both run per block, on mesh, Distant
Horizons and render threads).

## Client

- **Colours** use Serene Seasons' resolver override (no mixin); birch uses the lambda mixin above.
- **Re-mesh.** The local sub-seasons change at the same moments as the level's, so Serene Seasons'
  own re-mesh on a sub-season change covers the calendar; the strength does not change with time.
  What it does not cover is the latitude becoming known after the first chunks were meshed (the
  planet info can arrive late) or the switch changing in a running game. `SeasonsClient` re-meshes
  once when the circumference, the switch or the full-season latitude changes for the level on
  screen.
- **Distant Horizons** asks the resolvers with no position (x = z = 0), which would read as the
  equator. Those calls get Serene Seasons' own colour, so LODs look as they did before (the level's
  season), not seasonless.
- The client reads its own `mic_climate-common.toml` for the switch and the full-season latitude (a
  common config is not synced): keep them the same as the server's.

## Config and commands

- `deepTime.hemisphereSeasons` (default true), `deepTime.fullSeasonLatitude` (default 45°).
- `/mic_climate seasons [on|off|config]` reads the switch or sets it for the session.
- `/mic_climate probe` gains:
  - `seasons`: the discrete sub-season here; latitude, strength, the level's and the hemisphere's
    sub-season; Serene Seasons' temperature here beside the level's (snow and ice below 0.15); whether
    wheat grows; the melt chance and rolls; the circumference; how many of its four server classes
    bound;
  - `pa-season`: Project Atmosphere's regional season and sunlight beside its level-wide ones.

## Known limits

- **Serene Seasons Plus's storm counter and winter reset stay global.** It counts snow storms only in
  its (northern) snowy season and resets them at the northern Early Winter. Its storm-based snow piles
  therefore still happen only in northern winters; southern winters get Serene Seasons' and Project
  Atmosphere's ordinary snowfall (per position, already correct), and its warm-season melt now follows
  each chunk's own season, so it no longer clears the southern winter's snow.
- **Weather frequency is level-wide** (Serene Seasons changes how often it rains by season).
- **Tropical dry season** (`hasPrecipitationSeasonal`, no position) stays the level's.
- **The calendar item** shows the level's season.
- **Tropical wet/dry fades with the rest.** Real wet/dry seasonality is strongest at 10–20°; here the
  tropical cycle is shifted for the south and, like the temperate one, fades toward the equator
  (Project Atmosphere drops it below strength 0.5). A question for Ben.
- **Crops near the equator are summer crops.** Serene Seasons' neutral is Mid Summer, so spring- or
  autumn-only crops (carrots, potatoes, beetroot) never grow within about 8° of the equator, as in
  Serene Seasons' own tropical biomes.
- **Discrete decisions change in latitude bands** (at most one sub-season per band); the blended
  quantities (colours, temperature) are smooth.
- **Distant Horizons LODs** keep the level's season (above).

## Tests

`HemisphereSeasonsGameTests` (a stand-in planet: `ClimateConfig.Test.seasonTestPlanet`, which moves
the equator so the test's own blocks sit at any latitude):

- **`hemisphereSeasonsArithmetic`**: strength, the half-year shift, the fading, colour blending, the
  local state's progress.
- **`hemisphereSeasonsInvisibleOffPlanet`**: with every switch on and no planet, Serene Seasons'
  temperature at five z values, wheat's fertility and the melt loop are its own, bit for bit.
- **`hemisphereSeasonsFollowLatitude`**: at northern midwinter and midsummer, at 45 N, 30 N, 11.25 N,
  the equator and 45 S, Serene Seasons' own temperature equals its value for the expected (blended)
  season, the discrete sub-season is the table's, wheat grows where that season says; a chunk at 45 S
  melts at the summer rate while the level has none; switched off, and off the planet, its own again.
- **`hemisphereSeasonsHooksBind`**: 4/4 Serene Seasons, 1/1 Serene Seasons Plus, 2/2 Project
  Atmosphere (server) mixins applied.
- **`hemisphereSnowPolicyFollowsLatitude`** (Serene Seasons Plus): in its Mid Summer a chunk at 45 N
  melts, the same chunk at 45 S keeps its snow; at the equator in its Mid Winter it melts.
- **`hemisphereAtmosphereSeasonsFollowLatitude`** (Project Atmosphere): at northern midwinter its
  regional season is winter at 45 N, summer at the equator (no wet/dry stage) and at 45 S, while its
  level-wide one stays winter; southern sunlight is stronger.

On a real Deep Time planet: Deep Time's `tools/review/seasons-probe.sh` (branch
`climate-latitude-api`), a MIC server probing 32 places at 45 N, 15 N, the equator and 45 S in northern
midwinter and midsummer; its Mixin export confirms every server injector landed. On a real client: Deep
Time's `tools/client/mac-screenshots.sh` with a small mod set (`-PrunSet`), vistas over forests at about
41 N and 45 S in the same Mid Autumn. Results in PLAN.md's build log.

## Credit

Serene Seasons (Glitchfiend, All Rights Reserved), Serene Seasons Plus and Project Atmosphere (Gabou,
All Rights Reserved). mic-climate names their classes and methods and mixes into them at runtime; it
never redistributes, copies or modifies their jars or code.
