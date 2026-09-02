#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# operations/ sits three levels below the repository root:
# <repository>/deploy/team-beta/operations. Keep the rendered systemd paths anchored to the
# checkout instead of accidentally producing <repository>/deploy/deploy/team-beta/...
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../../.." && pwd)
SYSTEMD_DIR=${CREWSCOPE_SYSTEMD_UNIT_DIR:-/etc/systemd/system}
UNIT_NAMES='crewscope-backup-daily.service crewscope-backup-daily.timer crewscope-backup-weekly.service crewscope-backup-weekly.timer crewscope-backup-health.service crewscope-backup-health.timer'

usage() { printf 'Usage: %s install|uninstall\n' "$0"; }
[ "$#" -eq 1 ] || { usage; exit 2; }
[ "$(id -u)" -eq 0 ] || { printf 'systemd schedule management requires root\n' >&2; exit 2; }

case "$1" in
  install)
    for unit in $UNIT_NAMES; do
      # Render the checkout path into the unit so the repository can live outside /opt/crewscope.
      temporary=$(mktemp)
      sed "s#/opt/crewscope#$REPOSITORY_ROOT#g" "$SCRIPT_DIR/../systemd/$unit" >"$temporary"
      install -m 0644 "$temporary" "$SYSTEMD_DIR/$unit"
      rm -f -- "$temporary"
    done
    systemctl daemon-reload
    systemctl enable --now crewscope-backup-daily.timer crewscope-backup-weekly.timer crewscope-backup-health.timer
    ;;
  uninstall)
    systemctl disable --now crewscope-backup-daily.timer crewscope-backup-weekly.timer crewscope-backup-health.timer 2>/dev/null || true
    for unit in $UNIT_NAMES; do rm -f -- "$SYSTEMD_DIR/$unit"; done
    systemctl daemon-reload
    ;;
  *) usage; exit 2 ;;
esac
