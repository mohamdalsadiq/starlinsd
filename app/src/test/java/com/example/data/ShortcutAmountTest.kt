package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec 28 (3/4): the shortcut IS the source of truth for the amount. The session
 * created from the shortcut's own plan carries that plan's price verbatim —
 * 500 → 500 SDG, 1000 → 1000 SDG — so daily confirmation never guesses or re-asks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShortcutAmountTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: SubscriptionRepository

    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = SubscriptionRepository(context, db) { 1700000000000L }
        repo.initialize()
    }

    @After fun close() { db.close() }

    @Test fun fiveHundredShortcutBooksExactlyFiveHundred() = runBlocking {
        val plan = repo.dao.plans().first { it.minutes == 60 && !it.home }
        assertEquals("ساعة", plan.name)
        val session = repo.prepare("M05", plan.id, "CASH", "1")
        assertEquals(50000L, session.amount)
        assertEquals(50000L, session.cashEquivalent)
        // The day's reference number is allocated by the repository (first free number),
        // and it is what lands in the «[N]» stamp on the router device name.
        assertEquals("1", session.reference)
        assertEquals("M05", session.client)
    }

    @Test fun oneThousandShortcutBooksExactlyOneThousand() = runBlocking {
        val plan = repo.dao.plans().first { it.minutes == 180 && !it.home }
        assertEquals("3 ساعات", plan.name)
        val session = repo.prepare("M05", plan.id, "CASH", "2")
        assertEquals(100000L, session.amount)
        assertEquals(100000L, session.cashEquivalent)
        // Allocated by the repository, not the shortcut keyword (source).
        assertEquals("1", session.reference)
        assertEquals("M05", session.client)
    }
}
