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

# The install id. It changed from com.picfilter.spike to com.pixkin.app before
# first release, so a database saved from the old build restores into the new
# one with: PKG=com.picfilter.spike scripts/db-backup.sh save
#            scripts/db-backup.sh restore <that file>
PKG=${PKG:-com.pixkin.app}
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
DIR=backups

case ${1:-save} in
  save)
    mkdir -p $DIR
    OUT=$DIR/picfilter-$(date +%Y%m%d-%H%M%S).db
    # run-as works because this is a debuggable build; the db is not on /sdcard.
    # A -wal sidecar would mean recent transactions are NOT in the main file and
    # this copy would be silently stale. This database runs in rollback-journal
    # mode, so there is none -- but check rather than assume.
    # grep -c exits 1 on zero matches, which set -e would treat as failure.
    WAL=$($ADB exec-out run-as $PKG sh -c 'ls databases/ 2>/dev/null' | tr -d '\r' | grep -c 'picfilter.db-wal' || true)
    if [[ ${WAL:-0} -gt 0 ]]; then
      echo "picfilter.db-wal exists: the main file alone would be an incomplete"
      echo "backup. Checkpoint it or copy the sidecars too before trusting this."
      exit 1
    fi
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
    # Anything still holding the old database will flush its own pages over the
    # file after the copy, so the app stops before the file moves under it.
    $ADB shell am force-stop $PKG
    $ADB push "$SRC" /data/local/tmp/restore.db >/dev/null
    # A freshly reinstalled app has no databases/ directory -- it is created
    # when the app first opens the database. Restoring after an instrumented
    # run is EXACTLY that case, and without this the copy fails with "No such
    # file or directory" having already reported the push as succeeding.
    $ADB shell "run-as $PKG mkdir -p /data/data/$PKG/databases"
    # The whole thing has to be ONE argument to adb shell, with the redirect
    # inside the quotes, so that the inner run-as'd sh performs it. Written any
    # other way the outer shell does the redirect as the shell user and gets
    # EACCES on /data/data; and run-as's sh does not inherit the app data dir as
    # cwd, so the path has to be absolute too.
    $ADB shell "run-as $PKG sh -c 'cat /data/local/tmp/restore.db > /data/data/$PKG/databases/picfilter.db'"
    # Stale sidecars beside a replaced database get applied over it on the next
    # open, undoing part of what was just restored. The -journal is the rollback
    # journal; -wal and -shm are the write-ahead log, which SQLite will happily
    # replay on top of a file it was never written for.
    $ADB shell "run-as $PKG sh -c 'rm -f /data/data/$PKG/databases/picfilter.db-journal /data/data/$PKG/databases/picfilter.db-wal /data/data/$PKG/databases/picfilter.db-shm'"
    $ADB shell rm -f /data/local/tmp/restore.db
    echo "restored $SRC"
    ;;
  *) echo "usage: scripts/db-backup.sh [save|restore <file>]"; exit 1 ;;
esac
