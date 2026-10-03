package org.vestifeed.og

import org.vestifeed.db.table.ConfTable
import org.vestifeed.db.table.EntryTable

/**
 * The pure planning half of the OpenGraph image fetcher.
 *
 * The network, Coil and Android-connectivity parts stay in the app module;
 * everything here is a side-effect-free decision so it can be tested on the
 * JVM and shared with the web target.
 */
object OgImagePlanning {

    /**
     * Three-state resolution: an explicit per-feed value wins over the global
     * value; `null` means "follow settings" and falls back to the global value.
     * Mirrors the render-side `resolveShowImage` so the gating decision and the
     * render decision can never drift apart.
     */
    fun shouldFetchOgImage(perFeed: Boolean?, global: Boolean): Boolean = when (perFeed) {
        true -> true
        false -> false
        null -> global
    }

    /**
     * Pure gating decision. Given whether the device is online and whether the
     * app is in the foreground, decides whether the fetcher should
     * short-circuit without consulting the DB at all.
     */
    internal fun ogRunSkip(isOnline: Boolean, isForeground: Boolean): OgRunSkip =
        when {
            !isOnline -> OgRunSkip.Offline
            !isForeground -> OgRunSkip.NotForeground
            else -> OgRunSkip.No
        }

    /**
     * Pure gating decision. The SQL query already filtered out rows whose feed
     * explicitly hides preview images, so by the time we reach this function
     * every remaining candidate is eligible if the global toggle is on.
     */
    internal fun planOgImageFetch(
        conf: ConfTable.Conf,
        candidates: List<EntryTable.OgImageCandidate>,
    ): OgFetchPlan {
        if (!shouldFetchOgImage(perFeed = null, global = conf.showPreviewImages)) {
            return OgFetchPlan.GlobalOff
        }
        if (candidates.isEmpty()) {
            return OgFetchPlan.Empty
        }
        return OgFetchPlan.Fetch(candidates)
    }
}

/**
 * Short-circuit outcome produced by [OgImagePlanning.ogRunSkip].
 *
 * - [Offline]: device has no validated network — sleep, do nothing.
 * - [NotForeground]: app is in the background — sleep, do nothing.
 * - [No]: both gates pass — proceed with the normal planOgImageFetch flow.
 */
internal enum class OgRunSkip {
    Offline,
    NotForeground,
    No,
}

/**
 * What the fetcher should do on the next iteration. Pure value type; produced
 * by [OgImagePlanning.planOgImageFetch] and consumed by the loop body.
 *
 * - [GlobalOff]: global toggle is off; sleep, do nothing.
 * - [Empty]: global on but no unchecked entries; sleep, do nothing.
 * - [Fetch]: at least one candidate passed both gates.
 */
internal sealed interface OgFetchPlan {
    object GlobalOff : OgFetchPlan
    object Empty : OgFetchPlan
    data class Fetch(val candidates: List<EntryTable.OgImageCandidate>) : OgFetchPlan
}
