package com.jothivel.chits.ui.settings

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.jothivel.chits.data.local.AppDatabase
import com.jothivel.chits.data.local.entity.ChitGroupEntity
import com.jothivel.chits.data.local.entity.ChitMembershipEntity
import com.jothivel.chits.data.local.entity.CollectionReceiptEntity
import com.jothivel.chits.data.local.entity.InstallmentEntity
import com.jothivel.chits.data.local.entity.MemberEntity
import com.jothivel.chits.data.local.entity.PaymentEntity
import com.jothivel.chits.ui.base.BaseActivity
import com.jothivel.chits.ui.components.SmoothTransitions
import com.jothivel.chits.ui.theme.JothiVelChitsTheme
import com.jothivel.chits.ui.theme.MaroonPrimary
import com.jothivel.chits.ui.theme.OffWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.UUID
import java.util.zip.ZipInputStream

// ─── Data classes ─────────────────────────────────────────────────────────────

data class CsvImportResult(
    val members: List<MemberEntity>,
    val groups: List<ChitGroupEntity>,
    val skippedRows: Int,
    val errors: List<String>,
    val paymentRows: List<ImportPaymentRow> = emptyList()
)

/**
 * One row's installment/payment intent (columns 3, 5-9), resolved against the
 * *final* member/group DB ids later in [CsvImportActivity.importData] — at parse
 * time we only know the phone / group name, not whether that member or group
 * already exists in Room.
 */
data class ImportPaymentRow(
    val memberPhone: String,
    val groupName: String,
    val installmentNo: Int,
    val baseAmountPaise: Long,
    val amountDuePaise: Long,
    val amountPaidPaise: Long,
    val paymentStatus: String, // PAID | PARTIAL | DUE | OVERDUE
    val paymentMode: String,   // CASH | UPI | BANK_TRANSFER
    val dueDateRaw: String,
    // Optional 11th column. When the sheet says when the money was actually received it is used
    // as the payment date; otherwise the Due Date is the best available stand-in.
    val paidDateRaw: String = ""
)

/** Counters returned after writing to the DB. */
data class ImportSummary(
    val newMembers: Int, val updatedMembers: Int,
    val newGroups: Int, val updatedGroups: Int,
    val newInstallments: Int, val newMemberships: Int, val newPayments: Int
)

// ─── Activity ─────────────────────────────────────────────────────────────────

class CsvImportActivity : BaseActivity() {

    companion object {
        const val EXTRA_CSV_URI = "EXTRA_CSV_URI"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uriString = intent.getStringExtra(EXTRA_CSV_URI)
        val csvUri = uriString?.let { Uri.parse(it) }

        setContent {
            JothiVelChitsTheme {
                CsvImportScreen(
                    csvUri = csvUri,
                    onBack = { SmoothTransitions.finishSmooth(this) },
                    onImport = { members, groups, paymentRows ->
                        lifecycleScope.launch { importData(members, groups, paymentRows) }
                    }
                )
            }
        }
    }

