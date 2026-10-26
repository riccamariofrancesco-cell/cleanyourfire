#!/usr/bin/env bash
# CleanYourFire dal PC (Linux / macOS): quello che fa l'app "Pulizia Fire TV", via adb sulla rete.
# Serve solo se l'app non riesce a collegarsi da sola al debug ADB della stick.
#
#   tools/cleanyourfire.sh <IP-Fire-TV>                 backup + pulizia (tutte le categorie) + launcher pulito
#   tools/cleanyourfire.sh <IP> --install               come sopra, installando prima dist/CleanYourFire.apk
#   tools/cleanyourfire.sh <IP> --skip alexa --skip apps   salta una o più categorie (telemetry, ads, apps, alexa)
#   tools/cleanyourfire.sh <IP> --no-launcher           solo pulizia, Home di Amazon invariata
#   tools/cleanyourfire.sh <IP> --restore               riattiva tutto e spegne il launcher pulito
#
# Sulla Fire TV: La mia Fire TV > Opzioni sviluppatore > Debug ADB ON; alla prima connessione scegli "Consenti sempre".
# La lista è launcher/app/src/main/assets/debloat-fire-os7.txt, la stessa che usa l'app.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIST="$ROOT/launcher/app/src/main/assets/debloat-fire-os7.txt"
APK="$ROOT/dist/CleanYourFire.apk"
PKG="it.cleanyourfire.home"
SERVICE="$PKG/com.gathin.tvhome.HomeKeyService"
RECORD_DEVICE="/sdcard/Download/cleanyourfire-ripristino.txt"
ADB="${ADB:-adb}"

usage() { sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

[ $# -ge 1 ] || usage 1
case "$1" in -h|--help) usage 0 ;; esac
HOST="$1"; shift
[[ "$HOST" == *:* ]] || HOST="$HOST:5555"
RESTORE=0; INSTALL=0; LAUNCHER=1; SKIP=" "
while [ $# -gt 0 ]; do
  case "$1" in
    --restore) RESTORE=1 ;;
    --install) INSTALL=1 ;;
    --no-launcher) LAUNCHER=0 ;;
    --skip) shift; SKIP="$SKIP${1:?--skip vuole una categoria} " ;;
    -h|--help) usage 0 ;;
    *) echo "Opzione sconosciuta: $1" >&2; usage 1 ;;
  esac
  shift
done

STATE_DIR="$ROOT/backup/${HOST%%:*}"
mkdir -p "$STATE_DIR"
RECORD_LOCAL="$STATE_DIR/cleanyourfire-ripristino.txt"
ALARM_LOCAL="$STATE_DIR/alarm_manager_constants.originale"

sh_tv() { "$ADB" -s "$HOST" shell "$@" | tr -d '\r'; }

"$ADB" connect "$HOST" >/dev/null || true
if ! "$ADB" -s "$HOST" get-state >/dev/null 2>&1; then
  echo "Nessuna risposta da $HOST. Controlla Debug ADB e accetta la richiesta sulla TV, poi riprova." >&2
  exit 1
fi
echo "Connesso a $HOST: $(sh_tv getprop ro.product.model), $(sh_tv getprop ro.build.version.name)"

# The restore record: this run's local copy joined with the one on the stick (the app writes there too).
read_record() {
  { cat "$RECORD_LOCAL" 2>/dev/null || true; sh_tv cat "$RECORD_DEVICE" 2>/dev/null || true; } \
    | grep -E '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$' | awk '!seen[$0]++' || true
}

write_record() {
  sort -u -o "$RECORD_LOCAL" "$RECORD_LOCAL"
  "$ADB" -s "$HOST" push "$RECORD_LOCAL" "$RECORD_DEVICE" >/dev/null
}

launcher_off() {
  local services rest
  services="$(sh_tv settings get secure enabled_accessibility_services)"
  rest="$(printf '%s' "$services" | tr ':' '\n' | grep -v -x -F "$SERVICE" | grep -v -x 'null' | paste -sd: - || true)"
  if [ -z "$rest" ]; then
    sh_tv settings delete secure enabled_accessibility_services >/dev/null
    sh_tv settings put secure accessibility_enabled 0
  else
    sh_tv settings put secure enabled_accessibility_services "'$rest'"
  fi
  if [ -f "$ALARM_LOCAL" ]; then
    if [ -s "$ALARM_LOCAL" ]; then sh_tv settings put global alarm_manager_constants "'$(cat "$ALARM_LOCAL")'"
    else sh_tv settings delete global alarm_manager_constants >/dev/null; fi
    rm -f "$ALARM_LOCAL"
  fi
  echo "Launcher pulito disattivato."
}

