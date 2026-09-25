package com.jothivel.chits.data.local;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.jothivel.chits.data.local.dao.GroupDao;
import com.jothivel.chits.data.local.dao.MemberDao;
import com.jothivel.chits.data.local.entity.ChitGroupEntity;
import com.jothivel.chits.data.local.entity.MemberEntity;
import com.jothivel.chits.data.local.entity.InstallmentEntity;

import com.jothivel.chits.data.local.dao.PaymentDao;
import com.jothivel.chits.data.local.entity.PaymentEntity;
import com.jothivel.chits.data.local.entity.ActivityLogEntity;
import com.jothivel.chits.data.local.dao.ActivityLogDao;
import com.jothivel.chits.data.local.entity.ChitMembershipEntity;
import com.jothivel.chits.data.local.entity.CollectionReceiptEntity;
import com.jothivel.chits.data.local.dao.MembershipDao;
import com.jothivel.chits.data.local.dao.CollectionReceiptDao;
import com.jothivel.chits.data.local.entity.FinancialTransactionEntity;
import com.jothivel.chits.data.local.dao.FinancialTransactionDao;
import com.jothivel.chits.data.local.entity.CashHandoverEntity;
import com.jothivel.chits.data.local.dao.CashHandoverDao;

@Database(entities = {ChitGroupEntity.class, MemberEntity.class, InstallmentEntity.class, PaymentEntity.class, ActivityLogEntity.class, ChitMembershipEntity.class, CollectionReceiptEntity.class, FinancialTransactionEntity.class, CashHandoverEntity.class}, version = 18, exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {
    
    public abstract GroupDao groupDao();
    public abstract MemberDao memberDao();
    public abstract PaymentDao paymentDao();
    public abstract com.jothivel.chits.data.local.dao.InstallmentDao installmentDao();
    public abstract ActivityLogDao activityLogDao();
    public abstract MembershipDao membershipDao();
    public abstract CollectionReceiptDao collectionReceiptDao();
    public abstract FinancialTransactionDao financialTransactionDao();
    public abstract CashHandoverDao cashHandoverDao();

    /** The current schema version - restore validation accepts backups up to this version. */
    public static final int DATABASE_VERSION = 18;
    
    private static volatile AppDatabase INSTANCE;

    static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE payments ADD COLUMN groupId TEXT");
        }
    };

    static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE chit_groups ADD COLUMN name TEXT");
        }
    };

    static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `activity_logs` (`id` TEXT NOT NULL, `actionType` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`id`))");
        }
    };

    static final Migration MIGRATION_9_10 = new Migration(9, 10) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `chit_memberships` (`id` TEXT NOT NULL, `memberId` TEXT NOT NULL, `groupId` TEXT NOT NULL, `ticketNo` TEXT, `installmentAmountPaise` INTEGER NOT NULL, `joiningDate` TEXT, `dueDate` TEXT, `isActive` INTEGER NOT NULL, PRIMARY KEY(`id`))");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_chit_memberships_memberId_groupId` ON `chit_memberships` (`memberId`, `groupId`)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_chit_memberships_groupId_ticketNo` ON `chit_memberships` (`groupId`, `ticketNo`)");
            database.execSQL("INSERT OR IGNORE INTO `chit_memberships` (`id`,`memberId`,`groupId`,`ticketNo`,`installmentAmountPaise`,`joiningDate`,`dueDate`,`isActive`) SELECT `id` || ':' || `selectedChitId`, `id`, `selectedChitId`, `ticketNo`, CAST(COALESCE(NULLIF(`installmentAmount`,''),'0') AS INTEGER) * 100, `joiningDate`, `dueDate`, `isActive` FROM `members` WHERE `selectedChitId` IS NOT NULL AND `selectedChitId` != ''");
            database.execSQL("CREATE TABLE IF NOT EXISTS `collection_receipts` (`id` TEXT NOT NULL, `requestId` TEXT NOT NULL, `receiptNo` TEXT NOT NULL, `memberId` TEXT NOT NULL, `groupId` TEXT NOT NULL, `amountPaidPaise` INTEGER NOT NULL, `mode` TEXT NOT NULL, `referenceNo` TEXT, `notes` TEXT, `businessDate` TEXT NOT NULL, `paidAt` INTEGER NOT NULL, `status` TEXT NOT NULL, PRIMARY KEY(`id`))");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_collection_receipts_requestId` ON `collection_receipts` (`requestId`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_receipts_memberId_groupId_paidAt` ON `collection_receipts` (`memberId`, `groupId`, `paidAt`)");
        }
    };

    static final Migration MIGRATION_10_11 = new Migration(10, 11) {
        @Override public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `financial_transactions` (`id` TEXT NOT NULL, `requestId` TEXT NOT NULL, `type` TEXT NOT NULL, `memberId` TEXT NOT NULL, `groupId` TEXT NOT NULL, `amountPaise` INTEGER NOT NULL, `mode` TEXT NOT NULL, `referenceNo` TEXT, `notes` TEXT, `occurredAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `reversalReason` TEXT, `reversedAt` INTEGER, PRIMARY KEY(`id`))");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_financial_transactions_requestId` ON `financial_transactions` (`requestId`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_financial_transactions_memberId_groupId_occurredAt` ON `financial_transactions` (`memberId`, `groupId`, `occurredAt`)");
        }
    };

    static final Migration MIGRATION_11_12 = new Migration(11, 12) {
        @Override public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_memberId_groupId_installmentId` ON `payments` (`memberId`,`groupId`,`installmentId`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_paidAt` ON `payments` (`paidAt`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_installments_groupId_installmentNo` ON `installments` (`groupId`,`installmentNo`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_receipts_businessDate_status` ON `collection_receipts` (`businessDate`,`status`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_chit_memberships_groupId_isActive` ON `chit_memberships` (`groupId`,`isActive`)");
        }
    };

    static final Migration MIGRATION_12_13 = new Migration(12, 13) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE payments ADD COLUMN collectedBy TEXT");
            database.execSQL("ALTER TABLE payments ADD COLUMN collectedByAgentId TEXT");
        }
    };

    static final Migration MIGRATION_13_14 = new Migration(13, 14) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `cash_handovers` (`id` TEXT NOT NULL, `requestId` TEXT NOT NULL, `agentId` TEXT NOT NULL, `agentName` TEXT NOT NULL, `amountPaise` INTEGER NOT NULL, `handedAt` INTEGER NOT NULL, `businessDate` TEXT NOT NULL, `notes` TEXT, `status` TEXT NOT NULL, `reversalReason` TEXT, `reversedAt` INTEGER, PRIMARY KEY(`id`))");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_cash_handovers_requestId` ON `cash_handovers` (`requestId`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_cash_handovers_agentId_status` ON `cash_handovers` (`agentId`, `status`)");
            database.execSQL("CREATE TABLE IF NOT EXISTS `daily_closings` (`id` TEXT NOT NULL, `businessDate` TEXT NOT NULL, `openingCashPaise` INTEGER NOT NULL, `expectedCashPaise` INTEGER NOT NULL, `countedCashPaise` INTEGER NOT NULL, `differencePaise` INTEGER NOT NULL, `notes` TEXT, `closedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_closings_businessDate` ON `daily_closings` (`businessDate`)");
        }
    };

    static final Migration MIGRATION_14_15 = new Migration(14, 15) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE collection_receipts ADD COLUMN voidReason TEXT");
            database.execSQL("ALTER TABLE collection_receipts ADD COLUMN voidedAt INTEGER");
        }
    };

    static final Migration MIGRATION_15_16 = new Migration(15, 16) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `sync_records` (`recordKey` TEXT NOT NULL, `kind` TEXT NOT NULL, `localId` TEXT NOT NULL, `serverId` TEXT, `serverRef` TEXT, `contentHash` TEXT, `status` TEXT NOT NULL, `lastError` TEXT, `attempts` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `receiptNo` TEXT, PRIMARY KEY(`recordKey`))");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_records_kind_status` ON `sync_records` (`kind`, `status`)");
        }
    };

    /** The cash drawer count (opening / counted / difference) was removed from Daily Closing. */
    static final Migration MIGRATION_16_17 = new Migration(16, 17) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("DROP INDEX IF EXISTS `index_daily_closings_businessDate`");
            database.execSQL("DROP TABLE IF EXISTS `daily_closings`");
        }
    };

    /** Office server sync was removed; its record of server ids goes with it. */
    static final Migration MIGRATION_17_18 = new Migration(17, 18) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("DROP INDEX IF EXISTS `index_sync_records_kind_status`");
            database.execSQL("DROP TABLE IF EXISTS `sync_records`");
        }
    };

    public static AppDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                            AppDatabase.class, "jothivel_chits_database")
                            .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18)
                            // Schemas 1-5 were internal development builds that were never exported, so
                            // no migration path can be written for them. Without this, an install that
                            // still has one crashes on every launch with "a migration from N to 14 was
                            // required"; with it, only those pre-release databases are recreated. Every
                            // version from 6 up (the only ones ever shipped) keeps its data via the
                            // explicit migrations above - there is deliberately NO blanket destructive
                            // fallback.
                            .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5)
                            .build();
                }
            }
        }
        return INSTANCE;
    }

    /** Close the current Room connection and allow a restored database to reopen cleanly. */
    public static synchronized void closeAndReset() {
        if (INSTANCE != null) {
            if (INSTANCE.isOpen()) {
                INSTANCE.close();
            }
            INSTANCE = null;
        }
    }
}
