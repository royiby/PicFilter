# PicFilter

An Android app that stops school-WhatsApp-group junk from polluting a Google
Photos library — by filtering **on the way in** rather than deleting afterwards.

On the phone it was built against, **609 of 714 images (85%) came from WhatsApp.**

## Why it is not a website

The obvious design — a site that logs into Google Photos, reads the built-in
face groups, and deletes the misses — is not buildable. Three hard blocks:

| Block | Detail |
|---|---|
| No library read | `photoslibrary.readonly`, `.sharing`, `photoslibrary` removed 2025-03-31 → `403 PERMISSION_DENIED` |
| No delete | No Google Photos API has ever had a delete-media-item method |
| No face data | Face groups are UI-only; no API exposes people clusters |

The surviving scope is `appendonly` — upload. So PicFilter inverts the problem:
switch Google Photos backup **off** for WhatsApp folders and **on** for
`Pictures/PicFilter`, and the app's only job is deciding what gets copied there.
Default-deny. No OAuth, no network, no token expiry.

Deletion of local junk uses `MediaStore.createTrashRequest` — one system dialog
for a whole batch, fully reversible, officially sanctioned. No UI automation, no
terms-of-service grey area.

## How it decides

```
matched >= 1                    -> KEEP              (protected, never proposed)
indeterminate >= 1              -> REVIEW            (blocks auto-discard)
faces >= 3, none matched        -> DISCARD_CANDIDATE
faces 1-2, none matched         -> REVIEW
0 faces, text-heavy             -> DISCARD_CANDIDATE
0 faces, plain                  -> REVIEW
```

Matching is **three-state** — `match` / `no-match` / `INDETERMINATE` — and a
single indeterminate face forces review. That closes the "your child was in the
class photo but too small to recognise" failure mode by construction rather than
by threshold tuning.

People are **enrolled explicitly** (drop solo photos into
`Pictures/PicFilterEnroll/<Name>/`), not discovered by clustering. Faces are
embedded with MobileFaceNet (192-d) and matched by cosine distance to centroids.

## Measured findings

Everything in [SPIKES.md](SPIKES.md) is measured on a real library, including the
results that killed ideas:

- **EXIF-based screenshot detection fails.** WhatsApp strips EXIF from ~91% of
  its media, so "no camera tags" describes nearly every real photo too. The
  heuristic labelled 73% of the library "screenshot-shaped"; 146 of those had 3+
  faces and were group photos.
- **`MIN_SHARPNESS` did nothing.** Sweeping 0 → 25 moved 2 faces out of 1,409.
  Deleted.
- **Pose, not face size, was the binding constraint.** At a 45° limit,
  indeterminate faces hit 40.7% and crowd discards collapsed to 37 images; with
  pose inert it is 18.7% and 89 — reconciling the size-only measurement exactly.
- **Small faces over-merge.** Admitting them produced a single 205-face garbage
  cluster: to a 192-d model, tiny blurry faces all look alike.
- **Text-density detection is the one trustworthy signal today** — 129 images
  (21% of volume), and **invariant across all 36 gate configurations tested**.
- Face clustering over 953 faces produced a clear recurring-people head
  (92, 48, 46, 45, 42, 38, 34) over a long singleton tail; the top 10 clusters
  are 5% of clusters but **43% of all faces**.
- Mac and Android embeddings agree to **4 decimal places**, so thresholds fitted
  offline transfer to the phone with no calibration offset.

## Status

| Phase | State |
|---|---|
| 0 — spikes | done, all validated on-device |
| 1 — engine, enrollment, scorer | done, runs on real data |
| 2 — review UI | working (golden-set labelling + duplicate verification) |
| 3 — automation / promoter | not started |
| 4 — cloud backlog via Takeout | not started |

**Nothing is deleted, moved or uploaded today.** Phases 1–2 only observe, score
and record judgements. Auto-discard ships disabled and stays that way until
precision is measured against a golden set.

Four tunables remain unfitted (`MATCH_THRESHOLD`, pose limits, text thresholds,
dupe hamming). They need labelled ground truth — see [PLAN.md](PLAN.md).

## Build

```bash
./scripts/fetch_model.sh      # downloads + SHA-256 verifies MobileFaceNet.tflite
./gradlew :app:assembleDebug
```

Requires a JDK (Android Studio's bundled JBR works) and the Android SDK.
See [USAGE.md](USAGE.md) for the full operation list and enrollment convention.

## Documents

- [PLAN.md](PLAN.md) — architecture, decisions, phases, rejected options
- [SPIKES.md](SPIKES.md) — every experiment, including the failures
- [USAGE.md](USAGE.md) — build, enrol, run, read results
- [models/PROVENANCE.md](models/PROVENANCE.md) — model source and licensing caveats

## Licence

MIT (see [LICENSE](LICENSE)). Note the model weights are **not** covered by it —
MobileFaceNet is typically trained on MS1M, which Microsoft withdrew. Fine for
personal use; clear it before any commercial release.
