# PixKin

An Android app that rescues family photos out of a WhatsApp folder and into a
backed-up one — on-device, with no account and no network.

On the phone it was built against, **609 of 714 images (85%) came from
WhatsApp.**

> **Naming.** The product is PixKin. The repository, the package
> (`com.picfilter.spike`) and the destination folder (`Pictures/PicFilter`) are
> still PicFilter — renaming them costs a reinstall and an orphaned rescue
> folder, so it has not been done.

## Why it is not a website

The obvious design — a site that logs into Google Photos, reads the built-in
face groups, and deletes the misses — is not buildable. Three hard blocks:

| Block | Detail |
|---|---|
| No library read | `photoslibrary.readonly`, `.sharing`, `photoslibrary` removed 2025-03-31 → `403 PERMISSION_DENIED` |
| No delete | No Google Photos API has ever had a delete-media-item method |
| No face data | Face groups are UI-only; no API exposes people clusters |

The surviving scope is `appendonly` — upload. So the integration point is the
filesystem instead: WhatsApp writes media to disk, MediaStore indexes it, and
Google Photos backs up the device folders you choose. No OAuth, no network, no
token expiry.

## The premise changed, and that is the point

The app was designed as a **gatekeeper**: keep junk out of the cloud by backing
up `Pictures/PicFilter` instead of the WhatsApp folders.

Measuring the real device killed that design. WhatsApp backup had already been
switched off by hand, months earlier, precisely because of the junk — so there
was nothing left to gatekeep. What the blunt switch also stopped was the
keepers:

```
ALL whatsapp items     638   264.7 MB
KEEP (family)          143    59.5 MB   <- existed ONLY on this phone
REVIEW                 352   135.4 MB
DISCARD_CANDIDATE      143    69.8 MB
```

**143 irreplaceable photos had no cloud copy for ~2.5 months**, traded for 265 MB
of junk that is negligible on a modern phone. So the product inverted: PixKin is
a **rescue** tool. It copies keepers into a folder Google Photos backs up, and
never deletes anything by itself.

> **That 143 was itself too high**, and the measurement that found it came
> later: at the 0.55 threshold in force when this was written, only 47% of the
> photos called family actually were. At the fitted 0.70 the same library gives
> 48. The *shape* of the argument survives — dozens of irreplaceable photos had
> no cloud copy, and the junk they were traded for is worth nothing — but the
> number is a reminder that an unmeasured threshold makes every count
> downstream of it wrong.

## How it decides

```
matched >= 1                    -> KEEP              (copied to the backed-up folder)
indeterminate >= 1              -> REVIEW            (blocks auto-discard)
faces >= 3, none matched        -> DISCARD_CANDIDATE (CROWD_NO_MATCH)
faces 1-2, none matched         -> REVIEW            (FEW_FACES_NO_MATCH)
0 faces, text-heavy             -> DISCARD_CANDIDATE (TEXT_NO_FACE)
0 faces, plain                  -> REVIEW            (NO_FACE_PLAIN)
```

Matching is **three-state** — `match` / `no-match` / `INDETERMINATE` — and a
single indeterminate face forces review. That closes the "your child was in the
class photo but too small to recognise" failure mode by construction rather than
by threshold tuning, and `DecisionRulesTest` proves it exhaustively.

**Nothing is ever auto-discarded.** `Promoter.run` hard-refuses
`DISCARD_CANDIDATE` with a `require`, and `createTrashRequest` does not appear
anywhere in `engine/` — it lives only in `MainActivity`, behind a user tap. The
only deletes the engine performs are of files it created itself: its own
thumbnails, and copies it made in the destination folder. A source photo is
never touched by anything. Discard candidates are reported and nothing else.

## How people get enrolled

On first run PixKin finds the faces that keep recurring in the user's own
library and asks which ones matter:

```
permissions -> sample ~600 images (WhatsApp folders first) -> detect + embed
            -> average-linkage cluster -> rank by size -> show candidates as
               montages -> [name] / [not important] / [not the same person]
            -> named clusters' centroids become enrolled people
            -> warn about unclaimed recurring faces
```

That warning is a safety feature: enrol 3 people out of 6 and photos of the
other 3 become discard candidates.

The scan takes ~4.5 minutes, which is the worst thing in onboarding, so there is
a second path: **pick a handful of photos**. Measured cost in recall, by
rebuilding each person's centroid from only their N biggest faces — 80% of their
faces still match from one photo, 91% from three, against 100% from the full
cluster. Precision is untouched either way, so the cost falls entirely on faces
that stop being recognised, which is the dangerous direction. Hence: several
varied photos, and the app says so when fewer than three are used.

Only images with **exactly one quality-passing face** are used for enrolment,
which makes the face-to-name pairing unambiguous without asking anyone to tap a
bounding box.

*(`Pictures/PicFilterEnroll/<Name>/` folder enrolment still exists behind
`op=enroll`. It is a development shortcut, not the product.)*

## Thresholds, and what they cost

