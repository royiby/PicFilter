# PicFilter — Development Plan

Stop school-WhatsApp-group junk from entering the photo library, and flag the
existing backlog for deletion.

Revised 2026-10-15: **native Android app, on-device, no Photos API.**
Supersedes the earlier Mac + Syncthing design.

---

## 1. Constraints

### Google Photos API is useless for this (unchanged by going native)

| Block | Detail |
|---|---|
| No library read | `photoslibrary.readonly`, `.sharing`, `photoslibrary` removed 2025-03-31 → `403 PERMISSION_DENIED`. |
| No delete | No Google Photos API has ever had a delete-media-item method. |
| No face data | Face groups are UI-only. No API exposes people clusters. |

Surviving: `appendonly`, `readonly.appcreateddata`, `edit.appcreateddata`.
The Picker API is a file-picker, not a library API.

### WhatsApp has no API for a personal account

There is no "connect to your WhatsApp." The Cloud API / Business API is a
separate product bound to a **business** phone number, delivering *that* number's
messages over webhooks. Personal chats are not exposed, and unofficial libraries
that scrape a personal session risk a number ban.

**But we don't need an API.** On Android, WhatsApp *writes media to disk* and
MediaStore indexes it. The filesystem is the integration point:

```
/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/
                                                 /WhatsApp Video/
```

Readable via `READ_MEDIA_IMAGES` + a MediaStore query — no
`MANAGE_EXTERNAL_STORAGE`, no root, no ToS problem. Incoming files are named
`IMG-YYYYMMDD-WA####.jpg`, which is itself a useful signal.

### What Android *does* unlock: real batched deletion

`MediaStore.createTrashRequest(resolver, uris, true)` (API 30+) returns a
PendingIntent that trashes a batch of media **we don't own** behind a *single*
system confirmation dialog. Up to 2000 URIs per request on recent targets.
`createDeleteRequest` does the same for permanent deletion.

This is the official, sanctioned path — no UI automation, no ToS violation. It
only covers media **on the device**, not cloud-only items.

### Net conclusion

PicFilter needs **no Google account and no network** for its main job.
Google login was only ever needed to upload, and there's a simpler way to do
that (§3). The Photos API drops out of the design entirely.

---

## 2. Two requirement refinements (both improvements)

**"People you define" beats clustering.** The earlier plan ran HDBSCAN over the
whole library to discover the top 10 faces. Explicit enrollment — user picks 5–10
photos per family member, app stores one centroid each — is simpler, more
accurate, needs no bootstrap corpus, and matches the mental model. Drop
unsupervised clustering from v1.

**"Many faces, none of them defined" is a much better rule than "no defined
face."** This is the key refinement. The weak rule flags landscapes, kids'
artwork, receipts, plane tickets, and pet photos — all zero-face, all often
deliberate keeps. Requiring a *crowd* excludes every one of those, and lands
exactly on the real target: a stranger's-kids class photo forwarded to 40 parents.

```
face_count >= 3  AND  matched_enrolled == 0   →  high-confidence discard
face_count >= 1  AND  matched_enrolled == 0   →  review
face_count == 0                                →  review (weak signal, many TPs)
matched_enrolled >= 1                          →  KEEP, never proposed
```

---

## 3. Architecture

```
┌─ Android (Kotlin, minSdk 30) ───────────────────────────────┐
│  ContentObserver + WorkManager                              │
│        │ new items in WhatsApp folders                      │
│        ▼                                                    │
│  ML Kit face detect ──▶ MobileFaceNet TFLite embed           │
│        │                      │                             │
│        │              Room DB: people, centroids, items,     │
│        │                       scores, decisions, actions    │
│        ▼                                                    │
│  score ──▶ KEEP ───────▶ copy to /Pictures/PicFilter/        │
│        │                 (Google Photos backs up this folder)│
│        └──▶ DISCARD ───▶ nightly MediaStore.createTrashRequest│
│                          (one dialog per batch)              │
│  Review UI: notification ──▶ swipe grid                      │
└─────────────────────────────────────────────────────────────┘
          Google Photos backup for WhatsApp folders: OFF
          Google Photos backup for Camera + PicFilter:  ON
```

**The gatekeeper trick.** Turn off Google Photos backup for the WhatsApp device
folders; turn it on for a new `/sdcard/Pictures/PicFilter/`. The app's only job
is deciding what gets copied into that folder. Default-deny, and **no OAuth, no
`appendonly` upload, no network, no token expiry.** One-time user setup in the
Google Photos app.

### Transition policy — DECIDED 2026-10-15: both backups on

Flipping WhatsApp backup off before the promoter is proven would mean genuine
keepers stop reaching the cloud with nothing yet carrying them across. So during
transition **both** stay backed up: WhatsApp folders *and* `Pictures/PicFilter`.
No irreplaceable photo ever depends on unproven code.

