# mic-climate — Phase 2 implementation plan: Legendary Survival Overhaul bridge

Prerequisite: phase 0+1 (`phase-01-core.md`) is built. Parent design `../PLAN.md` §5.5.
Signatures below were read with javap from `legendarysurvivaloverhaul-1.21.1-2.4.5.jar`; the pack
pins 2.4.7.2 (`maven.modrinth:legendary-survival-overhaul:AZzEduN3`) — re-verify against that jar
once Gradle has fetched it (`javap -p -classpath <jar> sfiomn.legendarysurvivaloverhaul.api.temperature.ModifierBase`).

## Deliverable

Two LSO temperature modifiers registered on LSO's own registry, gated on LSO being present, plus the
pack config file that switches off LSO's overlapping built-ins. Builds with `--rerun-tasks`. No deploy.

## 1. LSO facts

```java
package sfiomn.legendarysurvivaloverhaul.api.temperature;
public abstract class ModifierBase {
    public ModifierBase();
    public float getPlayerInfluence(Player);                      // override: player-body effects (return 0)
    public float getWorldInfluence(Player, Level, BlockPos);      // override: world/position effects
    protected float getNormalizedTempForBiome(Level, Biome);
    protected float applyUndergroundEffect(float, Level, BlockPos, float);
    protected float clampNormalizeTemperature(float);
}
package sfiomn.legendarysurvivaloverhaul.registry;
public class TemperatureModifierRegistry {
    public static final ResourceKey<Registry<ModifierBase>> MODIFIERS_KEY;
    public static final DeferredRegister<ModifierBase> MODIFIERS;   // <-- register HERE; a separate DeferredRegister is ignored
    public static final DeferredHolder<ModifierBase, ModifierBase> BIOME, TIME, WEATHER, SERENE_SEASONS, ALTITUDE, BLOCKS, ...;
    public static void register(IEventBus);
}
package sfiomn.legendarysurvivaloverhaul.api.temperature;
public class TemperatureUtil { public static float getWorldTemperature(Level, BlockPos); public static float getPlayerTargetTemperature(Player); ... }
```
LSO's world temperature = Σ `getWorldInfluence` over `MODIFIERS.getEntries()`. Body-temperature
bands: 5 / 10 / 20 / 30 / 35 → frostbite / cold / normal / hot / heat stroke, so ±10 around 20 is
the comfortable band. LSO's built-in `BlockModifier` scans blocks within
`"Temperature Influence Maximum Distance"` (20) with up/outside multipliers; look at its
`getWorldInfluence` and `doBlocksAndFluidsRoutine` bytecode (`javap -c`) for the traversal shape
if you want to mirror it, but a simple cube scan is acceptable for a first version.

## 2. Registration

`lso/LsoBridge.java` (the only class importing `sfiomn.*`), called from `MicClimate`'s constructor
inside `if (Compat.isLoaded("legendarysurvivaloverhaul")) LsoBridge.init();`:

```java
public static void init() {
    TemperatureModifierRegistry.MODIFIERS.register("mic_climate_world", ClimateWorldModifier::new);
    TemperatureModifierRegistry.MODIFIERS.register("mic_climate_device_heat", DeviceHeatModifier::new);
}
```
Timing: `DeferredRegister.register(name, supplier)` only records the entry; entries are committed on
NeoForge's `RegisterEvent`, which fires after all mod constructors. Our constructor runs after LSO's
because mods.toml orders us AFTER `legendarysurvivaloverhaul`. Add a startup log line listing
`MODIFIERS.getEntries()` ids at `FMLCommonSetupEvent` to prove our two are present (keep it at DEBUG
after verification). The ids will be under LSO's namespace
(`legendarysurvivaloverhaul:mic_climate_world`) — document that in the class javadoc.
Fallback if registration is silently dropped in a dev run: replace `TemperatureUtil.internal` with a
delegating `ITemperatureUtil` that adds our two influences — implement only if needed.

## 3. `ClimateWorldModifier`

