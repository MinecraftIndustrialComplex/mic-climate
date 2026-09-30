package com.minecraftindustrialcomplex.mic_climate.command;

import net.Gabou.projectatmosphere.modules.atmosphere.AtmosphericUpdateScheduler;
import net.Gabou.projectatmosphere.modules.atmosphere.SeasonalAtmosphericDrift;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code /mic_climate atmosphere drive <ticks>}: runs Project Atmosphere's regional simulation on
 * the overworld for that many server ticks although no player is online. Headless test servers only:
 * the command exists only when the JVM was started with {@code -Dmic_climate.driveAtmosphere=true}.
 *
 * <p>Project Atmosphere simulates its regions only while players are in the overworld, so on a
 * dedicated server with nobody logged in its regions keep their creation values and there is no
 * weather to see. This calls the same two public entry points its own level tick calls when a
 * player is there ({@code AtmosphericUpdateScheduler.tick}, whose passive pass updates every region,
 * and {@code SeasonalAtmosphericDrift.tick}). Nothing is mixed into Project Atmosphere.
 */
final class AtmosphereDriver {

    private static final AtomicInteger REMAINING = new AtomicInteger();
    private static volatile ServerLevel level;
    private static boolean registered;

    private AtmosphereDriver() {}

    static synchronized void drive(ServerLevel overworld, int ticks) {
        level = overworld;
        REMAINING.set(ticks);
        if (!registered) {
            NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> tick());
            registered = true;
        }
    }

    static int remaining() {
        return REMAINING.get();
    }

    private static void tick() {
        ServerLevel l = level;
        if (l == null || REMAINING.get() <= 0)
            return;
        REMAINING.decrementAndGet();
        if (!l.players().isEmpty())
            return; // Project Atmosphere is simulating by itself
        SeasonalAtmosphericDrift.tick(l);
        AtmosphericUpdateScheduler.tick(l);
    }
}
