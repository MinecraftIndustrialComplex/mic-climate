# mic-climate — Phase 0+1 implementation plan: skeleton, unified provider, Power Grid + Destroy consumers, migration

Parent design: `../PLAN.md` (read §3–§6 first). This document is the build spec for the first
builder agent. Everything here is verified against sources unless marked *(verify)*.

## Deliverable

A buildable NeoForge 1.21.1 mod at `mods/mic-climate` (mod id `mic_climate`, group
`com.minecraftindustrialcomplex`, version `0.1.0`) that:

1. registers a Thermoo `EnvironmentProviderType` `mic_climate:unified` and a datapack environment
   definition selecting it for the overworld at priority 2000;
2. exposes `Climate.celsius(Level, BlockPos)` / `Climate.kelvin(...)` with a per-chunk cache;
3. makes Power Grid's `ThermalBehaviour.getAmbientTemperature` and Destroy's
   `PollutionHelper.getLocalTemperature` return the unified value (gated mixins);
4. carries the ambient cache-refresh mixins moved from `mic-destroy-electric`;
5. builds with `nix develop -c ./gradlew build -x test --console=plain --rerun-tasks`.

Plus the matching edits to `mods/mic-destroy-electric` (§7) so the two mods do not double-apply.
**Do not deploy** either jar to `pack/mods/`; do not run packwiz.

## 1. Project skeleton

Copy from `mods/mic-destroy-electric`: `flake.nix`, `flake.lock`, `gradlew`, `gradlew.bat`,
`gradle/`, `settings.gradle`, `build.gradle`, `gradle.properties`, `src/main/templates/META-INF/neoforge.mods.toml`.
Then adjust:

`gradle.properties` (keep the Gradle/NeoForge/parchment block identical to mic-destroy-electric):
```
mod_id          = mic_climate
mod_name        = MIC Climate
mod_version     = 0.1.0
mod_group_id    = com.minecraftindustrialcomplex
mod_authors     = Benjamin McIntyre
mod_license     = MIT
mod_description = One ambient temperature for the whole pack: feeds Project Atmosphere into Thermoo and makes Power Grid, Destroy and friends read it

## Dependencies — Modrinth version ids (verified 2026-09-04)
thermoo_version              = T0vIbHHv   # thermoo 4.8.1-neoforge (1.21.1)
ffapi_version                = V9WdDUTx   # forgified-fabric-api 0.116.15+2.3.5+1.21.1 (pack's pin)
powergrid_version            = ip4gJrgx   # Create: Power Grid 0.6.1
create_version               = 6.0.10-280 # only for compileOnly against PG's Create-typed API (SmartBlockEntity etc.)
ponder_version / flywheel_version / registrate_version  # same values as mic-destroy-electric
destroy_version              = 0.4.1      # local jar, see build.gradle
petrolpark_library_version   = 1.5.6      # flatDir, see build.gradle
lso_version                  = AZzEduN3   # legendary-survival-overhaul 1.21.1-2.4.7.2 (phase 2 uses it; add now)
project_atmosphere_version   = 3OXsfueJ   # project-atmosphere 0.8.1.0 (compileOnly only, never bundled)
crowns_version               = oXIJWFli   # create-crowns 2.2.5 (phase 3 uses it; add now)
```

`build.gradle` dependencies block:
```groovy
// Thermoo API + the Fabric API events it exposes
compileOnly("maven.modrinth:thermoo:${thermoo_version}")
compileOnly("maven.modrinth:forgified-fabric-api:${ffapi_version}")
// Create stack: needed because Power Grid's and Destroy's classes reference Create types
implementation("com.simibubi.create:create-${minecraft_version}:${create_version}:slim") { transitive = false }
implementation("net.createmod.ponder:ponder-neoforge:${ponder_version}+mc${minecraft_version}")
implementation("dev.engine-room.flywheel:flywheel-neoforge-${minecraft_version}:${flywheel_version}")
implementation("com.tterrag.registrate:Registrate:${registrate_version}")
// Bridged mods — compileOnly, all optional at runtime
compileOnly("maven.modrinth:power-grid:${powergrid_version}")
compileOnly(files("${project.projectDir}/../Destroy/a/b/c/build/libs/destroy-${minecraft_version}-${destroy_version}.jar"))
compileOnly("petrolpark:petrolpark-${minecraft_version}:${petrolpark_library_version}")   // flatDir as in mic-destroy-electric
compileOnly("maven.modrinth:legendary-survival-overhaul:${lso_version}")
compileOnly("maven.modrinth:project-atmosphere:${project_atmosphere_version}")
compileOnly("maven.modrinth:create-crowns:${crowns_version}")
```
Keep the same `repositories` block (Modrinth exclusiveContent, createmod, devos, flatDir for
petrolpark). If a Modrinth artifact fails to resolve, report the exact error; do not substitute.
*(verify)* the `thermoo` Modrinth jar's Maven classifier — if Gradle resolves a Fabric jar instead,
pin by file name via `maven.modrinth:thermoo:T0vIbHHv` should already be loader-specific since the
version id is the NeoForge upload.

