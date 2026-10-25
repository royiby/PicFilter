# Testing PicFilter

The app deletes nothing by itself, but it does propose deletions from someone's
family photos, and it copies files around on their behalf. The tests exist to
protect those two things. The test specification is largely the bug history:
every check below that cites a bug is guarding a mistake this code actually
made.

## Running it

```bash
./gradlew :app:testDebugUnitTest         # 67 JVM tests, ~5s, no device
scripts/db-backup.sh save                # cheap insurance; see the note below
./gradlew :app:connectedDebugAndroidTest # 56 instrumented tests, needs a phone
scripts/smoke.sh static                  # repo checks, no device
scripts/smoke.sh                         # full device walk-through
```

> **Gradle used to uninstall the app after an instrumented run, and
> uninstalling wipes `/data/data`** — enrolled people, clusters, scores,
> promotion rows, human labels, face crops, and the WorkManager database that
> holds whether auto-rescue is on. It destroyed a hand-tagged enrolment of five
> people and a 4.5-minute bootstrap, and the only reason it was noticed was an
> unrelated command failing with "Activity class does not exist".
>
> `gradle.properties` now sets
> `android.injected.androidTest.leaveApksInstalledAfterRun=true`, which stops
> it. The run still reinstalls the APK, so the code under test is current, but
> the app data stays. Measured over two consecutive runs: the database came
> back byte-identical by sha256, the 20 face crops survived, and auto-rescue
> was still enabled and armed — where before, every run cost a database
> restore plus about forty seconds of face detection to rebuild the avatars.
>
> **Back up anyway.** `scripts/db-backup.sh save` costs a second, the flag is
> one line somebody could remove, and the data is the user's own manual work.
> It is still the first line of the recipe above for that reason.

The tests never touch the real database: every one of them opens a throwaway
by name, which is what `Db(ctx, name)` exists for, and deletes it again in
`tearDown`. That is also what makes leaving the app installed safe — a run
leaves nothing behind for the next one to trip over, verified by listing
`databases/` afterwards and by running the suite twice in a row.

Only the first and third can run in CI. The instrumented tests need a device or
emulator; `smoke.sh` needs a phone with a real WhatsApp library, so it is a
developer tool.

## Tier 1 — JVM unit tests (`app/src/test`)

Pure logic, no device. These run in seconds and should stay that way.

| File | Guards |
|---|---|
| `DecisionRulesTest` | **Auto-discard is never proposed on an uncertain read.** Exhaustive over the shapes a scored image can take: if the verdict is `DISCARD_CANDIDATE`, there was no match and no unjudged face. This is the safety property of the whole app. |
| `MatchFaceTest` | Three-state matching. A face below the quality gate is `INDETERMINATE`, never a stranger. Per-person thresholds override the default and do not leak between people. The nearest person survives a miss, because the review screen shows it. Pins `MATCH_THRESHOLD` at the measured 0.70 — at 0.55 precision was 47%. |
| `ClusterAverageTest` | Average linkage does not chain. A bridging face within single-linkage reach of two tight groups must not merge them; greedy linkage did, and produced a 205-face garbage cluster. Also pins `CLUSTER_THRESHOLD`. |
| `VecMathTest` | `l2` and `cos` — including `l2` on a zero vector, which must not divide by zero. |
| `EmptyCentroidsTest` *(in `MatchFaceTest.kt`)* | The 290-mislabelled-photo bug, in two halves: an empty centroid set really does read as a clean miss rather than as uncertainty, and a clean miss on a crowd really does become a discard candidate. Neither is wrong alone; together they are why `Pipeline.score` refuses to run when people are enrolled and no centroids load. |
| `DestNameTest` | Promotion is safe to re-run. The promotion table is what PixKin *believes* it copied and the folder is what is *there*; clearing app data drifts them apart, and MediaStore writes "name (1).jpg" rather than overwriting. Pins that the folder is the authority, and that same-name-different-size is a real collision rather than a skip. |
| `FitThresholdTest` | Where a person's threshold may land. The defect: fitting from matched faces alone sees only points above the bar, so it could rise and never fall — three fitted thresholds (0.55, 0.63, 0.65) became unreachable that way. Pins that a bar comes down only on enough confirmed evidence below it, never under the 0.55 floor, that strangers below the bar keep it where it is, and that sub-threshold evidence alone holds the bar instead of triggering the 0.85 fail-safe. |
| `GridSpansTest` | How many columns the photo grid gets for a window width. The floor of 3 is the point: the count was the constant 3 because that is right in portrait, and landscape inherited it. A later tweak to the target tile width must not cost a phone in portrait a column, so the floor is pinned across the common widths rather than at one of them. |
| `ShortCountTest` | Abbreviating a counter: round rather than truncate (148,912 is 149K, not 148K), and never render something wider than the cell — 999,999 became "1000K", five characters where four fit, so the boundary moved to 999,500. |
| `WhatsAppFolderTest` | Which folders onboarding samples. WhatsApp has used several folder shapes over the years and the bucket names differ again; getting this wrong is quiet — the scan still runs, it just learns the wrong faces. |

`matchFace` was lifted out of `FaceEngine` to make this possible: it is pure
arithmetic that was trapped in a class needing a TFLite interpreter.

## The grid in landscape, and what a screenshot found that a test could not

`GRID_COLS` was the constant 3. It had always been right, because it had only
ever been looked at on a phone held upright. Turned sideways, the same three
columns divided a 780dp window into 770px tiles: **one row, cropped by the
bottom of the grid, with the rest of the width showing nothing.** It affected
the person screen and the review grids alike, since both took their column
count from that constant.

Nothing failed. No test covered it, lint has no opinion about it, and the
screen is perfectly usable in the orientation anyone would normally hold it in.
It took looking at the thing on a phone.

The count now comes from the width:

```kotlin
internal fun spansFor(widthDp: Float): Int =
    (widthDp / GRID_TILE_TARGET_DP).toInt().coerceIn(3, 8)
```

120dp is chosen so that **every phone in portrait keeps the three columns it
already had** — the narrowest width in common use is 360dp, and 360/120 is
exactly 3. That floor is the part a later tweak to the target is most likely to
break, so `GridSpansTest` pins it across the common portrait widths rather than
testing one.

Measured on the phone this was found on, a 1080x2340 at density 480 — 360dp
portrait, 780dp landscape:

| | before | after |
|---|---|---|
| portrait | 3 columns | 3 columns, unchanged |
| landscape | 3 columns, 1 cropped row | 6 columns, 2 rows |

`GridGaps` takes the span count as a constructor argument now. It has to be the
same number the `GridLayoutManager` was given, or the columns drift out of line
with their gutters — which is why both are derived from one `spans` value per
Activity rather than computed twice.

### A caption from a state that had already gone

The review screen's grid came up captioned **`DISCARD=490 SKIP=33 KEEP=13`**
under 113 photos. That string is `vm.summary()`, the tally meant for the empty
state when a queue is finished.

The empty-state branch ends:

```kotlin
info.text = ""
lifecycleScope.launch { info.text = vm.summary() }
```

A fire-and-forget write into a view, with a database read between the launch
and the assignment. On a cold open the queue is empty for a moment, so that
branch runs and starts the read; the queue then loads, `render()` takes the
grid path and sets `info.text` to "Tap one to check it."; and the read finally
returns and puts the tally on top of it. The screen had moved on and the
coroutine did not know.

Rotating made it disappear, which is the tell: after a rotation the ViewModel
still holds the queue, so the first `render()` already takes the grid path, the
empty branch never runs and nothing is launched to arrive late. **A bug that
only shows on a cold open is invisible to anyone testing by rotating.**

The write is now guarded by the condition that asked for it:

```kotlin
val tally = vm.summary()
if (idx >= queue.size && !vm.grid.value) info.text = tally
```

Verified both ways on the phone: a cold open of the saved queue reads "Tap one
to check it.", and the duplicates queue — genuinely empty — still shows
`DIFFERENT=3 SAME=5` under "Nothing here to look at.".

**This is not covered by a test.** It needs the review screen driven with a
fixture queue, which is the one thing the rules at the bottom of this file
forbid doing against the real library, so it is a screenshot and a reasoned
guard rather than a regression test. The same shape — `lifecycleScope.launch`
writing into a view without re-checking what it is writing into — is worth
grepping for whenever a screen gains an async read.

### The tally now says it in words

`DIFFERENT=3 SAME=5` was the database's vocabulary appearing at the one moment
the screen is telling someone they are finished. It reads as a sentence now:

> So far: 5 marked as copies, 3 marked as different
> עד כה: 5 סומנו ככפילויות, 3 סומנו כשונות

Three things that are deliberate rather than incidental:

**The order is fixed, not the query's.** `labelCounts()` returns counts
descending, so the tally would have opened on whichever number happened to be
largest — in practice always the discards, which is a dispiriting way to tell
someone they are done. KEEP, DISCARD, SKIP is now a fixed order and reads the
same every time.

**A key that is not in the list is dropped, not printed.** The old code
`joinToString { "${it.first}=${it.second}" }` would render whatever the
database held, which is exactly how an internal name reached the screen. A
label added later now goes missing from the tally rather than leaking its
enum name — a visible omission is a better failure than a visible identifier.

**Zero counts are omitted**, so a queue nobody has touched says nothing rather
than "0 kept, 0 not saved, 0 skipped".

Plurals in all five languages, including Hebrew's `two`. Verified on the phone
in English and in Hebrew, where the line is right-aligned and the title bar's
chevron mirrors.

**The label-mode tally was not seen on a device**, only the duplicates one. It
appears when a label-backed queue is empty, and none of them is: verify holds
113, rescue 14, no-face 4, junk 2. Making one empty means labelling real photos
of the user's family, which the rules at the bottom of this file forbid. It is
the same function, the same formatting and the same plurals mechanism as the
branch that was checked — but it is reasoned, not seen, and that difference is
worth keeping visible.

### Inspecting review without judging anything

The rule is never to automate the review screen, and it stands. This pass
worked within it:

- **`verify` mode opens in grid by default** (`_grid = state[S_GRID] ?: (mode ==
  "verify")`), and the grid branch of `render()` sets `actionBar` and `undoBtn`
  to `GONE`. So the saved queue can be opened and photographed with no
  judgement control on screen at all.
- **The counts were the guard.** Labels, promotions, `action_log` and
  `dupe_verdict` were hashed before and after. All four came back identical:
  `label` at `6bc29d2567172130`, `dupe_verdict` at `e91d6b4c8607303b`. The
  check is cheap and it is what makes a visual pass over this screen defensible
  rather than merely careful.

### What else the screenshot pass confirmed

Every figure on the home screen was checked against the database rather than
read and believed: 117 saved, 492 not saved, 113 of 117 still to check, 14
unsure, 4 no-face, and all five people's counts and their order. The review
queues hold no item in common. `PersonActivity` is genuinely unexported — an
attempt to launch it with `am start` is refused with a SecurityException, which
is the gate rather than an obstacle.

**Rotation was exercised on a device for the first time here.** Rotating the
person screen logged no database read at all: the Activity was recreated, the
ViewModel was not, and the grid came back with its list intact. That is the
first item under *What is not covered yet* finally getting a hand-verification
to go with its argument.

## Tier 2 — instrumented tests (`app/src/androidTest`)

Real SQLite, real WorkManager, on a device.

