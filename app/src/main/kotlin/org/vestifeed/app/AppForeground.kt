package org.vestifeed.app

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Whether any activity is started, i.e. whether the app is on screen.
 *
 * The OpenGraph fetcher consults [isForeground] so preview images are not
 * downloaded while the app is in the background: they are supplementary, and
 * spending battery and mobile data on them behind the reader's back is not
 * worth it. [onFirstForeground] lets the host start that fetcher only once a UI
 * exists at all, since a process started by the background sync worker has no
 * activity and nothing to draw the images into.
 *
 * The callbacks all arrive on the main thread; [isForeground] is also read from
 * the fetcher's coroutine, hence volatile.
 */
internal class AppForeground(
    private val onFirstForeground: () -> Unit = {},
) : Application.ActivityLifecycleCallbacks {

    @Volatile
    var isForeground: Boolean = false
        private set

    private var startedActivities = 0

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        isForeground = true
        if (startedActivities == 1) onFirstForeground()
    }

    override fun onActivityStopped(activity: Activity) {
        // A configuration change stops the old activity before starting the
        // new one; letting the count go negative would then report "foreground"
        // forever.
        if (startedActivities > 0) startedActivities--
        isForeground = startedActivities > 0
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
