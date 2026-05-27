# FaceAlbum — Play Store Assets

> **Status:** Template ready. Replace placeholder text and attach final screenshots before Play Console submission.

## App identity

| Field | Value |
|-------|-------|
| App name | FaceAlbum |
| Package name | com.facealbum |
| Category | Photography |
| Content rating | Everyone |

## Short description (max 80 chars)

```
Group photos by face — private, on-device, no cloud.
```

## Full description (max 4 000 chars)

```
FaceAlbum scans your photo library, detects every face, and automatically groups them so each person appears as their own album — exactly like the "People" view in Google Photos, but 100 % on your device.

✦ Whole-library scan — processes every photo in your gallery, not just recent ones.
✦ Automatic face grouping — faces are clustered by similarity; each cluster becomes a "person" tile.
✦ Name people — tap a cluster and give it a name. The name persists across rescans.
✦ Export albums — copy all photos of one person into Pictures/FaceAlbums/<name>/, instantly visible in Google Photos, Files, and any gallery app.
✦ Incremental rescans — only new or changed photos are processed after the first scan.
✦ Merge clusters — if the same person ends up in two groups, merge them with one tap.
✦ Move photos — long-press any photo to reassign it to the correct person.
✦ Select & export — multi-select specific photos for a partial export.
✦ Fully offline — no internet permission, no accounts, no analytics.
✦ Material You — dynamic colours on Android 12+, full dark-mode support, edge-to-edge.

Privacy first: face detection, embedding, and clustering all happen on your device. No photo data ever leaves it.

Powered by Google ML Kit face detection and MobileFaceNet embeddings.
```

## Screenshots required (Play Console)

Capture these on a Pixel device (or emulator) at 1080×1920 px minimum:

| # | Screen | Content |
|---|--------|---------|
| 1 | People grid | 6–8 named cluster tiles visible |
| 2 | Cluster detail | Hero image + photo grid for a named person |
| 3 | Export complete | "Album exported" confirmation screen |
| 4 | Settings | All sliders visible; theme row showing three chips |
| 5 | Welcome/onboarding | Permission request screen |

## Feature graphic (1024 × 500 px)

Dark background. App icon centred. Tagline below: **"Group faces. Keep privacy."**

## App icon

- Round icon: 512 × 512 px, background matches primary brand colour.
- Adaptive icon layers: `mipmap-*/ic_launcher_foreground.png` + `mipmap-*/ic_launcher_background.xml`.
- See `app/src/main/res/mipmap-*/README_ICONS.txt` for placeholder instructions.

## What's new (first release)

```
Initial release.
• Automatic face clustering from your full photo library.
• Name people, export albums, merge clusters.
• Runs entirely on-device — no cloud, no accounts.
```

## Data safety (Play Console form)

Answers are pre-filled in docs/release/compliance.md § 2. Complete the form using those answers and obtain sign-off before submission.
