# Phase 0 — Spike Results

Run 2026-10-15 on the `Medium_Phone_API_37.0` emulator:
**Android 17 / SDK 37**, Google APIs + Play Store image.
Spike app: `app/` (`com.picfilter.spike`), AGP 9.4.1, Gradle 9.7.1, JDK 25 (Studio JBR).
Drive it with `adb shell am start -n com.picfilter.spike/.MainActivity -e op <scan|trash|untrash|verify|promote>`.

---

## ✅ Spike 1 — MediaStore reaches WhatsApp's folder

**PASS.** An ordinary app holding only `READ_MEDIA_IMAGES` sees and reads
WhatsApp media. No `MANAGE_EXTERNAL_STORAGE`, no root, no media scan trigger.

```
ACCESS_MODE=FULL
ROW id=23 wa=true name=IMG-20261015-WA0001.jpg bucket=WhatsApp Images
          path=Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/
SPIKE1 total_images=7 whatsapp_images=6
SPIKE1 whatsapp_bytes_readable=6/6
```

Findings:
- Files pushed into `Android/media/com.whatsapp/...` were **auto-indexed
  immediately** — FUSE-backed, no `MEDIA_SCANNER_SCAN_FILE` broadcast needed.
- `bucket_display_name` comes back as the literal `WhatsApp Images`, so that
  column is a cleaner filter than a `relative_path LIKE` match.
- On-disk perms are `u0_a222:media_rw` mode `660` — **another app cannot read the
  raw path**. MediaStore/`openInputStream` is the only route in. Confirms the
  design; never try to touch file paths directly.
- `ACCESS_MEDIA_LOCATION` grants fine, so EXIF GPS survives.

### ⚠️ Risk found: Android 14+ partial grants
`READ_MEDIA_VISUAL_USER_SELECTED` is a real hazard. If the user taps
**"Select photos"** rather than "Allow all", MediaStore returns only hand-picked
items and the scanner silently sees almost nothing.

The app detects this correctly (`ACCESS_MODE=PARTIAL`). **Requirement:** detect
partial access, refuse to run, and explain why full access is needed. Do not
degrade silently.

---

## ✅ Spike 2 — Batched trash, one dialog, reversible

**PASS.** `MediaStore.createTrashRequest` on 6 items produced exactly one system
dialog from `com.google.android.providers.media.module`:

```
text="Allow PicFilter Spike to move 6 photos to trash?"
resource-id=".../thumb_more_text"  text="+4"
text="Allow"   text="Deny"
```

After approving:
```
RESULT trash_dialog resultCode=-1 accepted=true
SPIKE2 after_dialog trashed=6 live=0
```
Items disappeared from the folder listing and from default MediaStore queries.
Reading them back required `QUERY_ARG_MATCH_TRASHED=MATCH_INCLUDE`.

Untrash round-trip (`op=untrash`) — *"move 6 photos out of trash?"*:
```
SPIKE2 after_dialog trashed=0 live=6
```
All six restored to their original path. **Recoverability proven**, which is the
safety property §4 of the plan depends on.

Note: chunk requests — recent target SDKs cap a single request at **2000 URIs**.

---

## ✅ Spike (added) — Gatekeeper write path

**PASS.** Writing into a Google-Photos-watchable folder needs no extra permission:

```
SPIKE_WRITE ok uri=content://media/external/images/media/36 bytes=32048
SPIKE_WRITE own_delete_no_dialog rows=1
```

- `MediaStore.insert` with `RELATIVE_PATH=Pictures/PicFilter` just works.
- **Deleting our own entry needs no dialog at all** (`rows=1`, silent). The
  confirmation dialog applies only to media owned by *other* apps. So PicFilter
  can manage its own keep-folder freely and only prompts when trashing WhatsApp's
  files — exactly the right split.

### ⚠️ Gotcha found: stale MediaStore rows
After the silent own-delete, the file was gone from disk (`total 0`) but a
MediaStore row for it **persisted**:
```
Row: 7 _display_name=picfilter-keep-...jpg, relative_path=Pictures/PicFilter/
```
**Requirement:** never trust a row's existence to mean bytes exist. Always catch
`FileNotFoundException` on `openInputStream` and treat it as a dead row.

---

## ⏳ Spike 3 — MobileFaceNet throughput + accuracy — NOT RUN
Needs a TFLite model and, for accuracy, real enrolled faces + real class photos.
Emulator timings would be meaningless; this one needs the physical phone.

## ⏳ Spike 4 — Google Photos per-folder backup — NOT RUN
Cannot be tested on the emulator (needs the real Google Photos app signed into a
real account). Manual check on the phone:
1. Google Photos → Profile → Photos settings → Backup → **Back up device folders**
2. Confirm `WhatsApp Images` / `WhatsApp Video` can be toggled **off**
3. Confirm a new `Pictures/PicFilter` folder appears and can be toggled **on**
4. Drop a file in it, confirm it reaches the cloud library

Until step 3 is confirmed, the no-OAuth gatekeeper design is unvalidated; the
`appendonly` uploader is the documented fallback.

---

## Verdict

The two spikes that could have falsified the architecture both passed. On-device
read access and reversible batched deletion are **real and sanctioned** — no ToS
grey area, no UI automation. Remaining risk is concentrated in Spike 4
(backup toggle) and Spike 3 (recognition accuracy on children).

---

# Real-device run — 2026-10-15

Device: **Samsung SM-S948B, Android 16 / SDK 36**, One UI, real WhatsApp +
Google Photos installed. Read-only on real photos; aggregates only.

## ✅ Spike 1 re-validated on real hardware

```
total images in MediaStore: 714
WhatsApp-folder images:     609      (85.3%)
```

The premise is confirmed emphatically: **85% of the images on this phone came
from WhatsApp.** Samsung's One UI changes nothing about MediaStore visibility.

Two findings:
- There is **more than one** WhatsApp bucket. Besides `WhatsApp Images` (601),
  a chat-export folder appeared with a Hebrew name (8 items). **Requirement:**
  match on `relative_path LIKE '%com.whatsapp%'`, never a hardcoded folder, and
  handle non-ASCII bucket names.
- Only 714 images are on-device against a much larger cloud library — confirming
  that the phone cannot reach the backlog. Phase 4 still needs Takeout.

## ✅ Spike 3a — face counting: throughput and discrimination