    /**
     * Smart upsert, applied as ONE transaction - a failure halfway (bad row, ambiguous chit name,
     * disk full) rolls the whole import back instead of leaving a half-imported database.
     * See [applyImport] for the matching rules.
     */
    private suspend fun importData(
        members: List<MemberEntity>,
        groups: List<ChitGroupEntity>,
        paymentRows: List<ImportPaymentRow>
    ) {
        withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                var summary: ImportSummary? = null
                db.runInTransaction { summary = applyImport(db, members, groups, paymentRows) }
                val done = checkNotNull(summary)
                withContext(Dispatchers.Main) {
                    val msg = buildString {
                        append("✓ Import complete!\n")
                        if (done.newMembers > 0)      append("${done.newMembers} new member(s) added\n")
                        if (done.updatedMembers > 0)  append("${done.updatedMembers} member(s) updated\n")
                        if (done.newGroups > 0)       append("${done.newGroups} new chit group(s) added\n")
                        if (done.updatedGroups > 0)   append("${done.updatedGroups} existing chit group(s) matched\n")
                        if (done.newInstallments > 0) append("${done.newInstallments} installment(s) created\n")
                        if (done.newMemberships > 0)  append("${done.newMemberships} membership(s) linked\n")
                        if (done.newPayments > 0)     append("${done.newPayments} payment(s) recorded")
                    }
                    Toast.makeText(this@CsvImportActivity, msg.trim(), Toast.LENGTH_LONG).show()
                    SmoothTransitions.finishSmooth(this@CsvImportActivity)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@CsvImportActivity, "Import failed - nothing was changed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun finish() {
        super.finish()
        SmoothTransitions.applyExitTransition(this)
    }
}

// ─── Import writer ────────────────────────────────────────────────────────────

/**
 * Writes a parsed sheet into [db]. Must be called inside a transaction (see importData).
 *
 * - A chit named in the sheet is matched to an EXISTING chit by its chit number first, then by
 *   its name. Two existing chits sharing that name make the match ambiguous, so the import stops
 *   rather than guess and put payments on the wrong chit. Existing chits are never overwritten
 *   (they used to be replaced by the sheet's placeholder chit number, start date and value).
 * - Members are matched by phone; anything the sheet cannot supply is preserved.
 * - Every imported payment also gets a receipt row, so receipts, the daily summary and history
 *   see imported money the same way as collected money.
 */
internal fun applyImport(
    db: AppDatabase,
    members: List<MemberEntity>,
    groups: List<ChitGroupEntity>,
    paymentRows: List<ImportPaymentRow>
): ImportSummary {
    var newMembers = 0; var updatedMembers = 0
    var newGroups = 0; var updatedGroups = 0
    var newInstallments = 0; var newMemberships = 0; var newPayments = 0

    // ── Groups ────────────────────────────────────────────────────
    val groupByName = mutableMapOf<String?, ChitGroupEntity>()
    val groupsToInsert = mutableListOf<ChitGroupEntity>()
    groups.forEach { g ->
        val name = g.name.orEmpty()
        val byNumber = if (name.isBlank()) null else db.groupDao().getGroupByRegisterNoSync(name)
        val existing = byNumber ?: run {
            val sameName = db.groupDao().getGroupsByNameSync(name)
            check(sameName.size <= 1) {
                "More than one chit is named '$name'. Rename them, or put the chit number in the sheet's Chit Group column."
            }
            sameName.firstOrNull()
        }
        if (existing != null) {
            groupByName[g.name] = existing
            updatedGroups++
        } else {
            groupByName[g.name] = g
            groupsToInsert += g
            newGroups++
        }
    }
    if (groupsToInsert.isNotEmpty()) db.groupDao().insertAll(groupsToInsert)

    // ── Members ───────────────────────────────────────────────────
    val resolvedMembers = members.map { m ->
        val existing = m.phone?.let { db.memberDao().getMemberByPhoneSync(it) }
        if (existing != null) {
            m.id = existing.id
            // Preserve every field the spreadsheet can't supply, so re-importing
            // (e.g. to update payment status) never blanks out data entered by hand.
            m.photoUrl = existing.photoUrl
            m.aadhaarNoEncrypted = existing.aadhaarNoEncrypted
            m.panNo = existing.panNo
            m.aadhaarDocumentPath = existing.aadhaarDocumentPath
            m.panDocumentPath = existing.panDocumentPath
            m.addressLine = existing.addressLine
            m.city = existing.city
            m.state = existing.state
            m.pincode = existing.pincode
            m.dob = existing.dob
            m.gender = existing.gender
            m.nomineeName = existing.nomineeName
            m.nomineePhone = existing.nomineePhone
            m.nomineeRelationship = existing.nomineeRelationship
            // Baseline from the existing row; the group-linkage block below only
            // overrides selectedChitId/installmentAmount/joiningDate/dueDate when
            // the sheet actually resolves a group — ticketNo the sheet never supplies.
            m.ticketNo = existing.ticketNo
            m.selectedChitId = existing.selectedChitId
            m.installmentAmount = existing.installmentAmount
            m.joiningDate = existing.joiningDate
            m.dueDate = existing.dueDate
            updatedMembers++
        } else { newMembers++ }

        // Mirror what the Add Member screen sets when a member is linked to a chit,
        // so imported members show up wherever the app queries by selectedChitId
        // (Ledger, Groups, Collection-entry) and not just via ChitMembershipEntity
        // (Pending). Without this, imported rows were only ever visible on Pending.
        val firstRow = paymentRows.firstOrNull { it.memberPhone == m.phone }
        val groupForMember = firstRow?.let { groupByName[it.groupName] }
        if (groupForMember != null) {
            m.selectedChitId = groupForMember.id
            m.installmentAmount = (firstRow.baseAmountPaise / 100).toString()
            m.joiningDate = groupForMember.startDate
            m.dueDate = firstRow.dueDateRaw.ifBlank { null }
        }
        m
    }
    if (resolvedMembers.isNotEmpty()) db.memberDao().insertAll(resolvedMembers)

    // ── Installments / Memberships / Payments ────────────────────────
    // Every row that carried a group name also produced an ImportPaymentRow
    // (see rowToMemberAndGroup), so these lookups are guaranteed to resolve.
    val memberByPhone = resolvedMembers.associateBy { it.phone }
    val installmentCache = mutableMapOf<String, MutableMap<Int, InstallmentEntity>>()
    val receiptStamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
    val businessDayFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)