| File | Guards |
|---|---|
| `ScoreStalenessTest` | Enrolling a person, refitting a threshold, or wiping enrolment invalidates the existing scores. Without this, 638 photos stayed scored against nobody: `KEEP` was 0 and auto-rescue would have woken on every new photo and rescued nothing. Also: rescoring must not accumulate `face` rows. |
| `DbUpgradeTest` | The v5→v6 migration, on a database shaped like the one on the phone — scores first, people enrolled afterwards. That database *is* the bug, so the upgrade must mark its scores stale. And the migration must keep the scored corpus: rebuilding 638 scores costs two minutes of face detection. |
| `SyncedReclaimTest` | Reclaiming a local copy must not invite the app to copy it straight back. The folder and the promotion table together decide what still needs rescuing, so a file deliberately removed — because it is in the cloud and the space was reclaimed — has to stay recorded and stay out of the promotion queue. |
| `VouchedExtensionTest` | Attribution for `extendFromLabels`, clause by clause: a keep explained by an existing match teaches nothing, two near misses in one photo are ambiguous, a distant face is not a near miss, and a discard teaches nothing. A keep is a judgement about a *photo*, not about a face — getting this wrong means a centroid quietly drifted onto someone else. |
| `LabelledNearMissTest` | The query supplying the below-the-bar half of a threshold, clause by clause — and the boundary with `vouchedNearMisses`: the same discarded near miss must give the fitter a negative point and teach a centroid nothing. |
| `ScoreStalenessTest` *(also)* | Rewriting a threshold it already holds invalidates nothing. The bump restales every score — 641 of them, three passes and two minutes of face detection — so a fitter agreeing with itself must be free, or it becomes the most expensive thing it does. |
| `PersonFaceTest` | A row is not a file. Crops live in the app's own storage while the database holds their paths, so a wipe leaves rows pointing at nothing — and an empty thumb list is what marks someone as needing repair, so it has to mean "no file", not "no row". Got this wrong twice: 160 candidate cards with no face on them, and the people row stuck on initials while the repair believed there was nothing to fix. |
| `RemovePersonTest` | Taking someone out of the enrolled set, by blast radius: everyone else untouched, photos already rescued left alone, their cluster returned to the unclaimed pool so a mistake is undone by naming rather than by re-running the scan, and only the items that matched *them* requeued — removing a person can turn a MATCH into a NO_MATCH and nothing else. |
| `WatcherArmingTest` | Re-arming the content trigger never cancels a trigger already waiting. Arming used `REPLACE`, and since every app open re-arms to heal a broken chain, routine use kept resetting the trigger — and cancelled a run in progress during testing. Also: a cancelled slot is re-armable, so a force-stop is recoverable. And `healSchedule`, case by case: a recorded intent with nothing scheduled re-arms (the restore), an intent of off is never overridden, no recorded intent changes nothing, and healing an intact schedule does not replace the waiting trigger. |
| `AutoSaveIntentTest` | Where the auto-save intent is stored, which is the point of it: in `picfilter.db`, so it travels with a backup; null on a fresh database rather than false, so the heal cannot read "never asked" as "turned off"; and surviving a close and reopen, which is the only way it reaches a restored install. |

**Every DB test passes an explicit database name.** Instrumented tests run as
the app's own package, so a test using the default name would scribble on the
user's real scored corpus. `Db(ctx, name)` exists for exactly this.

## Tier 3 — device smoke test (`scripts/smoke.sh`)

Stage 0 needs no device and is the part worth wiring into CI:

- **No Kotlin source under `app/src` is excluded by `.gitignore`.** A `data/`
  entry once matched `app/.../spike/data/Db.kt` and silently kept the entire
  storage layer out of the repo. Nothing failed; the code just was not there.
  Uses `git check-ignore --no-index`, because once a file is tracked a plain
  `check-ignore` stays quiet and the original bug was a file never added at all.
- **Op names in `MainActivity` are unique.** Kotlin `when` takes the first
  matching branch, so a duplicated name binds the earlier handler and the later
  one is dead code. `promote` was bound twice.

Both checks were verified by reintroducing the original bug and confirming they
fail.

Stages 1–2 walk the device: build, install, **and assert the installed APK's
sha256 matches the one just built.** A failed build plus `install -r` once
redeployed a stale APK, so a run that looked successful was exercising code
that no longer existed. Never trust a device result without that check. Then:
full media access (not an Android 14+ partial grant), scan, score with zero
failures, watcher armed, a real content-trigger wake from a pushed image, and
`promotion` rows matching files on disk — 187 rows once pointed at 168 files
and nothing noticed.

`am start` collapses an intent that `filterEquals` an already-delivered one,
and extras are **not** part of `filterEquals`, so two ops in a row are silently
a no-op. `smoke.sh` gives every op a distinct action. This cost an hour.

## Performance and battery baselines

Measured on SM-S948B (8 cores), Android 17, battery at 79% and not charging, so
the charge counter reflects real drain. Each phase was preceded by
`dumpsys batterystats --reset`; power is the per-uid figure Android attributes.
Full capacity on this device is ~5040 mAh.

| Workload | Wall time | Energy | Notes |
|---|---|---|---|
| Idle, trigger armed, 5 min | — | 0.011 mAh | Charge counter did not move. Upper bound: an activity was still resident. |
| Content-trigger wake, nothing new | 30–31 s latency | 0.009 mAh | Three consecutive wakes: 31 s, 30 s, 30 s, against a 30 s settle delay. Slots alternated b → a → b as designed. |
| Score 100 photos cold | 14.5 s (6.9 img/s) | 4.18 mAh | 0.042 mAh per photo. Temperature moved 29.8 → 30.0 °C. |
| Score 200 photos | 28.3 s (7.1 img/s) | — | Throughput is flat with batch size. |
| Score all 638 cold | 115.9 s (5.5 img/s) | — | The whole library, fresh process. 118.5 s before the text gating. |

Extrapolating: 50 new WhatsApp photos a day costs about **2 mAh of scoring plus
well under 1 mAh of wakes — roughly 0.05% of this battery.** A full 638-photo
backlog is about 27 mAh and 90 seconds. Battery is not a concern; the earlier
2-hour polling interval was never the cost, and the content trigger is cheaper
than the polling it replaced because an empty wake costs one query.

Throughput under contention is lower: the same work measured 5.2 img/s while the
onboarding bootstrap was running.

### Native heap: localised to ML Kit, partly mitigated

The real resource problem is memory, not power. Peak PSS during scoring reached
595 MB, of which 429 MB was native heap, and the floor did not come back down.

Four scoring passes in one process, native-heap *allocated* (not PSS), measured
with `op=memprobe` and `dumpsys meminfo`:

| | at rest | +100 | +100 | +100 | +100 |
|---|---|---|---|---|---|
| original | 17 MB | 167 | 266 | 389 | **496** |

Linear, about +110 MB per 100 images, same pid throughout, with no plateau over
four passes. This is why the process was killed twice during a session of
repeated scoring and onboarding runs, one of which took a scoring pass with it.

**The first hypothesis was wrong, and the measurement is what caught it.**
`embed()` allocated a fresh 294 KB direct `ByteBuffer` per face — native memory
freed only when the GC gets round to the buffer's `Cleaner`, and ~1400 faces a
scan is ~410 MB, which matched the observed growth almost exactly. Reusing the
buffer changed nothing: 18 → 152 → 267 → 366 → 478 MB. A plausible cause that
matches the arithmetic is still a guess.

So `Pipeline.memProbe` (`op=memprobe`, `--es stage N`) bisects by stage, running
the same images through progressively more of the pipeline. Stage 2 proves the
point: it runs detection but constructs the engine with an impossible
`minOrigPx`, so every face fails the quality gate and `embed` is never called.