ML Kit `PERFORMANCE_MODE_FAST`, min face 4% of frame, 609 images:

```
processed=609 dead_rows=0 failed=0
throughput=14.8 img/s   avg_decode=24ms  avg_detect=41ms
zero_face=233 (38.3%)   one_or_two=226 (37.1%)   crowd_3plus=150 (24.6%)
long tail: 15,17,18,19,20,21,23,25 faces  → 20 images with >=14 faces
```

- **Throughput is a non-issue.** 65 ms/image single-threaded. Daily WhatsApp
  arrivals are trivial; a 50k backlog would be ~56 min.
- **The crowd rule has real mass:** 150 images at 3+ faces, with an unmistakable
  class-photo tail at 14–25 faces.
- **Zero-face is the single biggest bucket (38%)** — bigger than the crowd
  bucket. The plan under-weighted it. Screenshot/meme/text classification
  matters *more* than originally scoped, not less.

## ⚠️ Spike 3b — junk composition: one signal works, one fails

609 images, face + ML Kit text recognition + EXIF, 8.8 img/s:

```
TOTAL CAMERA            =  52  ( 8.5%)
TOTAL TEXT_HEAVY        = 103  (16.9%)
TOTAL SCREENSHOT_SHAPED = 445  (73.1%)
TOTAL OTHER_NOEXIF      =   9  ( 1.5%)

TEXT_HEAVY|f0   = 86     SCREENSHOT_SHAPED|f0   =  91
TEXT_HEAVY|f1-2 = 12     SCREENSHOT_SHAPED|f1-2 = 208
TEXT_HEAVY|f3+  =  5     SCREENSHOT_SHAPED|f3+  = 146
```

**Works — text density.** `TEXT_HEAVY` is 103 images (17%), and 86 of them have
zero faces. Text-heavy + no face is a clean junk class: flyers, school notices,
forwarded memes. Keep this signal.

**FAILED — EXIF/aspect-ratio screenshot detection.** The `SCREENSHOT_SHAPED`
bucket is 73% and meaningless. Root cause: **WhatsApp strips EXIF on
re-compression**, so only 8.5% of its media has any camera tags at all. My
heuristic was "no EXIF + ordinary aspect ratio", which on WhatsApp media
describes nearly every real photo too. The proof it is wrong: 146 of those
"screenshots" have 3+ faces — they are group photos.

**Consequences for the plan:**
- **Drop EXIF `Make`/`Model` as a primary origin signal.** It is absent from ~91%
  of WhatsApp media. It can only ever confirm the small CAMERA minority.
- Screenshot detection needs a real visual approach (status-bar / UI-chrome
  detection, flat-colour border runs), or should be abandoned in favour of text
  density, which already catches the same junk by a different route.

## Validated coverage so far

| Signal | Status | Share of WhatsApp volume |
|---|---|---|
| 3+ faces (crowd) | validated, needs recognition to confirm "none yours" | 24.6% |
| text-heavy + no face | validated standalone | ~14% |
| EXIF origin | **failed** — EXIF absent 91% of the time | — |
| 1–2 faces | **blocked on recognition** | 37.1% |

The largest ambiguous bucket (37%, one-or-two faces) is decidable *only* by face
recognition. That makes the embedding model the critical path, and it is still
unvalidated.

## ⏳ Spike 3c — recognition accuracy — STILL BLOCKED

Two things are needed and neither is a coding task:
1. **Model provenance.** ML Kit does no face recognition and Google ships no
   official face embedder. Options: a community MobileFaceNet/FaceNet `.tflite`
   (ubiquitous but third-party binary), or converting official InsightFace
   ArcFace weights ourselves, or measuring an upper bound with InsightFace in
   Python on the Mac first.
2. **Ground-truth labels.** Accuracy on ~10 family members — especially the
   children, which is the known weak spot — cannot be measured without someone
   saying who is in which photo.

---

# Spike 3c — recognition model: in progress

## Model acquired and verified

Chose `syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing` (MIT, 284★) —
the best-provenance option found. **No official Google or Kaggle TFLite face
embedder exists**; every candidate is a community build. Of the three evaluated:

| Repo | Stars | License | ships .tflite |
|---|---|---|---|
| sirius-ai/MobileFaceNet_TF | 482 | Apache-2.0 | no (training repo only) |
| **syaringan357/…FaceAntiSpoofing** | **284** | **MIT** | **yes, 4.99 MB** |
| JuheonYi/TFLiteFaceExample | 34 | none | yes |

Verified as a well-formed TFLite flatbuffer (`TFL3` identifier), 5,233,396 bytes,
SHA-256 `d8ba40c0…0443b`. Full details in [models/PROVENANCE.md](models/PROVENANCE.md).

## Two surprises about the model

```
MODEL in=2x112x112x3  inType=FLOAT32
MODEL out=2x192       outType=FLOAT32
```

1. **Input batch is 2, not 1.** The model was published for *pairwise
   comparison* — feed two faces, compare. `resizeInput` to batch 1 throws
   `IllegalStateException`, so the graph is fixed. Workaround: duplicate the same
   face into both slots and read slot 0. Costs 2× compute per face; acceptable,
   but worth replacing with a batch-1 export later.
2. **Embeddings are 192-d, not 512-d.** Lower capacity than ArcFace-R100
   (512-d). Fine for separating ~10 enrolled people, but this is the likeliest
   thing to limit accuracy on children. Record it as a known ceiling.

## Bug found: failed resize corrupts the interpreter

First run gave `images=609 faces=0 skipped=459`. The arithmetic identifies it:
609 − 459 = 150 images had no detected face, so **every** image that *did* have a
face threw on its first `interp.run`. Cause: the caught `resizeInput` exception
left the interpreter with unallocated tensors, and the per-image `try` aborted
the rest of that image's faces.

Also fixed a bug of mine in `decode()`: with `inJustDecodeBounds = true`,
`BitmapFactory.decodeStream` returns null **by design**, so an elvis operator on
its result bailed out of every single image (`images=0` on the run before).

Both fixed; the resize attempt is removed entirely. **Re-run not yet verified —
the phone disconnected mid-install.**

## Blocked on
1. **Phone reconnect** to verify the fix and get the label-free clustering result
   (cluster size distribution over real faces — meaningful embeddings should
   produce a few large recurring clusters).