    for (row in paymentRows) {
        val group = groupByName[row.groupName] ?: continue
        val member = memberByPhone[row.memberPhone] ?: continue

        // Find-or-create the installment for (groupId, installmentNo)
        val groupInstallments = installmentCache.getOrPut(group.id) {
            db.installmentDao().getInstallmentsForGroupSync(group.id)
                .associateBy { it.installmentNo }.toMutableMap()
        }
        val installment = groupInstallments[row.installmentNo]?.apply {
            // Don't clobber an already-configured fixed-schedule installment (its
            // baseAmount is stored as gross = net + kasaru, see
            // ChitAdminService.buildInstallments) with the CSV's raw figure while leaving
            // its kasaruAmount untouched - that mismatch would corrupt the due
            // calculation. Only backfill when this installment has no kasaru
            // component yet (kasaruAmount == 0), i.e. it wasn't set up that way.
            if ((kasaruAmount ?: 0) == 0) baseAmount = row.baseAmountPaise.toInt()
        } ?: InstallmentEntity().apply {
            id = UUID.randomUUID().toString()
            groupId = group.id
            installmentNo = row.installmentNo
            baseAmount = row.baseAmountPaise.toInt()
            kasaruAmount = 0
            payoutAmount = null
            auctionDate = null
            status = "UPCOMING"
            winningMemberId = null
        }.also { newInstallments++ }
        db.installmentDao().insert(installment)
        groupInstallments[row.installmentNo] = installment

        // Find-or-create + activate the member's subscription to this group,
        // since CollectionService/FinancialService gate dues/payments on isActive.
        val membership = db.membershipDao().getSync(member.id, group.id)?.apply { isActive = true }
            ?: ChitMembershipEntity().apply {
                id = UUID.randomUUID().toString()
                memberId = member.id
                groupId = group.id
                ticketNo = null
                installmentAmountPaise = 0L // per-installment baseAmount governs (see CollectionService)
                joiningDate = group.startDate
                dueDate = row.dueDateRaw.ifBlank { null }
                isActive = true
            }.also { newMemberships++ }
        db.membershipDao().upsertAll(listOf(membership))

        // Only PAID/PARTIAL create a PaymentEntity — DUE/OVERDUE is represented
        // by the *absence* of a payment row, matching CollectionService's convention
        // (pending = scheduled baseAmount - sum of PaymentEntity rows for that installment).
        if (row.paymentStatus == "PAID" || row.paymentStatus == "PARTIAL") {
            val alreadyPaid = db.paymentDao()
                .getPaymentsForInstallmentSync(member.id, group.id, row.installmentNo.toString())
                .sumOf { it.amountPaid }
            val delta = row.amountPaidPaise - alreadyPaid
            if (delta > 0) {
                val paidAt = parseImportDate(row.paidDateRaw) ?: parseImportDate(row.dueDateRaw) ?: System.currentTimeMillis()
                val paymentId = UUID.randomUUID().toString()
                val receiptNo = "IMP-${receiptStamp.format(java.util.Date())}-${UUID.randomUUID().toString().take(4).uppercase()}"
                db.paymentDao().insertPayment(PaymentEntity().apply {
                    id = paymentId
                    memberId = member.id
                    groupId = group.id
                    installmentId = row.installmentNo.toString()
                    amountPaid = delta
                    mode = row.paymentMode
                    referenceNo = null
                    this.receiptNo = receiptNo
                    this.paidAt = paidAt
                    status = row.paymentStatus
                    collectedBy = null
                    collectedByAgentId = null
                })
                db.collectionReceiptDao().insert(CollectionReceiptEntity().apply {
                    id = UUID.randomUUID().toString()
                    requestId = "IMPORT-$paymentId"
                    this.receiptNo = receiptNo
                    memberId = member.id
                    groupId = group.id
                    amountPaidPaise = delta
                    mode = when (row.paymentMode) { "UPI" -> "UPI"; "BANK_TRANSFER" -> "Bank"; else -> "Cash" }
                    referenceNo = null
                    notes = "Imported from spreadsheet"
                    businessDate = businessDayFormat.format(java.util.Date(paidAt))
                    this.paidAt = paidAt
                    status = "SAVED"
                })
                newPayments++
            }
        }
    }

