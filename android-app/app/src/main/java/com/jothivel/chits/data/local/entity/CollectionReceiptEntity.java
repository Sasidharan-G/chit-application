package com.jothivel.chits.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "collection_receipts", indices = {@Index(value = {"requestId"}, unique = true), @Index(value = {"memberId", "groupId", "paidAt"}), @Index(value={"businessDate","status"})})
public class CollectionReceiptEntity {
    @PrimaryKey @NonNull public String id;
    @NonNull public String requestId;
    @NonNull public String receiptNo;
    @NonNull public String memberId;
    @NonNull public String groupId;
    public long amountPaidPaise;
    @NonNull public String mode;
    public String referenceNo;
    public String notes;
    @NonNull public String businessDate;
    public long paidAt;
    @NonNull public String status; // SAVED | VOIDED
    public String voidReason;
    public Long voidedAt;
}