2. **Takeout export** for the labeled accuracy measurement. None exists on this
   Mac yet. Request *Google Photos only*; `people` names per photo are the ground
   truth (no bounding boxes, which is fine — the core rule is photo-level).

---

## ✅ Spike 3c — recognition: embeddings carry real identity structure

Fix verified on the phone (Samsung SM-S948B, Android 16). Both bugs were real:
removing the `resizeInput` attempt restored the interpreter, and the `decode()`
elvis bug is gone.

### Label-free validation: PASS

609 real WhatsApp images, 953 faces embedded, greedy cosine clustering @ 0.55:

```
EMBED  images=609 faces=953 skipped=450  avg_embed_ms=13  faces_per_s=12.3
CLUSTER clusters=192  singletons=109 (56.8%)
CLUSTER top20_sizes=92,48,46,45,42,38,34,26,22,20,19,17,17,17,16,16,15,13,12,12
CLUSTER top10_covers=413/953 faces (43.3%)
```

A clear head (92, 48, 46, 45, 42, 38, 34) decaying into a long singleton tail is
exactly the signature of a few recurring people among many one-off strangers.
**The top 10 clusters are 5% of clusters but 43% of all faces.** The "top 10
faces" premise is validated — and discovered with no labels at all.

Throughput is a non-issue: 13 ms per face embedding, ~12 faces/s end-to-end
*including* decode and detection, and that is with the batch-2 workaround doing
2× the necessary compute.

### ⚠️ The real risk: small faces

`skipped=450` — **32% of all 1,403 detected faces were dropped for being too
small**, and small faces are precisely what class photos consist of. This is the
dangerous failure direction: miss your child among 20 small faces and the photo
is scored as crowd junk.

Raising decode resolution recovers them, but introduces a different failure:

| config | faces | skipped | top cluster | top-10 coverage | wall |
|---|---|---|---|---|---|
| px=1000, minbox=24 | 953 | 450 (32%) | 92 | 43.3% | 78 s |
| px=2200, minbox=12 | 1256 | 159 (11%) | **206** | 47.6% | 144 s |
| px=2200, minbox=24 | 1232 | 183 (13%) | **205** | — | 183 s |

A single cluster holding 205–206 of ~1,240 faces (16%) is implausible for a real
person when the next largest is 68. **Small faces produce poorly-conditioned
embeddings that collapse into one garbage blob** — to this model, tiny blurry
faces all look alike.

### 🐛 Scale-invariance bug in the gate (mine)

The control run was supposed to isolate resolution from face size. It did
something more useful: it proved `MIN_BOX` was measured in **decoded** pixels, so
it is not scale-invariant. At px=1000 a face must be ≥2.4% of image width; at
px=2200, only ≥1.1%. Raising decode resolution silently relaxed the real gate by
2.2×, which is why both high-res rows behave identically.

**Requirement:** gate face size in **original-image pixels** (how much real
detail exists), never in decoded pixels. ML Kit's own `setMinFaceSize` is already
relative and was fighting this absolute check.

### Design conclusion — three-state matching

The tension resolves safely by refusing to force a binary answer:

```
match        — cosine >= threshold AND face passes the quality bar
no-match     — cosine <  threshold AND face passes the quality bar
INDETERMINATE— face fails the quality bar (too small / too blurry / extreme pose)
```

- Indeterminate faces are **never matched and never clustered** — that is what
  produced the garbage blob.
- **Any image containing an indeterminate face can never be auto-discarded.** It
  goes to review. This closes the "your kid was in the class photo but too small
  to recognise" failure mode by construction rather than by threshold tuning.
- Quality bar inputs available for free from ML Kit: face box size in original
  pixels, head Euler angles, plus a Laplacian sharpness check on the crop.

### Still outstanding
Labeled accuracy (precision/recall per person, and specifically on the children)
still needs the Takeout export. The clustering result says the embeddings are
*meaningful*; it cannot say they are *correct*. Over-split clusters — the same
person appearing as several clusters — would be invisible in this view.

---

## ✅ Spike 3d — per-image incidence: small-face policy is viable

Detection-only pass, permissive `setMinFaceSize(0.02f)`, face sizes recorded in
**original-image pixels** so one run yields the whole incidence curve.
609 images, 1,409 faces, 68 s.

### Face size distribution (original px, min box dimension)

```
   0..20   61   4.3%
  20..40  199  14.1%
  40..60  279  19.8%
  60..80  185  13.1%
 80..120  235  16.7%
120..200  234  16.6%
200..inf  216  15.3%
```

Smooth — there is no natural cliff to snap a threshold to. Buckets:
`f0=229  f1-2=217  f3plus=163`.

### The incidence curve

| threshold | faces below | images with ≥1 indet | **crowd with ≥1 indet** | crowd *entirely* indet |
|---|---|---|---|---|
| 30 px | 9.6% | 8.4% | **24.5%** | 1.2% |
| 40 px | 18.5% | 14.0% | **40.5%** | 3.7% |
| 50 px | 29.4% | 18.4% | **50.9%** | 6.7% |
| 60 px | 38.3% | 21.3% | **55.2%** | 12.9% |
| 80 px | 51.4% | 26.1% | **66.9%** | 22.7% |
| 100 px | 60.8% | 30.0% | **74.2%** | 28.8% |

### Verdict: "indeterminate blocks auto-discard" is NOT vacuous

That was the risk — that the safe policy would silently disable auto-discard.
It doesn't. At a 40 px gate, only **40.5%** of crowd images contain an
unidentifiable face, so auto-discard remains available on ~**60%** of crowd
candidates. Even at a strict 60 px gate it still covers ~45%.

Two structural facts make this work:
- **Per-image incidence is far lower than the per-face rate.** At 60 px, 38% of
  *faces* are too small but only 21% of *images* contain one — small faces
  concentrate in a handful of crowded photos rather than spreading out.
- **`crowd_all_indet` stays low** (3.7% at 40 px). Almost every crowd photo
  contains at least one large, identifiable face. Class photos are not uniformly
  tiny.

### Correction to the earlier 32% figure

The `skipped=450` (32%) reported in spike 3c conflated three causes: the size
gate, `align()` returning null, and the `eyeDist < 8f` alignment check. The
purely size-driven loss is lower — **18.5% at a 40 px gate**. The alignment
checks, not face size, were doing much of the dropping. Worth revisiting the
alignment path separately; it is likely recoverable loss.

