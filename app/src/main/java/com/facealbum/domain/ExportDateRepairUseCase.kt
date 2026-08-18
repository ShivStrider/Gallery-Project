package com.facealbum.domain

import android.net.Uri
import com.facealbum.data.PhotoRepository
import com.facealbum.data.db.ExportItemEntity
import com.facealbum.data.db.FaceAlbumDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Repairs the capture dates on albums exported before that bug was fixed.
 *
 * ## What was broken
 * Both export insert paths used to build their `ContentValues` with only
 * display name, MIME type, relative path and the pending flag. MediaStore
 * therefore stamped `DATE_TAKEN`/`DATE_MODIFIED` itself, at export time, and
 * gallery apps sort on those columns rather than on EXIF — so a person's
 * whole album jumped to the top of the timeline.
 *
 * **The files were never damaged.** Bytes are copied verbatim, EXIF included.
 * Only the MediaStore *row* was wrong, which is why this is repairable at all
 * and why nothing here rewrites a single byte of image data.
 *
 * ## Where the correct date comes from
 * Two sources, in order of trustworthiness:
 *
 *  1. **The original's MediaStore row**, when it still exists — a copy-mode
 *     export, or a move whose source survived. This is what the export should
 *     have carried across in the first place, so using it reproduces exactly
 *     the state a fixed export would have produced.
 *  2. **EXIF inside the exported file**, when the source row is gone (a move
 *     deleted it). The bytes are byte-for-byte identical to the original, so
 *     the capture time is still there even though the database no longer has
 *     it.
 *
 * If neither yields a date — a screenshot with no EXIF whose source has also
 * been deleted — the item is reported as unrepairable and left alone. Guessing
 * would be worse than the existing wrong date, because a wrong-but-plausible
 * date is harder to notice than one that is obviously "today".
 *
 * ## Safety
 * Every row this touches is app-owned (created by this app inside
 * `Pictures/FaceAlbums/`), so no deletion and no consent dialog is involved,
 * and the operation is idempotent: an item whose dates are already correct is
 * counted and skipped rather than rewritten. Running it twice is a no-op.
 */
class ExportDateRepairUseCase(
    private val db: FaceAlbumDatabase,
    private val photoRepository: PhotoRepository
) {

    /**
     * Outcome tallies. [repaired] plus [alreadyCorrect] plus [unrepairable]
     * plus [failed] equals [examined].
     */
    data class Result(
        val examined: Int = 0,
        val repaired: Int = 0,
        val alreadyCorrect: Int = 0,
        val unrepairable: Int = 0,
        val failed: Int = 0
    ) {
        /** True when there was nothing to look at — no exports have landed. */
        val isEmpty: Boolean get() = examined == 0
    }

    data class Progress(val processed: Int, val total: Int, val repaired: Int)

    suspend fun run(onProgress: suspend (Progress) -> Unit = {}): Result =
        withContext(Dispatchers.IO) {
            val items = db.exportDao().itemsWithLandedDestination()
            if (items.isEmpty()) {
                Timber.i("Export date repair: nothing to examine")
                return@withContext Result()
            }

            var repaired = 0
            var alreadyCorrect = 0
            var unrepairable = 0
            var failed = 0

            items.forEachIndexed { index, item ->
                currentCoroutineContext().ensureActive()
                when (repairItem(item)) {
                    Outcome.REPAIRED -> repaired++
                    Outcome.ALREADY_CORRECT -> alreadyCorrect++
                    Outcome.UNREPAIRABLE -> unrepairable++
                    Outcome.FAILED -> failed++
                }
                val processed = index + 1
                if (processed % 25 == 0 || processed == items.size) {
                    onProgress(Progress(processed, items.size, repaired))
                }
            }

            val result = Result(
                examined = items.size,
                repaired = repaired,
                alreadyCorrect = alreadyCorrect,
                unrepairable = unrepairable,
                failed = failed
            )
            Timber.i(
                "Export date repair: examined=${result.examined} repaired=${result.repaired} " +
                    "alreadyCorrect=${result.alreadyCorrect} unrepairable=${result.unrepairable} " +
                    "failed=${result.failed}"
            )
            result
        }

    private enum class Outcome { REPAIRED, ALREADY_CORRECT, UNREPAIRABLE, FAILED }

    private suspend fun repairItem(item: ExportItemEntity): Outcome {
        val destUri = item.destUri?.let(Uri::parse) ?: return Outcome.UNREPAIRABLE

        val correct = resolveCorrectDates(item) ?: return Outcome.UNREPAIRABLE

        val current = photoRepository.queryDates(destUri)
        if (matches(current, correct)) return Outcome.ALREADY_CORRECT

        return try {
            if (photoRepository.updateMediaDates(destUri, correct)) {
                Outcome.REPAIRED
            } else {
                // No row updated: the destination was removed outside the app
                // since the export log was written.
                Outcome.UNREPAIRABLE
            }
        } catch (e: SecurityException) {
            // Should not happen for app-owned rows, but a failed repair must
            // never take the whole pass down with it.
            Timber.w(e, "Date repair rejected for export item ${item.id}")
            Outcome.FAILED
        }
    }

    /**
     * Prefer the original row; fall back to the exported file's own EXIF.
     *
     * A source row that exists but carries no usable dateTaken is treated as
     * no answer, so EXIF still gets its turn — the source row having been
     * stamped by this same bug is exactly the case worth falling through on.
     */
    private suspend fun resolveCorrectDates(item: ExportItemEntity): PhotoRepository.SourceDates? {
        val sourceUri = runCatching { Uri.parse(item.sourceUri) }.getOrNull()
        if (sourceUri != null) {
            val fromSource = photoRepository.queryDates(sourceUri)
            if ((fromSource.dateTakenMs ?: 0L) > 0L) return fromSource
        }

        val destUri = item.destUri?.let(Uri::parse) ?: return null
        val exifMs = photoRepository.readExifDateTakenMs(destUri)?.takeIf { it > 0L } ?: return null
        return PhotoRepository.SourceDates(
            dateTakenMs = exifMs,
            // DATE_MODIFIED is seconds; DATE_TAKEN is milliseconds. Deriving
            // one from the other is the single easiest way to reintroduce the
            // original bug in mirror image, so the conversion is explicit.
            dateModifiedSec = exifMs / MILLIS_PER_SECOND
        )
    }

    private fun matches(
        current: PhotoRepository.SourceDates,
        correct: PhotoRepository.SourceDates
    ): Boolean {
        val takenMatches = correct.dateTakenMs == null || current.dateTakenMs == correct.dateTakenMs
        val modifiedMatches =
            correct.dateModifiedSec == null || current.dateModifiedSec == correct.dateModifiedSec
        return takenMatches && modifiedMatches
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
