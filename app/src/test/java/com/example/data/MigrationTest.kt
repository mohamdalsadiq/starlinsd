package com.example.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.db.AppDatabase
import com.example.db.DeviceIdentity
import kotlinx.coroutines.flow.first
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
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10).build()
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
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10).build()
        try {
            val row = db.businessDao().session("original")!!
            assertEquals("محمد", row.client); assertEquals(125000L, row.amount)
            assertEquals(100000L, row.cashEquivalent); assertEquals(1700001800000L, row.recognized)
            assertEquals("", row.reference)
            val repo = SubscriptionRepository(context, db); repo.initialize()
            val p = db.businessDao().plans().first()
            assertEquals("1", repo.prepare("", p.id, "CASH", "mm").reference)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun versionFiveUpgradeAddsBalancesWithoutChangingStoredSessions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v5-${System.nanoTime()}"
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val schema = org.json.JSONObject(java.io.File("schemas/com.example.db.AppDatabase/5.json").readText()).getJSONObject("database").getJSONArray("entities")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            for (index in 0 until schema.length()) {
                val entity = schema.getJSONObject(index)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO sessions VALUES ('original', 'محمد', '3 ساعات', 1700000000000, 1700000000000, 10800000, 120000, 'PAUSED', 125000, 100000, 'BANK', 2500, 0, 1800000, 0, 0, 0, 'mm', '7')")
            old.version = 5
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10).build()
        try {
            val repo = SubscriptionRepository(context, db); repo.initialize()
            val row = repo.dao.session("original")!!
            assertEquals("PAUSED", row.state); assertEquals(120000L, row.served)
            assertEquals(125000L, row.amount); assertEquals(100000L, row.cashEquivalent)
            assertEquals(1800000L, row.grace); assertEquals(0L, row.recognized)
            assertTrue(repo.dao.balanceUpdates().isEmpty())
            repo.updateBalance(100000, 125000, "افتتاحي")
            assertEquals(1, repo.dao.balanceUpdates().size)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun versionSixUpgradeAddsDeviceTrackingWithoutLosingData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v6-${System.nanoTime()}"
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val schema = org.json.JSONObject(java.io.File("schemas/com.example.db.AppDatabase/6.json").readText()).getJSONObject("database").getJSONArray("entities")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            for (index in 0 until schema.length()) {
                val entity = schema.getJSONObject(index)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO sessions VALUES ('original', 'محمد', '3 ساعات', 1700000000000, 1700000000000, 10800000, 120000, 'PAUSED', 125000, 100000, 'BANK', 2500, 0, 1800000, 0, 0, 0, 'mm', '7')")
            old.execSQL("INSERT INTO devices VALUES (7, '192.168.1.2', 'جهاز البيت', 123456, 1, 60000)")
            old.version = 6
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10).build()
        try {
            // Existing rows survive with their values; new device columns start empty/null.
            val row = db.businessDao().session("original")!!
            assertEquals("PAUSED", row.state); assertEquals(120000L, row.served)
            assertEquals(125000L, row.amount); assertEquals("7", row.reference)
            assertNull(row.deviceClientId); assertEquals("", row.deviceIp)
            assertEquals(1, db.deviceDao().getAll().first().size)
            // New tables are usable through the same database.
            db.businessDao().homeIp(com.example.db.HomeIp("192.168.1.5", "تلفاز", 100))
            db.businessDao().watchIp(com.example.db.WatchIp("192.168.1.9", "", 101))
            assertEquals(listOf("192.168.1.5"), db.businessDao().homeIps().map { it.ip })
            // Binding works after the migration and persists.
            val repo = SubscriptionRepository(context, db); repo.initialize()
            repo.bindDevice("original", TrackedDevice(102, "هاتف", "192.168.1.50", "aa:bb:cc:dd:ee:ff", IpLists.Category.UNKNOWN))
            assertEquals(102L, repo.dao.session("original")!!.deviceClientId)
            // Backup roundtrip keeps both legacy and new data.
            val backup = repo.exportJson()
            assertTrue(backup.contains("home_ips") && backup.contains("192.168.1.2"))
            repo.restoreJson(backup)
            assertEquals(102L, repo.dao.session("original")!!.deviceClientId)
            assertEquals(1, repo.dao.homeIps().size)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun versionSevenUpgradeAddsIdentityTablesWithoutLosingData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v7-${System.nanoTime()}"
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val schema = org.json.JSONObject(java.io.File("schemas/com.example.db.AppDatabase/7.json").readText()).getJSONObject("database").getJSONArray("entities")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            for (index in 0 until schema.length()) {
                val entity = schema.getJSONObject(index)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO sessions VALUES ('original', 'محمد', '3 ساعات', 1700000000000, 1700000000000, 10800000, 120000, 'PAUSED', 125000, 100000, 'BANK', 2500, 0, 1800000, 0, 0, 0, 'mm', '7', NULL, '', '', '')")
            old.execSQL("INSERT INTO home_ips VALUES ('192.168.1.69', 'Galaxy-A21s', 1700000000000)")
            old.execSQL("INSERT INTO watch_ips VALUES ('192.168.1.139', 'realme-C55', 1700000000000)")
            old.version = 7
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10).build()
        try {
            // Legacy IP rows survive the identity migration untouched; identity tables exist empty.
            assertEquals(listOf("192.168.1.69"), db.businessDao().homeIps().map { it.ip })
            assertEquals(listOf("192.168.1.139"), db.businessDao().watchIps().map { it.ip })
            assertEquals(0, db.businessDao().identities().size)
            val row = db.businessDao().session("original")!!
            assertEquals("PAUSED", row.state); assertEquals(125000L, row.amount)
            // Identity records and per-device confirmations are usable post-migration.
            db.businessDao().identity(DeviceIdentity(102, "HOME", "mac", "هاتف", "192.168.1.50", 1, 1))
            db.businessDao().insertConfirmation(com.example.db.DailyDeviceConfirmation("2026-09-30", 102, "original", 125000, 100000, "CASH", 2500, 1))
            assertEquals(1, db.businessDao().dayConfirmations("2026-09-30").size)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun versionEightUpgradeAddsUnregisteredSummaryTableWithoutLosingData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v8-${System.nanoTime()}"
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val schema = org.json.JSONObject(java.io.File("schemas/com.example.db.AppDatabase/8.json").readText()).getJSONObject("database").getJSONArray("entities")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            for (index in 0 until schema.length()) {
                val entity = schema.getJSONObject(index)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO sessions VALUES ('original', 'محمد', '3 ساعات', 1700000000000, 1700000000000, 10800000, 120000, 'PAUSED', 125000, 100000, 'BANK', 2500, 0, 1800000, 0, 0, 0, 'mm', '7', NULL, '', '', '')")
            old.execSQL("INSERT INTO manual_sales VALUES ('rev-2026-09-28:devices', 1700000000000, 2, 50000, 100000, 100000, 'CASH', 2500)")
            old.version = 8
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10).build()
        try {
            // Existing rows survive; the new summary table exists empty and is usable.
            val row = db.businessDao().session("original")!!
            assertEquals("PAUSED", row.state); assertEquals(125000L, row.amount)
            assertEquals(1, db.businessDao().manualSales().size)
            assertNull(db.businessDao().unregisteredSummary("2026-09-28"))
            db.businessDao().upsertUnregisteredSummary(
                com.example.db.UnregisteredDaySummary("2026-09-28", 50000L, 2, 0, 100000L, "CASH", 1))
            assertEquals(100000L, db.businessDao().unregisteredSummary("2026-09-28")!!.netTotal)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun migration9to10CreatesCutoffAndUsdInvoiceTables() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v9-10-${System.nanoTime()}"
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val schema = org.json.JSONObject(java.io.File("schemas/com.example.db.AppDatabase/9.json").readText()).getJSONObject("database").getJSONArray("entities")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            for (index in 0 until schema.length()) {
                val entity = schema.getJSONObject(index)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.version = 9
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_9_10).build()
        try {
            // New tables exist empty; the USD invoice shape round-trips.
            assertTrue(db.businessDao().invoicePayments().isEmpty())
            db.businessDao().insertInvoicePayment(
                com.example.db.InvoicePayment("p1", "c1", 1700000000000, 3000, 8100, 243000, ""))
            val row = db.businessDao().invoicePayments().single()
            assertEquals(3000L, row.usdCents); assertEquals(8100L, row.ratePerUsd); assertEquals(243000L, row.sdgPaid)
            assertNull(db.businessDao().dailyCutoff("2026-10-05"))
            db.businessDao().insertCutoff(
                com.example.db.DailyCutoff("2026-10-05", 1700000000000, 2, 2, 250000, 250000, 0, 1700000000001))
            assertEquals(2, db.businessDao().dailyCutoff("2026-10-05")!!.subscribedCount)
        } finally { db.close(); context.deleteDatabase(name) }
    }

}