### Chosen settings
- **Gate: 40–50 px in original-image pixels**, as one input to a quality score
  (size + Laplacian sharpness + head Euler angle), not a hard binary.
- **Policy: indeterminate blocks auto-discard** (option 1). Viable at ~60%
  crowd coverage; no need to fall back to blanket review-only.
- Crowd auto-discard still ships **disabled** until Takeout labels prove
  precision — the gate decision is now independent of that.

---

## ✅ Cross-platform embedding consistency — PASS

Thresholds fitted on the Mac are worthless if the two platforms embed the same
bytes differently. Verified with a deterministic synthetic image (no photo
needed), reproducing Android's exact pattern and normalisation in numpy
([tools/xplat_check.py](tools/xplat_check.py) vs `Embed.modelCheck`).

| statistic | Android (SDK 37) | Mac (TF 2.20, py3.9 arm64) |
|---|---|---|
| dim | 192 | 192 |
| l2 norm | 1.0000 | 1.0000 |
| min / max | -0.2910 / 0.2868 | -0.2910 / 0.2868 |
| mean | -0.0009 | -0.0009 |
| cos(A,B) | 0.9914 | 0.9914 |
| first8_A | -0.0028,0.0273,0.0195,0.0034,-0.0581,0.0126,-0.0169,0.0680 | *identical* |
| first8_B | -0.0038,0.0260,0.0186,0.0024,-0.0553,0.0031,-0.0162,0.0732 | *identical* |

**Agreement to 4 dp on every reported statistic.** Mac-fitted thresholds transfer
to Android with no calibration offset.

### Python stack notes
- `ai-edge-litert` ships a **broken Python 3.9 arm64 wheel** (missing
  `libpywrap_litert_common.dylib`). Do not use it.
- `tensorflow` 2.20.0 installs cleanly on Python 3.9 arm64 and its bundled
  `tf.lite.Interpreter` reports the same `in=[2,112,112,3] out=[2,192]`.
  Venv at `.venv/`.

## 🐛 Fixed: adb intent extras silently dropped

Root cause of recurring flakiness all session. When the activity is already
running, `am start` reports *"intent has been delivered to currently running
top-most instance"* and Android routes it to `onNewIntent()` — which was not
overridden, so the `op` extra was dropped and the **default op ran instead**.
That is why `modelcheck` kept executing `scan`.

Two fixes, both applied:
1. `MainActivity.onNewIntent()` now overridden: calls `setIntent()` then dispatches.
2. **Always cold-start:** `am force-stop` → `sleep 3` → `am start`. A warm start
   can also be served by a system-restarted instance carrying a null intent.

---

# Phase 1 first full run — 2026-10-15

609 real WhatsApp images, **no enrollment** (so the MATCH path is untested; every
quality face necessarily scored NO_MATCH).

```
SCORE enrolled_people=0
SCORE done=609 dead_rows=0 failed=0 wall_ms=115293 rate=5.3 img/s
DUPES groups=8 redundant_items=23 (hamming<=6)

face NO_MATCH      = 835        REVIEW            = 443  (72.7%)
face INDETERMINATE = 574        DISCARD_CANDIDATE = 166  (27.3%)

INDETERMINATE_FACE = 206 (33.8%)   TEXT_NO_FACE  = 129 (21.2%)  -> discard
FEW_FACES_NO_MATCH = 137 (22.5%)   NO_FACE_PLAIN = 100 (16.4%)
CROWD_NO_MATCH     =  37 ( 6.1%)                             -> discard
```

Total faces 835 + 574 = **1,409 — exactly matching spike 3d.** Detection is
reproducible across runs.

## ⚠️ CORRECTION: the conservative policy IS close to vacuous as configured

Spike 3d measured the 40 px **size** gate and concluded only 40.5% of crowd
images would be blocked, leaving ~60% (≈98 images) auto-discardable. I reported
that the policy was "not vacuous". **In the shipped config it nearly is:
`CROWD_NO_MATCH` is only 37 images, so ~77% of crowd photos are blocked, not 40%.**

Cause: the quality bar is `origPx >= 40 AND sharpness >= 12.0 AND |yaw| <= 45
AND |roll| <= 45`. Spike 3d only measured the first clause.

```
indeterminate faces, measured size gate alone  (spike 3d):  18.5%
indeterminate faces, full shipped quality bar  (this run):  40.7%   <- 574/1409
```

**The two unvalidated clauses reject more than twice as many faces as the one
measured clause.** `MIN_SHARPNESS = 12.0` and the 45° pose limits are pure
guesses and they now dominate the gate. My earlier "measured at 40px: 18.5%"
claim does not describe the configuration that actually runs.

This is tunable, not structural — and it is precisely what the Takeout labels
exist to fix. But until then, treat the crowd rule as mostly disabled.

## What holds regardless

`TEXT_NO_FACE = 129` (21% of volume) is the **largest discard class and it is
entirely face-independent** — no recognition, no quality gate, no thresholds in
question. The "safe wins don't need faces" strategy is confirmed by the data:
129 of the 166 discard candidates come from it.

## Under-performing: duplicate detection

`groups=8 redundant_items=23` — only 3.8% of 609. Expected more from a library
of re-forwarded WhatsApp images. Either dHash hamming <= 6 is too strict, or
WhatsApp's recompression perturbs dHash more than the conventional default
assumes. Needs a hamming sweep; the dupe signal is face-independent and
therefore cheap to tune without labels.

## Throughput
5.3 img/s with detection(ACCURATE) + embed + text recognition + dHash per image.
A 50k backlog extrapolates to ~2.6 hours. Acceptable.

---

## ✅ Threshold sensitivity sweep — pose is the binding constraint

Derived from the stored `face` metrics: 36 gate configurations, no re-detection,
instant. 609 items / 1,409 faces.

### Sharpness does essentially nothing
At px=40, pose=45°, varying `MIN_SHARPNESS` across **0 → 6 → 12 → 25**:
```
indeterminate faces = 573, 573, 574, 575
```
Two faces out of 1,409. Laplacian variance on aligned crops is nearly always
above 25, so the clause never fires. **Recommendation: delete `MIN_SHARPNESS`.**
It is dead weight pretending to be a safety control. (Mild effect only in the
most permissive corner: px=30/pose=90 moves 140 → 186.)

### Pose dominates everything
At px=40, sharp=12, varying the pose limit:

