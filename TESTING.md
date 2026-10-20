# Testing PicFilter

The app deletes nothing by itself, but it does propose deletions from someone's
family photos, and it copies files around on their behalf. The tests exist to
protect those two things. The test specification is largely the bug history:
every check below that cites a bug is guarding a mistake this code actually
made.

## Running it

```bash
./gradlew :app:testDebugUnitTest         # 56 JVM tests, ~5s, no device
scripts/db-backup.sh save                # ALWAYS, before the next line
./gradlew :app:connectedDebugAndroidTest # 34 instrumented tests, needs a phone
scripts/smoke.sh static                  # repo checks, no device
scripts/smoke.sh                         # full device walk-through
```

> **Gradle uninstalls the app after an instrumented run, and uninstalling wipes
> `/data/data`** — enrolled people, clusters, scores, promotion rows, human
> labels, all of it. This is not hypothetical: it destroyed a hand-tagged
> enrolment of five people and a 4.5-minute bootstrap, and the only reason it
> was noticed was an unrelated command failing with "Activity class does not
> exist". Rescued photos live on external storage and survive; nothing else
> does. Run `scripts/db-backup.sh save` first, every time.

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
| `WhatsAppFolderTest` | Which folders onboarding samples. WhatsApp has used several folder shapes over the years and the bucket names differ again; getting this wrong is quiet — the scan still runs, it just learns the wrong faces. |

`matchFace` was lifted out of `FaceEngine` to make this possible: it is pure
arithmetic that was trapped in a class needing a TFLite interpreter.

## Tier 2 — instrumented tests (`app/src/androidTest`)

Real SQLite, real WorkManager, on a device.

| File | Guards |
|---|---|
| `ScoreStalenessTest` | Enrolling a person, refitting a threshold, or wiping enrolment invalidates the existing scores. Without this, 638 photos stayed scored against nobody: `KEEP` was 0 and auto-rescue would have woken on every new photo and rescued nothing. Also: rescoring must not accumulate `face` rows. |
| `DbUpgradeTest` | The v5→v6 migration, on a database shaped like the one on the phone — scores first, people enrolled afterwards. That database *is* the bug, so the upgrade must mark its scores stale. And the migration must keep the scored corpus: rebuilding 638 scores costs two minutes of face detection. |
| `SyncedReclaimTest` | Reclaiming a local copy must not invite the app to copy it straight back. The folder and the promotion table together decide what still needs rescuing, so a file deliberately removed — because it is in the cloud and the space was reclaimed — has to stay recorded and stay out of the promotion queue. |
| `VouchedExtensionTest` | Attribution for `extendFromLabels`, clause by clause: a keep explained by an existing match teaches nothing, two near misses in one photo are ambiguous, a distant face is not a near miss, and a discard teaches nothing. A keep is a judgement about a *photo*, not about a face — getting this wrong means a centroid quietly drifted onto someone else. |
| `LabelledNearMissTest` | The query supplying the below-the-bar half of a threshold, clause by clause — and the boundary with `vouchedNearMisses`: the same discarded near miss must give the fitter a negative point and teach a centroid nothing. |
| `WatcherArmingTest` | Re-arming the content trigger never cancels a trigger already waiting. Arming used `REPLACE`, and since every app open re-arms to heal a broken chain, routine use kept resetting the trigger — and cancelled a run in progress during testing. Also: a cancelled slot is re-armable, so a force-stop is recoverable. |

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

## What is not covered yet

Ordered by the risk each one leaves open.

1. **A UI handler that silently does nothing.** Every rescue button once became a
   no-op because a `when` over card types quietly stopped being exhaustive, and
   nothing failed — the buttons just did nothing. Needs an instrumented test that
   clicks each review action and asserts a row appears in the database. This is
   the highest-value missing test and the hardest, because it must not be run
   against the user's real photos.
2. **Whether a lowered threshold actually holds.** `FitThresholdTest` pins the
   arithmetic and `LabelledNearMissTest` the attribution, but no measurement yet
   says a bar lowered from two-sided evidence keeps its promised precision on
   the library. The sample below the bar is biased twice over — the rescue queue
   is ordered by near-miss similarity, and the single-near-face clause drops
   crowded photos — so the fitted figure is optimistic by an unknown amount.
3. **Precision as a number, out of sample.** `MatchFaceTest` pins the threshold
   but cannot say what precision it buys. Needs a committed golden label set and
   a held-out split, so the suite can assert "precision ≥ 0.90 on data the
   thresholds were not fitted on". Blocked on the labels containing real photo
   filenames: the test must take a path and skip when absent.
4. **Date preservation end to end.** MediaProvider re-scans on `IS_PENDING`
   1→0 and recomputes the date from EXIF, which WhatsApp strips — so rescued
   photos landed in the cloud dated today instead of last June. Needs an
   instrumented test that promotes a fixture, re-reads `DATE_TAKEN`, and asserts
   the original date.
5. **The promote/verify invariant under interruption.** 19 rescued files went
   missing once. `op=promocheck` checks it after the fact; a test should kill a
   promote run partway and assert the rows and the files still agree.
6. **A memory regression test.** `op=memprobe` measures this by hand; nothing
   fails automatically. A test that asserts stage 1 stays flat and stage 3 adds
   under ~5 MB per 100 images would catch a regression in our own code without
   pinning ML Kit's leak in place. It needs committed fixture images, and the
   user's photos must not be used.
7. **Partial media grants.** Android 14+ `READ_MEDIA_VISUAL_USER_SELECTED` gives
   a library that looks readable and is not. `smoke.sh` asserts
   `ACCESS_MODE=FULL`; nothing tests that the app refuses a partial grant.
8. **Video.** 315 WhatsApp videos are entirely unhandled, so there is nothing to
   test — noted here so it is not mistaken for coverage.

## Rules for this suite

- **Never automate the review screen.** Tap automation once recorded 43 spurious
  judgements about the user's family across two runs. Any UI test must use
  fixtures, not the real library, and `op=clearcl` exists to undo damage.
- **Never promote, trash, or label from a test.** `smoke.sh` is read-only with
  respect to the user's photos; the heaviest thing it does is score photos
  already on the phone.
- **Never trust a device result without checking which APK is installed.**
- **Back up the database before any instrumented run.** See the warning above.
  The enrolment is the user's own manual work and cannot be regenerated.
- **A check that cries wolf gets ignored.** A first version of the gitignore
  check fired on every new file; it was narrowed until it fired only on the real
  bug.
