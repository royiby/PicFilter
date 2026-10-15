# PicFilter — Phase 1 usage

Phase 1 is the engine + enrollment + scorer. **No actions are taken** — nothing is
trashed, moved, or uploaded. It only observes and scores.

## Build & install

```bash
source env.sh
./gradlew :app:assembleDebug
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell pm grant com.picfilter.spike android.permission.READ_MEDIA_IMAGES
adb -s <serial> shell pm grant com.picfilter.spike android.permission.ACCESS_MEDIA_LOCATION
```

## Enrollment — folder convention

Put 5–10 clear photos of each person in their own folder:

```
/sdcard/Pictures/PicFilterEnroll/Ram/...
/sdcard/Pictures/PicFilterEnroll/Noa/...
```

MediaStore reports the folder name as `bucket_display_name`, so this needs no
picker UI and no extra permission. Push them with:

```bash
adb -s <serial> push ./ram_photos/. "/sdcard/Pictures/PicFilterEnroll/Ram/"
```

**Only images containing exactly ONE quality-passing face are used.** That makes
the face→name pairing unambiguous without needing bounding-box labels. Group
shots are rejected (and counted as `rejected_multiface`), so pick solo photos.

Enrollment stores one centroid per person per run and matching takes the **max**
similarity across a person's centroid set — so you can enroll the same person
again later (as kids' faces change) without discarding the earlier vector.

## Operations

All driven by intent extras; results go to logcat under tag `PICFILTER`.

**Always cold-start.** A warm `am start` gets routed to the running instance and
the `op` extra can be dropped, silently running the default op instead:

```bash
R() {
  adb -s <serial> shell am force-stop com.picfilter.spike
  sleep 3
  adb -s <serial> logcat -c
  adb -s <serial> shell am start -n com.picfilter.spike/.MainActivity --es op "$1" "${@:2}"
}
```

```bash
R enroll                      # build centroids from Pictures/PicFilterEnroll/*
R scanmedia --es limit 5000   # MediaStore -> item table (WhatsApp folders)
R score     --es limit 5000   # detect, gate, embed, match, text, dhash -> score table
R report                      # aggregate decisions / reasons / per-person matches
R reset                       # clear scores+faces (keeps items and enrollment)
R pipeline  --es limit 5000   # enroll + scanmedia + score + report in sequence
```

Read results with:
```bash
adb -s <serial> logcat -d -s PICFILTER | sed -E 's/^.* PICFILTER *: //'
```

### Phase 0 diagnostics (still available)
`scan` `trash` `untrash` `verify` `promote` `bench` `classify` `embed` `incidence` `modelcheck`

## Review UI (Phase 2)

Open the **PicFilter Spike** app on the phone. Two buttons:

- **Review items (golden set)** — one image at a time with its decision, reason
  and face counts. `DISCARD` / `SKIP` / `KEEP`, plus `Undo last`. Queue is
  stratified (40 per reason) so every decision class gets covered rather than
  whichever class happens to dominate.
- **Verify duplicate groups** — proposed near-dupes shown together, `SAME` /
  `DIFFERENT`. Only 8 groups at hamming 6, so this is a couple of minutes, and
  it is what settles the hamming threshold.

Nothing here deletes, moves or uploads anything. It records judgements only.
Read them back with:

```bash
adb -s <serial> shell "run-as com.picfilter.spike sqlite3 \
  /data/data/com.picfilter.spike/databases/picfilter.db \
  'SELECT label, COUNT(*) FROM label GROUP BY label;'"
```

Or use `op=report`, which prints label and dupe-verdict counts alongside the
scorer's own decisions, plus the scorer-vs-human confusion table once labels
exist.

## Decision rules

```
matched >= 1                    -> KEEP              (protected, never proposed)
indeterminate >= 1              -> REVIEW            (blocks auto-discard)
faces >= 3, none matched        -> DISCARD_CANDIDATE (CROWD_NO_MATCH)
faces 1-2, none matched         -> REVIEW
0 faces, text-heavy             -> DISCARD_CANDIDATE (TEXT_NO_FACE)
0 faces, plain                  -> REVIEW
```

A single INDETERMINATE face forces REVIEW. That is deliberate: it closes the
"your kid was in the class photo but too small to recognise" failure mode by
construction rather than by threshold tuning. Measured cost: only ~40% of crowd
images are affected at the 40 px gate (SPIKES.md spike 3d).

## Tunables

| Constant | File | Value | Source |
|---|---|---|---|
| `MIN_ORIG_PX` | FaceEngine.kt | 40 | measured, SPIKES.md 3d |
| ~~`MIN_SHARPNESS`~~ | — | **deleted** | swept 0→25, moved 2 faces of 1409 |
| `MAX_YAW` / `MAX_ROLL` | FaceEngine.kt | 45° | **unvalidated** |
| `MATCH_THRESHOLD` | FaceEngine.kt | 0.55 | **unvalidated — needs Takeout labels** |
| `TEXT_FRAC_HEAVY` | Pipeline.kt | 0.08 | from spike 3b |
| `DUPE_HAMMING` | Pipeline.kt | 6 | conventional dHash default |

Four of these are guesses. They are exactly what the golden set and the Takeout
labels exist to settle — do not trust `DISCARD_CANDIDATE` until they are measured.

## Schema notes

Raw SQLite (`picfilter.db`), not Room — Room needs KSP and AGP 9.4.1's built-in
Kotlin makes the KSP version story a build fight. Tables match the planned Room
entities (`person`, `centroid`, `item`, `face`, `score`, `action_log`), so a swap
later is mechanical.