| pose limit | indeterminate faces | blocked images | **crowd_discard** |
|---|---|---|---|
| 45° *(shipped)* | 40.7% | 206 | **37** |
| 60° | 28.5% | 157 | **62** |
| 90° *(inert)* | 20.0% | 100 | **89** |

Relaxing pose from 45° to inert **cuts indeterminate faces in half and multiplies
crowd coverage 2.4×**. My earlier correction blamed "sharpness and the pose
limits"; it is pose, essentially alone.

### Spike 3d reconciles exactly
At px=40 with pose inert, indeterminate faces = **18.7%** — against spike 3d's
size-only measurement of **18.5%**. The size gate was measured correctly all
along; the entire discrepancy was the unmeasured pose clause.

### Full coverage/permissiveness curve
```
px=30 sharp=0  pose=90   indet= 9.9%  crowd_discard=121  total_discard=250
px=40 sharp=0  pose=90   indet=18.7%  crowd_discard= 96  total_discard=225
px=40 sharp=12 pose=45   indet=40.7%  crowd_discard= 37  total_discard=166  <- shipped
px=50 sharp=12 pose=45   indet=49.3%  crowd_discard= 30  total_discard=159
```

### ⚠️ But more coverage means more LOSS risk, not less
The tempting read is "relax pose, get 2.4× coverage". The risk runs the other way:

- Rejecting a high-yaw face as INDETERMINATE is **safe** — it forces review.
- Admitting it means it gets matched, and a 70°-yaw face of your own child may
  well fail to reach `MATCH_THRESHOLD` → NO_MATCH → if every face in a crowd
  photo scores NO_MATCH, the image becomes a **discard candidate**. That is the
  data-loss path.

So the strict gate is safe-but-nearly-vacuous and the loose gate is
higher-coverage-but-lossy. **This cannot be resolved without labels** — the
measurement needed is whether high-yaw faces of *enrolled* people actually match.
Until then keep pose strict and take the value from the face-independent classes.

### Design conclusion
Pose should **modulate match confidence, not gate the face out**. A profile view
still carries identity; pre-rejecting it converts a recognition problem into a
review burden. Deferred until labels can calibrate it.

### `TEXT_NO_FACE` is invariant
**129 across all 36 configurations.** Completely independent of every face
threshold — confirming it as the one discard class that is trustworthy today.

### Dupe hamming: no knee, needs human verification
```
h= 2 →  6 redundant (1.0%)      h=10 → 51 (8.4%)
h= 4 → 16 (2.6%)                h=12 → 62 (10.2%)
h= 6 → 23 (3.8%)  <- shipped    h=16 → 102 (16.7%)
h= 8 → 37 (6.1%)
```
Smooth growth with no natural threshold; group count stays 6–10 until h=16 jumps
to 20 (likely merging unrelated images). 3.8% feels low for a re-forwarded
WhatsApp library, and 8–10 is plausible — but picking needs eyes on the proposed
groups. Cheap: only 37–51 items at h=8–10. **Defer to the Phase 2 review UI.**

---

# Phase 2 review UI — first run

`MIN_SHARPNESS` removed from the gate. Re-scored 609 items: indeterminate faces
**574 → 573**, `INDETERMINATE_FACE` **206 → 205**. Exactly what the sweep
predicted — the clause was inert. Decisions otherwise unchanged
(REVIEW=443, DISCARD_CANDIDATE=166).

## ⚠️ The first card served is the whole risk, in one frame

```
DISCARD_CANDIDATE · CROWD_NO_MATCH
faces=3  matched=0  indeterminate=0  text=0.000
```

The image is three children eating ice cream — an obviously precious family
photo. Three faces, none matching anyone enrolled (nothing *is* enrolled yet), so
`CROWD_NO_MATCH` fires and it becomes a discard candidate.

With auto-discard enabled this would have proposed deleting it. Two things
follow, and neither is a tuning question:

1. **Enrollment is not optional.** Without centroids, `matched` is structurally 0
   and every crowd photo — including every photo of your own children — scores as
   a discard candidate. The crowd rule is meaningless until people are enrolled.
2. **Auto-discard must stay off by default**, exactly as §4 of the plan says.
   This is the concrete instance the safety argument was abstract about.

Note the card also shows `indeterminate=0` — all three faces passed the quality
bar. So the INDETERMINATE guard, the thing designed to catch "your kid is in the
photo but unrecognisable", would **not** have saved this one. The guard protects
against *unrecognisable* faces; it offers nothing against *unenrolled* ones.

## UI bug found and fixed
The "Undo last" button rendered behind the system navigation bar. Now handled via
`setOnApplyWindowInsetsListener` (system bars + display cutout).

---

# Onboarding on a real library — 2026-10-18

First run of cluster-then-name against the actual phone library.

```
BOOTSTRAP sampling=600 images (strict gate 80px/30deg)
BOOTSTRAP faces=433 in 320s
BOOTSTRAP clusters=102 top=51,24,22,19,18,17,15,14,13,11
```

Two UI screens verified on real data: the intro and the candidate card
(montage + "Appears in N photos" + three buttons). No crashes, insets correct.

## ⚠️ Timing claim was wrong
The intro promised "a minute or two". Measured: **240–320 s**. The earlier ~80 s
figure was WhatsApp-only images at `targetPx=1000`; onboarding samples the whole
library (larger camera originals) at `targetPx=1200` with
`PERFORMANCE_MODE_ACCURATE`. Copy corrected to "about four minutes".

## ⚠️ CLUSTER_THRESHOLD 0.55 over-merges

The top cluster's montage did not look like one child. Threshold sweep over the
stored embeddings (instant — the 4-minute extraction is not repeated):

| threshold | clusters | singletons | largest | % of faces |
|---|---|---|---|---|
| 0.50 | 38 | 3 | 123 | 32.5% |
| **0.55** *(was shipped)* | 49 | 2 | **77** | **20.3%** |
| 0.60 | 87 | 25 | 37 | 9.8% |
| **0.65** *(adopted)* | 119 | 45 | **12** | **3.2%** |
| 0.70 | 152 | 72 | 11 | 2.9% |
| 0.80 | 233 | 156 | 10 | 2.6% |

One person holding 1-in-5 of every face in a 600-photo sample is implausible.
The merge breaks apart sharply between 0.55 and 0.65, then stabilises — beyond
0.65 the extra threshold mostly manufactures singletons (over-splitting).