Consequences to hold in mind:
- **Junk keeps reaching the cloud until the flip.** During transition PicFilter
  reduces *local* storage only; it cannot prevent cloud pollution. Local trashing
  does not remove an already-uploaded cloud copy. The filter only becomes a
  gatekeeper at the moment WhatsApp backup goes off.
- **Possible cloud duplication — verify in Phase 3.** Google Photos dedupes
  byte-identical uploads, so a bit-for-bit copy into `Pictures/PicFilter` may be
  recognised as the same item and not double-count against quota. Unverified:
  per-device-folder backup may treat the two paths as distinct items. If it does
  duplicate, every keeper costs 2× quota during transition. Measure before
  letting the promoter run at volume.
- **Exit criterion** (otherwise "once proven" stays vague): flip WhatsApp backup
  off after the promoter has run for 14 consecutive days with **zero keepers
  missed**, judged by reviewing everything it discarded, not only what it kept.
  Until then, treat the transition as permanent.

Writing into `Pictures/PicFilter/` needs no special permission — MediaStore
grants an app free rein over entries it creates.

*Fallback:* if per-folder backup proves unreliable, add an `appendonly` uploader.
Note the trap — an OAuth app left in **Testing** publishing status expires refresh
tokens after 7 days, which breaks an unattended background job. Avoiding this is
a real reason to prefer the folder approach.

### Enrolment: cluster-then-name (onboarding)

Enrolment is **not** a folder convention — that was a dev shortcut. On first run
the app finds the recurring faces in the user's own library and asks which ones
matter. The measurement from SPIKES.md *is* the product: clustering 953 faces
produced a clear recurring-people head (92,48,46,45,42,38,34) over a long
singleton tail, with the top 10 clusters covering 43% of all faces.

```
install -> permissions -> sample ~600 recent images (~4 min, measured)
        -> detect + embed under the STRICT gate -> cluster -> rank by size
        -> show top 15 candidates, each a montage of up to 6 crops
        -> [name] / [not important] / [these aren't the same person]
        -> named clusters' centroids become enrolled people
        -> warn about unclaimed recurring faces
```

**Enrolment uses a stricter gate than scoring.** Scoring is biased to recall —
every face must be judged, and an unjudgeable one becomes INDETERMINATE and
forces review. Enrolment is biased to precision, because centroids are only as
good as the faces behind them, and admitting small faces is exactly what
produced the 205-face garbage cluster.

| | min face (original px) | max pose |
|---|---|---|
| scoring | 40 | 45° |
| enrolment | 80 | 30° |

Other decisions:
- **Sample the whole library, not just WhatsApp** — a user's own camera photos
  are far better enrolment material than forwarded group shots.
- **Montage, not a single face**, so the user can see the cluster is coherent
  before naming it. `These aren't the same person` is a first-class button —
  clusters *are* sometimes wrong.
- **Names are optional** (`Person 3` otherwise). The algorithm only needs
  identity grouping; removing typing friction from the step that gates
  everything else is worth more than tidy names.
- **No hard cap on people.** Show 15 candidates, suggest ~10. Matching is
  O(people) per face, so extra enrolments are nearly free — and the error
  direction is safe: more enrolled people means more false KEEPs, fewer means
  more false discards.
- **Two centroids per person** when a cluster is big enough (main + the most
  distinct third). Matching takes the max across a person's set, so extra
  vectors only help recall — and this is the hook for re-enrolment as children's
  faces drift.
- Singleton clusters are **stored but not offered** as candidates — dropping
  them at write time eroded the face set on every regroup.
- **Cluster threshold 0.65**, not the spike's 0.55. At 0.55 the largest cluster
  held 20% of all faces on a real library. The errors are asymmetric: over-split
  is recoverable (name the clusters identically; centroids merge under one
  person), over-merge permanently poisons a centroid. Bias high. See SPIKES.md.
- Clustering can be redone from stored embeddings without re-scanning, so
  threshold work costs seconds rather than the 4-minute extraction.

**The skipped-person warning is a safety feature, not polish.** If someone
enrols 3 people but has 6 in the family, photos of the other 3 become discard
candidates — that is exactly the ice-cream photo. The final screen therefore
shows every unclaimed face appearing in 5+ photos and says plainly that photos
of them can be suggested for deletion.

### On-device ML
- **Detect:** ML Kit Face Detection (bundled model). Gives boxes, landmarks,
  head angles, and a smile/open-eyes signal. Detection only — no recognition.
