# PixKin — operation

The app is driven from its own UI; everything below is the developer surface
that sits behind it. **Nothing here deletes a source photo.** Promotion copies,
review records opinions, and the only destructive operations
(`tidydest`, reclaim) act on copies PixKin made itself and go through
`MediaStore.createTrashRequest` and the user's own confirmation.

## Build & install

```bash
source env.sh
./gradlew :app:assembleDebug
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell pm grant com.picfilter.spike android.permission.READ_MEDIA_IMAGES
adb -s <serial> shell pm grant com.picfilter.spike android.permission.ACCESS_MEDIA_LOCATION
```

**Always check which APK is actually installed.** A failed build plus
`install -r` once redeployed a stale APK, so a run that looked successful was
exercising code that no longer existed. `scripts/smoke.sh` asserts the installed
sha256 matches the one just built.

## Enrolment

The product path is **onboarding**: the app samples ~600 images (WhatsApp
folders first, camera roll to top up), embeds faces under the strict gate,
clusters them with average linkage, and offers the biggest clusters to be named.
Takes ~4.5 minutes. The alternative on the same screen is the **photo picker** —
pick a few varied photos of one person, which takes seconds and costs recall
(91% at three photos against 100% from a full cluster).

Both paths run from the app. `op=onboard` opens the flow;
`op=pickenrol --es name <Name>` drives the picker from adb.

### Folder convention — development shortcut only

```
/sdcard/Pictures/PicFilterEnroll/<PersonName>/...
```

MediaStore reports the folder name as `bucket_display_name`, so this needs no
picker UI and no extra permission. `op=enroll` builds centroids from it. **Only
images containing exactly ONE quality-passing face are used**, which makes the
face→name pairing unambiguous; group shots are rejected and counted as
`rejected_multiface`.

This is how enrolment worked before onboarding existed. It remains useful for
scripted setup, and `op=enroll` **clears all existing enrolment first**.

### How centroids work

One person holds several centroids and matching takes the **max** similarity
across the set, so an added vector can only turn a miss into a match — never the
reverse. That monotonicity is why `extendFromLabels` requeues only the items
that matched nobody rather than the whole library. A cluster of 6+ faces also
contributes a second centroid built from its most distinct third.

## Operations

All driven by intent extras; results go to logcat under tag `PICFILTER`.

**Always cold-start.** A warm `am start` gets routed to the running instance and
the `op` extra can be dropped, silently running the default op instead. Worse,
`am start` collapses an intent that `filterEquals` an already-delivered one, and
extras are **not** part of `filterEquals` — so two ops in a row are silently a
no-op unless each gets a distinct action.

```bash
R() {
  adb -s <serial> shell am force-stop com.picfilter.spike
  sleep 3
  adb -s <serial> logcat -c
  adb -s <serial> shell am start -n com.picfilter.spike/.MainActivity \
    -a "picfilter.OP.$(date +%s)$RANDOM" --es op "$1" "${@:2}"
}
```

The action is a throwaway unique string; the component is explicit, so the
action matches no filter and exists only to defeat intent collapsing. This is
the same trick `scripts/smoke.sh` uses.

### Core pipeline

```bash
R scanmedia                   # MediaStore -> item table (WhatsApp folders)
R score     --es limit 300    # detect, gate, embed, match, text, dhash -> score
R report                      # decisions / reasons / per-person matches / confusion
R pipeline  --es limit 5000   # enroll + scanmedia + score + report in sequence
R reset                       # clear scores+faces (keeps items and enrolment)
```

`score` only touches items that are unscored **or** whose `centroid_rev` is
behind the current one, so enrolling a person automatically queues a rescore.

### Enrolment and clustering

