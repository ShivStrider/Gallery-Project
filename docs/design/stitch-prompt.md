# Google Stitch prompt — FaceAlbum UI redesign

Paste **Section A** first (it sets context Stitch keeps for the session), then
one screen prompt from **Section B** at a time. Section C is the honest list of
what is weak today, so a redesign fixes real problems rather than restyling
around them.

Everything here was read off the source at the time of writing, not
recalled — file paths are given so you can check any claim.

---

## Section A — master context prompt

> Design a mobile app UI for **FaceAlbum**, a privacy-first Android photo app
> that groups a person's photos together on-device and exports one person's
> photos into their own album folder. Nothing ever leaves the phone: no
> accounts, no cloud, no internet permission at all.
>
> **Platform and system:** Android, Jetpack Compose with Material 3 (Material
> You). Must work in both light and dark themes, and support dynamic color on
> Android 12+. Design for a 360dp-wide phone as the baseline — small phones are
> a first-class target, not an afterthought.
>
> **Existing palette to stay compatible with** (Material 3 roles):
> - Primary `#6750A4` (warm purple), primary container `#EADDFF`
> - Secondary `#CC6F5C` (coral accent), secondary container `#FFDBD0`
> - Tertiary `#7D5260`, background/surface `#FFFBFF`, surface variant `#E7E0EC`
> - Error `#BA1A1A`
> - Spacing is a strict 4dp grid: 4 / 8 / 16 / 24 / 32 / 48
>
> **Design constraints that come from the product, not taste:**
> - Most pixels on most screens are photo content. Chrome must recede; never
>   compete with thumbnails for attention.
> - Grouping is *probabilistic*. The UI must make it feel safe and normal to
>   correct the app — merging, renaming, and moving a face to another person are
>   primary actions, not buried settings.
> - Export can be destructive (a future "move" mode deletes originals). Any
>   destructive affordance must be visually distinct from a safe one and must
>   never be one tap from a routine action.
> - The app is honest about uncertainty. Low-confidence faces get their own
>   surface rather than being silently dumped into someone's group.
>
> **Tone:** calm, warm, personal — this is someone's family photos, not an
> enterprise dashboard. Confident and quiet, not playful.

---

## Section B — per-screen prompts

### B1. People grid (home) — `ui/screens/PeopleScreen.kt`

> **What exists now:** a large collapsing top app bar titled "People", an
> extended FAB to start a scan, and an adaptive grid of cards (minimum 150dp
> wide) — one card per person, showing a cover face thumbnail and a name.
> Above the grid, up to three stacked banners can appear: a limited-photo-access
> warning with a "manage selection" action, a scan-progress banner with a linear
> progress bar, and an error banner. A separate tile links to a "Review needed"
> screen for low-confidence faces.
>
> **Redesign this screen to solve these specific problems:**
> 1. **There is no search, filter, or sort.** With 50+ people the grid is an
>    unnavigable wall. Add a way to find a person by name and to sort by photo
>    count or most recent.
> 2. **Most people are called "Unnamed."** Naming is the single highest-value
>    action a user can take, and nothing invites it. Design a way to surface
>    unnamed-but-large groups and make naming feel like a quick, satisfying
>    pass rather than a chore.
> 3. **Three banners can stack** and push the content the user came for off
>    screen. Consolidate transient status into something that does not steal the
>    whole viewport.
> 4. The "Review needed" entry point is one tile among many and reads as a
>    person. Give it a distinct treatment.
>
> Show the screen in three states: first run mid-scan, a healthy library with
> ~30 people, and a library where most groups are still unnamed.

### B2. Person detail — `ui/screens/ClusterDetailScreen.kt`

> **What exists now:** a hero header with the person's cover face and name, a
> stats card, a row of actions (Export / Merge / Rename), and a photo grid with
> multi-select. Selecting photos changes Export to "Export N selected."
>
> **Redesign to solve:**
> 1. **The stats card packs four tiles into a single row** — photo count, album
>    size, first seen, latest — each taking an equal fraction of the width. On a
>    360dp screen this is cramped to the point of truncation. Rework the layout
>    so it stays readable when all four are present.
> 2. Export, Merge and Rename sit in one undifferentiated row, though Export is
>    the consequential one and will eventually be able to delete originals.
>    Establish hierarchy.
> 3. Multi-select mode has no dedicated affordance to enter it and no clear
>    exit; selection state lives only in the changed button label.
>
> Show: default state, multi-select active with 12 photos chosen, and a person
> who is still unnamed.

### B3. Photo viewer — `ui/screens/ImageViewerScreen.kt`