- **Embed:** MobileFaceNet TFLite (5.2 MB, 112×112 input), **192-d output, input
  batch fixed at 2** — it was published for pairwise comparison and `resizeInput`
  throws, so duplicate the face into both slots and read slot 0. Measured at
  13 ms/face. Match by cosine distance to enrolled centroids. See
  [models/PROVENANCE.md](models/PROVENANCE.md) for licensing caveats.
- **Near-dupes:** dHash bucketing + embedding cosine within bucket; union-find.
  Keep best by `resolution × sharpness × filesize`.
- **Screenshots/memes:** **text density only** (ML Kit Text Recognition).
  EXIF `Make`/`Model` and aspect-ratio heuristics were measured and *rejected* —
  WhatsApp strips EXIF from ~91% of its media, so "no EXIF" carries no signal.
  See SPIKES.md.
- **Blur:** variance of Laplacian, normalized by face-box area.

### Three-state matching (measured in spike 3c)

Small faces are what class photos are made of, and admitting them produces a
single 205-face garbage cluster — tiny blurry faces all look alike to a 192-d
model. So matching never forces a binary answer:

```
match | no-match | INDETERMINATE  (face fails the quality bar)
```

- Indeterminate faces are **never matched and never clustered** — clustering them
  is what produced the garbage blob.
- **Any image holding an indeterminate face can never be auto-discarded**; it
  goes to review. This closes the "your kid was in the class photo but too small
  to recognise" failure mode *by construction* rather than by threshold tuning.
- Quality bar comes free from ML Kit: face box size in original pixels, head
  Euler angles, plus a Laplacian sharpness check on the crop.

**Gate face size in original-image pixels, never decoded pixels.** An absolute
decoded-pixel gate is not scale-invariant — raising decode resolution from 1000
to 2200 px silently relaxed it by 2.2×, which is how the garbage cluster appeared.

**Gate at 40–50 px (original), measured — but the size clause is not the binding
constraint.** The incidence curve over 1,409 real faces (SPIKES.md 3d) showed the
*size* gate alone blocks 40.5% of crowd images. The full shipped quality bar also
requires `sharpness >= 12.0` and `|yaw|,|roll| <= 45°`, both **guesses**. A
36-config sweep (SPIKES.md) isolated their effects:

- **`MIN_SHARPNESS` does nothing** — sweeping 0→25 moves 2 faces out of 1,409.
  Delete it; it is dead weight pretending to be a safety control.
- **The pose limit dominates.** At 45° indeterminate faces are 40.7% and
  `CROWD_NO_MATCH` collapses to 37 images; at 60° it is 62; with pose inert it is
  89 and indeterminate drops to 18.7% — reconciling spike 3d's size-only 18.5%
  exactly.

**Do not simply relax pose for coverage.** Rejecting a high-yaw face is *safe*
(forces review); admitting it risks a profile shot of your own child failing
`MATCH_THRESHOLD`, scoring NO_MATCH, and turning a crowd photo into a discard
candidate — the actual loss path. Resolving this needs labels: measure whether
high-yaw faces of enrolled people match. Until then keep pose strict, accept that
the crowd rule is mostly disabled, and take the value from `TEXT_NO_FACE`
(129 images, **invariant across all 36 configs**).

Longer term, pose should **modulate match confidence rather than gate the face
out** — a profile still carries identity, and pre-rejecting converts a
recognition problem into a review burden. Per-image incidence runs far below the
per-face rate (21% of images vs 38% of faces at 60 px) because small faces
concentrate in a few crowded photos, and only 3.7% of crowd photos are
*entirely* indeterminate — nearly all contain at least one large face.

Note the earlier "32% of faces skipped" conflated the size gate with
`align()`/eye-distance failures. Purely size-driven loss is **18.5%** at 40 px;
the alignment path is dropping the rest and is probably recoverable.

---

## 4. Safety

**Asymmetric target: precision on DISCARD must be near-perfect; recall may be
mediocre.** Missing junk costs nothing. Trashing a real memory does not.

- Auto-*keep* is allowed. Auto-*discard* ships **disabled**, and is only enabled
  per-class once the golden set proves it.
- Trash, never delete — `createTrashRequest`, which leaves a 30-day recovery
  window, plus our own action log in Room. Never `createDeleteRequest` in v1.
- Protected, never proposed: any enrolled match at any confidence, favorited,
  in an album, edited, sent-by-me (`/Sent/` subfolder), video over N seconds.
- **Golden set:** hand-label ~200 WhatsApp items before trusting a threshold.
  Track precision/recall per class, per release.

### Two failure modes specific to this use case
1. **Your kid is in the class photo but missed** — small, turned, or blurry face
   → scored as crowd junk. Mitigate: bias the *enrolled-match* threshold toward
   recall, impose a minimum face size before a face counts toward `face_count`,
   and keep crowd shots review-only until measured.
