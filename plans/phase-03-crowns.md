# mic-climate — Phase 3 implementation plan: Create: CROWNS bridge

Prerequisite: phase 0+1 built. Parent design `../PLAN.md` §5.6. Source facts below were read from
the CROWNS `1.21.1-dev` branch (source version 3.0.2, clone at
`/tmp/claude-1000/-home-greencheetah-Projects-mic/aba4b72d-2702-456a-b053-b7880f68b56e/scratchpad/crowns`).
**The compile jar is Modrinth `create-crowns` version `oXIJWFli` = 2.2.5**, which may predate some
of these classes. First step of this phase is to javap the 2.2.5 jar for every symbol below; if a
seam is missing there, adapt to what the jar has and say so in the report. CROWNS is not in the pack
yet; whichever version Ben adds must match the jar this was verified against.

## Deliverable

CROWNS' per-block temperature field uses the unified ambient (kelvin) as its biome default, both at
chunk initialisation and on a periodic refresh so seasons and weather move it. Gated on `crowns`
being loaded. Builds with `--rerun-tasks`. No deploy.

## 1. CROWNS facts (package `com.rae.crowns`, mod id `crowns`)

- `PhysicsSaveManager.getDefaultTemperature(LevelChunkSection section, Vec3i pos, BlockState state)`:
  `pos` is the **absolute** block position (callers pass `base + d`), and it does
  `float defaultT = CROWNS.BIOME_TEMPERATURES.getValue(biome.value(), 300f);` (the only ambient
  source; fluid/block tables override it per block). Datapack table
  `data/crowns/float_map/biomes/temperatures.json` in kelvin.
- It is invoked through `DataLayerType.DEFAULT_TEMPERATURE`'s initializer (a method reference) from
  `PhysicsWorldData.initialise(ServerLevel level)` (line ~305, has `level` + absolute `mutablePos`)
  and from `PhysicsWorldData.set(LevelChunkSection, BlockPos, BlockState, DataLayerType[])`
  (line ~417, no level parameter; its caller `updateChangedBlocks(ServerLevel, ...)` has it).
- Both run on CROWNS' own physics thread: `PhysicThread.tick(ServerLevel)` calls
  `data.initialise(serverLevel)` then `data.updateChangedBlocks(serverLevel, tempSolver)`.
- The `DEFAULT_TEMPERATURE` layer is filled at section init, refreshed per changed block, saved
  with the world, and otherwise never regenerated. The solver pulls every cell toward it
  (`TemperatureSolver`: `sourceVector = res * beta * defaultTemp`).
- Refresh seam: `public void scheduleInitialisation(long sectionPos, DataLayerType... layers)` on
  `PhysicsWorldData` re-runs the initializer for those layers on that section at the next
  `initialise()`; `reinitializeAll()` does it for every loaded section and all layers (heavy).
- No API package, no events. MIT.

## 2. Design

### 2.1 Ambient injection (mixins, all gated by the plugin on `crowns`)

`mixin/crowns/PhysicsWorldDataMixin`:
- `@Inject(method = "initialise", at = @At("HEAD"))` and
  `@Inject(method = "updateChangedBlocks", at = @At("HEAD"))`: store the `ServerLevel` argument in
  `CrownsBridge.CURRENT_LEVEL` (a `ThreadLocal<ServerLevel>`); clear it in matching `RETURN` injects
  (use `@Inject(at = @At("RETURN"))` — both methods may return early, RETURN covers all exits).
  The physics thread is single-threaded per tick, so a ThreadLocal is sufficient and never leaks
  across levels.

`mixin/crowns/PhysicsSaveManagerMixin`:
- `@ModifyExpressionValue(method = "getDefaultTemperature", at = @At(value = "INVOKE",
  target = "Lcom/rae/crowns/util/FloatMapDataLoader;getValue(Ljava/lang/Object;F)F"))` *(verify the
  exact owner/descriptor of `FloatMapDataLoader.getValue` in the 2.2.5 jar; if `getValue` is
  overloaded or generic-erased differently, match by ordinal — it is the FIRST `getValue` call in the
  method; the block/fluid `getValue` calls come after)*:
  ```java
  private static float micc$unifiedBiomeDefault(float original, LevelChunkSection section, Vec3i pos, BlockState state) {
      if (!ClimateConfig.crownsEnabled()) return original;
      ServerLevel level = CrownsBridge.CURRENT_LEVEL.get();
      if (level == null) return original;
      return Climate.kelvin(level, new BlockPos(pos.getX(), pos.getY(), pos.getZ()));
  }
  ```
  `Climate` is chunk-cached, so a full-section init (4096 calls) costs one Thermoo lookup.
  Do NOT touch `DebugRenderer` (client debug only).

### 2.2 Seasonal refresh

`crowns/CrownsBridge` (only class importing `com.rae.crowns.*` besides the mixins):
- Server tick handler (NeoForge `ServerTickEvent.Post`, once every `crowns.refreshIntervalTicks`,
  default 1200 = one in-game hour): for each `ServerLevel` that has CROWNS physics data, obtain its
  `PhysicsWorldData` *(verify how: `PhysicThread`/a per-level registry — find the accessor CROWNS
  itself uses in `ServerLevelMixin` / `PhysicThread`)*, iterate the loaded section keys of the
  `DEFAULT_TEMPERATURE` layer map, and call `scheduleInitialisation(sectionPos, DataLayerType.DEFAULT_TEMPERATURE)`
  for a rotating slice of them (at most `crowns.sectionsPerRefresh`, default 256) so a big world
  refreshes over several intervals instead of stalling the physics thread.
- Thread-safety: `scheduleInitialisation` is called by CROWNS from the server thread
  (`registerChanged` path) *(verify; if it is only ever called on the physics thread, enqueue via the
  same mechanism CROWNS uses, or post to `PhysicThread`)*.
- If the 2.2.5 jar lacks `scheduleInitialisation`, fall back to `reinitializeAll()` on a much
  longer interval (config default 24000, one day) and document the cost; if that is missing too,
  ship only §2.1 and note that the field follows the climate at chunk (re)load.

### 2.3 What is deliberately not done
- Block/fluid tables stay CROWNS' own (lava is hot regardless of season).
- `IHaveTemperature` machines are not touched.
- No reverse link (CROWNS field → Thermoo): the field is derived from ambient, feeding it back would loop.

## 3. Config keys

`crowns.enabled` (true), `crowns.refreshIntervalTicks` (1200, 200–72000), `crowns.sectionsPerRefresh` (256, 1–4096).

## 4. Build

Add nothing to build files (phase 1 already added `compileOnly("maven.modrinth:create-crowns:${crowns_version}")`
and the optional `crowns` mods.toml entry). Add `"crowns.PhysicsWorldDataMixin"`, `"crowns.PhysicsSaveManagerMixin"`
to `mic_climate.mixins.json`; the plugin already gates the `crowns` sub-package on the mod id.

## 5. Acceptance

- javap verification of every target in the 2.2.5 jar recorded in the report (method descriptors, the
  `getValue` owner, `scheduleInitialisation` presence).
- Build passes with `--rerun-tasks`; `MicClimate` has no `com.rae.*` import.
- In-game checks for the report: with CROWNS' debug renderer (or its thermometer/goggle readouts),
  a freshly generated snowy chunk's air cells sit near `Climate.kelvin` for that biome instead of the
  static table; after the refresh interval in a different season, air cells in an untouched chunk
  drift toward the new ambient; a lava pool still reads the block-table value.