if [ "$RESTORE" = 1 ]; then
  n=0
  while read -r pkg; do
    [ -n "$pkg" ] || continue
    out="$(sh_tv pm enable "$pkg")"
    case "$out" in *"new state: enabled"*) n=$((n+1)) ;; *) echo "  errore  $pkg: $out" ;; esac
  done < <(read_record)
  : > "$RECORD_LOCAL"; write_record
  echo "Riattivate $n app."
  launcher_off
  exit 0
fi

# 1. Backup, on the PC.
STAMP="$(date +%Y%m%d-%H%M%S)"
sh_tv pm list packages > "$STATE_DIR/backup-$STAMP-tutte.txt"
sh_tv pm list packages -d > "$STATE_DIR/backup-$STAMP-disattivate.txt"
sh_tv settings list global > "$STATE_DIR/backup-$STAMP-global.txt"
sh_tv settings list secure > "$STATE_DIR/backup-$STAMP-secure.txt"
echo "Backup in $STATE_DIR/backup-$STAMP-*.txt"

# 2. Optional install of the app.
if [ "$INSTALL" = 1 ]; then
  [ -f "$APK" ] || { echo "Manca $APK" >&2; exit 1; }
  "$ADB" -s "$HOST" install -r "$APK"
fi

# 3. Disable the chosen categories.
INSTALLED="$(sed 's/^package://' "$STATE_DIR/backup-$STAMP-tutte.txt")"
DISABLED="$(sed 's/^package://' "$STATE_DIR/backup-$STAMP-disattivate.txt")"
read_record > "$RECORD_LOCAL.new"; mv "$RECORD_LOCAL.new" "$RECORD_LOCAL"
done_n=0; skip_n=0; miss_n=0; fail_n=0; category=""
while IFS= read -r line; do
  if [[ "$line" =~ ^##[[:space:]]*\[([a-z0-9_-]+)\] ]]; then
    category="${BASH_REMATCH[1]}"; echo "— $line"; continue
  fi
  pkg="${line%%#*}"; pkg="$(printf '%s' "$pkg" | tr -d '[:space:]')"
  [ -n "$pkg" ] || continue
  [[ "$SKIP" == *" $category "* ]] && continue
  if ! grep -qxF "$pkg" <<<"$INSTALLED"; then miss_n=$((miss_n+1)); continue; fi
  if grep -qxF "$pkg" <<<"$DISABLED"; then skip_n=$((skip_n+1)); continue; fi
  out="$(sh_tv pm disable-user --user 0 "$pkg")"
  case "$out" in
    *"new state: disabled-user"*) done_n=$((done_n+1)); echo "$pkg" >> "$RECORD_LOCAL"; echo "  off  $pkg" ;;
    *) fail_n=$((fail_n+1)); echo "  errore  $pkg: $out" ;;
  esac
done < "$LIST"
write_record
echo "Fatto: $done_n disattivate, $skip_n già spente, $miss_n non presenti, $fail_n errori o protette."
echo "Registro di ripristino: $RECORD_LOCAL (copia sulla TV: $RECORD_DEVICE)"

# 4. Clean launcher: Home redirect through the app's accessibility service.
if [ "$LAUNCHER" = 1 ]; then
  if ! sh_tv pm list packages "$PKG" | grep -qx "package:$PKG"; then
    echo "App $PKG non installata: installa dist/CleanYourFire.apk (o usa --install) per il launcher pulito."
    exit 0
  fi
  sh_tv pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
  sh_tv pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION
  alarm="$(sh_tv settings get global alarm_manager_constants)"; [ "$alarm" = "null" ] && alarm=""
  [ -f "$ALARM_LOCAL" ] || printf '%s' "$alarm" > "$ALARM_LOCAL"
  alarm="$(printf '%s' "$alarm" | tr ',' '\n' | grep -v '^min_futurity=' | grep -v '^$' | paste -sd, - || true)"
  sh_tv settings put global alarm_manager_constants "'${alarm:+$alarm,}min_futurity=0'"
  services="$(sh_tv settings get secure enabled_accessibility_services)"
  services="$(printf '%s' "$services" | tr ':' '\n' | grep -v -x 'null' | grep -v -x -F "$SERVICE" | grep -v '^$' | paste -sd: - || true)"
  sh_tv settings put secure enabled_accessibility_services "'${services:+$services:}$SERVICE'"
  sh_tv settings put secure accessibility_enabled 1
  sh_tv am start -n "$PKG/com.gathin.tvhome.MainActivity" >/dev/null
  echo "Launcher pulito attivo: il tasto Home porta a CleanYourFire."
fi
echo "Fine. Per chiudere il debug ADB: $ADB -s $HOST shell settings put global adb_enabled 0"