2. **Children's faces drift fast.** Face recognition is measurably weaker on
   young kids, and a 6-year-old's centroid degrades within a year. Mitigate:
   prompt for re-enrollment periodically, and store multiple centroids per person
   with capture dates rather than one averaged vector.

---

## 5. Phases

### Phase 0 — Spikes
Results in [SPIKES.md](SPIKES.md). Run on emulator, **Android 17 / SDK 37**.
- [x] MediaStore surfaces `Android/media/com.whatsapp/...` with only
      `READ_MEDIA_IMAGES` — **PASS**, 6/6 visible and byte-readable, auto-indexed.
- [x] `createTrashRequest` batch → **PASS**, one dialog for 6 items, fully
      reversible via untrash.
- [x] *(added)* Gatekeeper write to `Pictures/PicFilter` — **PASS**, no extra
      permission, and own-entry delete needs no dialog.
- [x] Re-validated on real hardware (Samsung SM-S948B, Android 16) —
      **85% of on-device images (609/714) are WhatsApp**. Premise confirmed.
- [x] Face-count throughput — **PASS**, 14.8 img/s, 65 ms/image. Non-issue.
      Distribution: 38% zero-face, 37% one-or-two, **24.6% crowd (3+)**.
- [~] Junk composition — text density **works** (17% of volume); EXIF/aspect
      screenshot detection **FAILED** (WhatsApp strips EXIF from ~91% of media).
- [x] Recognition pipeline **verified on device**. 953 faces embedded from 609
      images, 13 ms/face, ~12 faces/s. Clustering gives a clear recurring-people
      head (92,48,46,45,42,38,34) over a long singleton tail; **top 10 clusters
      = 43% of all faces**. The "top 10 faces" premise holds, found label-free.
- [ ] **Labeled** accuracy per person (esp. children) — still needs Takeout.
      Clustering proves embeddings are *meaningful*, not that they are *correct*;
      over-split clusters would be invisible in that view.
- [ ] Google Photos per-folder backup toggle (WhatsApp off, `Pictures/PicFilter`
      on). Needs a real account — manual steps in SPIKES.md.

Two requirements that came out of the spikes:
- **Detect Android 14+ partial media grants** (`READ_MEDIA_VISUAL_USER_SELECTED`)
  and refuse to run. "Select photos" makes the scanner silently blind.
- **Never trust a MediaStore row to mean the bytes exist** — rows outlive deleted
  files. Catch `FileNotFoundException` and treat it as a dead row.
- Chunk trash requests at **2000 URIs** max per call.

### Phase 1 — Enrolment + scorer
Room schema, enrollment flow (pick photos → centroids), detect/embed/match,
scoring, debug screen showing per-item signals. No actions yet.

### Phase 2 — Review UI + golden set
Swipe grid tiered by class, keep/discard/undo, near-dupe groups side by side.
Hand-label the golden set here. Report precision/recall.

### Phase 3 — Automation  ← *first shipped value*
ContentObserver + WorkManager, copy keepers to `Pictures/PicFilter/`, nightly
batched trash request, daily digest notification. Backup toggles documented as
one-time setup.

### Phase 4 — Backlog
Two routes, use both:
- **On-device:** anything still local gets the same scorer + batched trash.
  Covers recent camera roll; does not cover cloud-only items.
- **Cloud-only:** Google Takeout → Python engine on the Mac → reviewed delete
  list, grouped by contiguous date ranges so shift-click range-select in the
  Photos web UI is tractable.
  **Use the same MobileFaceNet model on both sides** so enrolled centroids
  transfer; embeddings from different models are not comparable.

Honest framing: cloud-only deletion stays manual. Google provides no API for it.
Realistic win is a couple of hours instead of twenty — not one click.

### Phase 5 — Hardening
Battery/thermal tuning, multi-centroid-per-person, re-enrollment prompts,
threshold tuning against the golden set, storage-reclaimed reporting.

---

## 6. Rejected

- **Google Photos API for reading/deleting.** Closed surface. See §1.
- **WhatsApp API.** Doesn't exist for personal accounts; scrapers risk a ban.
- **Unsupervised top-10 clustering in v1.** Superseded by explicit enrollment.
- **Browser extension driving photos.google.com.** The only route to cloud bulk
  deletion, and it could read Google's own people filters — but it automates a UI
  Google's terms prohibit automating, and breaks on every frontend reship.
  Reconsider only if Phase 4's manual grind proves intolerable.
- **`MANAGE_EXTERNAL_STORAGE`.** Not needed; `READ_MEDIA_IMAGES` + MediaStore
  covers it, and Play Store review treats it as a red flag.
- **`MANAGE_MEDIA`** (silent trashing, no per-batch dialog). Deliberately
  deferred — the confirmation dialog is a safety feature while precision is
  unproven. Revisit in Phase 5.