| stage | what runs | per 100 images |
|---|---|---|
| 1 | decode + recycle | +3.5 MB |
| 2 | + ML Kit face detection | +87.9 MB |
| 3 | + embedding (TFLite) | +88.7 MB |
| 4 | + ML Kit text recognition | +132.7 MB |

Decode is clean. **Face detection accounts for ~84 MB per 100 images and text
recognition for ~44 MB. Embedding accounts for under 1 MB** — two orders of
magnitude off the hypothesis. Closing the detector reclaims only ~18 MB of
~106 MB, so recreating it periodically is not a remedy either.

**What was changed, and how little it bought.** `decide()` consults the text
signals in exactly one case, an image with no faces: a match, an unjudged face,
a crowd, or one or two strangers all settle the verdict before `textHeavy` is
reached. Scoring ran text recognition on every image regardless and discarded
the result for the ~63% of this library that contains a face. It now runs only
when the detector found nothing. `DecisionRulesTest` pins the invariant that
makes this safe — exhaustively, for every image shape with at least one face,
the verdict is identical with and without text — because the optimisation is
correct only for as long as that holds.

Measured end to end, all 638 images, fresh process, same images both times:

| | original | text gated |
|---|---|---|
| floor before | 17,096 kb | 18,268 kb |
| floor after | 167,572 kb | 161,732 kb |
| growth | **+147 MB** | **+140 MB** |
| wall | 118,540 ms | 115,894 ms |
| rate | 5.4 img/s | 5.5 img/s |

**−4.7% heap and −2% wall time.** The stage table above predicts far more, and
the gap is the lesson: those per-100-image rates were measured in a process
whose heap was already hundreds of MB, and they do not extrapolate to the first
pass in a fresh process, where the GC reclaims most transient allocation. A rate
measured at one heap level is not a rate.

So the text gating is correct, removes a whole ML Kit pass from most images, and
is worth keeping — and it is **not** a fix for this problem. Face detection is
the dominant term and is untouched.

It is at least provably behaviour-preserving on the real library, not only in
the unit test: a full rescore gives `TEXT_NO_FACE = 130` and
`NO_FACE_PLAIN = 105`, identical to the report from before the change.

**Face detection still leaks, and is not fixable from here.** ~84 MB per 100
images, inside ML Kit, with `PERFORMANCE_MODE_ACCURATE`, `LANDMARK_MODE_ALL` and
`setMinFaceSize(0.02f)`. Every lever there trades against small-face recall,
which was deliberately tuned, so none is a free change.

**So it is contained instead.** `ScoringService` runs the pass in a `:scoring`
process and ends that process when the work drains, which hands the leak back to
the kernel. Three passes from a clean start, native heap *allocated* per PID:

| pass | images | `:scoring` peak | released | main process |
|---|---|---|---|---|
| 1 | 253 | 236 MB | yes | 39.6 → 51.9 MB |
| 2 | 100 | 189 MB | yes | 61.4 → 69.9 MB |
| 3 | 100 | 187 MB | yes | 79.7 → 88.6 MB |

453 images scored. `:scoring` absorbed 187–236 MB each time and gave all of it
back each time. The main process grew ~9 MB per pass **flat with respect to
image count** — 100 images costs the same as 0 — so what remains is not the
scoring leak but the home screen decoding thumbnails on every relaunch, which
the harness triggers twice a pass. Against ~110 MB per 100 images accumulating
in the user's own process before, that is the problem solved rather than
reduced.

Details that cost a measurement each:

- `bindService`, not `startService`: a background app may not start a service on
  Android 8+, but it may bind to one, and the binding keeps `:scoring` alive for
  exactly as long as the worker waits on it.
- ML Kit bootstraps from a ContentProvider in its own manifest, and providers are
  instantiated **only in the default process**. Without an explicit
  `MlKit.initialize()` every pass in `:scoring` died with "MlKitContext has not
  been initialized".
- The process is killed in `onDestroy`, after the client has been answered and
  has unbound — killing right after the reply races the reply itself.
- One `:scoring` serves queued passes and exits when the queue drains, rather
  than respawning per pass.
- `dumpsys meminfo <package>` is ambiguous once a package has two processes and
  will report whichever it likes; measure per PID.
- A stale `:scoring` from an earlier run silently serves the next request on the
  same handler thread, conflating two passes into one measurement. `pkill` on the
  harness does not kill the Android service.
- Worker retries are now bounded: a pass that is broken rather than unlucky
  otherwise respawns `:scoring` and re-reads the whole library indefinitely.

### What StrictMode still reports, and why it is left alone

A clean launch logs 8 violations and seven come from Samsung's framework —
`IdsController.openIdsWindow` calling `deleteSharedPreferences` inside
`handleResumeActivity`. The eighth is Coil touching its disk cache on the main
thread when a view attaches and the request restarts. All are around a
millisecond.

**One of the eight does name our code**, and the frame is misleading. On the
first launch after a restore the Coil violation carries
`HomeViewModel.refresh` at `_home.value = data` (3 ms, `DiskReadViolation`).
The database read there is already inside `withContext(Dispatchers.IO)`; what
runs on the main thread is the collector that the assignment resumes
synchronously, which binds the avatar requests, and Coil's `enqueue` looks in
its own disk cache before handing off. The I/O is Coil's, not ours, and moving
our read would not remove it — but the frame is ours, so it is counted here
rather than filed under the framework. Left alone at 3 ms, once, on first
render.

Count what is ours rather than what mentions us:

```bash
adb logcat -d | grep 'D StrictMode:' | grep -c 'com.picfilter.spike'
```

That grep is deliberate. A looser one (`grep -A 30 'policy violation'`) sweeps
in neighbouring log lines that merely contain the package name and reported six
violations that did not exist. The same mistake with `AndroidRuntime` reported
six crashes that were the `uiautomator` command starting up. **A count is only
as good as what it is counting.**

`picfilter.db` is now opened from two processes. SQLite coordinates with file
locks so that is safe, but a concurrent UI read can still surface as
SQLITE_BUSY, in which case WorkManager retries the pass.

**A pass scores at most `MAX_PER_PASS` = 300 images** and schedules the next one
if a backlog remains. Containment makes a pass disposable, not cheap: the peak
still scales with the batch (~170 MB fixed plus ~0.26 MB per image, so 100
images peaked at 189 MB and 253 at 236 MB). The code used to ask for 5000 in one
go, which extrapolates to ~1.5 GB — killed, retried, then abandoned, leaving a
large first-install library permanently unscored. The continuation is delayed,
because one `:scoring` serves whatever is already queued and only exits when the
queue drains.

