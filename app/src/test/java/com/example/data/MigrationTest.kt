package com.example.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationTest {
    @Test fun originalDevicesAndShortcutsSurviveValidatedRoomMigration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${System.nanoTime()}"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("CREATE TABLE devices (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ip TEXT NOT NULL, name TEXT NOT NULL, endTime INTEGER NOT NULL, isPaused INTEGER NOT NULL, remainingWhenPaused INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE shortcuts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, keyword TEXT NOT NULL, phrase TEXT NOT NULL)")
            old.execSQL("INSERT INTO devices VALUES (7, '192.168.1.2', 'جهاز البيت', 123456, 1, 60000)")
            old.execSQL("INSERT INTO shortcuts VALUES (9, 'قديم', 'الساعة %time+2h%')")
            old.version = 1
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_1_2).build()
        try {
            val device = db.deviceDao().getByIp("192.168.1.2")!!
            assertEquals(7, device.id); assertEquals("جهاز البيت", device.name)
            assertEquals(123456L, device.endTime); assertTrue(device.isPaused)
            assertEquals(60000L, device.remainingWhenPaused)
            val shortcut = db.shortcutDao().list().single()
            assertEquals(9, shortcut.id); assertEquals("الساعة %time+2h%", shortcut.phrase)
            assertNull(shortcut.planId); assertEquals("CASH", shortcut.payment); assertTrue(shortcut.enabled)
            val repo = SubscriptionRepository(context, db)
            repo.initialize()
            assertTrue(db.shortcutDao().list().contains(shortcut))
            assertTrue(repo.exportJson().contains("جهاز البيت"))
            assertEquals(3, db.businessDao().plans().size)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