| Constant | File | Value | Basis |
|---|---|---|---|
| `MIN_ORIG_PX` / `MAX_POSE` (scoring) | FaceEngine.kt | 40 px / 45° | measured, SPIKES.md 3d |
| `ENROL_MIN_ORIG_PX` / `ENROL_MAX_POSE` | FaceEngine.kt | 80 px / 30° | precision-biased; small faces poison centroids |
| `MATCH_THRESHOLD` | FaceEngine.kt | **0.70** | fitted — see below |
| per-person threshold | `Fitter` | fitted at 90% target precision from labelled faces on **both** sides of the bar | overrides the global bar; floor 0.55 |
| `CLUSTER_THRESHOLD` | FaceEngine.kt | **0.60** (average linkage) | dominates greedy @0.65 on both axes |
| `TEXT_FRAC_HEAVY` | Pipeline.kt | 0.08 | spike 3b |
| `DUPE_HAMMING` | Pipeline.kt | 6 | conventional dHash default, unvalidated |
| ~~`MIN_SHARPNESS`~~ | — | **deleted** | swept 0→25, moved 2 faces of 1,409 |

Gating is done in **original-image pixels, never decoded pixels** — an absolute
decoded-pixel gate is not scale-invariant, and raising decode resolution once
relaxed it by 2.2× silently.

## Measured findings

Everything in [SPIKES.md](SPIKES.md) is measured on a real library, including
the results that killed ideas.

**Accuracy**

- **0.55 was far too low, and nobody knew because only the wrong side was
  measured.** Calibration asked whether photos *below* the threshold were family
  (they were not). It never asked whether matches *above* it were correct. They
  were not either: **47% (67/142)**. By band — 0.55–0.58 → 18%, 0.61–0.65 → 35%,
  0.70–0.80 → 82%, 0.80+ → 93%.
- **The error was concentrated in one person.** At 0.55, גבע was 24.7% correct
  over 73 matches while three others were 100%. A library of school-group photos
  is full of other children the same age, so that centroid was matching "child
  of that age". This is the children-vs-adults risk flagged in session one and
  then forgotten.
- **Raising to 0.70 plus per-person thresholds: precision 47.2% → 94.8%, false
  matches 75 → 3.** In-sample — fitted and evaluated on the same 196 labels. A
  held-out split would give a truthful number and does not exist yet.
- **False keeps are not safe.** They were filed as "the safe error" and stopped
  being thought about, which is how the project's own motivating photo got
  rescued when its owner had marked it "not mine". Safe only ever meant "does not
  lose data"; it does not mean "does not copy other people's children into your
  cloud".

- **`DISCARD_CANDIDATE` precision is now measured: 97.5%** over 159 human
  verdicts — but the two classes behind it are different decisions.
  `TEXT_NO_FACE` is **130/130, no false positives**. `CROWD_NO_MATCH` is
  **25/29**, losing about one photo in seven. All four misses had `indet=0`:
  every face was read clearly and simply belonged to nobody enrolled, which is
  the unclaimed-person failure onboarding warns about rather than anything a
  threshold can fix. Auto-discard stays off regardless; this says *which* class
  could ever justify it.

**Signals**

- **EXIF-based screenshot detection fails.** WhatsApp strips EXIF from ~91% of
  its media, so "no camera tags" describes nearly every real photo too. The
  heuristic labelled 73% of the library "screenshot-shaped".
- **Text density is the one trustworthy zero-face signal** — 129 images when the
  gate sweep ran, and **invariant across all 36 configurations** in it, which is
  what makes it trustworthy: every other signal moved with the gate. It stands
  at 130 today.
- **Pose, not face size, was the binding constraint.** At a 45° limit,
  indeterminate faces hit 40.7% and crowd discards collapse; with pose inert it
  is 18.7%, reconciling the size-only measurement exactly.
- **Small faces over-merge.** Admitting them produced a single 205-face garbage
  cluster: to a 192-d model, tiny blurry faces all look alike.
- Mac and Android embeddings agree to **4 decimal places**, so thresholds fitted
  offline transfer to the phone with no calibration offset.

**Things that were built and then rejected on the measurement**

- **Greedy clustering.** It chains: merging against a running centroid drifts the
  centroid, over-merging some people and splitting others at the same time.
  Average linkage @0.60 beats greedy @0.65 on *both* axes (largest cluster 13 vs
  18, singletons 79 vs 93) — not a trade-off, which is what you expect when the
  defect was chaining rather than threshold placement.
- **Growing centroids from confident matches.** Recovers nothing: 91% → 91%
  simulated, zero new matches on-device. Structural, not tuning — confident
  matches are by definition *inside* the region the centroid already covers,
  while the missed faces sit outside it.
- **Ranking the rescue queue by near-miss similarity.** Keep-rate is flat across
  the similarity bands and **zero** in the two closest to the threshold, so it
  does not surface family first.
- **Learning from vouched near-misses** (`extendFromLabels`) survives, but
  honestly: on the real library it recovered exactly the photos that were
  vouched for and generalised to nothing else. Kept because it is cheap and
  safe, not because the evidence is strong.

## Performance

Measured on SM-S948B (8 cores, Android 17). Details in [TESTING.md](TESTING.md).

