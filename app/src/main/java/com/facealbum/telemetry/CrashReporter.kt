package com.facealbum.telemetry

import timber.log.Timber

/**
 * Local-only failure reporter. Records non-fatal errors to the on-device log
 * (Timber) and nothing else — no network, no third-party SDK, no identifiers.
 *
 * The API is intentionally the same shape as the previous Crashlytics-backed
 * implementation so call sites across the pipeline stay untouched. Callers
 * must never pass photo URIs, filenames, album/person names, or embeddings in
 * [recordNonFatal]'s context map — stick to stable IDs and error enum names.
 */
object CrashReporter {

    fun initialize(isInternalBuild: Boolean) {
        Timber.i("CrashReporter initialized: internal=%s (local-only reporting)", isInternalBuild)
    }

    fun recordNonFatal(
        throwable: Throwable,
        source: String,
        context: Map<String, String> = emptyMap()
    ) {
        Timber.w(throwable, "Non-fatal [%s] %s", source, context)
    }
}
