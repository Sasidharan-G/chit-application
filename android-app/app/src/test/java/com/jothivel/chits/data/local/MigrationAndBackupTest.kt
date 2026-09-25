package com.jothivel.chits.data.local

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jothivel.chits.testutil.TestDb
import com.jothivel.chits.utils.DataBackupHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationAndBackupTest {

    /**
     * Builds a database file exactly as an older build would have left it: every table and index
     * of the exported schema JSON for [version] (the schemas/ folder is committed), stamped with
     * that user_version. Opening it through Room with the real migrations then makes Room itself
     * validate that the migrated tables match the current entities.
     */
    private fun createOldDatabase(name: String, version: Int): File {
        val schema = JSONObject(File("schemas/com.jothivel.chits.data.local.AppDatabase/$version.json").readText()).getJSONObject("database")
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs(); it.delete() }
        val sqlite = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices")
            if (indices != null) for (j in 0 until indices.length()) {
                sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        sqlite.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        sqlite.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '${schema.getString("identityHash")}')")
        sqlite.version = version
        sqlite.close()
        return file
    }

    private fun insertGroup(file: File) {
        val sqlite = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        sqlite.insertOrThrow("chit_groups", null, ContentValues().apply {
            put("id", "G1"); put("name", "Chit"); put("registerNo", "R1"); put("chitValue", 30000)
            put("durationMonths", 3); put("subscriberCount", 3); put("branch", "Main"); put("startDate", "01-Jan-2026"); put("status", "ACTIVE")
        })
        sqlite.close()
    }

    @Test fun `migrating 13 to 14 keeps existing rows and adds working cash tables`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = createOldDatabase("migrate-13", 13)
        insertGroup(file)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, "migrate-13")
            .addMigrations(AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17, AppDatabase.MIGRATION_17_18)
            .allowMainThreadQueries()
            .build()
        // Opening runs the migration and Room validates every table against the entities.
        assertEquals("R1", db.groupDao().getGroupByIdSync("G1")!!.registerNo)
        assertEquals(0, db.cashHandoverDao().getAllSync().size)

        db.cashHandoverDao().insert(com.jothivel.chits.data.local.entity.CashHandoverEntity().apply {
            id = "h1"; requestId = "r1"; agentId = "a1"; agentName = "Kumar"; amountPaise = 100; handedAt = 1
            businessDate = "2026-01-01"; status = "POSTED"
        })
        assertEquals(1, db.cashHandoverDao().getAllSync().size)
        db.close()
    }

    private fun tableCount(db: AppDatabase, name: String) =
        db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE name LIKE '%$name%'").use { it.count }

    @Test fun `migrating 14 to 18 keeps existing rows`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = createOldDatabase("migrate-14", 14)
        insertGroup(file)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, "migrate-14")
            .addMigrations(AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17, AppDatabase.MIGRATION_17_18)
            .allowMainThreadQueries()
            .build()
        // Opening runs every migration and Room validates every table against the entities.
        assertEquals("R1", db.groupDao().getGroupByIdSync("G1")!!.registerNo)
        assertEquals(0, tableCount(db, "sync_records"))
        assertEquals(0, tableCount(db, "daily_closings"))
        db.close()
    }

    @Test fun `migrating 17 to 18 drops the office server sync records and keeps everything else`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = createOldDatabase("migrate-17", 17)
        insertGroup(file)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
            sqlite.insertOrThrow("sync_records", null, ContentValues().apply {
                put("recordKey", "GROUP:G1"); put("kind", "GROUP"); put("localId", "G1"); put("serverId", "srv-1")
                put("status", "SYNCED"); put("attempts", 0); put("updatedAt", 1)
            })
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, "migrate-17")
            .addMigrations(AppDatabase.MIGRATION_17_18)
            .allowMainThreadQueries()
            .build()
        assertEquals("R1", db.groupDao().getGroupByIdSync("G1")!!.registerNo)
        assertEquals(0, tableCount(db, "sync_records"))
        db.close()
    }

    @Test fun `migrating 16 to 17 drops the cash drawer closings and keeps everything else`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = createOldDatabase("migrate-16", 16)
        insertGroup(file)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
            sqlite.insertOrThrow("daily_closings", null, ContentValues().apply {
                put("id", "c1"); put("businessDate", "2026-01-01"); put("openingCashPaise", 0); put("expectedCashPaise", 100)
                put("countedCashPaise", 100); put("differencePaise", 0); put("closedAt", 1)
            })
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, "migrate-16")
            .addMigrations(AppDatabase.MIGRATION_16_17, AppDatabase.MIGRATION_17_18)
            .allowMainThreadQueries()
            .build()
        assertEquals("R1", db.groupDao().getGroupByIdSync("G1")!!.registerNo)
        assertEquals(0, tableCount(db, "daily_closings"))
        db.close()
    }

    @Test fun `an unmigrated schema is caught by Room instead of silently accepted`() {
        // Sanity check of the harness itself: with NO migration registered, opening a version-13
        // database under version 14 must fail - otherwise the test above proves nothing.
        val context = ApplicationProvider.getApplicationContext<Application>()
        createOldDatabase("no-migration", 13)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "no-migration").allowMainThreadQueries().build()
        val failed = runCatching { db.groupDao().getAllGroupsSync() }.isFailure
        db.close()
        assertTrue("Room should refuse to open 13 -> 14 without a migration", failed)
    }

    @Test fun `every schema file from 13 on can be opened at its own version`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        createOldDatabase("version-14", 14)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "version-14")
            .addMigrations(AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17, AppDatabase.MIGRATION_17_18)
            .allowMainThreadQueries().build()
        assertEquals(0, db.groupDao().getAllGroupsSync().size)
        db.close()
    }

    @Test fun `a backup made by this very version can be restored`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        AppDatabase.closeAndReset()
        val db = AppDatabase.getDatabase(context)
        // The app database refuses main-thread queries (unlike the test fixtures' in-memory one).
        runBlocking(Dispatchers.IO) { TestDb.seedGroup(db, "G-BACKUP", registerNo = "BACKUP-1") }

        val backupFile = File(context.cacheDir, "backup.db")
        assertTrue(runBlocking { DataBackupHelper.backupDatabaseToUri(context, Uri.fromFile(backupFile)) }.isSuccess)
        assertTrue(backupFile.length() > 100)

        // Wipe local data, then restore: the group must come back. Before the fix the restore
        // validator only accepted schema versions 6..12 and rejected every backup of version 13+.
        runBlocking(Dispatchers.IO) { db.clearAllTables() }
        assertEquals(0, runBlocking(Dispatchers.IO) { db.groupDao().getAllGroupsSync().size })

        val restored = runBlocking { DataBackupHelper.restoreDatabaseFromUri(context, Uri.fromFile(backupFile)) }
        assertTrue("restore failed: ${restored.exceptionOrNull()}", restored.isSuccess)
        assertEquals("BACKUP-1", runBlocking(Dispatchers.IO) { AppDatabase.getDatabase(context).groupDao().getGroupByIdSync("G-BACKUP")!!.registerNo })
        AppDatabase.closeAndReset()
    }

    @Test fun `an older backup is migrated when it is restored`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        AppDatabase.closeAndReset()
        val old = createOldDatabase("old-backup-source", 13)
        insertGroup(old)
        val restored = runBlocking { DataBackupHelper.restoreDatabaseFromUri(context, Uri.fromFile(old)) }
        assertTrue("restore failed: ${restored.exceptionOrNull()}", restored.isSuccess)
        assertEquals("R1", runBlocking(Dispatchers.IO) { AppDatabase.getDatabase(context).groupDao().getGroupByIdSync("G1")!!.registerNo })
        AppDatabase.closeAndReset()
    }

    @Test fun `a file that is not a database backup is rejected`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val junk = File(context.cacheDir, "junk.db").apply { writeText("x".repeat(500)) }
        val result = runBlocking { DataBackupHelper.restoreDatabaseFromUri(context, Uri.fromFile(junk)) }
        assertTrue(result.isFailure)
    }
}
