package com.facealbum

import android.app.Application
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

        Timber.d("FaceAlbum application initialized")
    }
}
