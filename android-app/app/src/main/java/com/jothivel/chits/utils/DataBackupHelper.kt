package com.jothivel.chits.utils

import android.content.Context
import android.net.Uri
import android.database.sqlite.SQLiteDatabase
import com.jothivel.chits.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DataBackupHelper {

    suspend fun backupDatabaseToUri(context: Context, uri: Uri): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(context)
                // Force a WAL checkpoint to ensure all data is in the main .db file
                db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL);").use { it.moveToFirst() }
                
                val dbFile = context.getDatabasePath("jothivel_chits_database")
                if (!dbFile.exists()) {
                    return@withContext Result.failure(Exception("Database file not found"))
                }

                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    dbFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } ?: return@withContext Result.failure(Exception("Could not open output stream"))
                
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun restoreDatabaseFromUri(context: Context, uri: Uri): Result<Unit> {
        return withContext(Dispatchers.IO) {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            val databaseDir = dbFile.parentFile ?: return@withContext Result.failure(Exception("Database folder unavailable"))
            val incomingFile = java.io.File(databaseDir, "$DATABASE_NAME.restore.tmp")
            val recoveryFile = java.io.File(databaseDir, "$DATABASE_NAME.before_restore")
            try {
                databaseDir.mkdirs()
                if (incomingFile.exists()) incomingFile.delete()
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    incomingFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } ?: return@withContext Result.failure(Exception("Could not open input stream from file"))

                validateBackup(incomingFile)

                // Only after validation: checkpoint, close and reset Room before replacing files.
                val currentDb = AppDatabase.getDatabase(context)
                currentDb.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL);").use { it.moveToFirst() }
                AppDatabase.closeAndReset()

                if (recoveryFile.exists()) recoveryFile.delete()
                if (dbFile.exists()) dbFile.copyTo(recoveryFile, overwrite = true)
                clearSidecarFiles(context)

                try {
                    incomingFile.copyTo(dbFile, overwrite = true)
                    // Force Room to validate/open the restored schema before declaring success.
                    AppDatabase.getDatabase(context).openHelper.writableDatabase
                    if (recoveryFile.exists()) recoveryFile.delete()
                } catch (replaceError: Exception) {
                    AppDatabase.closeAndReset()
                    if (recoveryFile.exists()) recoveryFile.copyTo(dbFile, overwrite = true) else if (dbFile.exists()) dbFile.delete()
                    clearSidecarFiles(context)
                    throw replaceError
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                if (incomingFile.exists()) incomingFile.delete()
            }
        }
    }

    private fun validateBackup(file: java.io.File) {
        if (!file.exists() || file.length() < 100L) throw IllegalArgumentException("Selected backup file is empty or invalid")
        val header = ByteArray(16)
        file.inputStream().use { if (it.read(header) != header.size) throw IllegalArgumentException("Invalid database header") }
        if (!header.toString(Charsets.US_ASCII).startsWith("SQLite format 3")) throw IllegalArgumentException("Selected file is not a SQLite database backup")

        val sqlite = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val tables = mutableSetOf<String>()
            sqlite.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
                while (cursor.moveToNext()) tables += cursor.getString(0)
            }
            val missing = REQUIRED_TABLES - tables
            if (missing.isNotEmpty()) throw IllegalArgumentException("Wrong backup: missing ${missing.joinToString()}")
            val version = sqlite.rawQuery("PRAGMA user_version", null).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            // Every version from 6 (the first shipped one) up to the schema this build uses can be
            // opened and migrated by Room. Hard-coding the upper bound is what once made the app
            // reject its own backups the moment the schema was bumped.
            if (version !in MIN_SUPPORTED_BACKUP_VERSION..AppDatabase.DATABASE_VERSION) throw IllegalArgumentException("Unsupported backup database version: $version")
        } finally {
            sqlite.close()
        }
    }

    private fun clearSidecarFiles(context: Context) {
        listOf("$DATABASE_NAME-wal", "$DATABASE_NAME-shm", "$DATABASE_NAME-journal").forEach { name ->
            context.getDatabasePath(name).takeIf { it.exists() }?.delete()
        }
    }

    suspend fun exportDataToCsv(context: Context, uri: Uri): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(context)
                val groups = db.groupDao().getAllGroupsSync()
                val members = db.memberDao().getAllMembersSync()
                val payments = db.paymentDao().getAllPaymentsSync()
                val memberships = db.membershipDao().getAllActiveSync()
                val receipts = db.collectionReceiptDao().getRecentSync(Int.MAX_VALUE)
                val financial = db.financialTransactionDao().getAllSync()
                val installments = groups.flatMap { db.installmentDao().getInstallmentsForGroupSync(it.id) }
                val activity = db.activityLogDao().getAllSync()
                val handovers = db.cashHandoverDao().getAllSync()

                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    writeCsvToStream(outputStream, groups, members, payments, memberships, receipts, financial, installments, activity, handovers)
                } ?: return@withContext Result.failure(Exception("Could not open output stream"))

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * RFC 4180 CSV field escaping.
     * Wraps any value that contains a comma, double-quote, or newline in double-quotes
     * and escapes inner double-quotes as "". This prevents column misalignment in Excel.
     */
    private fun csvEscape(value: Any?): String {
        val s = value?.toString() ?: ""
        return if (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')) {
            "\"${s.replace("\"", "\"\"")}\""
        } else s
    }

    private fun writeCsvToStream(
        outputStream: OutputStream,
        groups: List<com.jothivel.chits.data.local.entity.ChitGroupEntity>,
        members: List<com.jothivel.chits.data.local.entity.MemberEntity>,
        payments: List<com.jothivel.chits.data.local.entity.PaymentEntity>,
        memberships: List<com.jothivel.chits.data.local.entity.ChitMembershipEntity>,
        receipts: List<com.jothivel.chits.data.local.entity.CollectionReceiptEntity>,
        financial: List<com.jothivel.chits.data.local.entity.FinancialTransactionEntity>,
        installments: List<com.jothivel.chits.data.local.entity.InstallmentEntity>,
        activity: List<com.jothivel.chits.data.local.entity.ActivityLogEntity>,
        handovers: List<com.jothivel.chits.data.local.entity.CashHandoverEntity>
    ) {
        val writer = outputStream.bufferedWriter()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        // ── CHIT GROUPS ────────────────────────────────────────────────────────
        writer.write("--- CHIT GROUPS ---\n")
        writer.write("ID,Name,Register No,Value,Subscriber Count\n")
        groups.forEach { g ->
            writer.write("${csvEscape(g.id)},${csvEscape(g.name)},${csvEscape(g.registerNo)},${csvEscape(g.chitValue)},${csvEscape(g.subscriberCount)}\n")
        }
        writer.write("\n")

        // ── MEMBERS ────────────────────────────────────────────────────────────
        writer.write("--- MEMBERS ---\n")
        writer.write("ID,Name,Phone,Group ID,Installment Amount\n")
        members.forEach { m ->
            writer.write("${csvEscape(m.id)},${csvEscape(m.name)},${csvEscape(m.phone)},${csvEscape(m.selectedChitId)},${csvEscape(m.installmentAmount)}\n")
        }
        writer.write("\n")

        // ── PAYMENTS ───────────────────────────────────────────────────────────
        writer.write("--- PAYMENTS ---\n")
        writer.write("ID,Member ID,Group ID,Amount Paid,Mode,Date\n")
        payments.forEach { p ->
            writer.write("${csvEscape(p.id)},${csvEscape(p.memberId)},${csvEscape(p.groupId)},${csvEscape(p.amountPaid)},${csvEscape(p.mode)},${csvEscape(sdf.format(Date(p.paidAt)))}\n")
        }
        writer.write("\n")

        // ── MEMBERSHIPS ────────────────────────────────────────────────────────
        writer.write("--- MEMBERSHIPS ---\n")
        writer.write("Member ID,Group ID,Ticket,Installment Paise,Joining Date,Due Date,Active\n")
        memberships.forEach { m ->
            writer.write("${csvEscape(m.memberId)},${csvEscape(m.groupId)},${csvEscape(m.ticketNo)},${csvEscape(m.installmentAmountPaise)},${csvEscape(m.joiningDate)},${csvEscape(m.dueDate)},${csvEscape(m.isActive)}\n")
        }

        // ── COLLECTION RECEIPTS ────────────────────────────────────────────────
        writer.write("\n--- COLLECTION RECEIPTS ---\n")
        writer.write("Receipt,Member ID,Group ID,Amount Paise,Mode,Reference,Business Date,Status\n")
        receipts.forEach { r ->
            writer.write("${csvEscape(r.receiptNo)},${csvEscape(r.memberId)},${csvEscape(r.groupId)},${csvEscape(r.amountPaidPaise)},${csvEscape(r.mode)},${csvEscape(r.referenceNo)},${csvEscape(r.businessDate)},${csvEscape(r.status)}\n")
        }

        // ── SETTLEMENT AND DELIVERY ────────────────────────────────────────────
        writer.write("\n--- SETTLEMENT AND DELIVERY ---\n")
        writer.write("ID,Type,Member ID,Group ID,Amount Paise,Mode,Reference,Status,Timestamp,Reversal Reason\n")
        financial.forEach { f ->
            writer.write("${csvEscape(f.id)},${csvEscape(f.type)},${csvEscape(f.memberId)},${csvEscape(f.groupId)},${csvEscape(f.amountPaise)},${csvEscape(f.mode)},${csvEscape(f.referenceNo)},${csvEscape(f.status)},${csvEscape(sdf.format(Date(f.occurredAt)))},${csvEscape(f.reversalReason)}\n")
        }

        // ── INSTALLMENT SCHEDULE ───────────────────────────────────────────────
        writer.write("\n--- INSTALLMENT SCHEDULE ---\n")
        writer.write("ID,Group ID,No,Base Paise,Kasaru Paise,Payout Paise,Auction Date,Status,Winner\n")
        installments.forEach { i ->
            writer.write("${csvEscape(i.id)},${csvEscape(i.groupId)},${csvEscape(i.installmentNo)},${csvEscape(i.baseAmount)},${csvEscape(i.kasaruAmount)},${csvEscape(i.payoutAmount)},${csvEscape(i.auctionDate)},${csvEscape(i.status)},${csvEscape(i.winningMemberId)}\n")
        }

        // ── AGENT CASH HANDOVERS ───────────────────────────────────────────────
        writer.write("\n--- AGENT CASH HANDOVERS ---\n")
        writer.write("ID,Agent ID,Agent,Amount Paise,Business Date,Status,Timestamp,Notes,Reversal Reason\n")
        handovers.forEach { h ->
            writer.write("${csvEscape(h.id)},${csvEscape(h.agentId)},${csvEscape(h.agentName)},${csvEscape(h.amountPaise)},${csvEscape(h.businessDate)},${csvEscape(h.status)},${csvEscape(sdf.format(Date(h.handedAt)))},${csvEscape(h.notes)},${csvEscape(h.reversalReason)}\n")
        }

        // ── AUDIT LOG ──────────────────────────────────────────────────────────
        writer.write("\n--- AUDIT LOG ---\n")
        writer.write("ID,Action,Title,Description,Timestamp\n")
        activity.forEach { a ->
            writer.write("${csvEscape(a.id)},${csvEscape(a.actionType)},${csvEscape(a.title)},${csvEscape(a.description)},${csvEscape(sdf.format(Date(a.timestamp)))}\n")
        }
        writer.write("\n")

        writer.flush()
    }

    private const val MIN_SUPPORTED_BACKUP_VERSION = 6
    private const val DATABASE_NAME = "jothivel_chits_database"
    private val REQUIRED_TABLES = setOf("members", "chit_groups", "installments", "payments")
}