```bash
R onboard                     # open the onboarding flow
R bootstrap --es sample 600   # sample, embed, cluster (the 4.5-minute part)
R recluster --es t 0.60       # regroup stored embeddings at another threshold — instant
                              # (note: the op's own default is still 0.65)
R sweepcl                     # cluster structure vs threshold, avg-link and greedy
R pickenrol --es name <Name>  # enrol from picked photos
R enroll                      # folder-convention enrolment (clears existing!)
R extend                      # widen centroids from vouched near-misses
R avatars                     # rebuild person face crops from the library
R clearcl                     # undo candidate decisions (does not unenroll)
```

### Rescue

```bash
R promote                     # DRY RUN by default — reports what would be copied
R promote   --es live 1       # copy KEEP items to Pictures/PicFilter
R promote   --es live 1 --es bylabel 1   # copy what a human labelled KEEP instead
R promocheck                  # do promotion rows and files on disk agree?
R repair                      # drop records whose file is gone (leaves synced rows)
R reclaim                     # dry run: local copies in the cloud 24h+
R reclaim   --es live 1       # ...and ask to trash them (system dialog)
R tidydest  --es live 1       # trash redundant copies in the destination folder
R unpromote --es id <itemId>  # delete our copy, keep the source
R fixdates                    # stamp EXIF on promoted files that lack a date
R exposure                    # what exists only on this phone
```

Every destructive op is **dry-run unless `--es live 1`**, and `promote` refuses
`DISCARD_CANDIDATE` in `decisions` with a `require`.

### Automation

```bash
R watchon                     # media content trigger + 6h periodic net
R watchoff
R watchnow                    # run one pass immediately
R watchstat                   # what WorkManager actually holds
```

### Measurement

```bash
R sweep                       # gate sensitivity from stored metrics — no re-detection
R calibrate                   # human keep-rate by near-miss similarity band
R precision                   # precision ABOVE the threshold, by band and by person
R fit                         # fit per-person thresholds from labelled faces
                              # (needs both sides: matches from the 'Saved
                              #  photos' queue, near misses from 'More family
                              #  photos')
R memprobe  --es stage 3 --es n 100   # native-heap growth by pipeline stage (stages 1-4)
R item      --es id <itemId>  # full scoring detail for one photo
R labels / clearlabels / unlabel --es id <itemId>
```

### Phase 0 diagnostics

`scan` `trash` `untrash` `verify` `spikewrite` `bench` `classify` `embed`
`incidence` `modelcheck`

> `spikewrite` was called `promote` until it collided with the rescue op.
> Kotlin's `when` takes the first match, so the rescue silently ran the old
> spike instead. `smoke.sh` now asserts op names are unique.

Read results with:

```bash
adb -s <serial> logcat -d -s PICFILTER | sed -E 's/^.* PICFILTER *: //'
```

## Review UI

Six queues, all reached from the home screen. Nothing in any of them deletes,
moves or uploads: they record judgements, which is what thresholds are fitted
against.

| Queue | What it holds | Actions |
|---|---|---|
| Saved photos (`verify`) | everything promoted, **weakest match first** | Not mine / Skip / Correct |
| More family photos (`rescue`) | REVIEW items with faces, by near-miss | Not mine / Skip / Keep |
| Photos with no faces (`noface`) | `NO_FACE_PLAIN` | Not mine / Skip / Keep |
| Suspected junk (`junk`) | unjudged `DISCARD_CANDIDATE` | It's junk / Skip / Keep |
| Suspected duplicates (`dupes`) | near-dupe groups side by side | Same photo / Different |
| Review (`golden`) | unlabelled items, **stratified 40 per reason** | Discard / Skip / Keep |

`Not mine` in the verify queue calls `Promoter.unpromote`: it deletes the copy
PixKin made and drops the record. The source is never touched, and no system
dialog is needed because the app created the destination file itself. **It does
not un-upload** — an item that already synced must also be deleted in Google
Photos by hand.

Read the labels back with:

```bash
adb -s <serial> shell "run-as com.picfilter.spike sqlite3 \
  /data/data/com.picfilter.spike/databases/picfilter.db \
  'SELECT label, COUNT(*) FROM label GROUP BY label;'"
```