### The errors are asymmetric, which settles the direction
- **Over-split is recoverable.** Name three clusters identically and
  `upsertPerson` returns the same id; all three centroids attach to that person.
  Matching takes the max across the set, so it works — and yields *better* pose
  coverage.
- **Over-merge is not.** Two children averaged into one centroid is permanently
  poisoned, and no naming fixes it.

So bias high. Adopted **0.65**, provisional until the montages are eyeballed.

Note the sweep's absolute numbers differ slightly from bootstrap's because it
ran over the stored (singleton-filtered) set; the *shape* is what matters.

## 🐛 Fixed: recluster eroded the face set
`recluster()` rebuilds from `cluster_face`, but `persist()` was dropping
singletons before writing — so every regroup permanently lost faces
(433 → 379 → 334, against 433 thumbnails still on disk). Now **all** faces are
stored and singletons are filtered at *presentation* instead
(`candidates()` requires `size >= 2`). Regrouping is now idempotent.

## Added: regroup without rescanning
`op=sweepcl` (threshold curve) and `op=recluster --es t <x>` operate on stored
embeddings, so iterating on clustering costs seconds rather than 5 minutes.
The onboarding intro also now offers "Continue with faces already found".

## ✅ 0.65 visually verified

Regrouped at 0.65 and reviewed card by card by the library's owner — the only
person who can judge whether a cluster is one human. Verdict: **"95% good
enough."** The threshold moves from provisional to accepted.

Remaining question is *which direction* the ~5% errs, because the two are not
equally costly: over-split costs the user a second naming tap, over-merge
poisons a centroid permanently.

### ⚠️ Process note: do not automate the candidate screen
Scripted `input tap` navigation repeatedly landed on decision buttons, silently
recording judgements about the user's family photos — 28 in one pass and 15 in
another, all spurious, all reset. Buttons that record opinions about people must
be driven by the person, not by a test harness. `op=clearcl` exists to undo this
class of mistake; the real fix was to stop automating that screen.

## ✅ Average-linkage clustering replaces greedy

The owner's verdict on greedy @0.65 was "95% good enough", with **both** failure
modes present: some people split across cards, some cards mixing people. Both at
once is the signature of **chaining**, not a mis-set threshold — greedy merges
against a running centroid, which drifts toward each new member, pulling in
unrelated faces while pushing genuine matches below threshold.

Average linkage compares a candidate against the mean similarity to *every*
member, so one drifting member cannot drag the group. Lance-Williams update
keeps it O(n²); 433 faces cluster in well under a second.

Measured on the same 433 stored faces:

| threshold | greedy largest | **avg-link largest** | greedy singletons | **avg-link singletons** |
|---|---|---|---|---|
| 0.45 | 133 (30.7%) | **40** | 24 | **24** |
| 0.50 | 70 (16.2%) | **22** | 31 | 39 |
| 0.55 | 51 (11.8%) | **17** | 48 | 59 |
| 0.60 | 30 (6.9%) | **13** | 75 | **79** |
| 0.65 | 18 (4.2%) | **10** | 93 | 105 |
| 0.70 | 13 (3.0%) | **10** | 123 | 131 |

**Average linkage @0.60 dominates greedy @0.65 on both axes at once:** largest
cluster 13 vs 18, singletons 79 vs 93. Not a trade-off — strictly better, which
is what you expect when the underlying defect was chaining rather than threshold
placement.

Adopted: average linkage, threshold **0.60**. The greedy implementation is kept
only so `op=sweepcl` can print both for comparison.

---

# End-to-end validation — 2026-10-18

9 people enrolled through the onboarding flow (5 named, 4 left as "Person N"),
638 WhatsApp items scored.

```
face MATCH = 198   NO_MATCH = 953   INDETERMINATE = 693

KEEP              = 143   (ENROLLED_PERSON)
REVIEW            = 352
DISCARD_CANDIDATE = 143   (TEXT_NO_FACE 130 + CROWD_NO_MATCH 13)
```

| | before enrolment | after |
|---|---|---|
| KEEP | 0 | **143** |
| `CROWD_NO_MATCH` | 37 | **13** |

**Enrolment protected two-thirds of the crowd discards.** Matches per person:

```
גבע       74  mean_sim 0.645
דני       45  mean_sim 0.686
Person 1  40  mean_sim 0.648
מישי      19  mean_sim 0.698
אבי       13  mean_sim 0.848
אמא        7  mean_sim 0.869
```

## ✅ The originating failure case is fixed

```
IMG-20260630-WA0181.jpg -> KEEP / ENROLLED_PERSON  faces=3 matched=2 indet=0
    face NO_MATCH         sim=0.511 px=212
    face MATCH = Person 1 sim=0.573 px=228
    face MATCH = גבע      sim=0.619 px=246
```

This is the photo that previously scored `DISCARD_CANDIDATE / CROWD_NO_MATCH`
with `indeterminate=0` — the case proving the INDETERMINATE guard protects
against *unrecognisable* faces but offers nothing against *unenrolled* ones.

## ⚠️ It was saved by 0.023

`MATCH_THRESHOLD` is 0.55. Person 1 matched at **0.573**. The third face sits at
**0.511**, 0.039 below the line. Had the threshold been 0.60 — a value that
looked entirely reasonable before this measurement — Person 1 would not have
matched.

**The threshold is load-bearing at exactly the margin where real photos live,
and it remains the one major parameter never fitted against labels.** This is
now the strongest argument for the Takeout export: not that accuracy is
unknown in the abstract, but that a known-good photo survives by 4% of the
decision boundary.

## Other observations
- **Person 2, 3 and 4 matched nothing.** Enrolment samples the whole library;
  scoring currently covers only WhatsApp folders. People who appear mainly in
  camera-roll photos will legitimately score zero here. Worth confirming rather
  than assuming — the alternative explanation is that their centroids are bad.
- **Confidence varies widely by person** (0.645–0.869). אבי and אמא are few but
  certain; גבע has 74 matches at a 0.645 mean, so a meaningful share sit near
  the threshold and are the likeliest false positives.
- Face count rose to 1,844 across 638 items (2.9/image) from 1,409 across 609
  (2.3/image). 29 new items cannot account for 435 faces; worth checking whether
  the recent additions are large group photos or whether something double-counts.

