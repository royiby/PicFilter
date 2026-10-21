#!/usr/bin/env bash
# PicFilter device smoke test.
#
# Three stages. Stage 0 needs no device. Stages 1-2 need a connected phone with
# a real WhatsApp library on it, so this is a developer tool, not CI.
#
#   scripts/smoke.sh            # all stages
#   scripts/smoke.sh static     # stage 0 only
#
# It is read-only with respect to the user's photos: it never promotes, trashes
# or labels anything. The heaviest thing it does is score photos already on the
# phone.
set -uo pipefail

PKG=com.picfilter.spike
APK=app/build/outputs/apk/debug/app-debug.apk
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
STAGE=${1:-all}
FAIL=0

pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL=$((FAIL+1)); }
hdr() { printf '\n== %s ==\n' "$1"; }

# `am start` collapses an intent that filterEquals an already-delivered one, and
# extras are not part of filterEquals -- so two ops in a row are silently a
# no-op without a distinct action each time. This cost an hour of debugging.
op() {
  $ADB shell am start -n $PKG/.MainActivity \
    -a "picfilter.OP.$(date +%s)$RANDOM" --es op "$1" ${2:+--es n "$2"} >/dev/null 2>&1
}

# Wait for a logcat marker, up to $2 seconds.
await() {
  local pattern=$1 secs=${2:-120} i
  for ((i = 0; i < secs; i += 3)); do
    sleep 3
    $ADB logcat -d -s PICFILTER | grep -qE "$pattern" && return 0
  done
  return 1
}

# ---------------------------------------------------------------- stage 0
hdr "stage 0: static checks (no device)"

# A .gitignore entry of `data/` once matched app/.../spike/data/Db.kt and
# silently kept the whole storage layer out of the repo. Nothing failed; the
# code just was not there.
# --no-index matters: once a file is tracked, plain check-ignore stays quiet,
# and the original bug was a source file that was never added in the first place.
IGNORED=$(find app/src -name '*.kt' -print0 | xargs -0 git check-ignore --no-index 2>/dev/null)
if [[ -z $IGNORED ]]; then
  pass "no Kotlin source under app/src is excluded by .gitignore"
else
  fail "gitignore is excluding real sources from the repo:"
  echo "$IGNORED" | sed 's/^/         /'
fi

# Kotlin `when` takes the FIRST matching branch, so a duplicated op name binds
# the earlier handler and the later one is dead. "promote" was bound twice.
#
# The ops moved from MainActivity to DevOps.kt, and this check kept passing
# against the file they left -- a check that greps the wrong file does not fail,
# it goes quiet. So it counts what it found and fails on zero.
OPS_FILE=app/src/main/java/com/picfilter/spike/DevOps.kt
OPS=$(grep -oE '^\s+"[a-z]+"\s+->' $OPS_FILE | grep -oE '"[a-z]+"' | sort)
OPS_N=$(echo "$OPS" | grep -c . || true)
DUPES=$(echo "$OPS" | uniq -d)
if [[ ${OPS_N:-0} -lt 20 ]]; then
  fail "only ${OPS_N:-0} ops found in $OPS_FILE — is this still the right file?"
elif [[ -z $DUPES ]]; then
  pass "op names are unique ($OPS_N ops in $(basename $OPS_FILE))"
else
  fail "duplicate op names (the later handler is dead code): $DUPES"
fi

[[ $STAGE == static ]] && { echo; echo "static checks: $FAIL failure(s)"; exit $((FAIL > 0)); }

# ---------------------------------------------------------------- stage 1
hdr "stage 1: build, install, and prove the device is running THIS build"

if ! $ADB get-state >/dev/null 2>&1; then
  fail "no device; run 'scripts/smoke.sh static' or connect a phone"
  exit 1
fi
pass "device $($ADB shell getprop ro.product.model | tr -d '\r') on Android $($ADB shell getprop ro.build.version.release | tr -d '\r')"

./gradlew :app:assembleDebug -q || { fail "build"; exit 1; }
pass "build"

# A failed build plus `install -r` once redeployed a STALE apk, so a run that
# looked successful was exercising code that no longer existed. Never trust a
# device result without checking which bytes are actually installed.
LOCAL_SHA=$(shasum -a 256 "$APK" | cut -d' ' -f1)
$ADB install -r "$APK" >/dev/null 2>&1 || { fail "install"; exit 1; }
REMOTE_PATH=$($ADB shell pm path $PKG | sed 's/package://' | tr -d '\r')
REMOTE_SHA=$($ADB shell sha256sum "$REMOTE_PATH" 2>/dev/null | cut -d' ' -f1 | tr -d '\r')
if [[ $LOCAL_SHA == "$REMOTE_SHA" ]]; then
  pass "installed apk matches the one just built (${LOCAL_SHA:0:12})"
