# What is left, and in what order

Written after a pass that found a crash on the first-run path, a `ClassCastException`,
a dead toggle, an RTL layout bug, a race that painted a finished-queue tally over a
grid of 113 photos, stale paths rendering blank tiles, a warning that told a user
their family would be deleted, and nine untranslated strings.

**All 129 tests were green through every one of them.** That is the fact this plan is
ordered around. The suite covers the engine — pure arithmetic, SQL, WorkManager — and
until now covered none of the screens, which is where all of those bugs were.

## Done, and why it came first

**A dependency seam.** Every ViewModel called `Db(app)` in its own constructor, so a
screen could only be driven against the user's real photo library — and the one rule
this project will not break is that tap automation never touches those photos.
`Deps` + `PixKinApp.deps` lets a test hand the Activity a database with its own name.
It is a hand-written container, not Hilt: one module, one thing worth injecting, and
the project already avoids annotation processing (raw SQLite rather than Room).

**The first UI test.** `ReviewActionsTest` drives the real review screen against a
fixture queue and asserts that each button writes the row it claims to. That is the
test *What is not covered yet* listed as the highest-value missing one, for a bug that
has actually happened: every rescue button became a no-op when a `when` stopped being
exhaustive, and nothing failed.

## 1. Continuous integration — done

`.github/workflows/ci.yml` runs the static checks, unit tests, lint and a debug
assemble on every push and pull request. `instrumented.yml` runs the device suite
nightly on an emulator, and on demand.

Two things worth knowing about it:

- **The model is not in the repository.** `app/src/main/assets/*.tflite` is gitignored
  and fetched by `scripts/fetch_model.sh`. Verified locally that unit tests, lint and
  `assembleDebug` all succeed without it; anything that needs the weights has to be an
  instrumented test, which does not run in the push workflow.
- **The instrumented suite does not gate merges yet.** It is the slowest and the most
  likely to be flaky on an emulator. Promote it to the push workflow once it has been
  quiet for a couple of weeks.

## 2. Crash reporting — done, except the Firebase project

The app catches its own crashes, scrubs them and keeps them. What is **not** done is
the transport: Crashlytics needs an `app/google-services.json` from a Firebase
project, which needs a Google account. Drop that file in and the Gradle plugins and
the dependency apply themselves; leave it out and the build carries no Google code at
all, which is how CI builds it.

Three decisions worth keeping:

- **Scrubbing covers the reports this app writes, not what Crashlytics captures.**
  Crashlytics has a single collection switch governing both its own handler and
  `recordException`, so there is no configuration where it sends our sanitised copy
  and not the original. The dialog says so in those words rather than promising a
  redaction the library cannot deliver.
- **Opt-in, asked once, and only when a report is actually waiting.** Someone whose
  app never crashes is never asked, and the question is about something that just
  happened to them. A dismissed dialog is not consent — it asks again next time.
- **Scrubbed before it is stored**, not before it is sent. A report sitting in app
  storage with a child's name in it is already the problem.

## 3. Finish the UI tests — two days

With the seam in place these are ordinary now. In value order:

- **Rotation and process death** (`What is not covered yet` #1). `ActivityScenario
  .recreate()` against fixtures: the review queue keeps its position, onboarding keeps
  its step, the person grid keeps its list. Hand-verified once on a device; nothing
  stops it regressing.
- **The home screen's figures** match the database rather than a stale read — the bug
  that started this whole line of work.
- **Partial media grants** (#8). Android 14+ `READ_MEDIA_VISUAL_USER_SELECTED` gives a
  library that looks readable and is not. Nothing tests that the app refuses it.
- **Date preservation** (#5) and **promote/verify under interruption** (#6), both of
  which have lost real files before.

## 4. Break up the god objects — two days

`Db.kt` is 1,501 lines and `Ui.kt` is 950. Reviewers will say so before they say
anything else. `Db` splits cleanly along the lines its own sections already draw —
items and scores, people and faces, queues, promotions — behind a repository
interface per screen, which also narrows what a fake has to implement.

Mechanical, low risk, high signal. Do it after the tests exist, not before.

## 5. Decide about Compose — a day to prototype

Programmatic Views in 2026 is a defensible choice that has to be defended. Porting one
screen — `PersonActivity` is the smallest — answers the question honestly and gives a
real basis for either keeping Views or committing to a port.

## 6. R8 — half a day

Off, because it needs keep rules for TFLite's and ML Kit's reflection and getting them
wrong fails at runtime in a build that compiled cleanly. Turning it on means re-running
the embedding equality check in TESTING.md: the whole-corpus fingerprint must still be
1156 faces, sum 454.099573.

## Blocked, not scheduled

**Model licensing.** MobileFaceNet's weights have no clear commercial licence, and no
indie-tier on-device face SDK exists to replace them. This blocks a Play Store listing
outright; it does not block a free APK from a blog post, which is the current plan.
The repository ships no weights — `fetch_model.sh` pulls them from the MIT-licensed
source with a SHA-256 check — so the problem travels with the binary, not the source.

**Video.** 315 WhatsApp videos are entirely unhandled. Not a gap in tests, a gap in the
product; noted so it is not mistaken for coverage.

## Not worth doing

- **Hilt**, unless the app grows a second module or a second thing to inject.
- **Room**, unless the hand-written migrations start costing more than they save. They
  are tested, and `DbUpgradeTest` runs them against a database shaped like the real one.
- **An app bundle**, until there is a Play Store listing to upload it to.