---

# The rescue — 2026-10-18

143 KEEP items copied into `Pictures/PicFilter`. Verified independently of the
app's own logging:

```
files on disk          143   60 MB
MediaStore indexed     143
whatsapp sources       638   (unchanged — copy only, nothing moved or deleted)
trashed anywhere         0
```

## 🐛 DATE_TAKEN at insert is silently discarded

First run produced `datetaken=NULL, date_added=<today>` on all 143. Cause:
**MediaProvider re-scans the file when `IS_PENDING` goes 1 → 0 and recomputes
metadata from EXIF**, overwriting the `DATE_TAKEN` supplied at insert. WhatsApp
strips EXIF from ~91% of its media, so there was nothing to recompute *from* and
the column was nulled.

Left alone, all 143 rescued photos would have filed into Google Photos under
today's date rather than across June–October. For a rescue of family photos
that is close to a silent data-quality disaster: the files are safe but the
timeline is destroyed.

**Fix: write the date into the JPEG's own EXIF**, not just the MediaStore row.
Verified by pulling a rescued file and reading its bytes:

```
rescued file:  DateTimeOriginal  = 2026:06:30 00:00:00
               DateTimeDigitized = 2026:06:30 00:00:00
               DateTime          = 2026:06:30 00:00:00
source file:   0 EXIF tags
```

Google Photos reads `DateTimeOriginal` cloud-side, so the date now travels with
the file instead of living in a local database row.

### Date source: filename beats `date_added`
WhatsApp filenames encode the date (`IMG-20260630-WA0181.jpg`), and on this
device they disagree with `date_added` by a month — files named June 30 have
`date_added` of July 29, almost certainly because the phone was set up then and
WhatsApp media restored in bulk. The filename is the better signal; the
promoter parses it and falls back to `date_added` only when absent.

### Known cosmetic limitation
MediaStore's `datetaken` column stays NULL even after `MediaScannerConnection`
re-scans. This affects only the **local gallery's sort order** for that folder.
The cloud outcome depends on EXIF, which is correct and verified, so this was
not worth further cycles.

## 🐛 Op-name collision
`op=promote` already belonged to the Phase 0 gatekeeper write test, and Kotlin's
`when` takes the first match — so the rescue silently ran the old spike instead.
The Phase 0 test is now `op=spikewrite`. Worth remembering: adding a branch to a
long `when` can be shadowed with no warning.

---

# Rescue review — widening beyond the confident 143

A second review mode ordered by **how close each photo's best face came to an
enrolled person**, so the most-likely-family items come first and the user can
stop when they stop seeing family. Required recording the nearest person even
on a miss (previously discarded), then a rescore.

```
RESCUEQ size=247                       (INDETERMINATE_FACE + FEW_FACES_NO_MATCH)
  near=גבע      0.549  faces=2
  near=גבע      0.548  faces=9
  near=Person 1 0.547  faces=3
  near=דני      0.546  faces=2
  near=Person 1 0.544  faces=3
  ...
items with a near-miss >= 0.45: 78
```

## ⚠️ The threshold cuts through a dense region, not a gap

The head of the queue is a tight cluster of faces sitting **0.001–0.01 below
`MATCH_THRESHOLD = 0.55`**. These are not ambiguous strangers; they came within
a rounding error of matching a named family member.

A threshold is only safe where the distribution is sparse. Here it slices
through a populated band, which is why the ice-cream photo survived by 0.023 —
that was not bad luck, it is where this threshold always sits.

**The rescue review doubles as threshold calibration.** Every KEEP/NOT-MINE
decision in the 0.50–0.55 band is a labelled point exactly where the boundary
is contested. If most of that band comes back KEEP, 0.55 is too high and the
evidence to lower it will have been produced as a by-product of protecting
photos — no Takeout export required.

---

# Threshold calibration from 247 human labels — 2026-10-18

The rescue review produced 203 DISCARD + 44 KEEP over the 247 REVIEW items that
contain faces. Every one is a labelled point in the band where
`MATCH_THRESHOLD = 0.55` is contested, so it settles the question directly —
no Takeout export needed.

## ❌ Near-miss similarity does NOT predict "this is family"

Keep-rate by the photo's best face similarity:

```
ALL                          FEW_FACES_NO_MATCH (clean subset)
0.000..0.350  13.5%          0.000..0.350  16.1%
0.350..0.400  23.1%          0.350..0.400  28.6%
0.400..0.450  17.9%          0.400..0.450  28.6%
0.450..0.475  21.7%          0.450..0.475  21.4%
0.475..0.500  15.4%          0.475..0.500  25.0%
0.500..0.520   5.9%          0.500..0.520   0.0%   <- 0/10
0.520..0.540   6.3%          0.520..0.540   0.0%   <- 0/5
```

If similarity predicted family-ness, keep-rate would climb steeply toward 0.55.
It is flat, and **zero** in the two bands closest to the threshold on the clean
subset: 15 photos whose faces came nearest to matching an enrolled person, none
of them family.

The `INDETERMINATE_FACE` confound was checked and ruled out — those items' max
similarity belongs to a different face than the family member, but filtering to
`FEW_FACES_NO_MATCH` (where every face passed the quality gate) makes the result
*stronger*, not weaker.

## Consequences

1. **Do not lower `MATCH_THRESHOLD`.** The region just below 0.55 is genuinely
   not family. Dropping to 0.50 would admit ~15 non-family photos and recover
   none. 0.55 is not too high — a worry carried for several sessions, now retired.
2. **The ice-cream photo surviving by 0.023 was not evidence of a mis-placed
   threshold.** It was a true positive near the boundary, not a near-miss of a
   boundary in the wrong place.
3. **Ranking the rescue queue by near-miss was a failed idea.** It did not
   surface family first. Harmless here (all 247 were reviewed) but it should not
   be presented to a user as "most likely family".
4. The 44 genuinely-missed family photos were missed for reasons the embedding
   never saw: faces too small or turned to pass the quality gate, or photos that
   are family-relevant without a recognisable face at all.

**Takeout's remaining value is measuring recall independently** — this method
can only sample what the scorer already routed to REVIEW. It is no longer the
blocker on threshold work, because there is no threshold work to do.

---

# 🐛 Silent promotion loss — 19 files reported rescued that were not there

