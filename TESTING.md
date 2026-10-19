# Testing PicFilter

The app deletes nothing by itself, but it does propose deletions from someone's
family photos, and it copies files around on their behalf. The tests exist to
protect those two things. The test specification is largely the bug history:
every check below that cites a bug is guarding a mistake this code actually
made.

## Running it

```bash
./gradlew :app:testDebugUnitTest         # 27 JVM tests, ~5s, no device
scripts/db-backup.sh save                # ALWAYS, before the next line
./gradlew :app:connectedDebugAndroidTest # 14 instrumented tests, ~10s, needs a phone
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

`matchFace` was lifted out of `FaceEngine` to make this possible: it is pure
arithmetic that was trapped in a class needing a TFLite interpreter.

## Tier 2 — instrumented tests (`app/src/androidTest`)

Real SQLite, real WorkManager, on a device.

| File | Guards |
|---|---|
| `ScoreStalenessTest` | Enrolling a person, refitting a threshold, or wiping enrolment invalidates the existing scores. Without this, 638 photos stayed scored against nobody: `KEEP` was 0 and auto-rescue would have woken on every new photo and rescued nothing. Also: rescoring must not accumulate `face` rows. |
| `DbUpgradeTest` | The v5→v6 migration, on a database shaped like the one on the phone — scores first, people enrolled afterwards. That database *is* the bug, so the upgrade must mark its scores stale. And the migration must keep the scored corpus: rebuilding 638 scores costs two minutes of face detection. |
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

**What was fixed.** `decide()` consults the text signals in exactly one case, an
image with no faces: a match, an unjudged face, a crowd, or one or two strangers
all settle the verdict before `textHeavy` is reached. Scoring ran text
recognition on every image regardless and discarded the result for the ~63% of
this library that contains a face. It is now skipped unless the detector found
nothing, which removes most of the 44 MB and a whole ML Kit pass from the
majority of images. `DecisionRulesTest` pins the invariant that makes this safe —
exhaustively, for every image shape with at least one face, the verdict is
identical with and without text — because the optimisation is only correct for
as long as that holds.

**What is not fixed.** Face detection still leaks ~84 MB per 100 images inside
ML Kit, with `PERFORMANCE_MODE_ACCURATE`, `LANDMARK_MODE_ALL` and
`setMinFaceSize(0.02f)`. Every lever there trades against small-face recall,
which was deliberately tuned, so none of them is a free change. The remaining
options are architectural: run scanning in its own `:scoring` process (via
`androidx.work:work-multiprocess`) so the leak dies with the process, or bound
the images scored per process lifetime. Neither is attempted here.

## What is not covered yet

Ordered by the risk each one leaves open.

1. **A UI handler that silently does nothing.** Every rescue button once became a
   no-op because a `when` over card types quietly stopped being exhaustive, and
   nothing failed — the buttons just did nothing. Needs an instrumented test that
   clicks each review action and asserts a row appears in the database. This is
   the highest-value missing test and the hardest, because it must not be run
   against the user's real photos.
2. **Precision as a number, out of sample.** `MatchFaceTest` pins the threshold
   but cannot say what precision it buys. Needs a committed golden label set and
   a held-out split, so the suite can assert "precision ≥ 0.90 on data the
   thresholds were not fitted on". Blocked on the labels containing real photo
   filenames: the test must take a path and skip when absent.
3. **Date preservation end to end.** MediaProvider re-scans on `IS_PENDING`
   1→0 and recomputes the date from EXIF, which WhatsApp strips — so rescued
   photos landed in the cloud dated today instead of last June. Needs an
   instrumented test that promotes a fixture, re-reads `DATE_TAKEN`, and asserts
   the original date.
4. **The promote/verify invariant under interruption.** 19 rescued files went
   missing once. `op=promocheck` checks it after the fact; a test should kill a
   promote run partway and assert the rows and the files still agree.
5. **A memory regression test.** `op=memprobe` measures this by hand; nothing
   fails automatically. A test that asserts stage 1 stays flat and stage 3 adds
   under ~5 MB per 100 images would catch a regression in our own code without
   pinning ML Kit's leak in place. It needs committed fixture images, and the
   user's photos must not be used.
6. **Partial media grants.** Android 14+ `READ_MEDIA_VISUAL_USER_SELECTED` gives
   a library that looks readable and is not. `smoke.sh` asserts
   `ACCESS_MODE=FULL`; nothing tests that the app refuses a partial grant.
7. **Video.** 315 WhatsApp videos are entirely unhandled, so there is nothing to
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