MixinExtras (`@ModifyReturnValue`) comes with NeoForge 21.1; no extra dependency.

`neoforge.mods.toml` dependencies: `minecraft`, `neoforge` (required, as in mic-destroy-electric);
`thermoo` required `[4.8,)` ordering AFTER; `fabric_api` required (no range) AFTER;
optional AFTER: `powergrid`, `destroy`, `projectatmosphere`, `legendarysurvivaloverhaul`, `crowns`,
`thermoo_patches` *(verify its modId from the Thermoo Patches jar/mods.toml)*.
Mixins: `[[mixins]] config = "mic_climate.mixins.json"`.

Config: use NeoForge `ModConfigSpec` directly (do NOT use catnip `ConfigBase` — that would make
Create a hard dependency). Register `ModConfig.Type.COMMON` as `mic_climate-common.toml`.

## 2. Package layout (`com.minecraftindustrialcomplex.mic_climate`)

```
MicClimate.java                       @Mod entry
Compat.java                           static isLoaded(String modid) via ModList.get().isLoaded (runtime) — plus a
                                      LoadingModList-based variant for the mixin plugin (mod list is not built yet then)
Climate.java                          the read path + cache
config/ClimateConfig.java             ModConfigSpec: SPEC + typed accessors
provider/UnifiedEnvironmentProvider.java
provider/ProjectAtmosphereSource.java  (only class that imports net.Gabou.*)
provider/DestroyPollutionShift.java    (only provider class that imports petrolpark.*)
provider/BiomeSeasonFallback.java
mixin/MicClimateMixinPlugin.java
mixin/powergrid/ThermalBehaviourMixin.java
mixin/powergrid/LightBulbStateMixin.java
mixin/powergrid/SolarPanelBlockEntityMixin.java
mixin/powergrid/CeilingTileSolarBlockEntityMixin.java
mixin/destroy/PollutionHelperMixin.java
```
Resources:
```
mic_climate.mixins.json
data/mic_climate/thermoo/environment/overworld.json
data/mic_climate/thermoo/environment_provider/unified.json
```
Rule: classes that import an optional mod's packages must only be loaded when that mod is present.
`MicClimate` must not import `net.Gabou.*`, `petrolpark.*`, `org.patryk3211.*`, `sfiomn.*`, `com.rae.*`;
it calls into bridge classes through `Compat.isLoaded(...)` guards, and the JVM loads those classes
lazily only on that call path.

## 3. Thermoo integration (verified against the `1.21.1-neoforge` branch)

Registry keys (`ThermooRegistryKeys`): provider types `thermoo:environment_provider_type`
(static `ThermooRegistries.ENVIRONMENT_PROVIDER_TYPE`, a Fabric `Registry`), providers
`thermoo:environment_provider` (datapack), definitions `thermoo:environment` (datapack).
Datapack folders therefore: `data/<ns>/thermoo/environment_provider/*.json` and `data/<ns>/thermoo/environment/*.json`.