After the second promotion run the app reported **187 promoted**, but the folder
held **168 files**. The app's own logging said `copied=44 failed=0`; only an
independent `ls` caught it.

Diagnosis: all 19 missing files were written in the **first** run, in its final
second (`promoted_at=16:19:10–11`), with real recorded byte counts. They were
verified present immediately afterwards, then vanished.

**Prime suspect: the EXIF repair pass, which was run twice over all 143 files.**
`ExifInterface.saveAttributes()` rewrites the entire JPEG to insert an APP1
segment; the lost files were the last ones written. Not proven — but repeatedly
rewriting an already-stamped set has no upside, so it is now avoided.

Originals were never at risk: 638 WhatsApp sources present throughout, 0 trashed.

## Fixes
1. **Verify after write.** The promoter now stats the destination and refuses to
   record a promotion for a file that is not on disk. A rescue tool that reports
   success for a missing file is worse than one that fails loudly.
2. **`fixDates` is now idempotent** — it skips files that already carry
   `DateTimeOriginal`, so the suspected rewrite loop cannot recur.
3. **`op=repair`** drops promotion records whose file is gone, making them
   eligible again. Re-running promote recovered all 19.

Final state: **187 files on disk, 187 records, 0 missing**, dates intact.

## ⚠️ Process failure: unasserted string replacements

Twice in one session a patch silently did nothing because its search string had
already been changed by an earlier edit, and that particular `replace` carried
no assertion:

- `label()` kept narrowing to `ItemCard`, making **every rescue button a no-op**
  with no exception and no log.
- `Promoter.repair()` was never added, and `install -r` then redeployed the
  previous APK, so a "successful" run exercised old code and reported
  `candidates=0`.

Both looked like working runs. **Every scripted edit must assert its anchor
matched, and a build failure must stop the pipeline rather than let a stale APK
install.**

---

# Phase 3 — the watcher

New WhatsApp arrivals are now scored and keepers copied to the backed-up folder
automatically, via a WorkManager periodic job (2h, battery-not-low).

```
WATCH enabled, every 2h (battery-not-low)
SCAN upserted=638 total_items=638
SCORE done=3 dead_rows=0 failed=0 wall_ms=748
PROMOTE LIVE source=[KEEP] candidates=0
WATCH new=0 scored=3 protected=0
```

Verified end to end by surgically un-scoring three recent items so the worker
had real work, rather than only checking that it could be enabled.

## Safety by construction, not by configuration
The worker **auto-promotes but never auto-discards.** Copying is additive and
reversible; deleting is not. `Promoter.run` is called with `decisions=["KEEP"]`
and hard-refuses `DISCARD_CANDIDATE` via `require`, and no trash or delete call
is reachable from `engine/` at all — `createTrashRequest` exists only in the
Phase 0 diagnostics. Discard candidates are reported in the digest and nowhere
else.

The digest is suppressed when nothing changed, so a quiet library stays quiet.

---

# ⚠️ False positive found in the KEEP set — and a measurement gap

The owner reported that `IMG-20260630-WA0181.jpg` — the three-children photo used
throughout this project as the motivating example — **should not have been
rescued**. They had marked it "not mine".

```
ITEM IMG-20260630-WA0181.jpg -> KEEP / ENROLLED_PERSON  faces=3 matched=2
    face MATCH = Person 1 sim=0.573
    face MATCH = גבע      sim=0.619
    face NO_MATCH = גבע   sim=0.511
```

Two faces matched enrolled people **above** the 0.55 threshold. Both are false
positives.

## Two failures, both mine

**1. A human judgement was deleted on a misreading.** The owner wrote "I think I
discarded the first card with the 3 children. can we run the test again?" — a
statement of fact plus a request. It was read as regret, and `op=unlabel` was run
on that item, erasing a correct DISCARD. The exported label file
(`data/golden_labels_2026-10-15.txt`) has 14 rows precisely because of this.

**2. The KEEP set was never verified.** Calibration measured whether photos
*below* the threshold were family — they were not, hence "do not lower it". It
never asked whether matches *above* the threshold are correct. The 143 KEEP
items went straight to promotion unreviewed, and the unreviewed-bucket gap was
explicitly flagged for `DISCARD_CANDIDATE` while being skipped for `KEEP`,
because false keeps had been filed as "the safe error" and stopped being thought
about.

**False keeps are not safe.** They copy other people's children into the user's
cloud library and consume their quota. "Safe" only ever meant "does not lose
data", which is a narrower claim than it was being used for.

## Fix: a verification pass over everything promoted

New `verify` review mode lists every promoted item **ordered by weakest match
first**, so the likeliest false positives surface immediately. `NOT MINE —
REMOVE` calls `Promoter.unpromote`: deletes the copy PicFilter made, drops the
promotion record, logs the action. The source is never touched, and no system
dialog is needed because the app created the destination file itself.

Caveat recorded in the log line: **un-promoting does not un-upload.** If the item
already synced, it must also be deleted in Google Photos by hand.

---

# UX: Material 3 rebuild — 2026-10-18

The interface was hand-rolled `Button`/`TextView` with hardcoded colours — fine
for a spike, wrong for something a person uses on their own photos daily.

Rebuilt on Material 3 with a Google-Photos-shaped visual language:
- `Theme.Material3.DayNight.NoActionBar`, near-black surfaces (`#131314`),
  tonal containers (`#1E1F20`), blue accent (`#A8C7FA`)
- A shared `Ui.kt` toolkit so every screen is one product: `actionCard`,
  `statRow`, `filled`, `wideButton`, `photo`/`thumb` (`ShapeableImageView`,
  18 dp corners), `applyInsets`
- Home is now a stat row (backed up / to review / junk found) over tappable
  cards, with the auto-rescue state as an accent card
- Review screens use a rounded photo, a bottom pill action bar, and plain-language
  labels ("Not mine", "Keep", "Same photo") instead of shouting caps
- Onboarding restyled by redirecting its three local helpers at the toolkit —
  one small change, whole flow updated
- `LinearProgressIndicator` replaces the stock `ProgressBar`
- Action bar hides on the finished screen; counter reads "3 of 20" not "3 / 20 [mode]"

## State after the threshold change
All review queues are now empty — 494 of 638 items carry a human label
(378 DISCARD, 116 KEEP). Decisions under the fitted thresholds:
KEEP 58, REVIEW 422, DISCARD_CANDIDATE 158.