    return ImportSummary(
        newMembers, updatedMembers, newGroups, updatedGroups,
        newInstallments, newMemberships, newPayments
    )
}

// ─── Composable Screen ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CsvImportScreen(
    csvUri: Uri?,
    onBack: () -> Unit,
    onImport: (List<MemberEntity>, List<ChitGroupEntity>, List<ImportPaymentRow>) -> Unit
) {
    val context = LocalContext.current
    var parseState by remember { mutableStateOf<CsvImportResult?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }

    // Auto-detect format (CSV / XLSX / XLS) and parse on launch
    LaunchedEffect(csvUri) {
        if (csvUri != null) {
            isLoading = true
            withContext(Dispatchers.IO) {
                val mime = context.contentResolver.getType(csvUri) ?: ""
                val path = csvUri.path ?: ""
                val result = when {
                    path.endsWith(".xlsx", ignoreCase = true) ||
                    mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ->
                        parseXlsxFile(context, csvUri)

                    path.endsWith(".xls", ignoreCase = true) ||
                    mime == "application/vnd.ms-excel" ->
                        // Legacy XLS (BIFF8) requires Apache POI which is incompatible with minSdk 24.
                        // Guide user to re-export as XLSX or CSV.
                        CsvImportResult(
                            members = emptyList(), groups = emptyList(), skippedRows = 0,
                            errors = listOf(
                                "Legacy .xls format is not supported.",
                                "Please open the file in Excel / Google Sheets and save/export it as .xlsx or .csv, then try again."
                            )
                        )

                    else -> parseCsvFile(context, csvUri)
                }
                withContext(Dispatchers.Main) {
                    parseState = result
                    isLoading = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import File", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaroonPrimary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = OffWhite
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            when {
                csvUri == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.FileOpen, contentDescription = null,
                                tint = Color.LightGray, modifier = Modifier.size(64.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("No file selected", color = Color.Gray, fontSize = 16.sp)
                            Text("Go back and select a CSV or XLSX file from Settings",
                                color = Color.LightGray, fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 8.dp).padding(horizontal = 24.dp))
                        }
                    }
                }
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = MaroonPrimary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Reading file…", color = Color.Gray)
                        }
                    }
                }
                parseState == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Failed to read file.", color = Color.Red)
                    }
                }
                else -> {
                    val result = parseState!!
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                        // ── Summary card ──────────────────────────────────────
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                elevation = CardDefaults.cardElevation(2.dp)
                            ) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Text("Import Preview", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                    Spacer(modifier = Modifier.height(14.dp))
                                    SummaryRow(Icons.Default.People, "Members to import",
                                        "${result.members.size}", Color(0xFF1E6B3B))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    SummaryRow(Icons.Default.Group, "Chit Groups to import",
                                        "${result.groups.size}", MaroonPrimary)
                                    if (result.skippedRows > 0) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        SummaryRow(Icons.Default.Warning, "Skipped rows",
                                            "${result.skippedRows}", Color(0xFFE65100))
                                    }
                                }
                            }
                        }

                        // ── Column format guide ───────────────────────────────
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F9FA)),
                                elevation = CardDefaults.cardElevation(0.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Info, contentDescription = null,
                                            tint = MaroonPrimary, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Expected Columns (CSV / XLSX)",
                                            fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                                            color = MaroonPrimary)
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "Member Name • Phone • Chit Group • Installment No • Base Amount • " +
                                        "Amount Due • Amount Paid • Payment Status • Payment Mode • Due Date",
                                        fontSize = 11.sp, color = Color.Gray, lineHeight = 18.sp
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        "ℹ Existing members matched by Phone, groups by Name — no duplicates created.",
                                        fontSize = 11.sp, color = Color(0xFF1565C0), lineHeight = 16.sp
                                    )
                                }
                            }
                        }

                        // ── Members preview ───────────────────────────────────
                        if (result.members.isNotEmpty()) {
                            item {
                                Text("Members Preview",
                                    fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                            }
                            items(result.members.take(5)) { member ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    elevation = CardDefaults.cardElevation(1.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .background(MaroonPrimary.copy(alpha = 0.1f), RoundedCornerShape(18.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                (member.name?.take(1) ?: "?").uppercase(),
                                                color = MaroonPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(member.name ?: "—", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                            Text(member.phone ?: "—", color = Color.Gray, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                            if (result.members.size > 5) {
                                item {
                                    Text("+ ${result.members.size - 5} more members",
                                        color = Color.Gray, fontSize = 13.sp,
                                        modifier = Modifier.padding(start = 4.dp))
                                }
                            }
                        }

                        // ── Errors / warnings ─────────────────────────────────
                        if (result.errors.isNotEmpty()) {
                            item {
                                Text("Warnings", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                    color = Color(0xFFE65100),
                                    modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                            }
                            items(result.errors) { err ->
                                Text("• $err", fontSize = 12.sp, color = Color(0xFFE65100),
                                    modifier = Modifier.padding(start = 8.dp))
                            }
                        }

                        // ── Import button ─────────────────────────────────────
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    isImporting = true
                                    onImport(result.members, result.groups, result.paymentRows)
                                },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaroonPrimary),
                                enabled = !isImporting &&
                                          (result.members.isNotEmpty() || result.groups.isNotEmpty())
                            ) {
                                if (isImporting) {
                                    CircularProgressIndicator(color = Color.White,
                                        modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Importing…", fontWeight = FontWeight.Bold)
                                } else {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null,
                                        modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Import ${result.members.size} Members & ${result.groups.size} Chit Groups",
                                        fontWeight = FontWeight.Bold, fontSize = 13.sp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(32.dp))
                        }
                    }
                }
            }
        }
    }
}

