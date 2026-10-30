#!/bin/sh
# Double-click starter for macOS: opens the confluence-helper web UI in the browser.
cd "$(dirname "$0")" || exit 1
echo "Confluence-Helper wird gestartet …"
echo "Dieses Fenster offen lassen, solange du die Oberfläche benutzt."
echo
if ! sh ./bin/cfh-ui "$@"; then
  echo
  echo "Confluence-Helper konnte nicht gestartet werden oder wurde mit einem Fehler beendet."
  printf "Taste drücken zum Schliessen "
  # Read a single key if the terminal allows it, otherwise wait for Enter.
  old=$(stty -g 2>/dev/null) && stty -icanon -echo min 1 2>/dev/null
  dd bs=1 count=1 >/dev/null 2>&1 || read -r _
  [ -n "$old" ] && stty "$old" 2>/dev/null
  echo
  exit 1
fi
