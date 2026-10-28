package com.real3rr0r303yt.fsociety.engine

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime

/** What the runtime's Enhanced Tracking Protection is actually configured to do. */
data class ProtectionStatus(
    val etpLevel: String,
    /** Disconnect "advertising" list — GeckoView's built-in ad blocking. */
    val ads: Boolean,
    /** Analytics + social trackers. */
    val trackers: Boolean,
    val fingerprinters: Boolean,
    val cryptominers: Boolean,
) {
    companion object {
        /** Reads the live settings from the runtime instead of assuming what we asked for. */
        fun of(runtime: GeckoRuntime): ProtectionStatus {
            val cb = runtime.settings.contentBlocking
            val cats = cb.antiTrackingCategories
            val level = when (cb.enhancedTrackingProtectionLevel) {
                ContentBlocking.EtpLevel.STRICT -> "STRICT"
                ContentBlocking.EtpLevel.DEFAULT -> "STANDARD"
                else -> "OFF"
            }
            return ProtectionStatus(
                etpLevel = level,
                ads = cats and ContentBlocking.AntiTracking.AD != 0,
                trackers = cats and (ContentBlocking.AntiTracking.ANALYTIC or ContentBlocking.AntiTracking.SOCIAL) != 0,
                fingerprinters = cats and ContentBlocking.AntiTracking.FINGERPRINTING != 0,
                cryptominers = cats and ContentBlocking.AntiTracking.CRYPTOMINING != 0,
            )
        }
    }
}

/**
 * Session-wide counters of requests ETP blocked, fed by [ContentBlocking.Delegate.onContentBlocked].
 * Only categories and counts are kept and logged — never the blocked URL or the page it was on.
 */
object BlockStats {
    private const val TAG = "fsociety/Block"

    var ads by mutableIntStateOf(0)
        private set
    var trackers by mutableIntStateOf(0)
        private set
    var other by mutableIntStateOf(0)
        private set
    val total: Int get() = ads + trackers + other

    fun record(event: ContentBlocking.BlockEvent) {
        val cat = event.antiTrackingCategory
        val label = when {
            cat and ContentBlocking.AntiTracking.AD != 0 -> { ads++; "ad" }
            cat and (ContentBlocking.AntiTracking.ANALYTIC or ContentBlocking.AntiTracking.SOCIAL or
                ContentBlocking.AntiTracking.CONTENT) != 0 -> { trackers++; "tracker" }
            cat != 0 -> { other++; "fingerprinter/cryptominer" }
            else -> return // cookie-only events carry no anti-tracking category
        }
        Log.d(TAG, "blocked $label request (ads=$ads trackers=$trackers other=$other)")
    }

    fun reset() {
        ads = 0
        trackers = 0
        other = 0
    }
}