Provider JSON shape (from Thermoo's own test data):
```json
{ "type": "thermoo:constant", "components": { "thermoo:temperature": 22.0, "thermoo:relative_humidity": 0.52 } }
```
Ours (`environment_provider/unified.json`):
```json
{ "type": "mic_climate:unified", "pollution": true }
```
Definition (`environment/overworld.json`) — fields `biomes`, optional `exclude_biomes`, `provider`, `priority`:
```json
{ "biomes": "#minecraft:is_overworld", "provider": "mic_climate:unified", "priority": 2000 }
```
*(verify)* the exact JSON form of a `HolderSet` tag reference and a provider holder in
`EnvironmentDefinition.CODEC` (`RegistryCodecs.homogeneousList` accepts `"#tag"` or a list; the
provider is `RegistryFileCodec` so a string id works). Copy the pattern from
`src/testmod/resources/data/thermoo_test/thermoo/environment/*.json` in the Thermoo clone at
`/tmp/claude-1000/-home-greencheetah-Projects-mic/aba4b72d-2702-456a-b053-b7880f68b56e/scratchpad/thermoo`.

Registering the type: mirror how Thermoo registers `EnvironmentProviderTypes.CONSTANT` etc. into
`ThermooRegistries.ENVIRONMENT_PROVIDER_TYPE` (find the call site in the clone — likely
`Registry.register(ThermooRegistries.ENVIRONMENT_PROVIDER_TYPE, id, type)` from a static
initialiser or mod init). Do it in `MicClimate`'s constructor: Thermoo is a required dependency
ordered before us, so its registry exists.

Reading a component: `EnvironmentLookup.getInstance().findEnvironmentComponents(level, pos)`
→ `DataComponentMap`; `map.getOrDefault(EnvironmentComponentTypes.TEMPERATURE, TemperatureRecordComponent.DEFAULT)`
→ `TemperatureRecord`; convert with its `TemperatureUnit` to Celsius (see `TemperatureUnit`'s
`toCelsius`/`fromCelsius` — read the class for exact names).

Reading back from a `DataComponentMap.Builder` inside a provider: Thermoo's own
`TemperatureShiftEnvironmentProvider` casts the builder to a `FabricComponentMapBuilder` and calls
`getOrDefault(...)`. Use the same cast (find its FQN in the clone). Prefer building the fallback
into a temporary map via `DataComponentMap.builder()...build()` if the cast is awkward.

## 4. `Climate`

```java
public final class Climate {
    public static float celsius(Level level, BlockPos pos);
    public static float kelvin(Level level, BlockPos pos);   // celsius + 273.15f
    public static void invalidate(Level level);              // for tests/commands
}
```
- Cache key: `(Level identity, ChunkPos.asLong(pos))`; value `(gameTime, celsius)`; TTL
  `ClimateConfig.cacheTicks` (default 20). `WeakHashMap<Level, Long2ObjectOpenHashMap<Sample>>`
  (fastutil is available through Minecraft). Synchronise: the lookup is called from the server
  thread and the render thread on the client; use a `ConcurrentHashMap`-per-level or lock.
- Cap each level's map at 4096 chunks (evict oldest on overflow) so long flights do not grow it.
- On any exception (early load, ponder virtual levels — `level.isClientSide()` with no biome data,
  `Level` being a Create `PonderLevel`/`SchematicLevel`) return `20f` and cache it for the TTL.
- Reads are chunk-resolution deliberately: the unified value is region/biome-resolution anyway.

## 5. `UnifiedEnvironmentProvider`

Codec: `MapCodec` with `pollution` (bool, default true). `getType()` returns our registered type.

```java
public void buildCurrentComponents(Level level, BlockPos pos, Holder<Biome> biome, DataComponentMap.Builder builder) {
    Float t = null;
    ClimateConfig.Source source = ClimateConfig.source();
    if (source != THERMOO && Compat.isLoaded("projectatmosphere"))
        t = ProjectAtmosphereSource.celsius(level, pos, biome);         // null on any failure
    if (t == null && source == PROJECT_ATMOSPHERE) MicClimate.LOGGER.warn(once) ...
    if (t == null)
        t = BiomeSeasonFallback.celsius(level, pos, biome);
    if (pollution && Compat.isLoaded("destroy") && ClimateConfig.pollutionMode() == MODIFIER)
        t += DestroyPollutionShift.shift(level);
    builder.set(EnvironmentComponentTypes.TEMPERATURE, new TemperatureRecord(t, TemperatureUnit.CELSIUS));
}
```
Humidity: if PA answered, also set `EnvironmentComponentTypes.RELATIVE_HUMIDITY` from the snapshot
if `WeatherSnapshot` carries one *(it does not in 0.8.1.0 — skip unless present)*.

### 5.1 `ProjectAtmosphereSource` (imports `net.Gabou.projectatmosphere.*` only here)
- Server (`level instanceof ServerLevel sl`): `AtmoApi.getInstance().getCurrentWeather(sl, pos).temperatureC()`.
  PA returns an all-zero snapshot when it has no region state yet — treat `temperatureC == 0f && !state-known` as
  unknown: *(verify)* whether `AtmosphericStateRegistry.getState(RegionInstanceKey.from(pos))` is
  null before init; if so check it first and return null in that case rather than 0 °C.
- Client: `BiomeClientTemperatureCache.getTemperature(biomeId, level)` where `biomeId` is
  `biome.unwrapKey().map(ResourceKey::location)`; return null if the cache has no entry (read the
  class to see its sentinel).
- Wrap everything in try/catch (Throwable → null, log once at debug).

### 5.2 `BiomeSeasonFallback` (no PA)
Thermoo ships no default environment definitions, so without PA the lookup would return the
20 °C default everywhere. Provide a biome+season value using Project Atmosphere's own vanilla
mapping so the two paths agree in spirit:
```
base = clamp(biome.getBaseTemperature(), -0.5, 2.0)
T    = -20 + 30.4 * (base + 0.5)                      // -0.5 → -20 °C, 0.8 → 19.5 °C, 2.0 → 56 °C
T   += seasonOffset(ThermooSeason.getCurrentSeason(level [, pos]))   // Optional<ThermooSeason>; empty → 0
```
`seasonOffset` from config `fallback.<season>Offset`: WINTER −8, SPRING −2, SUMMER +4, AUTUMN −1,
tropical seasons 0 (read the enum's constants in `api/season/ThermooSeason.java` and add a key per
constant). Thermoo Patches supplies Serene Seasons' season through `ThermooSeasonEvents`; without
it the offset is simply 0.

### 5.3 `DestroyPollutionShift` (moved from mic-destroy-electric's `PollutionTemperature`)
Same code: `level.getData(DestroyAttachmentTypes.LEVEL_POLLUTION).getOutdoorTemperature() − LevelPollution.BASELINE_OUTDOOR_TEMPERATURE_K`,
times `ClimateConfig.pollutionMultiplier`, cached 20 ticks per level, 0 on any exception. Keep the
class-level javadoc explaining that Destroy's `enablePollution`/`temperatureAffected` are honoured
because `getOutdoorTemperature()` returns the flat baseline when they are off.

## 6. Consumers (mixins)

`mic_climate.mixins.json`:
```json
{ "required": true, "minVersion": "0.8", "package": "com.minecraftindustrialcomplex.mic_climate.mixin",
  "compatibilityLevel": "JAVA_21", "plugin": "com.minecraftindustrialcomplex.mic_climate.mixin.MicClimateMixinPlugin",
  "mixins": [ "powergrid.ThermalBehaviourMixin", "powergrid.LightBulbStateMixin",
              "powergrid.SolarPanelBlockEntityMixin", "powergrid.CeilingTileSolarBlockEntityMixin",
              "destroy.PollutionHelperMixin" ],
  "injectors": { "defaultRequire": 1 } }
```
`MicClimateMixinPlugin implements IMixinConfigPlugin`: `shouldApplyMixin(target, mixinClassName)`
returns true iff the sub-package (`powergrid`, `destroy`, later `crowns`) names a mod id that
`LoadingModList.get().getModFileById(modid) != null`. Other methods no-op. Log which mixins were
skipped at INFO.

### 6.1 Power Grid (`org.patryk3211.powergrid.electricity.base.ThermalBehaviour` etc., PG 0.6.1)
- `ThermalBehaviourMixin`: `@ModifyReturnValue(method = "getAmbientTemperature", at = @At("RETURN"))`
  `private static float micc$unified(float original, Level level, BlockPos pos)` → if
  `ClimateConfig.powergridEnabled()` return `Climate.celsius(level, pos)` else `original`.
  Plus `@Shadow private float cachedAmbientTemperature;` and `@Inject(method = "tick", at = @At("HEAD"))`
  re-sampling it every 100 ticks via `ThermalBehaviour.getAmbientTemperature(getWorld(), getPos())`
  — copy `mic-destroy-electric/src/main/java/.../mixin/ThermalBehaviourMixin.java` verbatim, renaming
  the `micde$` prefix to `micc$`.
- `LightBulbStateMixin`: copy from mic-destroy-electric (`@Shadow private Float cachedAmbientTemperature`, null every 100 ticks in `tick` HEAD).
- `SolarPanelBlockEntityMixin` / `CeilingTileSolarBlockEntityMixin`: ONLY the ambient re-sample part of
  mic-destroy-electric's versions (`@Shadow private float ambientTemp`, `@Inject(method = "electricalTick", at = @At("HEAD"))`,
  100-tick countdown, `ThermalBehaviour.getAmbientTemperature(level, pos)`). Do NOT copy the smog-dimming
  `@ModifyReturnValue` — that stays in mic-destroy-electric.