// ─── Helper composable ────────────────────────────────────────────────────────

@Composable
fun SummaryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    color: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f), color = Color(0xFF333333))
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

// ─── Shared row builder ───────────────────────────────────────────────────────

/**
 * Converts one spreadsheet row into a [MemberEntity] and/or [ChitGroupEntity], plus an
 * [ImportPaymentRow] carrying the installment/payment columns for later DB resolution.
 * Column order (0-indexed):
 * 0 Member Name | 1 Phone | 2 Chit Group | 3 Installment No | 4 Base Amount |
 * 5 Amount Due  | 6 Amount Paid | 7 Payment Status | 8 Payment Mode | 9 Due Date |
 * 10 Payment Date (optional - falls back to the Due Date)
 *
 * @return 1 if the row was skipped, 0 if processed.
 */
internal fun rowToMemberAndGroup(
    memberName: String,
    phone: String,
    groupName: String,
    installmentNoStr: String,
    baseAmountStr: String,
    amountDueStr: String,
    amountPaidStr: String,
    paymentStatusStr: String,
    paymentModeStr: String,
    dueDateStr: String,
    members: MutableList<MemberEntity>,
    groupMap: MutableMap<String, ChitGroupEntity>,
    paymentRows: MutableList<ImportPaymentRow>,
    seenPhones: MutableSet<String>,
    errors: MutableList<String>,
    lineLabel: String,
    paidDateStr: String = ""
): Int {
    if (memberName.isBlank() || phone.isBlank()) {
        errors.add("$lineLabel: missing name or phone, skipped")
        return 1
    }
    if (phone !in seenPhones) {
        seenPhones.add(phone)
        val m = MemberEntity().apply {
            id = UUID.randomUUID().toString()
            name = memberName
            this.phone = phone
            isActive = true
            role = "MEMBER"
        }
        members.add(m)
    }
    if (groupName.isNotBlank() && groupName !in groupMap) {
        val baseAmount = baseAmountStr.toLongOrNull() ?: 0L
        val months = Regex("(\\d+)M").find(groupName)?.groupValues?.get(1)?.toIntOrNull() ?: 20
        val g = ChitGroupEntity().apply {
            id = UUID.randomUUID().toString()
            this.name = groupName
            registerNo = "IMP-${System.currentTimeMillis()}-${groupMap.size + 1}"
            chitValue = (baseAmount * months * 100).toInt()
            durationMonths = months
            subscriberCount = months
            branch = "Main"
            startDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date())
            status = "ACTIVE"
        }
        groupMap[groupName] = g
    }

    // Installment / payment columns — only meaningful when the row names a group.
    if (groupName.isNotBlank()) {
        val installmentNo = installmentNoStr.toIntOrNull()
        if (installmentNo == null || installmentNo <= 0) {
            errors.add("$lineLabel: invalid or missing Installment No '$installmentNoStr', payment data skipped")
        } else {
            val baseAmountPaise = (baseAmountStr.toLongOrNull() ?: 0L) * 100
            val amountDuePaise = (amountDueStr.toLongOrNull() ?: 0L) * 100
            val amountPaidPaise = (amountPaidStr.toLongOrNull() ?: 0L) * 100
            val status = paymentStatusStr.trim().uppercase().let {
                if (it in setOf("PAID", "PARTIAL", "DUE", "OVERDUE")) it else {
                    errors.add("$lineLabel: unknown Payment Status '$paymentStatusStr', treated as DUE")
                    "DUE"
                }
            }
            val mode = when (paymentModeStr.trim().uppercase()) {
                "UPI" -> "UPI"
                "BANK_TRANSFER", "BANK TRANSFER", "BANK", "NEFT", "IMPS", "RTGS" -> "BANK_TRANSFER"
                else -> "CASH"
            }
            // Amount Due isn't stored anywhere (PaymentEntity has no amountDue field) —
            // it's only sanity-checked against baseAmount - amountPaid and logged if it disagrees.
            val expectedDue = baseAmountPaise - amountPaidPaise
            if (amountDueStr.isNotBlank() && kotlin.math.abs(amountDuePaise - expectedDue) > 100L) {
                errors.add("$lineLabel: Amount Due ($amountDueStr) doesn't match Base Amount - Amount Paid, using computed value")
            }
            paymentRows.add(
                ImportPaymentRow(
                    memberPhone = phone,
                    groupName = groupName,
                    installmentNo = installmentNo,
                    baseAmountPaise = baseAmountPaise,
                    amountDuePaise = amountDuePaise,
                    amountPaidPaise = amountPaidPaise,
                    paymentStatus = status,
                    paymentMode = mode,
                    dueDateRaw = dueDateStr.trim(),
                    paidDateRaw = paidDateStr.trim()
                )
            )
        }
    }
    return 0
}

