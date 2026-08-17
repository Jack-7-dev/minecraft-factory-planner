#!/usr/bin/env bash
# Run MFP commands against the *real* Star-Technology pack, headlessly.
#
# The dev environment is GregTech alone, and the pack is mostly not GregTech: its important machines
# come from start_core, its recipe types from KubeJS, and half the faults reported so far involved
# mods that are simply absent from `:forge:runServer` (STATUS 9.11). This runs the same commands
# against a real Forge server carrying the whole pack, so those faults become reproducible.
#
#   tools/packtest.sh 'mfp plan 1 gtceu:steel_ingot' 'mfp alternatives minecraft:cobblestone'
#   tools/packtest.sh --setup            # re-copy mods/config from the CurseForge instance
#   tools/packtest.sh --raw 'mfp index'  # print the whole server log rather than MFP's lines
#
# Note win_args.txt, not unix_args: this is java.exe under Git Bash, and the unix file separates
# the classpath with colons, which Windows reads as a drive letter.
#
# It is a *production* server, not a dev run, and that is the point: dev runs are deobfuscated and
# the pack's jars are SRG-mapped, so loading 183 of them into `runServer` would mean remapping every
# one. Here the jar under test is the same reobfuscated artifact the user plays with.
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER="$(cd "$REPO/.." && pwd)/packtest"
INSTANCE="/c/Users/jackb/curseforge/minecraft/Instances/Star Technology THETA"
JAVA="/c/Program Files/Java/jdk-17/bin/java"
# Read from gradle.properties rather than written here. It was hardcoded, and the version bump to
# 2.1.0 left this pointing at a jar the build no longer produces - so the copy below silently did
# nothing and every run tested the stale artifact still sitting in the server's mods folder, which
# is precisely the failure the comment further down warns about.
MOD_VERSION="$(sed -n 's/^[[:space:]]*mod_version=//p' "$REPO/gradle.properties" | tr -d '[:space:]')"
JAR="mfp-1.20.1-${MOD_VERSION}.jar"
BOOT_TIMEOUT="${PACKTEST_TIMEOUT:-900}"

# Mods that cannot run on a dedicated server. Kept as a list rather than discovered every run: each
# one cost a boot to find, and a boot of this pack is about forty seconds plus the JVM.
CLIENT_ONLY=(
  embeddium oculus oculus-flywheel-compat entityculling fancymenu konkrete melody
  spanorama "Stellar View" classicbar FpsReducer2 betterfpsdist BHMenu CraftPresence
  Controlling ExtremeSoundMuffler chloride observable CrashAssistant DailyDad
  zume clearvoid immersive_optimization Fastload SkyGUIs bingus craftpresence UniLib
  emiextraworkstations
)

raw=0
setup=0
args=()
for arg in "$@"; do
  case "$arg" in
    --raw) raw=1 ;;
    --setup) setup=1 ;;
    *) args+=("$arg") ;;
  esac
done

if [[ ! -d "$SERVER/libraries" ]]; then
  echo "packtest: no server at $SERVER — see docs/STATUS.md 9.15 for the one-time install" >&2
  exit 1
fi

if [[ $setup -eq 1 ]]; then
  echo "packtest: refreshing mods and config from the instance"
  rm -rf "$SERVER/mods" "$SERVER/config" "$SERVER/kubejs" "$SERVER/defaultconfigs"
  cp -r "$INSTANCE/mods" "$INSTANCE/config" "$SERVER/"
  cp -r "$INSTANCE/kubejs" "$INSTANCE/defaultconfigs" "$SERVER/" 2>/dev/null
  for pattern in "${CLIENT_ONLY[@]}"; do
    rm -f "$SERVER/mods/"*"$pattern"*.jar
  done
fi

# Always the jar we just built: testing yesterday's artifact against today's question is how a
# "fixed" bug comes back.
if [[ -f "$REPO/forge/build/libs/$JAR" ]]; then
  cp "$REPO/forge/build/libs/$JAR" "$SERVER/mods/$JAR"
fi

if [[ ${#args[@]} -eq 0 ]]; then
  args=("mfp index")
fi

commands=""
for command in "${args[@]}"; do
  commands+="$command"$'\n'
done
commands+="stop"$'\n'

cd "$SERVER" || exit 1
log="$SERVER/packtest.log"
printf '%s' "$commands" | timeout "$BOOT_TIMEOUT" "$JAVA" @user_jvm_args.txt \
  @libraries/net/minecraftforge/forge/1.20.1-47.4.20/win_args.txt --nogui > "$log" 2>&1
status=$?

if [[ $raw -eq 1 ]]; then
  cat "$log"
  exit $status
fi

# MFP's own output, and only the errors that actually stop the server. A modded pack logs thousands
# of complaints it survives - missing loot tables, dist-cleaner warnings, mixins that did not apply -
# so grepping for ERROR buries the answer among them.
grep -E "MinecraftServer\]:|\[MFP/\]|Failed to start the minecraft server|LoadingFailedException|Missing or unsupported mandatory" "$log" \
  | grep -vE "Preparing|Time elapsed|Saving|ThreadedAnvil|Stopping|Done \(|players online|Starting minecraft server|Loading properties|Default game type|Generating keypair|Environment|Using .* thread|Loaded .* recipes|Loaded .* advancements" \
  | sed -E 's/^\[[0-9:]+\] \[[^]]+\]: ?//'
echo "--- exit $status; full log: $log"
exit $status