### The library version is not a way out

Both version-level levers were measured and neither helps:

- `com.google.mlkit:face-detection` is already at its latest, **16.1.7**. There
  is nothing to upgrade to.
- The unbundled `com.google.android.gms:play-services-mlkit-face-detection:17.1.0`
  loads its model *from* Play Services but still runs inference in our process,
  so the allocations land in our heap regardless: **+94.9 MB** per 100 images
  against the bundled **+87.9 MB**, i.e. no better and possibly slightly worse.
  Not worth a Play Services runtime dependency and a first-run model download.

### Scoring against nobody: 290 photos mislabelled

One window scored 290 images with **every face detected normally and every
single one recorded NO_MATCH**, because the centroid set came back empty while
five people were enrolled. 17 of the user's keepers silently became REVIEW or
DISCARD_CANDIDATE — family photos moved toward the junk pile. Proven by diffing
two snapshots: on the affected items, faces 44 → 44 and indeterminate 15 → 15,
but matched **18 → 0**.

The root cause is not established and did not reproduce: scoring the same 100
images in `:scoring` and in the main process gives byte-identical verdicts — 0
decision changes, same 258 faces, same 9 matches, same 97 indeterminates — so
process isolation is not responsible.

The defect that *is* established, and is independent of the cause: the scorer
could not tell "nobody matched" from "nobody to match against", and wrote 290
confident wrong answers. `Pipeline.score` now refuses to run when people are
enrolled and no centroids load, and `EmptyCentroidsTest` pins the two halves of
why that matters — an empty set reads as a clean miss, and a clean miss on a
crowd is a discard candidate. Neither is wrong alone; together they are the bug.

Scoring with nobody enrolled at all stays legal: that is a new install, where
"we do not know yet" is the honest answer.

**Still in the main process:** the onboarding bootstrap (`Clusterer.bootstrap`,
600 images) and the adb dev ops. The bootstrap is the other heavy ML Kit user
and would need the same treatment plus cross-process progress reporting.

## Measured and rejected: growing centroids from confident matches

Enrolling from a few picked photos costs recall — 91% at three photos against
100% from a full cluster. The obvious repair is to let the library teach the
centroid: every confident match is a free, already-verified extra view of the
person, so add the least-similar matches above 0.85 as a new centroid. Matching
takes the max over a person's set, so this is monotonic and needs no full
rescan — only the items that matched nobody.

It was built, run, and **it recovers nothing.**

On the device: one person out of five gained a centroid, 357 of 641 items were
correctly requeued rather than the whole corpus, `centroid_rev` never moved —
the machinery behaved exactly as designed — and the rescore produced **zero**
new matches. KEEP stayed at 48 and every per-person count was identical.
Simulated offline against the stored embeddings for the case it was meant to
fix, a two-or-three-photo enrolment: **91% → 91%.**

The reason is structural rather than a tuning problem. Growth learns from faces
that already match, which are by definition **inside** the region the centroid
already covers. The faces being missed sit below the threshold, **outside** it,
and growth never sees them. It can interpolate within the covered region; it
cannot extend beyond it. Lowering the bar to reach them is exactly the drift
that bar exists to prevent — and it would be learning from faces we cannot tell
apart from strangers.

What does work, though less than first claimed, is the signal growth cannot
reach: **a human saying "this one is them" about a face that scored below the
threshold.** That hands over a point outside the covered region, vouched for by
someone who can actually tell. Built as `Clusterer.extendFromLabels`.

**Run on the real library, it recovered exactly the photos that were vouched for
and nothing else.** Two keeps in the review queue, two photos of that person
moved from REVIEW to KEEP — and both were the vouched items themselves. The
widened centroid generalised to no other photo. Nothing stopped matching, so
monotonicity held.

The first simulation said 91% → 98%, and that figure was wrong: it counted the
vouched faces as recoveries, which is circular — those are known by assumption.
Measuring only the faces that were *not* vouched for, generalisation is **1 of
2**. Real, but a single data point.

Why the live run generalised not at all: that person was enrolled from a full
18-face cluster, so the centroid set already covered them and the missed faces
were isolated outliers rather than representatives of an uncovered region. The
case this is built for — someone enrolled from three picked photos, with a
population of similar faces outside the centroid — has not been tested on real
data, because nobody on this library is enrolled that way.

Worth being plain about the implication: when extension only recovers the
vouched photo itself, it buys nothing over honouring the label directly, which
`itemsForPromotion`/`promote byLabel` can already do. It is kept because it is
cheap (monotonic, requeues 357 of 641, runs only when labels exist) and safe
(attribution is pinned by seven tests), not because the live evidence is strong.

Attribution is the whole difficulty, because a keep is a judgement about a photo
and not about a face. The rule: the item was kept, nothing in it matched anyone
(otherwise the keep is explained by that match), and exactly one face came close
to exactly one person. `VouchedExtensionTest` pins each clause — a keep
explained by a match teaches nothing, two near misses in one photo are
ambiguous, a distant face is not a near miss, and a discard teaches nothing —
because the cost of getting this wrong is a centroid that has quietly drifted
onto someone else.

### What the review itself said

267 verdicts on the "more family photos" queue came back **264 DISCARD, 3
KEEP**. So that queue is about 1% useful: the app is surfacing faces it is
unsure about, and the user is almost always confirming it was right to doubt
them. Those 264 negatives cannot feed `Fitter`, which fits per-person thresholds
from labelled MATCHES, and only 2 labelled matches exist — far below its minimum
of 6. The thresholds therefore remain the default 0.70 rather than fitted.

## Main-thread work, and the tripwire for it

The home screen opened the database and ran eight queries inside `onCreate` and
again on every `onResume`; the review screen loaded its whole queue the same
way and then decoded a 1400px bitmap on the UI thread for every card. None of
it ever looked broken — the database is a megabyte and the phone has eight
cores — which is exactly why it survived so long.

`PixKinApp` now arms `StrictMode` in debug builds: disk reads, disk writes,
network and slow calls on the main thread, plus leaked closables and leaked
SQLite objects. Logged, not fatal — a death penalty would take the app down on
a violation inside a library we do not control, and the point is to be told.