| Workload | Result |
|---|---|
| Score 638 images, cold | 115.9 s — 5.5 img/s |
| Score 100 images | 14.5 s, 4.18 mAh — 0.042 mAh/photo |
| Content-trigger wake, nothing new | 30 s latency, 0.009 mAh |
| 50 new photos/day | ≈2 mAh scoring — about 0.05% of this battery |

**Memory was the real constraint, not power.** ML Kit leaks native memory per
call: a stage bisect put ~84 MB per 100 images in face detection and ~44 MB in
text recognition, against **under 1 MB** for our own TFLite embedding. Four
passes in one process reached 496 MB and never came back down, and the process
was killed mid-run twice.

Two responses, one of which barely worked:

- **Gate text recognition** to images where no face was found — correct, removes
  a whole ML Kit pass from ~63% of the library, and bought only **−4.7% heap,
  −2% wall**. The per-100 rates above were measured in a process already
  hundreds of MB deep and do not extrapolate to a fresh one. *A rate measured at
  one heap level is not a rate.*
- **Contain the leak in a process that ends.** The pass runs in `:scoring`,
  which exits when the queue drains and hands everything back to the kernel.
  `:scoring` absorbs 187–236 MB per pass and releases all of it; the main
  process now grows flat with respect to image count. A pass scores at most 300
  images and schedules the next one.

Face detection still leaks. It is not fixable from here — every ML Kit lever
(performance mode, landmark mode, min face size) trades directly against
small-face recall, which was deliberately tuned, and the unbundled Play Services
detector measured *worse* (+94.9 MB vs +87.9 MB per 100 images).

## Where it departs from Google's recommendations

Deliberately, and it is worth being able to say which is which. **Not** Compose,
**not** Room, no Hilt, no ViewModel, no repository layer: this started as a
measurement spike, and optimising for how fast the scoring pipeline could change
is what bought the findings above. `Ui.kt` is a hand-rolled token system that a
Compose theme would do better.

What it does follow: the system **photo picker** for enrolment (no permission at
all), **WorkManager** with content-URI triggers rather than polling,
`MediaStore.createTrashRequest` rather than `MANAGE_EXTERNAL_STORAGE`,
partial-grant detection, `values-night` and edge-to-edge insets, and 48dp touch
targets.

Six things that were plain defects rather than trade-offs have been fixed:
main-thread database and bitmap work (now behind a `StrictMode` tripwire in
debug), no configuration-change handling at all, hardcoded `/sdcard` paths where
MediaStore is the only thing that actually knows, missing accessibility labels,
unstructured `Thread {}` with callbacks into possibly-dead Activities, and
spike-era version metadata. `isMinifyEnabled = false` remains: R8 against
ML Kit and TFLite needs testing this project has not done.

## Status

| Phase | State |
|---|---|
| 0 — spikes | done, validated on-device |
| 1 — engine, enrolment, scorer | done |
| 2 — review UI + golden set | done — 267 human labels recorded |
| 3 — automation, promoter, reclaim | done — watcher, rescue, sync-reclaim all running |
| 4 — cloud backlog via Takeout | not started |
| 5 — hardening | partial — per-person thresholds and memory containment done |

Current state on the device:

```
items 644   people 5, 11 centroids
labels 535  (489 DISCARD, 13 KEEP, 33 SKIP)
promoted 113, of which 110 confirmed synced and reclaimed locally
```

Known gaps, in order of risk: no held-out precision figure, and the 97.5%
above is in-sample on the same corpus the thresholds were set from; per-person
thresholds are still unfitted, because only **2** labelled matches exist
against `Fitter`'s minimum of 6 — the 535 verdicts came from the junk and
rescue queues, and the `verify` queue that supplies matches has 109 items and
none reviewed; most of the rescued photos were promoted under the old 0.55 bar
and would not be rescued today; 315 WhatsApp videos are entirely unhandled; no
instrumented test clicks a review button.

## Build

```bash
./scripts/fetch_model.sh      # downloads + SHA-256 verifies MobileFaceNet.tflite
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest   # 43 JVM tests, ~5s, no device
```

Requires a JDK (Android Studio's bundled JBR works) and the Android SDK.
`minSdk 30`, `targetSdk 36`. See [USAGE.md](USAGE.md) for operations and
[TESTING.md](TESTING.md) before running instrumented tests — **they uninstall
the app, which wipes the on-device database.**

## Documents

- [PLAN.md](PLAN.md) — architecture, decisions, phases, rejected options
- [SPIKES.md](SPIKES.md) — every experiment, in order, including the failures
- [TESTING.md](TESTING.md) — the suite, the baselines, and what is not covered
- [USAGE.md](USAGE.md) — build, enrol, run, read results
- [models/PROVENANCE.md](models/PROVENANCE.md) — model source and licensing caveats

## Licence

MIT (see [LICENSE](LICENSE)). Note the model weights are **not** covered by it —
MobileFaceNet is typically trained on MS1M, which Microsoft withdrew. Fine for
personal use; clear it before any commercial release.
