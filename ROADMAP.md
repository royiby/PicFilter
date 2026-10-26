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

- ~~Rotation and process death~~ — done, `RotationTest`, seven cases, each proven by
  mutation. The lesson worth keeping: `recreate()` keeps the ViewModel, so it cannot
  test `SavedStateHandle` at all; both halves are needed.
- **The home screen's figures** match the database rather than a stale read — the bug
  that started this whole line of work.
- **Partial media grants** (#8). Android 14+ `READ_MEDIA_VISUAL_USER_SELECTED` gives a
  library that looks readable and is not. Nothing tests that the app refuses it.
- **Date preservation** (#5) and **promote/verify under interruption** (#6), both of
  which have lost real files before.

## 4. Break up the god objects — `Db` done, `Ui` left

`Db.kt` was 1,501 lines and is now 170: the class, the schema and eight migrations.
The queries moved into eight files named for what they answer, as extensions on `Db`,
so every call site is unchanged. The row types went to `DbRows.kt` and the two byte
converters to `DbBytes.kt`; neither ever belonged to a SQLiteOpenHelper.

| | lines |
|---|---|
| `Db.kt` (class, schema, migrations) | 170 |
| `DbPromotion.kt` | 357 |
| `DbScreens.kt` | 307 |
| `DbClusters.kt` | 208 |
| `DbLabels.kt` / `DbScoring.kt` / `DbPeople.kt` / `DbMeta.kt` / `DbItems.kt` | 69–127 |

**And the repositories are done too.** Each screen now depends on an interface only
as wide as it needs: `PersonRepo` is two methods, `OnboardingRepo` four, `ReviewRepo`
eleven, `HomeRepo` fifteen. `PersonRepoTest` drives the person screen with a
twelve-line fake and no SQLite anywhere — its `openDb()` throws, so the test passing
is the assertion that the screen never reaches past its interface.

`Ui.kt` is done too: 950 lines down to 158, holding the tokens — spacing, radius,
sizes, colour roles, the type scale and the three nested types. The components moved
into seven files named for what they build. Same package as every caller, so unlike
the Db split not one import changed anywhere.

| | lines |
|---|---|
| `Ui.kt` (tokens and nested types) | 158 |
| `UiCards.kt` | 288 |
| `UiPeople.kt` | 191 |
| `UiScaffold.kt` / `UiStates.kt` | 156–157 |
| `UiButtons.kt` / `UiPhotos.kt` / `UiText.kt` | 48–106 |

The cost worth knowing: extensions in another package need importing, so callers
carry `import com.picfilter.spike.data.*`. A wildcard for a package that is entirely
this app's own is a fair trade for not touching 24 files' worth of call sites.

## 5. Compose — prototyped, and the numbers argue against a port

`PersonActivity` is Compose now, the only screen that is, kept as a measured
comparison. See *Compose, measured on one screen* in TESTING.md. The short version:

| | Views | Compose |
|---|---|---|
| lines for this screen | 125 | 286 (45 + 185 + 56 theme) |
| release APK | 51 MB | 67 MB |
| test API | view-tree walk | semantics tree, and a forced Espresso bump |

**Compose came out longer, not shorter**, because the View screen reuses a design
system that already exists — `Ui.titleBar`, `PhotoGridAdapter`, `GridGaps` — and the
Compose one could reuse none of it. That is the honest shape of a first port: the
saving arrives on screen number five, after the second design system is built.

So the recommendation is to **keep the Views and keep this screen**, which is worth
more as an answer than as an architecture. 16 MB and a parallel design system is a
real price for an app whose pitch is a free download, and "I ported one screen and
measured it" is a stronger interview answer than either dogma.

## 6. R8 — done

On, with keep rules in `proguard-rules.pro`, and the embedding equality check re-run
against a minified build first: the corpus fingerprint, the five re-scored
similarities and the whole `face` table hash are all unchanged.

**Release APK 67.0 MB → 38.5 MB**, which is smaller than the 51 MB this app shipped
before the Compose screen — so R8 paid for Compose twice over.

Not verified: a signed release on a phone. That needs the developer's key.

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
