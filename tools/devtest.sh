#!/usr/bin/env bash
# Run MFP commands against the dev server (GregTech only), headlessly and under a time cap.
#
#   tools/devtest.sh 'mfp plan 1 gtceu:steel_ingot' 'mfp explain 1'
#   tools/devtest.sh --raw 'mfp index'          # the whole server log, not just MFP's lines
#   tools/devtest.sh --vanilla 'mfp index'      # -PwithGregTech=false, to check MFP alone
#
# The counterpart of tools/packtest.sh, and it exists for the same two reasons. The boilerplate
# (`printf ... | ./gradlew :forge:runServer`) was being retyped for every check, so the *shape* of
# the check varied run to run and two runs were not reliably diffable. And nothing bounded it: a
# chooser bug that turns a two-second plan into an unbounded one presents as a terminal that never
# comes back, which is the slowest possible way to find out. Five minutes by default; raise it
# deliberately when the wait is expected:
#
#   DEVTEST_TIMEOUT=900 tools/devtest.sh 'mfp plan 1000 fluid:gtceu:lubricant'
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_TIMEOUT="${DEVTEST_TIMEOUT:-300}"

raw=0
gradle_args=()
args=()
for arg in "$@"; do
  case "$arg" in
    --raw) raw=1 ;;
    --vanilla) gradle_args+=("-PwithGregTech=false") ;;
    *) args+=("$arg") ;;
  esac
done

if [[ ${#args[@]} -eq 0 ]]; then
  args=("mfp index")
fi

commands=""
for command in "${args[@]}"; do
  commands+="$command"$'\n'
done
commands+="stop"$'\n'

log="$REPO/build/devtest.log"
mkdir -p "$REPO/build"

# A previous run killed by its cap, or by an interrupted terminal, may still be holding the port.
. "$REPO/tools/freeport.sh"
free_port_25565 || exit 1

cd "$REPO" || exit 1
started=$SECONDS
printf '%s' "$commands" | timeout "$RUN_TIMEOUT" ./gradlew :forge:runServer \
  "${gradle_args[@]+"${gradle_args[@]}"}" > "$log" 2>&1
status=$?
elapsed=$(( SECONDS - started ))

if [[ $status -eq 124 ]]; then
  # Which command was in flight is the whole question, and the log's last lines answer it: the plan
  # that never printed is the one still choosing.
  echo "devtest: TIMED OUT after ${RUN_TIMEOUT}s. Commands given:" >&2
  for command in "${args[@]}"; do echo "devtest:   $command" >&2; done
  echo "devtest: last output before the cap:" >&2
  grep -E 'MinecraftServer\]:|\[MFP/\]' "$log" | tail -15 \
    | sed -E 's/^\[[0-9:]+\] \[[^]]+\]: ?/devtest:   /' >&2
  echo "devtest: raise it with DEVTEST_TIMEOUT=<seconds> only when the wait is expected." >&2
fi

if [[ $raw -eq 1 ]]; then
  cat "$log"
  echo "--- exit $status in ${elapsed}s"
  exit $status
fi

# MFP replies come through the server console unprefixed by any MFP marker, so they are the
# MinecraftServer lines; the rest of a modded boot is noise this is not asking about.
grep -E "MinecraftServer\]:|\[MFP/\]|Failed to start the minecraft server" "$log" \
  | grep -vE "Preparing|Time elapsed|Saving|ThreadedAnvil|Stopping|Done \(|players online|Starting minecraft server|Loading properties|Default game type|Generating keypair|Environment|Using .* thread|Loaded .* recipes|Loaded .* advancements" \
  | sed -E 's/^\[[0-9:]+\] \[[^]]+\]: ?//'
echo "--- exit $status in ${elapsed}s; full log: $log"
exit $status
