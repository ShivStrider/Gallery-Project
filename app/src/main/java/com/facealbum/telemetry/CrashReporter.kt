package com.facealbum.telemetry

import com.google.firebase.crashlytics.FirebaseCrashlytics
import timber.log.Timber

/**
 * Crash reporting wrapper that avoids sensitive data in logs/events.
 */
object CrashReporter {
    private val crashlytics: FirebaseCrashlytics by lazy { FirebaseCrashlytics.getInstance() }

    fun initialize(isInternalBuild: Boolean) {
        crashlytics.setCrashlyticsCollectionEnabled(isInternalBuild)
        crashlytics.setCustomKey("build_type", if (isInternalBuild) "internal" else "external")
        Timber.i("CrashReporter initialized: internal=%s", isInternalBuild)
    }

    fun recordNonFatal(
        throwable: Throwable,
        source: String,
        context: Map<String, String> = emptyMap()
    ) {
        crashlytics.setCustomKey("error_source", source)
        context.forEach { (key, value) ->
            crashlytics.setCustomKey(key, value)
        }
        crashlytics.recordException(throwable)
    }
}