else
  fail "installed apk is NOT the one just built -- every result below is suspect"
  fail "  local  ${LOCAL_SHA:0:12}"
  fail "  device ${REMOTE_SHA:0:12}"
fi

$ADB shell pm grant $PKG android.permission.READ_MEDIA_IMAGES 2>/dev/null
$ADB logcat -c; op report; sleep 4

# Android 14+ partial grants give a library that looks readable and is not. The
# app must detect this rather than reporting a confidently wrong scan.
ACCESS=$($ADB logcat -d -s PICFILTER | grep -oE 'ACCESS_MODE=[A-Z_]+' | tail -1)
if [[ $ACCESS == "ACCESS_MODE=FULL" ]]; then
  pass "full media access ($ACCESS)"
else
  fail "expected ACCESS_MODE=FULL, got '${ACCESS:-nothing}'"
fi

# ---------------------------------------------------------------- stage 2
hdr "stage 2: the pipeline, end to end"

$ADB logcat -c; op scanmedia
if await "SCAN upserted=" 120; then
  pass "$($ADB logcat -d -s PICFILTER | grep -oE 'SCAN upserted=[0-9]+ total_items=[0-9]+' | tail -1)"
else
  fail "scan produced no SCAN line"
fi

$ADB logcat -c; op score
if await "SCORE done=" 600; then
  SCORED=$($ADB logcat -d -s PICFILTER | grep -oE 'SCORE done=[0-9]+ dead_rows=[0-9]+ failed=[0-9]+' | tail -1)
  pass "$SCORED"
  FAILED=$(echo "$SCORED" | grep -oE 'failed=[0-9]+' | cut -d= -f2)
  [[ ${FAILED:-1} -eq 0 ]] && pass "no scoring failures" || fail "$FAILED items failed to score"
  # A rescore must leave nothing stale behind.
  $ADB logcat -d -s PICFILTER | grep -qE 'SCORE enrolled_people=[1-9]' \
    && pass "scored against enrolled people" \
    || printf '  \033[33mWARN\033[0m nobody enrolled; matching was not exercised\n'
else
  fail "scoring did not finish"
fi

$ADB logcat -c; op watchon; sleep 4
STAT=$($ADB logcat -d -s PICFILTER | grep -oE 'WATCH stat .*' | tail -1)
op watchstat; sleep 4
STAT=$($ADB logcat -d -s PICFILTER | grep -oE 'WATCH stat .*' | tail -1)
if [[ $STAT == *"armed=true"* && $STAT == *"enabled=true"* ]]; then
  pass "watcher armed: $STAT"
else
  fail "watcher not armed: ${STAT:-no stat line}"
fi

# The content trigger, for real: drop an image in the gallery and wait for the
# OS to wake the worker. Measured at ~31s against a 30s settle delay.
TMP=$(mktemp -d)
printf '\xff\xd8\xff\xdb\x00C\x00\x03\x02\x02\x03' > "$TMP/probe.jpg"
$ADB logcat -c
PUSHED=$(date +%s)
$ADB push "$TMP/probe.jpg" /sdcard/Pictures/pf-smoke-probe.jpg >/dev/null 2>&1
if await "WATCH nothing new|WATCH new=" 180; then
  pass "content trigger woke the worker in $(( $(date +%s) - PUSHED ))s"
  $ADB logcat -d -s PICFILTER | grep -qE 'WATCH trigger armed, slot=' \
    && pass "the run armed the next slot: $($ADB logcat -d -s PICFILTER | grep -oE 'slot=[ab]' | tail -1)" \
    || fail "the run did not re-arm -- the trigger chain is now dead"
else
  fail "no wake within 180s of a media change"
fi
$ADB shell rm -f /sdcard/Pictures/pf-smoke-probe.jpg
rm -rf "$TMP"

# Rescued files and promotion rows must agree. 187 rows once pointed at 168
# files, and nothing noticed.
$ADB logcat -c; op promocheck; sleep 6
CHECK=$($ADB logcat -d -s PICFILTER | grep -oE 'PROMOCHECK.*' | tail -1)
if [[ -n $CHECK ]]; then
  [[ $CHECK == *"missing=0"* ]] && pass "$CHECK" || fail "$CHECK"
else
  printf '  \033[33mWARN\033[0m no PROMOCHECK output (nothing promoted yet?)\n'
fi

hdr "result"
if [[ $FAIL -eq 0 ]]; then
  printf '\033[32mall checks passed\033[0m\n'
else
  printf '\033[31m%d check(s) failed\033[0m\n' "$FAIL"
fi
exit $((FAIL > 0))
