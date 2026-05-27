# FaceAlbum Release Compliance Notes

## 1) Public privacy policy URL and behavior alignment

> **Status:** Draft ready for legal review. Replace `<your-domain>` with the actual hosted URL and obtain sign-off before Play Console submission.

Use a publicly accessible privacy policy URL in Play Console (for example, `https://<your-domain>/facealbum/privacy`). The published policy should match current app behavior implemented in code:

- The app reads image metadata and content from device media storage to scan user photos for faces.
- The app performs face detection and embedding locally on the device using ML Kit + bundled TensorFlow Lite model code paths.
- Matching photos can be exported (copied) into `Pictures/FaceAlbums/<AlbumName>` on device storage.
- No network transmission path is implemented for photo data, face embeddings, or user identifiers.
- No account creation, third-party analytics SDK, ads SDK, or cloud backup/upload logic is currently present.

Policy text should explicitly state:

- **Collected data categories** (photos/media files) are accessed only to provide core app functionality.
- **Processing location** is on-device.
- **Sharing** is "not shared with third parties" for current release.
- **Retention**: app does not maintain a remote copy; exported copies remain on user device until user deletes them.
- **User control**: users can deny/revoke photo permissions in Android settings.

## 2) Google Play Data safety (recommended answers for current implementation)

These answers should be validated by legal/product before final submission.

### 2.1 Data collected

- **Personal info:** No
- **Financial info:** No
- **Health and fitness:** No
- **Messages:** No
- **Photos and videos:** **Yes** (photos are accessed for feature operation)
- **Audio files:** No
- **Files and docs (non-photo):** No
- **Calendar / Contacts / App activity / Web browsing / Diagnostics / Device IDs:** No (based on current codebase)

### 2.2 Data shared with third parties

- **Any data shared?** **No** (current release)

### 2.3 Processing and purpose declarations (Photos and videos)

For Photos and videos (accessed from device gallery):

- **Collected:** Yes (in Play form terms; accessed/processed by app)
- **Shared:** No
- **Purpose:** App functionality (face-based matching and export)
- **Ephemeral processing:** On-device during scan session
- **Required or optional:** Required for core feature

### 2.4 Security practices

- Data is processed locally; no transport encryption section is applicable for photo upload because upload is not implemented.
- Users can request deletion by removing exported files and uninstalling app (no server-side storage exists).

## 3) In-app disclosure text consistency

Ensure store listing, onboarding copy, and permission rationale are consistent.

### Current in-app permission rationale

Current string:

- `FaceAlbum needs access to your photos to find pictures of people you select.`

Recommended expanded disclosure (same meaning, clearer compliance language):

- "FaceAlbum needs photo access to scan your on-device images, find matches for selected people, and optionally export matched photos to a local album. Processing happens on your device."

### Feature claims that must stay aligned with implementation

Allowed claims for this release:

- "On-device face matching"
- "No cloud upload in current release"
- "Exports matched photos to Pictures/FaceAlbums"

Avoid claims not currently implemented:

- Real-time camera scanning
- Cross-device sync/cloud backup
- Server-side recognition
- End-to-end encrypted cloud storage

## 4) Data collected/shared matrix

| Data type | Accessed by app | Stored by app backend | Shared with third parties | Purpose | Code path evidence |
|---|---:|---:|---:|---|---|
| Photos/media image content | Yes | No | No | Detect faces, generate embeddings, compare similarity | `PhotoRepository.queryRecentPhotos`, face scan pipeline, `FaceEmbedder.getEmbedding` |
| Photo metadata (ID, display name, date taken) | Yes | No | No | List/retrieve candidate photos for scan flow | `PhotoRepository.queryRecentPhotos` |
| Exported matched photos | Yes (copy action) | No | No | User-requested local album export | `PhotoRepository.copyToAlbum` |
| Face embeddings (numeric vectors) | Derived in memory | No | No | On-device similarity matching | `FaceEmbedder.getEmbedding` |
| Account identifiers / email / phone / advertising ID | No | No | No | N/A | No corresponding collection path found |

## 5) Permission justification

Declared Android permissions:

- `android.permission.READ_MEDIA_IMAGES` (Android 13+)
- `android.permission.READ_EXTERNAL_STORAGE` (maxSdkVersion 32)

Justification:

- Required to read user-selected gallery images so the app can run face detection/matching and let user export matched photos.
- No microphone, location, contacts, SMS, or camera permission is requested in this release.

## 6) Offline / on-device processing claims and limitations

### Supported claim

- Face detection + embedding inference are performed on-device using local model/runtime components.

### Operational limitations to disclose

- Model file must exist in assets for embedding to run; otherwise model-related error state occurs.
- Accuracy depends on image quality, face visibility, and selected seed photos.
- Processing occurs on local device resources (CPU/accelerator); performance varies by hardware.

## 7) Release acceptance checklist

Before shipping, obtain explicit sign-off from legal/product on:

- [ ] Public privacy policy URL content and availability
- [ ] Final Google Play Data safety answers
- [ ] Store listing language vs. actual behavior
- [ ] In-app disclosure and permission rationale wording

Acceptance criteria mapping:

- **Legal/product approval of policy and Data safety form:** tracked by checklist items above.
- **Store listing claims match implemented behavior:** enforce allowed/forbidden claim list above in release review.

## 3) Pre-submission checklist

Before submitting to the Play Store, complete the following:

- [ ] Host the privacy policy at `https://<your-domain>/facealbum/privacy` (static page is fine).
- [ ] Paste the URL into Play Console → App content → Privacy policy.
- [ ] Complete the Data safety form using the pre-filled answers in section 2 above.
- [ ] Obtain internal legal/product sign-off on the policy text.
- [ ] Confirm app version name and versionCode in `app/build.gradle.kts` match the release build.
- [ ] Verify no analytics SDK, ads SDK, or cloud-upload path was added since last review.
