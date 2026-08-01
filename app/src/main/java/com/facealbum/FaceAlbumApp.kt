package com.facealbum

import android.app.Application
import com.facealbum.work.FaceIndexWorker
import com.facealbum.telemetry.CrashReporter
import timber.log.Timber

/**
 * Application class for FaceAlbum app.
 * Initializes Timber logging in debug builds.
 */
class FaceAlbumApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Plant Timber debug tree for debug builds
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        CrashReporter.initialize(isInternalBuild = BuildConfig.DEBUG)

        // WorkManager auto-init can fail under unit-test classloaders. Don't let
        // background scheduling block app startup.
        try {
            FaceIndexWorker.enqueuePeriodic(this)
        } catch (t: Throwable) {
            Timber.w(t, "Periodic indexer enqueue failed; manual scan still available")
        }

        Timber.d("FaceAlbum application initialized")
    }
}
