#!/usr/bin/env bash
#
# The same suite, in a real modded client.
#
#   ./gametest-client.sh
#
# gametest.sh proves the server half: four mods agreeing about one number, with
# every mixin bound. Two claims are not server claims -- that a client-side
# Climate read still falls back to Thermoo's 20 degrees (the documented
# limitation, PLAN.md build log 2026-09-04) and that a Power Grid thermometer
# can stand in a world the client is rendering -- and only a client can answer
# them. This runs one, headlessly, via HeadlessMC and headlesshq/mc-runtime-test
# (MIT), which boots the real client into a singleplayer world and runs the
# registered GameTests in it.
#
# Everything lands under build/gametest-client/, except Minecraft's own assets
# and libraries, which go to the usual ~/.minecraft (HMC_MC_DIR below) so a
# second run does not re-download 400 MB.
#
# HeadlessMC's -lwjgl flag rewrites every org.lwjgl class body to return
# defaults, so no GL context, no GLFW, and no Xvfb: the native libraries are
# never dlopen'd at all. That matters here -- prebuilt LWJGL .so files are
# exactly what a non-FHS NixOS cannot load -- and it is why this script does not
# use xvfb-run despite the mc-runtime-test action defaulting to it. If the stub
# turns out to be too thin, the fallback is
#   nix shell nixpkgs#xorg.xvfb -c xvfb-run <the java line below, minus -lwjgl>
#
# HeadlessMC runs offline (hmc.offline=true). No account is used or needed; its
# own README scopes offline accounts to exactly this headless-CI case.
#
# STATUS on this machine (2026-09-05): everything up to the tests works. The
# script installs NeoForge 21.1.249, stages 19 mods, boots the real modded
# client, loads every resource pack, creates a singleplayer world and joins it,
# then quits with exit 0 -- and mic_climate itself reports nothing wrong at any
# point, which is already more than the headless server can prove. What does NOT
# happen is the GameTest stage: mc-runtime-test never logs anything after its
# mod-list line and never runs /test runall, so ClientGameTests never executes
# and -DMcRuntimeGameTestMinExpectedGameTests=1 does not fail the run either.
# Whether the -D properties are reaching the game JVM through HeadlessMC's --jvm
# at all is the open question; that is the next thing to check (mc-runtime-test
# 4.5.1's own CI is the reference).
#
# Two modes:
#   ./gametest-client.sh                      -lwjgl (LWJGL stubbed, no X server)
#   GAMETEST_CLIENT_XVFB=1 ./gametest-client.sh   real GL under Xvfb + Mesa
# The Xvfb mode gets furthest: under -lwjgl the client sits on "Waiting for
# player to load" and gives up, while under Xvfb it actually joins the world.

set -euo pipefail
cd "$(dirname "$0")"

HMC_VERSION=2.10.0
MCRT_VERSION=4.5.1
MC_VERSION=1.21.1

WORK="$PWD/build/gametest-client"
RUN="$WORK/run"
HMC_MC_DIR="${HMC_MC_DIR:-$HOME/.minecraft}"
LOG="$WORK/gametest-client.log"

mkdir -p "$WORK/HeadlessMC" "$RUN/mods" "$HMC_MC_DIR"

# A nixpkgs JVM, not one HeadlessMC downloads for itself: an upstream JDK
# tarball would not run on NixOS without nix-ld or patchelf.
JAVA_BIN=$(nix develop -c sh -c 'command -v java')
echo "Using java: $JAVA_BIN"

cat > "$WORK/HeadlessMC/config.properties" <<EOF
hmc.java.versions=$JAVA_BIN
hmc.gamedir=$RUN
hmc.mcdir=$HMC_MC_DIR
hmc.offline=true
hmc.rethrow.launch.exceptions=true
hmc.exit.on.failed.command=true
hmc.assets.dummy=true
hmc.jline.enabled=false
EOF

LAUNCHER="$WORK/headlessmc-launcher-$HMC_VERSION.jar"
if [ ! -f "$LAUNCHER" ]; then
    echo "Downloading HeadlessMC $HMC_VERSION..."
    curl -fsSL -o "$LAUNCHER" \
        "https://github.com/headlesshq/headlessmc/releases/download/$HMC_VERSION/headlessmc-launcher-$HMC_VERSION.jar"
fi

# The mc-runtime-test mod itself: it is what joins a world and runs /test runall.
if ! ls "$RUN/mods"/mc-runtime-test-*.jar >/dev/null 2>&1; then
    echo "Resolving mc-runtime-test $MCRT_VERSION for $MC_VERSION/neoforge..."
    url=$(curl -fsSL "https://api.github.com/repos/headlesshq/mc-runtime-test/releases/tags/$MCRT_VERSION" \
          | grep -oE '"browser_download_url": *"[^"]*"' \
          | sed 's/.*": *"//; s/"$//' \
          | grep -E "mc-runtime-test-$MC_VERSION-.*-neoforge-release\.jar$" \
          | head -1)
    if [ -z "$url" ]; then
        echo "Could not find an mc-runtime-test asset for $MC_VERSION/neoforge in release $MCRT_VERSION." >&2
        exit 1
    fi
    echo "  $url"
    curl -fsSL -o "$RUN/mods/$(basename "$url")" "$url"
fi