> **What exists now:** full-screen black, horizontal swipe between photos,
> pinch-zoom, double-tap zoom, drag-down to dismiss, and a translucent top bar
> showing "3 of 47" with a close button and an info button. The info button
> opens a bottom sheet with file name, capture date, dimensions, file size,
> folder, type and face count.
>
> **Redesign to solve:**
> 1. The top bar carries a position counter and two icons and nothing else.
>    There is no way to act on the photo you are looking at — no reassign this
>    face to another person, no remove from this group, no share.
> 2. The metadata sheet is a flat label/value list. Give it visual structure so
>    size and date are scannable.
>
> Show: viewer with chrome visible, chrome hidden, and the metadata sheet open.

### B4. Export preview and result — `ui/screens/ExportPreviewSheet.kt`, `ExportCompleteScreen.kt`

> **What exists now:** a confirmation bottom sheet before any file is touched,
> showing the exact file count, destination folder, total size, and a flag for
> photos that contain other people. After export, a separate full-screen success
> page with counts and an "Open in gallery" action.
>
> **Redesign to solve:**
> 1. The preview must make "this copies files" versus "this moves and deletes
>    originals" unmistakable at a glance. Design both variants, with the
>    destructive one clearly distinct — this is the highest-risk screen in the
>    app.
> 2. The "contains other people" warning needs to be understandable without
>    explanation.
> 3. A full-screen takeover for a successful copy is heavy. Consider a lighter
>    result treatment that still surfaces the counts and the undo action.
>
> Show: copy preview, move preview (destructive), and the post-export result.

### B5. Settings — `ui/screens/SettingsScreen.kt`

> **What exists now:** a single scrolling list of rows in four sections —
> Clustering (minimum group size, a similarity "strictness" slider, a re-cluster
> action with inline progress), Appearance (light/dark/system), Data (rescan
> everything, fix dates on exported albums, delete all face data), and About.
>
> **Redesign to solve:**
> 1. **Safe, slow, and irreversible actions all look identical.** "Rescan
>    everything" (slow), "Fix dates on exported albums" (safe, idempotent) and
>    "Delete all face data" (irreversible) are the same visual weight in the same
>    list. Separate them by consequence.
> 2. The similarity slider is an abstract number with no feedback. Show the user
>    what raising or lowering it will actually do to their groups.
>
> Show: the full settings screen, and the re-cluster action mid-progress.

### B6. Welcome / permissions — `ui/screens/WelcomeScreen.kt`

> **What exists now:** a title, subtitle, three feature cards (private, group,
> export), and a "Grant access" button. Handles full grant, Android 14 partial
> photo access, and denial with a link to system settings.
>
> **Redesign to solve:** the privacy claim is the product's main differentiator
> and currently reads as one of three equal bullet points. Make "these photos
> never leave your phone" the thing a user remembers. Also design the
> partial-access state, where Android has granted only a hand-picked subset.

---

## Section C — known weaknesses, for reference

Substantiated against the source, so you can weigh which to hand to Stitch.

| # | Issue | Where |
|---|---|---|
| 1 | No search, filter, or sort anywhere in the people grid | `PeopleScreen.kt` |
| 2 | Unnamed groups show a generic "Unnamed" label; nothing drives naming | `PeopleScreen.kt:279`, `strings.xml` `people_unnamed` |
| 3 | Four stat tiles share one row at `weight(1f)` — cramped below ~400dp | `clusterdetail/PersonStatsCard.kt` |
| 4 | Up to three banners stack above the grid | `PeopleScreen.kt` |
| 5 | Export / Merge / Rename are visually equal despite unequal consequence | `clusterdetail/ActionRow.kt` |
| 6 | Destructive and routine settings rows look identical | `SettingsScreen.kt` |
| 7 | Viewer offers no per-photo actions (reassign, remove, share) | `ImageViewerScreen.kt` |
| 8 | Full-screen success page for a routine copy | `ExportCompleteScreen.kt` |
| 9 | "Review needed" is styled as just another person tile | `PeopleScreen.kt:353` |
| 10 | Similarity threshold is a bare number with no preview of its effect | `SettingsScreen.kt` |

### Not a design problem — a code defect found while writing this

`clusterdetail/PersonStatsCard.kt` hardcodes two English labels,
`"First seen"` and `"Latest"`, instead of using `stringResource`. Every other
user-visible string in the app is in `strings.xml`. Worth fixing regardless of
any redesign.

---

## Working notes

- Stitch output is a starting point. This app's theme tokens live in
  `ui/theme/Color.kt`, `Type.kt` and `Spacing.kt`; keep generated designs
  expressible in Material 3 roles so they can be implemented without abandoning
  dynamic color.
- The viewer's gesture handling is deliberately intricate (pager swipe vs pinch
  vs drag-to-dismiss arbitration). Redesigns that add gestures there carry real
  implementation risk — see `docs/release/known-limitations.md`.
- Move/delete export is currently disabled behind a feature flag. Designing its
  screens now is useful; shipping them is gated on separate sign-off.