/** Best-effort parse of the Due Date column into epoch millis; null if unparseable. */
internal fun parseImportDate(raw: String): Long? {
    if (raw.isBlank()) return null
    val patterns = listOf("dd-MMM-yyyy", "dd MMM yyyy", "yyyy-MM-dd", "dd-MM-yyyy", "dd/MM/yyyy", "MM/dd/yyyy")
    for (pattern in patterns) {
        val parsed = runCatching {
            java.text.SimpleDateFormat(pattern, java.util.Locale.ENGLISH).apply { isLenient = false }.parse(raw)
        }.getOrNull()
        if (parsed != null) return parsed.time
    }
    return null
}

// ─── CSV Parser ───────────────────────────────────────────────────────────────

/** Parse a plain UTF-8 comma-separated CSV. */
private fun parseCsvFile(context: android.content.Context, uri: Uri): CsvImportResult {
    val members = mutableListOf<MemberEntity>()
    val groupMap = mutableMapOf<String, ChitGroupEntity>()
    val paymentRows = mutableListOf<ImportPaymentRow>()
    val errors = mutableListOf<String>()
    var skippedRows = 0
    val seenPhones = mutableSetOf<String>()

    try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
            var lineIndex = 0
            reader.forEachLine { rawLine ->
                lineIndex++
                if (lineIndex == 1) return@forEachLine // skip header
                val cols = rawLine.split(",").map { it.trim() }
                if (cols.size < 6) {
                    skippedRows++
                    errors.add("Row $lineIndex: not enough columns (${cols.size}), skipped")
                    return@forEachLine
                }
                skippedRows += rowToMemberAndGroup(
                    memberName       = cols.getOrElse(0) { "" },
                    phone            = cols.getOrElse(1) { "" },
                    groupName        = cols.getOrElse(2) { "" },
                    installmentNoStr = cols.getOrElse(3) { "" },
                    baseAmountStr    = cols.getOrElse(4) { "0" },
                    amountDueStr     = cols.getOrElse(5) { "0" },
                    amountPaidStr    = cols.getOrElse(6) { "0" },
                    paymentStatusStr = cols.getOrElse(7) { "" },
                    paymentModeStr   = cols.getOrElse(8) { "" },
                    dueDateStr       = cols.getOrElse(9) { "" },
                    members, groupMap, paymentRows, seenPhones, errors, "Row $lineIndex",
                    paidDateStr      = cols.getOrElse(10) { "" }
                )
            }
        }
    } catch (e: Exception) {
        errors.add("CSV read error: ${e.message}")
    }
    return CsvImportResult(members, groupMap.values.toList(), skippedRows, errors, paymentRows)
}

