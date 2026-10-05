#!/usr/bin/env bash
# Hemisphere seasons on a real Deep Time planet, whatever its projection (Petroff-Guyou, the default
# from Deep Time's world-data format 0.5, or Lambert): a MIC planet server (Deep Time's smoke set
# `mic`) with this checkout's jar in place of the pack's, probing a grid of places with
# `/mic_climate probe` in northern midwinter and midsummer. Each probe line says the block's latitude
# (Deep Time's own, by its projection), the strength of the season there, the hemisphere's
# sub-season, and the planet's circumference and projection kind. The grid straddles the equator
# (z = 0 on both projections), takes five longitudes (on Petroff-Guyou latitude depends on x too) and
# stays 300+ blocks inside the north and south edges.
#
# Runs on the Deep Time test VM from this checkout's remote copy, driven from Deep Time's checkout:
#
#   cd <deep-time> && tools/remote/mac.sh --pack -- true                      # sync Deep Time + the pack
#   cd <deep-time> && tools/remote/mic-climate.sh --src <this checkout> --server \
#       --pull review-out -- tools/planet-seasons-probe.sh [<deep-time dir on the VM>]
#
# Needs build/libs/mic_climate-*.jar (./gradlew build) in this checkout. Results are copied to
# review-out/: rcon.jsonl and report.md from Deep Time's smoke run, mixins.txt (the mod's log lines).
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
DT="${1:-/Volumes/Graphide/ban/mic/mods/deep-time}"
PRESET="${PRESET:-earthlike_quick_16k}"
START="${START:-scattered_continents}"
MICJAR="$(find "$HERE/build/libs" -maxdepth 1 -name 'mic_climate-*.jar' ! -name '*-sources.jar' | sort | tail -1)"
[ -f "$MICJAR" ] || { echo "planet-seasons-probe: no mic-climate jar in $HERE/build/libs" >&2; exit 1; }
OUT="$HERE/review-out"
mkdir -p "$OUT"

# z rows north to south (north is -z; the equator is z = 0) and x columns across the 13312-block world.
ZS=(-3000 -1500 -500 0 500 1500 3000)
XS=(-6000 -3000 0 3000 6000)

cd "$DT"
tools/smoke/install.sh mic --preset "$PRESET" --start "$START" --length-myr 500
mods=smoke/mic/server/mods
rm -f "$mods"/mic_climate-*.jar
cp "$MICJAR" "$mods/$(basename "$MICJAR")"

rc=(); chunky=()
for z in "${ZS[@]}"; do
    for x in "${XS[@]}"; do
        chunky+=(--chunky-at "$x,$z,16")
        rc+=(--rcon "forceload add $x $z")
    done
done
rc+=(--rcon "sleep 10" --rcon "gamerule doDaylightCycle false" --rcon "time set 6000" --rcon "mic_climate seasons")
probe_all() {
    for z in "${ZS[@]}"; do
        for x in "${XS[@]}"; do
            rc+=(--rcon "execute positioned $x 0 $z positioned over motion_blocking_no_leaves run mic_climate probe ~ ~ ~")
        done
    done
}
rc+=(--rcon "season set mid_winter")
probe_all
rc+=(--rcon "season set mid_summer")
probe_all

python3 tools/smoke/run.py --profile mic --fresh "${chunky[@]}" "${rc[@]}" --timeout 3600 || true
cp smoke/mic/rcon.jsonl smoke/mic/report.md "$OUT/" 2>/dev/null || true
grep -hE "Hemisphere seasons|hemisphere seasons|Deep Time|climate API" smoke/mic/logs/server.log > "$OUT/mixins.txt" 2>/dev/null || true
ls -la "$OUT"