# The mod under test plus everything it bridges, staged by Gradle from the same
# localRuntime configuration the headless server run uses -- so the client sees
# exactly the mod set gametest.sh proved.
echo "Staging mods..."
nix develop -c ./gradlew collectClientMods --console=plain -q
# Mirror, do not merge: a jar left over from a previous mod set is a mod the
# client still loads, and that has already cost one debugging round.
find "$RUN/mods" -name '*.jar' ! -name 'mc-runtime-test-*.jar' -delete
find "$WORK/mods" -name '*.jar' -exec cp -f {} "$RUN/mods/" \;
echo "  $(ls "$RUN"/mods/*.jar | wc -l) mods in $RUN/mods"

cat > "$RUN/options.txt" <<'EOF'
onboardAccessibility:false
pauseOnLostFocus:false
EOF

# NeoForge's early loading window is a real GL window, which is both pointless
# under -lwjgl and the first thing that crashed here: on a run directory with no
# config/fml.toml yet, fml_earlydisplay's initRender read a boolean out of a file
# that did not exist and died on the null. earlyWindowControl = false sends FML
# to its DummyProvider instead and skips the whole thing.
mkdir -p "$RUN/config"
if [ -f "$RUN/config/fml.toml" ]; then
    sed -i 's/^earlyWindowControl = true$/earlyWindowControl = false/' "$RUN/config/fml.toml"
else
    printf 'earlyWindowControl = false\n' > "$RUN/config/fml.toml"
fi

# HeadlessMC reads HeadlessMC/config.properties relative to the working
# directory, so every invocation has to run from $WORK -- without that it never
# sees hmc.offline and refuses to launch with "You can't play the game without
# an account".
hmc() { ( cd "$WORK" && "$JAVA_BIN" -jar "$LAUNCHER" --command "$@" ); }

if [ ! -f "$HMC_MC_DIR/versions/$MC_VERSION/$MC_VERSION.json" ]; then
    echo "Downloading Minecraft $MC_VERSION..."
    hmc download "$MC_VERSION"
fi

if ! ls -d "$HMC_MC_DIR"/versions/*neoforge* >/dev/null 2>&1; then
    echo "Installing NeoForge for $MC_VERSION..."
    hmc neoforge "$MC_VERSION" --java 21
fi

# McRuntimeGameTestCloseAnyScreen is not optional here, whatever its default
# says: Serene Seasons Plus opens a modal "PerformanceWarning" screen over the
# main menu, and mc-runtime-test's normal path only knows how to close the
# create-world screen -- it will sit on that warning until it is killed.
# One --jvm, one quoted string: `help launch` documents --jvm as "Jvm args to
# use" (singular), and that is also the shape the mc-runtime-test action itself
# uses. Repeating the flag was tried and did not help.
#
# The first two are hygiene. -Dneoforge.enabledGameTestNamespaces is what makes
# NeoForge register (and therefore run) this mod's @GameTestHolder classes at
# all; -Dmic_climate.gametest unlocks ClimateConfig.Test. The McRuntime* ones are
# mc-runtime-test's own, and are plain -D<Name>, not dotted mc-runtime-test.*:
#   McRuntimeGameTest                     run /test runall after joining a world
#   McRuntimeGameTestMinExpectedGameTests fail if fewer than this many ran
#   McRuntimeGameTestCloseAnyScreen       close any screen, not just create-world
GAME_JVM="-Djava.awt.headless=true -Xmx4G -Dneoforge.enabledGameTestNamespaces=mic_climate -Dmic_climate.gametest=true -DMcRuntimeGameTest=true -DMcRuntimeGameTestMinExpectedGameTests=1 -DMcRuntimeGameTestCloseAnyScreen=true"

set +e
if [ "${GAMETEST_CLIENT_XVFB:-0}" = 1 ]; then
    # The real-GL path, which is what the mc-runtime-test action itself uses.
    # Needs Mesa on the loader path as well as an X server: the LWJGL natives are
    # prebuilt ELF objects looking for libGL.so.1, which a non-FHS NixOS does not
    # have anywhere the dynamic linker looks by default.
    echo "Launching the client (headless, Xvfb + Mesa)..."
    # nix shell does not build an LD_LIBRARY_PATH, so the GL store paths are
    # resolved explicitly and put there by hand.
    gl_libs=$(nix build --no-link --print-out-paths nixpkgs#libGL nixpkgs#libglvnd 2>/dev/null \
              | sed 's|$|/lib|' | paste -sd: -)
    echo "  LD_LIBRARY_PATH=$gl_libs"
    (
        cd "$WORK" && LD_LIBRARY_PATH="$gl_libs${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
            nix shell nixpkgs#xvfb-run \
            --command xvfb-run -a "$JAVA_BIN" -jar "$LAUNCHER" \
                --command launch '.*neoforge.*' -regex --jvm "$GAME_JVM"
    ) 2>&1 | tee "$LOG"
else
    echo "Launching the client (headless, -lwjgl)..."
    (
        cd "$WORK" && "$JAVA_BIN" -jar "$LAUNCHER" --command launch '.*neoforge.*' -regex -lwjgl \
            --jvm "$GAME_JVM"
    ) 2>&1 | tee "$LOG"
fi
status=${PIPESTATUS[0]}
set -e

echo
echo "--------------------------------------------------------------------"
grep -E "GAME TESTS COMPLETE|required tests (failed|passed)|\[gametest\]" "$LOG" | tail -30 || true
echo
echo "Full log: $LOG"
exit "$status"
