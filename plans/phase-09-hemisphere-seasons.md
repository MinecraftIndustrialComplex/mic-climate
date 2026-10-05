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

Follow-up decisions (2026-09-30, after the first build): crops **"Let them grow year-round"** in the
seasonless band; tropical wet/dry **"Exempt wet/dry"**; the two settings **"Server-synced"**; the
full-season latitude stays 45°. The branch stays unmerged for now.

Second follow-up decisions (2026-09-30): **"Temperate seasons there"** (Serene Seasons' tropical
biomes follow the normal temperate seasons outside the wet/dry band, cross-fading over 20° to 25°,
for everything Serene Seasons decides with its tropical rule: colours, crops, precipitation,
temperature) and **"Follow the sun"** (the tropical wet season is each hemisphere's summer half).

## The model (`seasons.LatitudeSeasons`)

Latitude is Deep Time's, by its projection (below: API 4; on a Lambert world φ = asin(−z / R), R = C / 2π).

- **The south is half a year out.** South of the equator the temperate calendar is shifted by half
  a cycle: exactly six of Serene Seasons' twelve sub-seasons. The local boundaries therefore fall on
  the global ones; Serene Seasons' season-change events, its client re-mesh and mic-climate's calendar
  (Early Spring = 1 March) all stay valid. (The tropical calendar is half a year out between the
  hemispheres too, but see "Follow the sun" below: it is the north that is moved.)
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
  | < 7.9° | ≤ 1/12 | Mid Summer (the seasonless band) |
  | 45° S | 1.00 | Mid Summer |

Two things are exempt from the fading:

- **Crops grow year-round in the seasonless band** (strength ≤ 1/12, about 7.9° either side of the
  equator, exactly where the discrete season never leaves Mid Summer). Every crop counts as in season
  there, spring- and autumn-only ones included, and in Serene Seasons' tropical biomes too (which
  otherwise grow only the summer crops). Its other rules still decide: infertile biomes, cold biomes'
  winter crops, greenhouse glass, underground.
- **The tropical wet/dry cycle** has its own strength: 0 within 5° of the equator, a smoothstep up to
  1 at 10°, 1 to 20°, down to 0 at 25°. Serene Seasons' tropical biomes blend their grass, foliage and
  birch colours by it (not by the temperate strength), and Project Atmosphere keeps its tropical
  wet/dry stage where it is at least 0.5 (7.5° to 22.5°).

### Tropical biomes beyond the wet/dry band ("Temperate seasons there")

Serene Seasons gives the biomes in its `tropical_biomes` tag (jungles, savannas, deserts, badlands,
mangroves, mushroom fields, warm ocean, plus whatever the pack adds) a separate rule: no temperature
shift, a wet/dry calendar for colours and for whether it rains, summer crops only, and never the
temperate cycle. Beyond the wet/dry band that left a desert at 40° N with no winter. Now, outside the
band, they follow the normal temperate seasons, the same as any biome at that latitude (the same
strength and, in the south, the same inversion):

- `LatitudeSeasons.temperateWeight(lat)`: 0 up to 20°, a smoothstep to 1 at 25°, 1 beyond (the
  complement of the tropical strength between 20° and 25°). Inside the band they keep wet/dry.
- **Colours** (grass, foliage, birch) cross-fade continuously: the wet/dry colour (blended by the
  tropical strength) to the temperate one (blended by the temperate strength) by that weight
  (`LatitudeSeasons.tropicalBiomeColour`). At 40° N a desert is autumn orange in northern autumn.
- **Decisions that need one rule** switch at the middle of the fade, 22.5° (`temperateRule`: beyond;
  `wetDryRule`: 7.5° to 22.5°, the same cut as Project Atmosphere's stage): crop fertility (the
  "summer crops only" rule gives way to the temperate crop seasons), precipitation, and the tropical
  rule of the temperature. Nearer than 7.5° there is no wet/dry cycle at all: a tropical biome has
  its own precipitation all year there (and every crop in the seasonless band).