```java
public float getWorldInfluence(Player player, Level level, BlockPos pos) {
    if (!ClimateConfig.lsoEnabled()) return 0f;
    float t = Climate.celsius(level, pos);
    return (t - ClimateConfig.lsoNeutralCelsius()) * ClimateConfig.lsoUnitsPerDegree();
}
public float getPlayerInfluence(Player player) { return 0f; }
```
Defaults: `lso.neutralCelsius = 20`, `lso.unitsPerDegree = 0.24` (LSO's biome modifier spans
0…18 units over the −20…56 °C range that Project Atmosphere maps vanilla biomes to; 18/76 ≈ 0.24).
Result at defaults: −20 °C → −9.6, 56 °C → +8.6 — the same envelope LSO's own biome term had.
Optional smoothing: average the 9 offsets LSO's `BiomeModifier` samples (0,0,0; ±10 on x; ±10 on z;
±7,±7) if biome borders produce visible jumps; `Climate` is chunk-cached so this costs little.

## 4. `DeviceHeatModifier`

Mirrors Power Grid's dormant Cold Sweat bridge (`ElectricBlockTemp`): for each block entity within
range carrying a Power Grid `ThermalBehaviour` (this covers PG devices and, via
mic-destroy-electric feature 4, Destroy vats):
```
excess  = max(0, T_device - 22)
temp    = excess * lso.deviceHeat.tempScalar / 100        // default 0.04 per 100 °C
rangeMax= excess * lso.deviceHeat.rangeScalar / 100       // default 0.5 blocks per 100 °C
influence += blend(temp → 0 as distance goes 0.5 → rangeMax)
```
- Only when `Compat.isLoaded("powergrid")`; the PG import lives in a nested helper class loaded
  lazily (`PowerGridThermalReader`) so LSO-without-PG does not NoClassDefFound.
- Scan: cube of radius `min(rangeMaxOfHottestPossible, "Temperature Influence Maximum Distance")` —
  simpler: iterate loaded block entities in the player's chunk and the 8 neighbours
  (`level.getChunk(...).getBlockEntities()`), filter by distance ≤ 20. That avoids a 41³ block scan.
- `BlockEntityBehaviour.get(be, ThermalBehaviour.TYPE)` → `getTemperature()`.
- Cache per player for 10 ticks (LSO evaluates modifiers every "Temperature Tick Time" = 20 ticks
  anyway; verify it does not call more often).
- A 1600 °C basin heater at 1 block: excess 1578 → temp 0.63, range 7.9 blocks. That is mild; if
  play-testing wants "dangerous", raise `tempScalar` in config — do not change the default here.

## 5. Pack config (data, not code)

Write `pack/config/legendarysurvivaloverhaul-common.toml` (copy the instance's file as the base,
keeping all other keys) with:
```
"Biomes affect Temperature" = false
"Serene Seasons Enabled" = false
"Time Based Temperature Modifier" = 0.0
"Rain Temperature Modifier" = 0.0
"Snow Temperature Modifier" = 0.0
```
Leave altitude, shade, wetness, freeze, blocks, huddling, sprint, on-fire as they are. Add a comment
block at the top explaining that mic_climate supplies biome, season, time and weather through
Project Atmosphere. Do NOT remove the basin heater JSON in `pack/kubejs/data/powergrid/...` in this
phase; note in the report that it becomes redundant once `DeviceHeatModifier` is verified in game.
Confirm packwiz picks up `pack/config/` files (check `pack/pack.toml` / `index.toml` for how other
configs are shipped) — if configs are not part of the pack, say so and leave the file for Ben.

## 6. Config keys added

`lso.enabled` (true), `lso.neutralCelsius` (20), `lso.unitsPerDegree` (0.24),
`lso.deviceHeat.enabled` (true), `lso.deviceHeat.tempScalar` (0.04), `lso.deviceHeat.rangeScalar` (0.5).

## 7. Acceptance

- Build passes with `--rerun-tasks`; `MicClimate` has no `sfiomn.*` import.
- javap-verified signatures against the 2.4.7.2 jar.
- Report the in-game checks: LSO HUD temperature tracks the Thermoo environment command's value
  when moving between biomes and as PA weather changes; no season double-count (toggle
  `"Serene Seasons Enabled"` and compare); standing next to a seething basin heater or a hot vat
  raises the reading; a cold device does nothing.
