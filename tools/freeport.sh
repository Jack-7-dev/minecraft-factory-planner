#!/usr/bin/env bash
# Free port 25565 if — and only if — an MFP test server is still holding it.
#
# Sourced by tools/packtest.sh and tools/devtest.sh. A run killed by its time cap, or by a terminal
# interrupt, leaves the dedicated server alive: `timeout` kills the JVM it launched, and an
# interrupted shell does not even do that. The next run then dies with "FAILED TO BIND TO PORT",
# several minutes into a boot, with the real cause fifty thousand log lines up.
#
# It refuses to guess. The listener is killed only when its own command line says it is one of these
# two servers; anything else — most importantly the user's game, which is a client launched from the
# CurseForge instance — is reported and left alone, and the run stops rather than fighting it.
free_port_25565() {
  local pid cmd
  pid="$(powershell -NoProfile -Command \
    "(Get-NetTCPConnection -LocalPort 25565 -State Listen -ErrorAction SilentlyContinue).OwningProcess" \
    2>/dev/null | tr -d '\r' | head -1)"
  [[ -z "$pid" ]] && return 0

  cmd="$(powershell -NoProfile -Command \
    "(Get-CimInstance Win32_Process -Filter \"ProcessId=$pid\" -ErrorAction SilentlyContinue).CommandLine" \
    2>/dev/null | tr -d '\r')"

  # The packtest server (a production Forge server started from win_args.txt) or the dev server
  # (gradle's runServer, which runs out of this repo). Nothing else qualifies.
  if [[ "$cmd" == *"win_args.txt"*"--nogui"* || "$cmd" == *"$REPO"* || "$cmd" == *"MFP\\forge"* ]]; then
    echo "packtest/devtest: port 25565 still held by a stale MFP server (pid $pid) - killing it" >&2
    powershell -NoProfile -Command "Stop-Process -Id $pid -Force" >/dev/null 2>&1
    sleep 1
    return 0
  fi

  echo "packtest/devtest: port 25565 is held by pid $pid, which is not an MFP test server:" >&2
  echo "packtest/devtest:   ${cmd:0:160}" >&2
  echo "packtest/devtest: refusing to kill it. Close it, or free the port, and run again." >&2
  return 1
}