**The tripwire is the regression test.** There is no automated assertion that
the main thread stays clean, so a violation shows up as a `StrictMode` line in
logcat during any debug run. Check for one after touching a screen:

```bash
adb logcat -d | grep -i StrictMode
```

Database access from a screen goes through `DbQueue`: one thread per screen,
reads and writes both, with the handle's `close()` queued behind whatever has
already been asked for. That ordering is the point — a verdict and the undo
that follows it have to land in the order they were tapped, and closing the
handle while a read is still running throws.

Work that must outlive the screen that started it — un-promoting a photo — goes
to `Background.io` instead, because `lifecycleScope` would cancel it and half an
un-promotion leaves a file in the backed-up folder with no record of it.

## The review grids at scale

Both photo grids used to build one `FrameLayout` and one `ImageView` per item
inside a `ScrollView`, recycling nothing, and every decoded thumbnail stayed
attached to its view. At the 109 items in the verify queue that is invisible.
Extrapolated to five thousand it is roughly 11,700 views in a single layout
pass and about 1.25 GB of retained bitmaps, in an app that already fights ML
Kit for native heap. The queue queries made it reachable too: `limit = -1` is
SQL `LIMIT -1`, which is unbounded.

They are `RecyclerView` + `GridLayoutManager` now, with Coil loading the
thumbnails — a bounded memory cache, cancellation when a tile is recycled, and
parallel decode.

**Measured on the device rather than argued**, on the 109-item verify grid:

| | attached tiles | showing |
|---|---|---|
| at the top | 15 | items 1–15 |
| after scrolling nine rows | **15** | items 61–75 |

Fifteen is what fits on the screen, and it does not move. That is the whole
claim: the cost of a grid is now its viewport, not its length. Native heap sat
at 32 MB and Java heap at 20 MB while scrolling.

What is still unmeasured: an actual five-thousand-item queue. The numbers above
prove the mechanism is right, not that nothing else breaks at that size — the
queries themselves are still unbounded, and `Paging 3` over them is the next
step if a real library gets there.

## Lint, and what is deliberately left

The first run reported 108 findings. It reports 9 now, none of them errors:
eight `PluralsCandidate` on diagnostic lines ("faces=3  matched=1") and
screen-reader strings that read a position aloud — a plural form of a column
name is not a thing — and one `GradleDependency` for LiteRT 2.3.0, declined
for the reason below.

`app/lint.xml` carries every other exemption with its reason, so the report
stays worth reading. `abortOnError` is on: a lint error now fails the build.

One of those findings was a real bug rather than a style note. `minSdk` was
30 while every path in the app gates on `READ_MEDIA_IMAGES`, which does not
exist before 33 — on Android 11 and 12 the constant names a permission the
platform does not have, the request can never be granted, and the app would
install and then read nothing at all. `minSdk` is 33. Four `InlinedApi`
warnings had been saying so from the start.

## Upgrading the inference runtime

`org.tensorflow:tensorflow-lite` stops at 2.17.0 and pins `litert 1.0.1`,
whose native libraries are not aligned for the 16 KB memory pages Android 15
introduced. Lint flagged three of them, and on a 16 KB device an unaligned
library does not load at all. The app is on `com.google.ai.edge.litert:litert`
directly now — the same `org.tensorflow.lite.Interpreter` API, no code change.

**Swapping the thing that produces embeddings invalidates every threshold in
the app if the numbers move**, so the test is equality, not "it still runs":

1. Back up, and record the fingerprint —
   `SELECT COUNT(*), SUM(similarity) FROM face WHERE similarity IS NOT NULL`.
2. Note the per-face similarities of the photos `op=unscore` will target.
3. Upgrade, then `op=unscore --es n 5` and `op=score --es limit 5`.
4. Compare. The similarities must match to six decimal places and the
   corpus-wide sum must be unchanged, because nothing else was re-scored.

Measured across two runs: every similarity reproduced exactly (0.286414,
0.413478, 0.309158, 0.318958) and the fingerprint held at 1156 faces,
sum 454.099573. `op=modelcheck` also reports the same shape, 192-d and
batch 2.

LiteRT 2.3.0 is declined for now and the reason is in `build.gradle.kts`: it
ships Kotlin 2.4.0 metadata against this project's 2.2.0 toolchain, so taking
it is a Kotlin upgrade rather than a dependency bump, and would want this
whole procedure run again.

`compileSdk` and `targetSdk` are both 37. Targeting a new platform opts the
app in to its behaviour changes, so it was verified rather than assumed:
permissions still resolve to `ACCESS_MODE=FULL`, WorkManager still arms both
trigger slots and the periodic net, and the corpus fingerprint is unchanged.

## State, and what happens when the screen is destroyed

Every screen has a ViewModel now. Before, the Activities held the queue, the
position, the undo history, the candidate list and the database handle in
fields, and a rotation threw all of it away: the handle was closed and
reopened, the queue re-read, the candidate list re-queried, and the position
restored by hand from a Bundle the Activity packed itself.

`SavedStateHandle` still carries the things a ViewModel alone cannot keep —
the review position and the undo stack — because those survive the process
being killed and are the user's own work rather than something re-derivable.
The queue deliberately is not saved: reloading is more correct, since a
verdict recorded before the rotation has taken that item out of it.

Work that must finish moved with it. Un-promoting a photo runs on
`Background.io`, not a screen scope, because half an un-promotion leaves a
copy in the backed-up folder with no record of it. Removing a person runs on
`viewModelScope`, because the screen closes immediately after the tap.

Verified by rotating each screen on the device: review holds "5 of 109",
person holds its count, onboarding holds its step.

## Exceptions

47 blanket `catch (e: Exception)` became 28, and the ones that remain are
deliberate rather than leftover.

The one that was a real bug: a `catch (e: Exception)` inside a coroutine also
catches `CancellationException`, so cancelling the enrolment coroutine would
have been swallowed and reported as a failed enrolment. It rethrows now.

Narrowed where the throwing surface is known — `SQLException` for the
migrations, `ExecutionException`/`InterruptedException` for WorkManager's
futures, `IOException`/`SecurityException` for file and provider work,
`RemoteException` for the `:scoring` reply, `ParseException` for a date.

Left broad, with the reason written next to them, where a single call reaches
three third-party surfaces: decoding one photo goes through the platform
decoder, ML Kit and TFLite, which throw `IOException`, `MlKitException` and
`IllegalStateException` between them, and one unreadable photo must not end a
pass over six hundred.