// ─── XLSX Parser (zero-dependency) ───────────────────────────────────────────
//
// XLSX = ZIP archive containing:
//   xl/sharedStrings.xml  – deduplicated string table (cells with t="s" reference this)
//   xl/worksheets/sheet1.xml – cell data
//
// We read both using ZipInputStream and parse XML with Android's XmlPullParser.

/** Parse a .xlsx file without any external library (works on minSdk 24). */
private fun parseXlsxFile(context: android.content.Context, uri: Uri): CsvImportResult {
    val members = mutableListOf<MemberEntity>()
    val groupMap = mutableMapOf<String, ChitGroupEntity>()
    val paymentRows = mutableListOf<ImportPaymentRow>()
    val errors = mutableListOf<String>()
    var skippedRows = 0
    val seenPhones = mutableSetOf<String>()

    try {
        // Buffer the whole file so we can make two passes (strings + sheet)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return CsvImportResult(emptyList(), emptyList(), 0, listOf("Could not open file"))

        // ── Pass 1: read shared string table ─────────────────────────────────
        val sharedStrings = mutableListOf<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "xl/sharedStrings.xml") {
                    readSharedStrings(zip, sharedStrings)
                    break
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        // ── Pass 2: read sheet rows ───────────────────────────────────────────
        val rows = mutableListOf<List<String>>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "xl/worksheets/sheet1.xml") {
                    readSheetRows(zip, sharedStrings, rows)
                    break
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        if (rows.isEmpty()) {
            return CsvImportResult(emptyList(), emptyList(), 0,
                listOf("No data found in the first sheet. Is the file empty?"))
        }

        // rows[0] = header row — skip it
        rows.drop(1).forEachIndexed { idx, cols ->
            if (cols.size < 6) {
                skippedRows++
                errors.add("Row ${idx + 2}: not enough columns (${cols.size}), skipped")
                return@forEachIndexed
            }
            skippedRows += rowToMemberAndGroup(
                memberName       = cols.getOrElse(0) { "" },
                phone            = cols.getOrElse(1) { "" },
                groupName        = cols.getOrElse(2) { "" },
                installmentNoStr = cols.getOrElse(3) { "" },
                baseAmountStr    = cols.getOrElse(4) { "0" },
                amountDueStr     = cols.getOrElse(5) { "0" },
                amountPaidStr    = cols.getOrElse(6) { "0" },
                paymentStatusStr = cols.getOrElse(7) { "" },
                paymentModeStr   = cols.getOrElse(8) { "" },
                dueDateStr       = cols.getOrElse(9) { "" },
                members, groupMap, paymentRows, seenPhones, errors, "Row ${idx + 2}",
                paidDateStr      = cols.getOrElse(10) { "" }
            )
        }
    } catch (e: Exception) {
        errors.add("XLSX read error: ${e.message}")
    }
    return CsvImportResult(members, groupMap.values.toList(), skippedRows, errors, paymentRows)
}

