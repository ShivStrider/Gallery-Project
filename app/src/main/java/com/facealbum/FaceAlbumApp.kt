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

        // Internal builds collect crash reports for triage.
        val isInternalBuild = BuildConfig.DEBUG
        CrashReporter.initialize(isInternalBuild)

        // Keep face index warm with periodic incremental scans.
        FaceIndexWorker.enqueuePeriodic(this)

        Timber.d("FaceAlbum application initialized")
    }
}