- No wire or bearing mixin: both read the static ambient live.

### 6.2 Destroy (`petrolpark.mc.destroy.core.pollution.PollutionHelper`, Destroy 0.4.1)
`PollutionHelperMixin`: `@ModifyReturnValue(method = "getLocalTemperature", at = @At("RETURN"))`
`private static float micc$unified(float original, Level level, BlockPos pos)` → if
`ClimateConfig.destroyEnabled()` return `Climate.kelvin(level, pos)` else `original`. Static
method target: `getLocalTemperature` is `public static final float getLocalTemperature(Level, BlockPos)`.
Full replacement: the pollution term is already inside the unified value.

## 7. Edits to `mods/mic-destroy-electric` (do these in the same task, after mic-climate compiles)

1. Delete `content/PollutionTemperature.java`, `mixin/ThermalBehaviourMixin.java`, `mixin/LightBulbStateMixin.java`.
2. In `mixin/SolarPanelBlockEntityMixin.java` and `mixin/CeilingTileSolarBlockEntityMixin.java`: remove
   the `@Shadow ambientTemp`, the countdown field and the `electricalTick` refresh inject; keep the
   smog `@ModifyReturnValue` on `getIrradiance`. Update class javadoc accordingly.
3. `mic_destroy_electric.mixins.json`: remove `LightBulbStateMixin` and `ThermalBehaviourMixin`.
4. `config/CDestroyElectric.java`: remove `pollution.temperatureMultiplier` (keep `smogSolarDimming`);
   fix the `pollution` section comment.