- **Temperature** cross-fades too: unshifted at 20°, the temperate shift (blended by the temperate
  strength) at 25° and beyond. Most tagged biomes are warmer than 0.8 and never shifted by Serene
  Seasons anyway (jungle 0.95, savanna, desert, badlands); mangrove swamp, warm ocean and any modded
  tagged biome of 0.8 or less now cool in winter beyond the band.

### The wet season follows the sun ("Follow the sun")

Serene Seasons' tropical calendar has its wet season from Early Winter to Late Spring (December to
May, with Early Spring = 1 March): the southern tropics' wet season on Earth, and the northern
tropics' dry one. Here the wet season is each hemisphere's summer half: **the south keeps Serene
Seasons' own tropical calendar, the north's is moved half a cycle** (three tropical seasons, exactly
six sub-seasons; `LatitudeSeasons.shiftTropical`). At 15° N it is wet from Early Summer to Late
Autumn (sub-seasons 3 to 8: June to November) and dry from Early Winter to Late Spring, at 15° S the
reverse. Everything that reads the tropical season gets it from the same place: the colours, the
local season state Project Atmosphere's delegate reads (its WET and DRY stage), and Serene Seasons'
own precipitation rule.

## Where it hooks

All positions are in hand at every decision point, so every hook reads the latitude of the block,
chunk or region it is deciding for.

### Serene Seasons (`mic_climate.seasons.mixins.json`, `mixin/seasons/sereneseasons`)