Or `op=report`, which prints labels and dupe verdicts alongside the scorer's own
decisions, plus the scorer-vs-human confusion table.

## Decision rules

```
matched >= 1                    -> KEEP              (ENROLLED_PERSON)
indeterminate >= 1              -> REVIEW            (INDETERMINATE_FACE)
faces >= 3, none matched        -> DISCARD_CANDIDATE (CROWD_NO_MATCH)
faces 1-2, none matched         -> REVIEW            (FEW_FACES_NO_MATCH)
0 faces, text-heavy             -> DISCARD_CANDIDATE (TEXT_NO_FACE)
0 faces, plain                  -> REVIEW            (NO_FACE_PLAIN)
```

A single INDETERMINATE face forces REVIEW, by construction rather than by
threshold tuning. Text recognition runs **only** when no face was found, because
`decide()` consults the text signals in exactly that one case — every other
shape settles the verdict before `textHeavy` is reached. `DecisionRulesTest`
pins that invariant, because the optimisation is correct only while it holds.

## Tunables

| Constant | File | Value | Source |
|---|---|---|---|
| `MIN_ORIG_PX` (scoring) | FaceEngine.kt | 40 px | measured, SPIKES.md 3d |
| `MAX_POSE` (scoring) | FaceEngine.kt | 45° | binding constraint; strict on purpose |
| `ENROL_MIN_ORIG_PX` / `ENROL_MAX_POSE` | FaceEngine.kt | 80 px / 30° | precision-biased |
| `MATCH_THRESHOLD` | FaceEngine.kt | 0.70 | fitted; 0.55 gave 47% precision |
| per-person threshold | person.threshold | fitted by `Fitter` | 90% target, ≥6 labelled points |
| `MIN_BELOW_SAMPLES` | Fitter.kt | 5 | labelled points below the default before a fit may go below it |
| `ABSOLUTE_FLOOR` | Fitter.kt | 0.55 | calibration found keep-rate zero in the bands beneath it |
| `CLUSTER_THRESHOLD` | FaceEngine.kt | 0.60 | average linkage; dominates greedy @0.65 |
| `TEXT_FRAC_HEAVY` / `TEXT_BLOCKS_HEAVY` | Pipeline.kt | 0.08 / 10 | spike 3b |
| `DUPE_HAMMING` | Pipeline.kt | 6 | **unvalidated** — dHash convention |
| `MAX_PER_PASS` | Watcher.kt | 300 | ~250 MB peak in `:scoring` |
| `SYNC_GRACE_HOURS` | Promoter.kt | 24 | before a local copy is reclaimable |
| `VOUCH_MIN_SIM` / `MAX_CENTROIDS` | Clusterer.kt | 0.55 / 6 | vouched-extension limits |
| ~~`MIN_SHARPNESS`~~ | — | **deleted** | swept 0→25, moved 2 faces of 1,409 |

`DUPE_HAMMING` is the one remaining unfitted tunable. It needs the dupe-verdict
queue worked through.

## Schema notes

Raw SQLite (`picfilter.db`), version 7, **not** Room — Room needs KSP and AGP
9.4.1's built-in Kotlin makes the KSP version story a build fight. Migrations
are additive only, because the scored corpus costs two minutes of face detection
to rebuild. Tables: `person`, `centroid`, `item`, `face`, `score`, `action_log`,
`label`, `dupe_verdict`, `cluster`, `cluster_face`, `promotion`, `meta`.

`Db(ctx, name)` takes a database name so tests can work on a throwaway file —
an instrumented test runs as this package and would otherwise scribble on the
user's own scored corpus.

The database is opened from two processes (main and `:scoring`). SQLite
coordinates with file locks, but a concurrent UI read can surface as
`SQLITE_BUSY`, in which case WorkManager retries the pass.
