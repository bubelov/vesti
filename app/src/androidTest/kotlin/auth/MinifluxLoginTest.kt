package org.vestifeed.auth

import android.os.SystemClock
import androidx.fragment.app.Fragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.vestifeed.R
import org.vestifeed.app.db
import org.vestifeed.db.table.ConfTable
import org.vestifeed.db.table.FeedTable
import org.vestifeed.entries.EntriesFragment
import org.vestifeed.navigation.Activity

@RunWith(AndroidJUnit4::class)
class MinifluxLoginTest {

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .db().conf.delete()
        AuthEvents.reset()
    }

    @After
    fun tearDown() {
        AuthEvents.reset()
    }

    @Test
    fun successfulLoginOpensEntriesFragment() {
        val db = InstrumentationRegistry.getInstrumentation().targetContext.db()

        db.feed.insertOrReplace(
            FeedTable.Feed(
                id = "dummy-feed",
                title = "Dummy Feed",
                extOpenEntriesInBrowser = false,
                extBlockedWords = "",
                extShowPreviewImages = null,
            )
        )

        db.conf.update {
            it.copy(
                backend = ConfTable.Backend.Miniflux,
                minifluxUrl = "https://miniflux.example.com",
                minifluxToken = "dummy-token",
                syncOnStartup = false,
            )
        }

        ActivityScenario.launch(Activity::class.java).use { scenario ->
            waitForFragment<EntriesFragment>(scenario, STARTUP_TIMEOUT_MILLIS)
            onView(withId(R.id.message)).check(matches(withText(R.string.news_list_is_empty)))
        }
    }

    private inline fun <reified T : Fragment> waitForFragment(
        scenario: ActivityScenario<Activity>,
        timeoutMillis: Long,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        var lastSeen: Fragment? = null

        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                activity.supportFragmentManager.executePendingTransactions()
                lastSeen = activity.supportFragmentManager.findFragmentById(
                    R.id.fragmentContainerView,
                )
            }
            if (lastSeen is T) return
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }

        throw AssertionError(
            "Expected ${T::class.java.simpleName} but found ${lastSeen?.javaClass?.simpleName}",
        )
    }

    private companion object {
        const val STARTUP_TIMEOUT_MILLIS = 10_000L
        const val POLL_INTERVAL_MILLIS = 100L
    }
}
