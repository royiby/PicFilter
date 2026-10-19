#!/usr/bin/env bash
# Back up / restore the on-device database.
#
# Run the backup BEFORE ./gradlew connectedDebugAndroidTest. Gradle uninstalls
# the app after an instrumented run, and uninstalling takes /data/data with it
# -- enrolled people, clusters, scores, promotion rows, human labels. That cost
# a hand-tagged enrolment and a 4.5-minute bootstrap once.
#
#   scripts/db-backup.sh save            # -> backups/picfilter-<stamp>.db
#   scripts/db-backup.sh restore <file>
set -euo pipefail

PKG=com.picfilter.spike
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
DIR=backups

case ${1:-save} in
  save)
    mkdir -p $DIR
    OUT=$DIR/picfilter-$(date +%Y%m%d-%H%M%S).db
    # run-as works because this is a debuggable build; the db is not on /sdcard.
    $ADB exec-out run-as $PKG cat databases/picfilter.db > "$OUT" 2>/dev/null || true
    # run-as writes its errors to the same stream, so a non-empty file proves
    # nothing -- a 55-byte "unknown package" message once passed as a backup.
    # Check the actual SQLite magic instead.
    if [[ $(head -c 15 "$OUT" 2>/dev/null) == "SQLite format 3" ]]; then
      echo "saved $OUT ($(wc -c < "$OUT" | tr -d ' ') bytes)"
    else
      echo "NOT a database, discarding: $(head -c 80 "$OUT" 2>/dev/null | tr -d '\r\n')"
      rm -f "$OUT"
      echo "nothing saved (app not installed, or no database yet)"; exit 1
    fi
    ;;
  restore)
    SRC=${2:?usage: scripts/db-backup.sh restore <file>}
    [[ $(head -c 15 "$SRC" 2>/dev/null) == "SQLite format 3" ]] || {
      echo "not a SQLite database: $SRC"; exit 1; }
    $ADB push "$SRC" /data/local/tmp/restore.db >/dev/null
    $ADB shell run-as $PKG sh -c 'cat /data/local/tmp/restore.db > databases/picfilter.db'
    $ADB shell rm -f /data/local/tmp/restore.db
    echo "restored $SRC"
    ;;
  *) echo "usage: scripts/db-backup.sh [save|restore <file>]"; exit 1 ;;
esac