5. `neoforge.mods.toml`: add optional dependency `mic_climate` (`ordering = "NONE"`), comment
   "provides the ambient-temperature link; without it Power Grid ignores Destroy's pollution".
6. Grep the addon for any remaining `PollutionTemperature` reference (ponder scenes? lang?) and fix.
7. Rebuild mic-destroy-electric with `--rerun-tasks`; must pass.

## 8. Config keys (this phase)

| key | default | notes |
|---|---|---|
| `source` | `AUTO` | enum AUTO / PROJECT_ATMOSPHERE / THERMOO |
| `cacheTicks` | 20 | 1–200 |
| `pollution.mode` | `MODIFIER` | enum MODIFIER / ATMOSPHERE (ATMOSPHERE is implemented in phase 4; accept it now, treat as MODIFIER with a warning) |
| `pollution.multiplier` | 1.0 | 0–100 |
| `fallback.winterOffset` … one per `ThermooSeason` constant | see §5.2 | −50…50 |
| `powergrid.enabled`, `destroy.enabled` | true | |
| `lso.*`, `crowns.*` | — | do not add yet (later phases own them) |

## 9. Acceptance

- `nix develop -c ./gradlew build -x test --console=plain --rerun-tasks` passes for BOTH mods.
- `mic_climate` jar contains the two datapack JSONs and the mixin config with the plugin.
- Every mixin target/shadow was checked against the actual jars (`javap -p -classpath <jar>`)
  — PG `power-grid-ip4gJrgx.jar` in the Gradle cache, Destroy `destroy-1.21.1-0.4.1.jar`. There is
  no mixin annotation processor; the compiler will not catch a wrong target.
- No class in `MicClimate`'s import list belongs to an optional mod.
- Report: files created, exact hooks used, any *(verify)* item's resolution, build output, and the
  in-game checks (Thermoo's environment command at the player vs a PG thermometer vs an empty vat).