/**
 * Parse xl/sharedStrings.xml and populate [out].
 * Format: <sst><si><t>string value</t></si>…</sst>
 */
private fun readSharedStrings(stream: InputStream, out: MutableList<String>) {
    val parser = android.util.Xml.newPullParser()
    parser.setInput(stream, "UTF-8")
    var inSi = false
    var inT = false
    val currentText = StringBuilder()

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "si" -> { inSi = true; currentText.clear() }
                "t"  -> if (inSi) inT = true
            }
            XmlPullParser.TEXT -> if (inT) currentText.append(parser.text)
            XmlPullParser.END_TAG -> when (parser.name) {
                "t"  -> inT = false
                "si" -> { out.add(currentText.toString()); inSi = false }
            }
        }
        event = parser.next()
    }
}

/**
 * Parse xl/worksheets/sheet1.xml and append each row (as a list of strings) to [rows].
 * Handles shared-string cells (t="s"), numeric cells, and sparse columns (gaps in A→Z).
 */
private fun readSheetRows(
    stream: InputStream,
    sharedStrings: List<String>,
    rows: MutableList<List<String>>
) {
    val parser = android.util.Xml.newPullParser()
    parser.setInput(stream, "UTF-8")

    var inRow      = false
    var cellType   = ""        // "s" = shared string, else numeric/inline
    var inV        = false
    val cellValue  = StringBuilder()
    var currentRow = mutableListOf<Pair<Int, String>>() // (colIndex, value)
    var cellColIdx = 0

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "row" -> { inRow = true; currentRow = mutableListOf() }
                "c"   -> {
                    val cellRef = parser.getAttributeValue(null, "r") ?: ""
                    cellType   = parser.getAttributeValue(null, "t") ?: ""
                    cellColIdx = cellRefToColIndex(cellRef)
                    cellValue.clear()
                }
                "v"   -> if (inRow) inV = true
                "t"   -> if (inRow && cellType == "inlineStr") inV = true // <is><t> inline
            }
            XmlPullParser.TEXT -> if (inV) cellValue.append(parser.text)
            XmlPullParser.END_TAG -> when (parser.name) {
                "v", "t" -> inV = false
                "c" -> {
                    val raw = cellValue.toString().trim()
                    val resolved = if (cellType == "s") {
                        sharedStrings.getOrElse(raw.toIntOrNull() ?: -1) { raw }
                    } else {
                        // Numeric cells: drop trailing ".0" that Excel adds
                        raw.removeSuffix(".0")
                    }
                    currentRow.add(Pair(cellColIdx, resolved))
                }
                "row" -> {
                    if (inRow) {
                        // Rebuild as a dense list, respecting sparse column indices
                        val maxCol = currentRow.maxOfOrNull { it.first + 1 } ?: 0
                        val dense = Array(maxCol) { "" }
                        currentRow.forEach { (col, v) -> if (col < maxCol) dense[col] = v }
                        rows.add(dense.toList())
                        inRow = false
                    }
                }
            }
        }
        event = parser.next()
    }
}

/**
 * Converts an Excel cell reference column part (e.g. "A", "B", "AA") to a 0-based index.
 * "A" → 0, "B" → 1, "Z" → 25, "AA" → 26
 */
private fun cellRefToColIndex(cellRef: String): Int {
    val colStr = cellRef.takeWhile { it.isLetter() }.uppercase()
    var result = 0
    for (ch in colStr) result = result * 26 + (ch - 'A' + 1)
    return result - 1
}
