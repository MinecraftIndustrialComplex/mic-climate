package com.minecraftindustrialcomplex.mic_climate.command;

import com.minecraftindustrialcomplex.mic_climate.Climate;
import com.minecraftindustrialcomplex.mic_climate.Compat;
import com.minecraftindustrialcomplex.mic_climate.MicClimate;
import com.minecraftindustrialcomplex.mic_climate.config.ClimateConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /mic_climate} — what every temperature-aware mod in the pack thinks
 * the temperature is, side by side, from a console.
 *
 * <p>This mod's whole claim is that a dozen mods now answer one number. The
 * gametest suite proves that in a runtime built for the purpose; this proves it
 * on the pack, in a running world, in one line per mod — which is also the only
 * way to check it over RCON on a dedicated server, where there is no HUD, no
 * goggles and no F3 screen.
 *
 * <pre>
 * /mic_climate probe [&lt;pos&gt;]      every source's answer at a position
 * /mic_climate invalidate         drop the per-chunk cache, so the next read is real
 * /mic_climate pollution &lt;0..1&gt;   set Destroy's greenhouse to a fraction of its maximum
 * /mic_climate mode [modifier|atmosphere|config]   read or set pollution.mode for this session
 * </pre>
 *
 * <p>Permission level 2 throughout: these read another mod's internals and two
 * of them change the world.
 *
 * <p><b>The optional-mod rule.</b> Same as everywhere else in this mod — this
 * class imports nothing from a bridged mod. Each line of the probe is produced
 * by a small class of its own ({@link PowerGridProbe}, {@link DestroyProbe},
 * {@link LsoProbe}, {@link CrownsProbe}, {@link AtmosphereProbe}), reached only
 * behind {@link Compat#isLoaded(String)}, so a pack missing any of them prints
 * fewer lines rather than failing to load a class.
 */
public final class ClimateCommands {

    /** Where a probe with no arguments and no caller position looks. */
    private static final BlockPos DEFAULT_POS = new BlockPos(0, 64, 0);

    private ClimateCommands() {}

    public static void register(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(MicClimate.MODID)
                .requires(source -> source.hasPermission(2));

        root.then(Commands.literal("probe")
                .executes(ctx -> probe(ctx, defaultPos(ctx.getSource())))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> probe(ctx, BlockPosArgument.getLoadedBlockPos(ctx, "pos")))));

        root.then(Commands.literal("invalidate").executes(ClimateCommands::invalidate));

        root.then(Commands.literal("pollution")
                .then(Commands.argument("fraction", FloatArgumentType.floatArg(0f, 1f))
                        .executes(ctx -> pollution(ctx, FloatArgumentType.getFloat(ctx, "fraction")))));

        LiteralArgumentBuilder<CommandSourceStack> mode = Commands.literal("mode")
                .executes(ClimateCommands::reportMode);
        for (ClimateConfig.PollutionMode value : ClimateConfig.PollutionMode.values()) {
            mode.then(Commands.literal(value.name().toLowerCase(Locale.ROOT))
                    .executes(ctx -> setMode(ctx, value)));
        }
        // "config" is the way back: the override is a session thing and this
        // hands the setting to mic_climate-common.toml again without a restart.
        mode.then(Commands.literal("config").executes(ctx -> setMode(ctx, null)));
        root.then(mode);

        dispatcher.register(root);
    }

    // ------------------------------------------------------------------
    // probe
    // ------------------------------------------------------------------

    /**
     * One line per source that is actually installed.
     *
     * <p>Absent mods print nothing rather than "n/a": the point of the output is
     * that the numbers on it agree, and a reader should not have to filter the
     * list first. Which mods answered is on the header line instead.
     */
    private static int probe(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();

        float climate = Climate.celsius(level, pos);
        float thermoo = Climate.uncachedCelsius(level, pos);
        int age = Climate.cacheAge(level, pos);

        List<String> lines = new ArrayList<>();
        lines.add(String.format(
                Locale.ROOT,
                "mic_climate probe at %d %d %d  (%s, biome %s)",
                pos.getX(), pos.getY(), pos.getZ(),
                level.dimension().location(), biomeId(level, pos)));

        lines.add(line("thermoo", celsius(thermoo), "(EnvironmentLookup -> TEMPERATURE)"));
        lines.add(line("climate", celsius(climate),
                age < 0 ? "(Climate.celsius) cache miss" : "(Climate.celsius) cache age " + age + "t"));

        if (Compat.isLoaded(Compat.POWERGRID))
            lines.add(line("powergrid", celsius(PowerGridProbe.ambientCelsius(level, pos)),
                    "(ThermalBehaviour.getAmbientTemperature)"));

        if (Compat.isLoaded(Compat.DESTROY))
            lines.add(DestroyProbe.probeLine(level, pos));

        if (Compat.isLoaded(Compat.LEGENDARY_SURVIVAL_OVERHAUL))
            lines.add(LsoProbe.probeLine(level, pos));

        if (Compat.isLoaded(Compat.CROWNS))
            lines.add(CrownsProbe.probeLine(level, pos));

        if (Compat.isLoaded(Compat.PROJECT_ATMOSPHERE))
            lines.add(AtmosphereProbe.probeLine(level, pos));

        lines.add(String.format(
                Locale.ROOT,
                "config     : source=%s pollution.mode=%s%s multiplier=%.2f cacheTicks=%d",
                ClimateConfig.source(),
                ClimateConfig.pollutionMode(),
                ClimateConfig.pollutionModeOverride() == null ? "" : " (session override)",
                ClimateConfig.pollutionMultiplier(),
                ClimateConfig.cacheTicks()));

        for (String text : lines)
            source.sendSuccess(() -> Component.literal(text), false);

        return 1;
    }

    /**
     * The shared shape of every probe line, so that one regular expression
     * reads all of them: a name, a colon, a number, a unit, then prose.
     */
    static String line(String name, String value, String detail) {
        return String.format(Locale.ROOT, "%-10s : %-14s %s", name, value, detail);
    }

    /** {@code 18.40 C}. Celsius is spelled without the degree sign on purpose:
     *  this output is read back over RCON, and one non-ASCII byte in it is one
     *  more thing that can arrive mangled. */
    static String celsius(float value) {
        return String.format(Locale.ROOT, "%.2f C", value);
    }

    /** {@code 291.55 K}. */
    static String kelvin(float value) {
        return String.format(Locale.ROOT, "%.2f K", value);
    }

    // ------------------------------------------------------------------
    // invalidate / pollution / mode
    // ------------------------------------------------------------------

    private static int invalidate(CommandContext<CommandSourceStack> ctx) {
        // Every level, not just this one: the caches downstream (Power Grid's
        // per-device sample, Destroy's per-level shift) are not ours to clear,
        // and clearing one level's would give a half-fresh picture.
        Climate.invalidate(null);
        ctx.getSource().sendSuccess(
                () -> Component.literal("mic_climate: dropped the cached ambient for every level"), true);
        return 1;
    }

    /**
     * Destroy's greenhouse dial, as a fraction of its own maximum.
     *
     * <p>Exists so a script can move the pack's temperature without knowing
     * Destroy's command syntax or its pollution scale: {@code pollution 1} is a
     * maximally polluted sky, {@code pollution 0} a clean one, and what those
     * are worth in kelvin is Destroy's business.
     */
    private static int pollution(CommandContext<CommandSourceStack> ctx, float fraction) {
        CommandSourceStack source = ctx.getSource();
        if (!Compat.isLoaded(Compat.DESTROY)) {
            source.sendFailure(Component.literal("mic_climate: Destroy is not installed"));
            return 0;
        }

        int set = DestroyProbe.setGreenhouseFraction(source.getLevel(), fraction);
        source.sendSuccess(() -> Component.literal(String.format(
                Locale.ROOT,
                "mic_climate: greenhouse pollution set to %d (%.0f%% of maximum)",
                set, fraction * 100f)), true);
        return 1;
    }

    private static int reportMode(CommandContext<CommandSourceStack> ctx) {
        ClimateConfig.PollutionMode override = ClimateConfig.pollutionModeOverride();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                Locale.ROOT,
                "mic_climate: pollution.mode = %s (%s)",
                ClimateConfig.pollutionMode(),
                override == null ? "from mic_climate-common.toml" : "session override")), false);
        return 1;
    }

    private static int setMode(CommandContext<CommandSourceStack> ctx, ClimateConfig.PollutionMode mode) {
        ClimateConfig.pollutionModeOverride(mode);
        // The mode decides whether the provider adds Destroy's shift, and every
        // chunk is holding a value from before the switch.
        Climate.invalidate(null);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                Locale.ROOT,
                "mic_climate: pollution.mode = %s (%s)",
                ClimateConfig.pollutionMode(),
                mode == null ? "session override cleared" : "session override")), true);
        return 1;
    }

    // ------------------------------------------------------------------

    /**
     * Where an argument-less probe looks: the caller's own block, or
     * {@code 0 64 0} for a console that is only nominally somewhere.
     */
    private static BlockPos defaultPos(CommandSourceStack source) {
        return source.getEntity() == null ? DEFAULT_POS : BlockPos.containing(source.getPosition());
    }

    private static String biomeId(ServerLevel level, BlockPos pos) {
        Holder<Biome> biome = level.getBiome(pos);
        return biome.unwrapKey().map(ResourceKey::location).map(ResourceLocation::toString).orElse("?");
    }
}
