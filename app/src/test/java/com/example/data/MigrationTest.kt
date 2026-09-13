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
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()
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
    @Test fun versionTwoSubscribersSurviveTheReferenceMigration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v2-${System.nanoTime()}"
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val schema = org.json.JSONObject(java.io.File("schemas/com.example.db.AppDatabase/2.json").readText()).getJSONObject("database").getJSONArray("entities")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            for (index in 0 until schema.length()) {
                val entity = schema.getJSONObject(index)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO sessions VALUES ('original', 'محمد', '3 ساعات', 1700000000000, 1700000000000, 10800000, 0, 'ACTIVE', 125000, 100000, 'BANK', 2500, 0, 1800000, 1700001800000, 0, 0, 'mm')")
            old.version = 2
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_2_3).build()
        try {
            val row = db.businessDao().session("original")!!
            assertEquals("محمد", row.client); assertEquals(125000L, row.amount)
            assertEquals(100000L, row.cashEquivalent); assertEquals(1700001800000L, row.recognized)
            assertEquals("", row.reference)
            val repo = SubscriptionRepository(context, db); repo.initialize()
            val p = db.businessDao().plans().first()
            assertEquals("001", repo.prepare("", p.id, "CASH", "mm").reference)
        } finally { db.close(); context.deleteDatabase(name) }
    }

}
