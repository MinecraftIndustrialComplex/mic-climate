#!/usr/bin/env bash
#
# mic-climate's headless GameTest suite.
#
#   ./gametest.sh                  run it
#   ./gametest.sh -PwithAtmosphere run it with Project Atmosphere on the
#                                  classpath and Serene Seasons Plus pinned down
#                                  to 4.2.3 (see build.gradle for why)
#   ./gametest.sh -PwithoutDestroy run it without Destroy (and mic-destroy-electric);
#                                  combine with -PwithAtmosphere for Project Atmosphere alone
#
# Boots a dedicated NeoForge server with the pack's whole temperature graph on
# the classpath, runs every @GameTest in the mic_climate namespace, and exits
# non-zero if anything failed. The full log is left in build/gametest.log.
#
# Two things can fail, and they fail differently:
#
#   * A mixin that no longer matches its target. mic_climate.mixins.json is
#     defaultRequire 1, so this kills the launch before a single test runs. Look
#     for "InjectionError", "MixinTransformerError" or "was not found" in the
#     log. Booting at all is therefore the suite's first assertion.
#   * An assertion. Every one of them logs the numbers it compared on a
#     "[gametest]" line, pass or fail, so the log says what Power Grid and
#     Destroy actually answered and not just whether it was the right answer.
#
# Expected wall time on an 8-core machine: about 90 seconds, nearly all of it
# mod loading; the tests themselves take under a second. Peak RSS of the server
# JVM is around 1.5 GB against the -Xmx6G it is given.

set -uo pipefail
cd "$(dirname "$0")"

LOG=build/gametest.log
mkdir -p build

# Peak RSS of the server JVM, sampled while it runs. /usr/bin/time cannot help:
# it would measure Gradle, which forks the JVM we care about.
peak_rss_file=$(mktemp)
echo 0 > "$peak_rss_file"
(
    while true; do
        rss=$(ps -eo rss=,args= 2>/dev/null \
              | grep -F 'net.neoforged.devlaunch.Main' \
              | grep -v grep \
              | awk '{ if ($1 > max) max = $1 } END { print max + 0 }')
        if [ -n "${rss:-}" ] && [ "$rss" -gt "$(cat "$peak_rss_file")" ]; then
            echo "$rss" > "$peak_rss_file"
        fi
        sleep 2
    done
) &
sampler=$!
trap 'kill "$sampler" 2>/dev/null' EXIT

start=$(date +%s)
# Inside a dev shell already (IN_NIX_SHELL: e.g. a remote runner that provides JDK 21 itself, where
# this flake's Linux-only libraries do not build)? Then run Gradle directly.
dev() { if [ -n "${IN_NIX_SHELL:-}" ]; then "$@"; else nix develop -c "$@"; fi; }
dev ./gradlew runGameTestServer --console=plain "$@" 2>&1 | tee "$LOG"
status=${PIPESTATUS[0]}
elapsed=$(( $(date +%s) - start ))

kill "$sampler" 2>/dev/null
peak_kb=$(cat "$peak_rss_file")

echo
echo "--------------------------------------------------------------------"

# The launcher can die before the test runner starts -- a mixin that will not
# apply, a bridged mod that will not load on a dedicated server -- and that has
# been seen to leave Gradle's exit status at 0. Treat a run that never reported
# a result as a failure regardless of what Gradle said.
if ! grep -q "GAME TESTS COMPLETE" "$LOG"; then
    echo "FAILED: the server never got as far as running the tests."
    grep -nE "Failed to start|MixinTransformerError|InjectionError|InvalidInjectionException|Mod loading has failed|Cannot find class" "$LOG" | head -20
    echo "Full log: $LOG"
    exit 1
fi

grep -E "GAME TESTS COMPLETE|required tests (failed|passed)|GameTestServer\]:    - " "$LOG" | tail -20
echo
echo "Mixin application:"
# Mixin logs "Mixing <mixin> into <target>" at DEBUG, which this run does not
# enable; at INFO the evidence is negative -- the launch reached the tests, and
# none of these appeared. For the positive list, run:
#   nix develop -c ./gradlew runGameTestServer -PgametestDebugLog
# and grep the output for "from mic_climate.mixins.json".
if grep -qE "InjectionError|MixinTransformerError|InvalidInjectionException|Critical injection failure|mic_climate\.mixins\.json.*was not found" "$LOG"; then
    echo "  PROBLEMS:"
    grep -nE "InjectionError|MixinTransformerError|InvalidInjectionException|Critical injection failure|mic_climate\.mixins\.json.*was not found" "$LOG" | head -10
else
    echo "  all mic_climate mixins bound (defaultRequire 1, and the server reached the tests)"
fi
grep -E "Skipping mixin" "$LOG" | head -10
echo
echo "Wall time: ${elapsed}s   Peak server-JVM RSS: $(( peak_kb / 1024 )) MB"
echo "Full log:  $LOG"
exit "$status"