| Mixin / hook | Target (10.1.0.3) | Technique | Covers |
|---|---|---|---|
| `SeasonHooksMixin` | `SeasonHooks.getBiomeTemperature(Level, Holder, BlockPos)`, its call to `getBiomeTemperatureInSeason` | `@WrapOperation`: call it with the hemisphere's sub-season, and again with Mid Summer, then blend; for a tropical biome beyond the band, again with its tropical tag reading false (a thread-local), cross-faded from the unshifted value | snow and ice placement, rain or snow (server and client, per column per frame), `isRainingAt`, the melt test, Serene Seasons Plus's snow test |
| same | `SeasonHooks.getBiomeTemperatureInSeason`, its `Holder.is` | `@WrapOperation`: the tropical-biome tag reads false while the thread-local says so | the temperate temperature shift in tropical biomes beyond the band |
| same | `SeasonHooks.getPrecipitationAtSeasonal(Level, Holder, BlockPos)`, its call to `hasPrecipitationSeasonal`; and in `hasPrecipitationSeasonal` its `Holder.is` and `SeasonHelper.getSeasonState` | `@WrapOperation` on the call (it has the position) sets a thread-local; the two inner wraps make the tag read false (no wet/dry cycle here, or the temperate seasons) or hand over the local tropical season state | rain in tropical biomes: Serene Seasons' dry and wet seasons from the local calendar in the band, the biome's own precipitation elsewhere (client rain rendering, `isRainingAt`) |
| `ModFertilityMixin` | `ModFertility.isCropFertile(String, Level, BlockPos)`: its `SeasonHelper.getSeasonState`, its `Holder.is(TagKey)` calls, its returns | `@WrapOperation`: the discrete local state. In the seasonless band and beyond 22.5°: the tropical-biome tag reads false (every crop near the equator, the temperate crop seasons beyond), and a refused crop in the band is asked about again in each season through `isCropFertile` itself (`@ModifyReturnValue`, a thread-local season) | crop growth, out-of-season behaviour, bonemeal (server and client), year-round crops near the equator |
| `RandomUpdateHandlerMixin` | `RandomUpdateHandler.onWorldTick` | `@ModifyExpressionValue` on `meltChance()`/`meltRolls()` (the loop runs at the largest of any sub-season), `@WrapOperation` on `meltInChunk` (a chunk's first roll runs its own rolls at its own chance, the loop's other rolls for it are skipped), `@Inject` at HEAD (reset) | melting in the south during the northern winter, and none in the tropics' "winter" |
| `SeasonSensorBlockMixin` | `SeasonSensorBlock.updatePower(Level, BlockPos)`, its `getSeasonState` | `@WrapOperation`: the discrete local state | the season sensor's redstone output |
| `ModClientMixin` (client) | `ModClient.lambda$registerBlockColors$0` (the birch leaf colour handler) | `@WrapOperation` on its `getSeasonState` (the hemisphere's state), `@ModifyReturnValue` (blend toward vanilla's birch colour by the temperate strength; in tropical biomes the wet/dry colour by the tropical strength, cross-faded over 20° to 25° to the temperate birch colour for the hemisphere's season) | birch leaves |
| colour override (client, `seasons.client.SeasonsClient`) | `SeasonColorHandlers.registerResolverOverride(GRASS / FOLIAGE, …)` | **no mixin**: Serene Seasons' own extension point. Recolour with its `applySeasonal*Colouring` for the hemisphere's season, blend toward the biome's own colour by the temperate strength; tropical biomes use the (moved) tropical season blended by the tropical strength inside the band, cross-fading to the temperate colour over 20° to 25° | grass and foliage |

Not patched: Serene Seasons' own season (`SeasonSavedData`), its season-change events, its weather
frequency (level-wide rain), the calendar item, and Thermoo Patches' / InControl's / LSO's
level-wide reads.

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
| `SereneSeasonsSeasonDelegateMixin` | `SereneSeasonsSeasonDelegate.snapshot(Level, BlockPos)` and `moistureStage(Level, BlockPos)`, their `getSeasonState` and `usesTropicalSeasons` | asked with a position, the delegate reads the discrete local state; the tropical wet/dry stage (the local tropical season: the wet season is each hemisphere's summer half) applies where the tropical strength is at least 0.5, 7.5° to 22.5°, and gives way to the temperate stage beyond |
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

## The seamless world: latitude by projection (Deep Time climate API 4)

From Deep Time's world-data format 0.5 the default planet is the Petroff-Guyou world (an equal-area
2:1 map, 13,312 x 6,656 blocks at 16k: an east-west slide on the x edges and half-turns on the north and
south edges). Latitude there depends on x as well as z, so `-z * 360 / C` (and even Lambert's
`asin(-z / R)`) is wrong. Deep Time's climate API 4 adds `latitudeDeg(Level, x, z)` (both sides,
by the planet's projection, north positive, NaN off a planet) and `projectionKind(Level)`
(`"petroff_guyou"`, `"lambert"` or `""`). mic-climate now:

- asks `DeepTimeSource.latitudeDeg(level, x, z)` for every latitude (`PlanetLatitude.latitude` takes the
  block's x and z; every caller passes both: the position, a chunk's middle, the colour resolvers' x and
  z, a Project Atmosphere region's position);
- falls back, for a Deep Time older than API 4 (a Lambert world whatever its version), to Deep Time's own
  `latitudeDeg(z, circumference)` of API 2, and to the linear `-z * 360 / C` when even that is missing; each
  missing method is logged once;
- caches each thread's last 1,024 answers per level, since Deep Time's answer allocates a little on a
  Petroff-Guyou world and the colour resolvers ask per block per biome-blend sample;
- reads `projectionKind` for the probe line (`projection <kind>`) and the client's re-mesh trigger
  (the planet becoming a different projection re-meshes, like a circumference change).

Nothing else assumed the old shape: the seasons depend on latitude only (hemisphere = its sign, the
equator is seasonless), and Project Atmosphere's regions are keyed by position. The poles of a
Petroff-Guyou world are points on its long edges, not lines; the strength clamps at the full-season
latitude, so nothing special happens there.

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

**At runtime** (`seasons.PlanetLatitude`): only with `deepTime.hemisphereSeasons` (server config,
default true) on, and only on a level whose circumference is positive
(a Deep Time planet). North of the full-season latitude the level's temperate season is the local one and
every temperate hook passes Serene Seasons' own value through (a tropical biome's wet/dry calendar is
the north's moved one everywhere, but is read only inside the band). Off a planet every hook returns
exactly what the patched code computed.

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
- **The settings come from the server.** Both live in the world's server config
  (`serverconfig/mic_climate-server.toml`), which NeoForge sends to every client when it joins, so a
  client's colours follow the server's switch and full-season latitude. The session switch
  (`/mic_climate seasons on|off`) lives in the server's JVM only: in singleplayer the client sees it
  too; clients of a dedicated server keep the file value.

## Config and commands

- In the world's server config `mic_climate-server.toml` (synced to clients): `deepTime.hemisphereSeasons`
  (default true) and `deepTime.fullSeasonLatitude` (default 45°). The switch no longer depends on the
  common `deepTime.enabled` (a client cannot see a common setting of the server).
- `/mic_climate seasons [on|off|config]` reads the switch or sets it for the session.
- `/mic_climate probe` gains:
  - `seasons`: the discrete sub-season here; latitude, strength, the level's and the hemisphere's
    sub-season; the tropical wet/dry strength; Serene Seasons' temperature here beside the level's (snow
    and ice below 0.15); whether wheat and carrots grow; the melt chance and rolls; the circumference;
    how many of its four server classes bound;
  - `pa-season`: Project Atmosphere's regional season and sunlight beside its level-wide ones.

## Known limits

- **Serene Seasons Plus's storm counter and winter reset stay global.** It counts snow storms only in
  its (northern) snowy season and resets them at the northern Early Winter. Its storm-based snow piles
  therefore still happen only in northern winters; southern winters get Serene Seasons' and Project
  Atmosphere's ordinary snowfall (per position, already correct), and its warm-season melt now follows
  each chunk's own season, so it no longer clears the southern winter's snow.
- **Weather frequency is level-wide** (Serene Seasons changes how often it rains by season).
- **The calendar item** shows the level's season.
- **The tropical-to-temperate switch of discrete decisions is on or off** (22.5°), as is Project
  Atmosphere's wet/dry stage (7.5° to 22.5°); only colours and the temperature cross-fade smoothly.
- **Tropical biomes' temperature beyond the band** is shifted only for tagged biomes Serene Seasons
  would shift if they were not tropical (base temperature 0.8 or less): mangrove swamp, warm ocean,
  modded biomes. Jungles, savannas, deserts and badlands are warmer than that and are never shifted,
  at any latitude, as in Serene Seasons.
- **Serene Seasons' birch handler cannot be asked twice**, so a tropical biome's temperate birch colour
  beyond the band is computed from Serene Seasons' public season colours, its configuration and
  its lesser-colour tag (`SereneSeasonsHemispheres.temperateBirch`), the one place the tropical rule
  mirrors its logic.
- **The calendar item and Serene Seasons' own debug text** show the level's tropical season.
- **Discrete decisions change in latitude bands** (at most one sub-season per band); the blended
  quantities (colours, temperature) are smooth.
- **Distant Horizons LODs** keep the level's season (above).

## Tests

`HemisphereSeasonsGameTests` (a stand-in planet: `ClimateConfig.Test.seasonTestPlanet`, which moves
the equator so the test's own blocks sit at any latitude):

- **`hemisphereSeasonsArithmetic`**: strength, the half-year shift, the fading, the seasonless band,
  the tropical wet/dry strength, the moved tropical calendar, the temperate share of tropical
  biomes and its two rules, colour blending (including a tropical biome's cross-fade), the local
  state's progress.
- **`hemisphereSeasonsInvisibleOffPlanet`**: with every switch on and no planet, Serene Seasons'
  temperature at five z values, wheat's fertility and the melt loop are its own, bit for bit.
- **`hemisphereSeasonsFollowLatitude`**: at northern midwinter and midsummer, at 45 N, 30 N, 11.25 N,
  5 N, the equator and 45 S, Serene Seasons' own temperature equals its value for the expected (blended)
  season, the discrete sub-season is the table's, wheat and carrots grow where that season says, and
  carrots grow all year in the seasonless band (5 N, the equator) but not at 11.25 N; a chunk at 45 S
  melts at the summer rate while the level has none; switched off, and off the planet, its own again.
- **`hemisphereTropicalBiomesFollowTemperateSeasons`** ("Temperate seasons there"; a savanna and a
  desert via `/fillbiome`): at 40 N and 40 S the season decisions use is winter / spring / summer
  (autumn in the south's spring) in the expected hemisphere, wheat and carrots follow the temperate
  crop seasons where Serene Seasons' own tropical rule grows wheat all year and carrots never, the
  crop rule flips from tropical (22) to temperate (23) in both hemispheres and stays tropical at 15,
  the seasonless band keeps every crop; a jungle rains and a savanna does not at 40 N / 40 S whatever
  Serene Seasons' tropical calendar says, there is no dry season at 2 N, switched off is Serene
  Seasons' own; a mangrove swamp's temperature equals the biome's own inside the band, the temperate
  shift beyond it (40, 50, 40 S, 25) and lies between at 22.5. The colour blend is arithmetic in
  `hemisphereSeasonsArithmetic` (a client class cannot run on the dedicated server): at 15 and 20 the
  wet/dry colour, at 22.5 between, at 25 and beyond the temperate one at the temperate strength.
- **`hemisphereTropicalWetSeasonFollowsTheSun`** ("Follow the sun"): for each of the twelve level
  sub-seasons, the tropical season at 15 N is wet from Early Summer to Late Autumn and dry otherwise,
  at 15 S the reverse; northern midsummer is wet in the north and dry in the south, northern
  midwinter the reverse; precipitation in a savanna and a jungle agrees (rain in the wet season, none
  in the dry, both hemispheres); Serene Seasons alone keeps its own calendar.
- **`hemisphereAtmosphereWetDryFollowsTheSun`** (Project Atmosphere): its stage in a savanna at 15 N is
  WET in northern midsummer and DRY in midwinter, at 15 S the reverse, agreeing with Serene Seasons'
  tropical season; it applies at 22 and gives way at 23 (both hemispheres), and beyond the band the
  stage is the hemisphere's temperate season.
- **`hemisphereSeasonsServerConfig`**: both settings come from the world's server config, with their
  defaults.
- **`hemisphereSeasonsHooksBind`**: 4/4 Serene Seasons, 1/1 Serene Seasons Plus, 2/2 Project
  Atmosphere (server) mixins applied.
- **`hemisphereSnowPolicyFollowsLatitude`** (Serene Seasons Plus): in its Mid Summer a chunk at 45 N
  melts, the same chunk at 45 S keeps its snow; at the equator in its Mid Winter it melts.
- **`hemisphereAtmosphereSeasonsFollowLatitude`** (Project Atmosphere): at northern midwinter its
  regional season is winter at 45 N, summer at the equator (no wet/dry stage) and at 45 S, while its
  level-wide one stays winter; southern sunlight is stronger; its tropical wet/dry stage applies from
  7.5° to 22.5° in both hemispheres and nowhere else.

On a real Deep Time planet: Deep Time's `tools/review/seasons-probe.sh` (branch
`climate-latitude-api`), a MIC server probing 32 places at 45 N, 15 N, the equator and 45 S in northern
midwinter and midsummer; its Mixin export confirms every server injector landed. On a real client: Deep
Time's `tools/client/mac-screenshots.sh` with a small mod set (`-PrunSet`), vistas over forests at about
41 N and 45 S in the same Mid Autumn. Results in PLAN.md's build log.

## Credit

Serene Seasons (Glitchfiend, All Rights Reserved), Serene Seasons Plus and Project Atmosphere (Gabou,
All Rights Reserved). mic-climate names their classes and methods and mixes into them at runtime; it
never redistributes, copies or modifies their jars or code.
