package com.facealbum.config

import com.facealbum.domain.ExportPlanner

/**
 * Rollout gate for the move-export feature.
 *
 * Move deletes the user's original files. Per the project's execution rules
 * the app stays copy-only until the destructive-operation suite
 * (`docs/plan/05-safe-export-design.md`, task 6.7) proves — with synthetic
 * files — that unselected files are never touched, that a source survives any
 * verification failure, and that an interrupted run neither loses nor
 * duplicates anything.
 *
 * Flipping [MOVE_ENABLED] to true is that suite's exit criterion, not a
 * convenience switch. The copy path is fully functional meanwhile.
 */
object ExportFeature {

    /** Set to true only when the destructive-operation suite is green in CI. */
    const val MOVE_ENABLED = false

    /**
     * Whether the UI should offer a Copy/Move choice at all: the feature has
     * to be enabled *and* the platform has to support deleting media this app
     * does not own (API 30+, via `MediaStore.createDeleteRequest`).
     */
    fun moveAvailable(): Boolean = MOVE_ENABLED && ExportPlanner.isMoveSupported()
}