## Languages and layout direction

Every user-facing string is in `res/values/strings.xml`, with Hebrew, Spanish,
French and German beside it. Counted things are `<plurals>`, which matters more
than it looks: Hebrew has a dual, and Spanish and French need a `many` form that
English does not.

The layout mirrors. `supportsRtl` is declared, padding is
`setPaddingRelative`, margins go through `Ui.setMarginsRelative`, the back
arrow is `autoMirrored`, and `GridGaps` flips its offsets when the layout
direction is RTL.

Lint is the check here, and it earns its place: it found three strings with no
translation, sixteen plurals per locale missing Spanish and French `many`, and
two French `one` forms that omitted the number — French `one` covers zero as
well, so "La photo conservée est vérifiée" would have been shown for none.

Test a locale without changing the phone's own language:

```bash
adb shell cmd locale set-app-locales com.pixkin.app --locales he
adb shell cmd locale set-app-locales com.pixkin.app --locales ""   # back to default
```

**Verified by screenshot in all four**, because this is the class of bug that
compiles and passes every test. The Hebrew layout mirrors correctly and the
counts read right — and the auto-save caption was clipped to its first word,
"שמירה", with no ellipsis to admit it. Three separate layout faults stacked:
the caption could not wrap because it was `WRAP_CONTENT`, then could not wrap
because its parent column was, and then wrapped but was cut off because the
cell matched a row height set by its shorter neighbours. It now autosizes
within two lines and the tallest cell sets the row height. Spanish, French and
German hit the same wall and clear it the same way.

## The debug gate, on a real release build

`DevOps.kt` refuses every op unless `BuildConfig.DEBUG`. That claim could not
be made from a device until there was a signed release to install, so it shipped
argued rather than demonstrated. It is demonstrated now.

A release build needs a key, and the developer's real release key must not be
used for a test — so the verification used a one-day throwaway keystore
generated outside the repository, with `keystore.properties` deleted and the
key destroyed afterwards. `apksigner verify --print-certs` confirmed which
certificate signed the APK before any device result was trusted, and
`aapt2 dump badging` confirmed `com.pixkin.app`, no `application-debuggable`,
`arm64-v8a` alone.

The permission grant is the part that makes or breaks the test.
`MainActivity.onNewIntent` stashes the op and returns when the app has no full
image access, so firing ops at a fresh install exercises the permission check
and never reaches `dispatch` at all — a pass that proves nothing. Grant first:

```bash
adb shell pm grant com.pixkin.app android.permission.READ_MEDIA_IMAGES
for op in reset clearlabels promote; do
  adb shell am start -S -n com.pixkin.app/com.picfilter.spike.MainActivity --es op $op
done
adb logcat -d | grep -E 'PixKin start|DEV op'
```

All three reached `dispatch` and were refused:

```
I PICFILTER: === PixKin start op=reset sdk=37 ===
W PICFILTER: DEV op 'reset' ignored: diagnostics are debug-build only
```

`findByName` is evaluated when `buildTypes` is configured, so `signingConfigs`
has to be declared **before** `buildTypes` in `app/build.gradle.kts`. Declared
after, as it originally was, the release build silently takes a null signing
config and emits `app-release-unsigned.apk`. Nothing warns; the build succeeds.
The check is the filename.

### Restoring across the applicationId change

The enrolment is hand-made work that cannot be regenerated, so the old
package's database was saved before anything was installed, and the restore was
verified by reading it back:

```bash
PKG=com.picfilter.spike scripts/db-backup.sh save   # old id
scripts/db-backup.sh restore backups/<that file>    # new id
scripts/db-backup.sh save                           # read it back
shasum -a 256 backups/*.db                          # must match
adb uninstall com.picfilter.spike                   # LAST, and not optional
```

The uninstall is part of the migration, not tidying after it — see *Two
installs of the same app rescue the same photo twice* below. Leave it to the
end: until the new install is verified, the old one is the only copy of the
enrolment that is not a file on a laptop.

646 items, 5 people, 535 labels, 113 promotions, 12 centroids, and the round
trip was byte-identical.

Two faults in `db-backup.sh` surfaced only by doing this. It removed a stale
`-journal` beside the replaced file but not `-wal`/`-shm`, and SQLite will
replay a write-ahead log over a database it was never written for; and it did
not stop the app, which can flush its own pages over the file after the copy.
Both are fixed.

The face crops do **not** come across — they lived in the old package's
private files directory, which the new package has no access to. The app
detects that it has no face to show and rebuilds from the library:

```
I PICFILTER: AVATAR 5 of 5 have no face to show; rebuilding
I PICFILTER: AVATAR גבע saved=4 rejected=0 bar=0.70
I PICFILTER: AVATAR done people_fixed=5
```

That path was written for crops deleted from under the app, and it covers the
package rename for free. The thresholds and labels are in the database, so
nothing was re-decided: `bar=0.70` is the restored value.

### Auto-save did not survive it either, and that one was silent

The crops announce themselves — the people row shows initials until the rebuild
runs. Auto-save said nothing. It came up **off** on an install that otherwise
looked complete, and it would have stayed off.

There was no setting behind that toggle. `Watcher.isEnabled` asked WorkManager
whether `picfilter-watch` had unfinished work, and WorkManager keeps its
schedule in `no_backup/androidx.work.workdb`, private to the applicationId and
carried by no backup. `db-backup.sh` moves `picfilter.db` alone, so the restore
produced an install with every person, label and threshold intact and nothing
scheduled. The two packages still showed it: the old one reported
`net=[ENQUEUED] a=[ENQUEUED] b=[SUCCEEDED]`, slot B having actually run a
trigger cycle, and the new one `b=[]`.

The heal that existed could not see this. It was

```kotlin
if (watchOn && !armed) Watcher.enable(ctx)
```

which repairs a half-broken chain but needs auto-save to be **on already** — and
the thing that was wrong was that it read as off. It healed the schedule from
the schedule.

So the user's *intent* is now recorded separately, in `meta` in the database,
because the database is what gets backed up. `Watcher.healSchedule(ctx, wanted)`
reads the intent and brings the schedule to it. The intent is null until someone
has said either way, and null must not read as false: the first launch writes
down whatever WorkManager already held, so an existing install with auto-save
running keeps it.

Verified on the phone by reproducing the restore exactly — remove WorkManager's
database and leave `picfilter.db` alone:

```bash
adb shell am force-stop com.pixkin.app
adb shell "run-as com.pixkin.app sh -c 'rm -f no_backup/androidx.work.workdb*'"
adb shell am start -S -n com.pixkin.app/com.picfilter.spike.MainActivity
```

```
I PICFILTER: WATCH heal: wanted on, nothing scheduled -- re-arming
I PICFILTER: WATCH stat net=[ENQUEUED] a=[ENQUEUED] b=[] enabled=true armed=true
```

**The negative case is the one that matters**, because a fix that re-arms too
eagerly overrides someone who deliberately turned it off. With the intent set to
off and WorkManager wiped the same way, the launch logs no heal at all and
`watchstat` reports `enabled=false armed=false`.

A reinstall or a clear-data is a different matter and is left alone: everything
private goes, the database with it, and the user re-enrols from scratch. The
asymmetry only exists where the database comes back and the schedule does not.

`op=watchon` and `op=watchoff` now write the intent as well as the schedule.
They used to set only the schedule, which the next launch would heal straight
back — a diagnostic that undid itself.

### Two installs of the same app rescue the same photo twice

Changing the applicationId does not replace the old install, it adds a second
one. Both were left on the phone for three days with auto-save on, both watching
the same library, and on 24 October both rescued the same four new photos. The
destination collision is the only reason this did not end in a lost file: the
first copy landed as `IMG-20261024-WA0002.jpg` and the second as
`IMG-20261024-WA0002_42048.jpg`, byte-identical, both in `Pictures/PicFilter/`.

Nothing in the app can see another copy of itself. The dedupe that saved it is
per-name, inside one install's own promote path, and it fired here only because
the two installs happened to write to the same folder.

Row counts are not enough to notice this. Both databases held 117 promotions
over an identical set of item ids; the four duplicated rows differed only in
`dest_uri`, `dest_name` and `at`. Compare the tables, not the counts:

```bash
sqlite3 old.db ".mode insert" "select * from promotion order by item_id" | shasum -a 256
sqlite3 new.db ".mode insert" "select * from promotion order by item_id" | shasum -a 256
```

An earlier attempt at the same check used `quote(t.*)`, which is not valid and
returned empty for every table — so every table compared equal, including the
one already known to differ. **Any comparison that can only report "same" is
worth testing against a difference you have already found**, which is the same
lesson as the grep that invented six StrictMode violations.

The old install also held one `DISCARD` the new one did not: a judgement made in
it two hours after the restore, when both were on the phone. Merging it across
before uninstalling is the whole reason to diff rather than just delete.

**So the migration is not done until the old package is uninstalled.** Until
then there are two schedulers, two scoring processes and two sets of rescued
files competing over one library.

## What is not covered yet

Ordered by the risk each one leaves open.

1. **Configuration changes and process death.** `ReviewActivity` and
   `OnboardingActivity` now save their step, index and undo history, and the
   onboarding scan survives in `Scan` rather than dying with the Activity.
   **None of it is tested automatically**, and it wants
   `ActivityScenario.recreate()` against fixtures, which collides with the rule
   below about never automating the review screen. The person screen has now
   been rotated on a device and came back with its list intact and no database
   read — see *The grid in landscape* above — so the ViewModel retention is
   demonstrated for that screen. Still hand-verification only, and still
   unverified where it matters most: rotate mid-review and mid-scan, and check
   the queue position and progress survive.
2. **A UI handler that silently does nothing.** Every rescue button once became a
   no-op because a `when` over card types quietly stopped being exhaustive, and
   nothing failed — the buttons just did nothing. Needs an instrumented test that
   clicks each review action and asserts a row appears in the database. This is
   the highest-value missing test and the hardest, because it must not be run
   against the user's real photos.
3. **Whether a lowered threshold actually holds.** `FitThresholdTest` pins the
   arithmetic and `LabelledNearMissTest` the attribution, but no measurement yet
   says a bar lowered from two-sided evidence keeps its promised precision on
   the library. The sample below the bar is biased twice over — the rescue queue
   is ordered by near-miss similarity, and the single-near-face clause drops
   crowded photos — so the fitted figure is optimistic by an unknown amount.
4. **Precision as a number, out of sample.** `MatchFaceTest` pins the threshold
   but cannot say what precision it buys. Needs a committed golden label set and
   a held-out split, so the suite can assert "precision ≥ 0.90 on data the
   thresholds were not fitted on". Blocked on the labels containing real photo
   filenames: the test must take a path and skip when absent.
5. **Date preservation end to end.** MediaProvider re-scans on `IS_PENDING`
   1→0 and recomputes the date from EXIF, which WhatsApp strips — so rescued
   photos landed in the cloud dated today instead of last June. Needs an
   instrumented test that promotes a fixture, re-reads `DATE_TAKEN`, and asserts
   the original date.
6. **The promote/verify invariant under interruption.** 19 rescued files went
   missing once. `op=promocheck` checks it after the fact; a test should kill a
   promote run partway and assert the rows and the files still agree.
7. **A memory regression test.** `op=memprobe` measures this by hand; nothing
   fails automatically. A test that asserts stage 1 stays flat and stage 3 adds
   under ~5 MB per 100 images would catch a regression in our own code without
   pinning ML Kit's leak in place. It needs committed fixture images, and the
   user's photos must not be used.
8. **Partial media grants.** Android 14+ `READ_MEDIA_VISUAL_USER_SELECTED` gives
   a library that looks readable and is not. `smoke.sh` asserts
   `ACCESS_MODE=FULL`; nothing tests that the app refuses a partial grant.
9. **Video.** 315 WhatsApp videos are entirely unhandled, so there is nothing to
   test — noted here so it is not mistaken for coverage.

## Rules for this suite

- **Never automate the review screen.** Tap automation once recorded 43 spurious
  judgements about the user's family across two runs. Any UI test must use
  fixtures, not the real library, and `op=clearcl` exists to undo damage.
- **Never promote, trash, or label from a test.** `smoke.sh` is read-only with
  respect to the user's photos; the heaviest thing it does is score photos
  already on the phone.
- **Never trust a device result without checking which APK is installed.**
- **Back up the database before any instrumented run.** The uninstall that made
  this essential is switched off now, but the flag is one line and the
  enrolment is the user's own manual work that cannot be regenerated.
- **A check that cries wolf gets ignored.** A first version of the gitignore
  check fired on every new file; it was narrowed until it fired only on the real
  bug.
