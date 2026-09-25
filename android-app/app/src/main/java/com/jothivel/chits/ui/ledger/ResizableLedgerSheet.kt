package com.jothivel.chits.ui.ledger

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.jothivel.chits.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.jothivel.chits.ui.components.AshAnimatedSearchBar
import com.jothivel.chits.ui.components.BottomSheetPickerField
import com.jothivel.chits.ui.components.PremiumInputField
import com.jothivel.chits.ui.theme.*
import com.jothivel.chits.data.local.AppDatabase
import com.jothivel.chits.data.local.LedgerEditService
import com.jothivel.chits.data.local.CollectionService
import com.jothivel.chits.utils.CsvDownloadHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset

private data class SheetColumn(val title: String, val initialWidth: Dp, val alignEnd: Boolean = false)

// Column order (must stay in sync with sheetColumns below and every index check in this file):
// 0 citsNo, 1 chitName, 2 members, 3 month, 4 chitValue, 5 startDate, 6 endDate,
// 7 customerCode, 8 serial, 9 oldCode, 10 mobile, 11 customerName, 12 address, 13 city,
// 14 labour, 15 collection, 16 settlement, 17 balance, 18 delivery.
private data class LedgerSheetRow(
    val memberId: String, val groupId: String,
    val citsNo: String, val chitName: String, val members: String, val month: String, val chitValue: String,
    val startDate: String, val endDate: String,
    val customerCode: String, val serial: String, val oldCode: String, val mobile: String,
    val customerName: String, val address: String, val city: String, val labour: String,
    val collection: String, val settlement: String, val balance: String, val delivery: String,
    val collectionValue: Long, val settlementValue: Long, val balanceValue: Long, val deliveryValue: Long
) {
    fun cells() = listOf(citsNo, chitName, members, month, chitValue, startDate, endDate, customerCode, serial, oldCode, mobile, customerName, address, city, labour, collection, settlement, balance, delivery)

    fun withCell(index: Int, value: String) = when (index) {
        0 -> copy(citsNo = value); 1 -> copy(chitName = value); 5 -> copy(startDate = value)
        6 -> copy(endDate = value); 7 -> copy(customerCode = value); 8 -> copy(serial = value)
        9 -> copy(oldCode = value); 10 -> copy(mobile = value); 11 -> copy(customerName = value)
        12 -> copy(address = value); 13 -> copy(city = value); 15 -> copy(collection = value)
        16 -> copy(settlement = value); 17 -> copy(balance = value); 18 -> copy(delivery = value)
        else -> this
    }
}

// One row of the per-member "Collection History" popup opened from the Collection Amount
// column - a plain read-only overlay Dialog, kept entirely separate from the sheet's own
// grid/cells() model so it can never disturb the ledger table's column widths or borders.
private data class PaymentHistoryEntry(val dateLabel: String, val amountLabel: String, val collectedBy: String?)

// The top "Chits" filter dropdown's options: a leading null-groupId entry ("All Chits") plus one
// entry per distinct chit group actually present in the loaded rows.
private data class LedgerGroupFilterOption(val groupId: String?, val label: String)

@Composable
fun ResizableLedgerSheet(onBack: () -> Unit) {
    val sheetColumns = listOf(
        SheetColumn(stringResource(R.string.ledger_col_cits_no), 66.dp), SheetColumn(stringResource(R.string.ledger_col_name), 92.dp),
        SheetColumn(stringResource(R.string.ledger_col_members), 76.dp), SheetColumn(stringResource(R.string.ledger_col_month), 70.dp),
        SheetColumn(stringResource(R.string.ledger_col_chit_value), 96.dp, true), SheetColumn(stringResource(R.string.ledger_col_start_date), 78.dp),
        SheetColumn(stringResource(R.string.ledger_col_end_date), 78.dp), SheetColumn(stringResource(R.string.ledger_col_customer_code), 94.dp), SheetColumn(stringResource(R.string.ledger_col_serial), 54.dp),
        SheetColumn(stringResource(R.string.ledger_col_old_code), 72.dp), SheetColumn(stringResource(R.string.ledger_col_mobile_no), 92.dp), SheetColumn(stringResource(R.string.ledger_col_customer_name), 112.dp),
        SheetColumn(stringResource(R.string.ledger_col_address1), 104.dp), SheetColumn(stringResource(R.string.ledger_col_city), 84.dp), SheetColumn(stringResource(R.string.ledger_col_labour), 84.dp),
        SheetColumn(stringResource(R.string.ledger_col_collection_amount), 128.dp, true),
        SheetColumn(stringResource(R.string.ledger_col_settlement_amount), 112.dp, true), SheetColumn(stringResource(R.string.ledger_col_balance), 88.dp, true), SheetColumn(stringResource(R.string.ledger_col_delivery_amount), 104.dp, true)
    )
    val labourDash = stringResource(R.string.ledger_labour_dash)
    val historyEmptyText = stringResource(R.string.ledger_history_empty)
    val labourColumnTitle = stringResource(R.string.ledger_col_labour)
    val dateColumnTitle = stringResource(R.string.ledger_history_date_col)
    val downloadedToText = stringResource(R.string.ledger_downloaded_to)
    val downloadFailedText = stringResource(R.string.ledger_download_failed)
    val cancelLabel = stringResource(R.string.ledger_cancel)
    val saveLabel = stringResource(R.string.ledger_save)
    val undoLabel = stringResource(R.string.ledger_undo)
    var query by remember { mutableStateOf("") }
    var selectedGroupFilterId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val rows = remember { mutableStateListOf<LedgerSheetRow>() }
    val paymentHistory = remember { mutableStateMapOf<String, List<PaymentHistoryEntry>>() }
    var historyRow by remember { mutableStateOf<LedgerSheetRow?>(null) }
    var selectedRow by remember { mutableStateOf<LedgerSheetRow?>(null) }
    var editingCell by remember { mutableStateOf<Pair<LedgerSheetRow, Int>?>(null) }
    var editValue by remember { mutableStateOf("") }
    val widths = remember { mutableStateListOf<Dp>().apply { addAll(sheetColumns.map { it.initialWidth }) } }
    val density = LocalDensity.current
    val horizontalState = rememberScrollState()
    val allChitsLabel = stringResource(R.string.ledger_chit_filter_all)
    val groupFilterOptions = listOf(LedgerGroupFilterOption(null, allChitsLabel)) +
        rows.distinctBy { it.groupId }.sortedBy { it.citsNo }.map { LedgerGroupFilterOption(it.groupId, "${it.citsNo} • ${it.chitName}") }
    val selectedGroupFilterOption = groupFilterOptions.firstOrNull { it.groupId == selectedGroupFilterId } ?: groupFilterOptions.first()
    val filteredRows = rows
        .filter { row -> selectedGroupFilterId == null || row.groupId == selectedGroupFilterId }
        .filter { row -> query.isBlank() || row.cells().any { it.contains(query, true) } }
    val totalWidth = widths.fold(0.dp) { acc, width -> acc + width }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val (databaseRows, historyByKey) = withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val members = db.memberDao().getAllMembersSync().associateBy { it.id }
            val groups = db.groupDao().getAllGroupsSync().associateBy { it.id }
            val financial = db.financialTransactionDao().getAllSync().filter { it.status == "POSTED" }.groupBy { it.memberId to it.groupId }
            val payments = db.paymentDao().getAllPaymentsSync().groupBy { it.memberId to it.groupId }
            val dateFormat = SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH)
            val historyMap = HashMap<String, List<PaymentHistoryEntry>>()
            val ledgerRows = db.membershipDao().getAllActiveSync().mapNotNull { membership ->
                val member = members[membership.memberId] ?: return@mapNotNull null
                val group = groups[membership.groupId] ?: return@mapNotNull null
                val breakdown = CollectionService.calculateDueBreakdown(db, member.id, group.id)
                val paid = breakdown.paidPaise / 100
                val entries = financial[member.id to group.id].orEmpty()
                val settlement = entries.filter { it.type == "SETTLEMENT" }.sumOf { it.amountPaise } / 100
                val delivery = entries.filter { it.type == "DELIVERY" }.sumOf { it.amountPaise } / 100
                val balance = paid - settlement
                // Payments are already ORDER BY paidAt DESC from the DAO, so the first non-blank
                // collectedBy is whoever most recently handled this member's chit - the name shown
                // in the Labour column. History rows keep every payment's own collector, so a
                // member who's been handled by more than one agent over time still shows correctly
                // who collected which installment.
                val memberPayments = payments[member.id to group.id].orEmpty()
                val labourName = memberPayments.firstOrNull { !it.collectedBy.isNullOrBlank() }?.collectedBy ?: labourDash
                historyMap["${member.id}:${group.id}"] = memberPayments.map { payment ->
                    PaymentHistoryEntry(
                        dateLabel = dateFormat.format(Date(payment.paidAt)),
                        amountLabel = "₹${formatLedgerNumber(payment.amountPaid / 100)}",
                        collectedBy = payment.collectedBy?.takeIf { it.isNotBlank() }
                    )
                }
                LedgerSheetRow(
                    memberId = member.id,
                    groupId = group.id,
                    citsNo = group.registerNo ?: group.id,
                    chitName = group.name.orEmpty(),
                    members = group.subscriberCount.toString(),
                    month = group.durationMonths.toString(),
                    chitValue = "₹${formatLedgerNumber(group.chitValue.toLong() / 100)}",
                    startDate = group.startDate.orEmpty(),
                    endDate = ledgerEndDate(group.startDate,group.durationMonths),
                    customerCode = member.id,
                    serial = membership.ticketNo.orEmpty(),
                    oldCode = member.panNo.orEmpty(),
                    mobile = member.phone.orEmpty(),
                    customerName = member.name.orEmpty(),
                    address = member.addressLine.orEmpty(),
                    city = member.city.orEmpty(),
                    labour = labourName,
                    collection = "₹${formatLedgerNumber(paid)}",
                    settlement = "₹${formatLedgerNumber(settlement)}",
                    balance = (if (balance < 0) "-₹" else "₹") + formatLedgerNumber(kotlin.math.abs(balance)),
                    delivery = "₹${formatLedgerNumber(delivery)}",
                    collectionValue = paid,
                    settlementValue = settlement,
                    balanceValue = balance,
                    deliveryValue = delivery
                )
            }
            ledgerRows to historyMap
        }
        rows.clear()
        rows.addAll(databaseRows)
        paymentHistory.clear()
        paymentHistory.putAll(historyByKey)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(MaroonBackground)) {
        Row(Modifier.fillMaxWidth().height(46.dp).background(Color.White).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.ArrowBack, "Back", tint = MaroonPrimary, modifier = Modifier.size(20.dp)) }
            AshAnimatedSearchBar(query, { query = it }, stringResource(R.string.ledger_search_placeholder), Modifier.weight(1f))
            Text(stringResource(R.string.ledger_rows_count, filteredRows.size), fontSize = 9.sp, color = TextGray)
            IconButton(onClick = { shareLedgerCsv(context, filteredRows, sheetColumns, downloadedToText, downloadFailedText) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.FileDownload, "Download ledger", tint = MaroonPrimary, modifier = Modifier.size(20.dp)) }
        }
        // Chit-wise filter: "All Chits" (default, matches the old unfiltered behaviour) or one
        // specific group, narrowing every row/total/export below to just that chit's members.
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ledger_chit_filter_label), fontSize = 9.sp, color = TextGray, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            BottomSheetPickerField(
                label = stringResource(R.string.ledger_chit_filter_label),
                options = groupFilterOptions,
                selectedOption = selectedGroupFilterOption,
                optionKey = { it.groupId ?: "ALL" },
                optionLabel = { it.label },
                onOptionSelected = { selectedGroupFilterId = it.groupId },
                showSearch = groupFilterOptions.size > 6,
                trigger = { displayText, onClick ->
                    Surface(
                        modifier = Modifier.clickable(onClick = onClick),
                        shape = RoundedCornerShape(50),
                        color = if (selectedGroupFilterId != null) SurfaceElevated else Color(0xFFF4F1F1),
                        border = BorderStroke(1.dp, DividerGray)
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(displayText, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Color(0xFF242528), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.ArrowDropDown, null, tint = MaroonPrimary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            )
        }
        Row(Modifier.fillMaxWidth().height(40.dp).background(Color.White).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.ledger_rows_count, filteredRows.size), fontSize = 9.sp, color = TextGray)
            LedgerTotal(stringResource(R.string.ledger_total_collection), filteredRows.sumOf { it.collectionValue }, AccentGreen)
            LedgerTotal(stringResource(R.string.ledger_total_settlement), filteredRows.sumOf { it.settlementValue }, Color(0xFF285A9B))
            LedgerTotal(stringResource(R.string.ledger_total_balance), filteredRows.sumOf { it.balanceValue }, AccentRed)
        }
        Box(Modifier.weight(1f).fillMaxWidth().border(1.dp, DividerGray).horizontalScroll(horizontalState)) {
            Column(Modifier.width(totalWidth).fillMaxHeight()) {
                Row(Modifier.height(34.dp).background(Color(0xFFECEDEF))) {
                    sheetColumns.forEachIndexed { index, column ->
                        ResizableHeaderCell(column, widths[index]) { dragPx ->
                            val dragDp = with(density) { dragPx.toDp() }
                            widths[index] = (widths[index] + dragDp).coerceIn(64.dp, 240.dp)
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filteredRows) { row ->
                        Row(Modifier.height(34.dp)) {
                            row.cells().forEachIndexed { index, value ->
                                LedgerDataCell(
                                    value = value,
                                    width = widths[index],
                                    index = index,
                                    rowSelected = selectedRow === row,
                                    onTap = { if (index == 8) selectedRow = row },
                                    onDoubleTap = {
                                        if (index in setOf(8, 9, 10, 11, 12, 13)) {
                                            editingCell = row to index
                                            editValue = value
                                        }
                                    },
                                    onHistoryClick = { if (index == 15) historyRow = if (historyRow === row) null else row },
                                    historyExpanded = historyRow === row,
                                    historyEntries = if (historyRow === row) paymentHistory["${row.memberId}:${row.groupId}"].orEmpty() else emptyList(),
                                    onDismissHistory = { historyRow = null },
                                    historyEmptyText = historyEmptyText,
                                    labourColumnTitle = labourColumnTitle,
                                    dateColumnTitle = dateColumnTitle
                                )
                            }
                        }
                    }
                }
            }
        }
        } // closes Column(2)
        
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
        )

    editingCell?.let { (row, columnIndex) ->
        val fieldUpdatedText = stringResource(R.string.ledger_field_updated, sheetColumns[columnIndex].title)
        val editFailedText = stringResource(R.string.ledger_edit_failed)
        Dialog(
            onDismissRequest = { editingCell = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.width(276.dp).wrapContentHeight(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFFFFFAFA),
                tonalElevation = 5.dp,
                shadowElevation = 8.dp
            ) {
                Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
                    Text(stringResource(R.string.ledger_edit_title, sheetColumns[columnIndex].title), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    PremiumInputField(
                        value = editValue,
                        onValueChange = { editValue = it },
                        label = sheetColumns[columnIndex].title,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { editingCell = null }, modifier = Modifier.height(36.dp)) { Text(cancelLabel, fontSize = 12.sp) }
                        TextButton(onClick = {
                            val rowIndex = rows.indexOfFirst { it === row }
                            if (rowIndex >= 0) {
                                val oldRow = row
                                val oldValue = oldRow.cells()[columnIndex]
                                val updated = row.withCell(columnIndex, editValue.trim())
                                rows[rowIndex] = updated
                                if (selectedRow === row) selectedRow = updated
                                
                                val newValue = editValue.trim()
                                coroutineScope.launch {
                                    // Save first; the screen only keeps the new value if the database accepted it.
                                    val saved = withContext(Dispatchers.IO) {
                                        runCatching { LedgerEditService.edit(AppDatabase.getDatabase(context), row.memberId, row.groupId, columnIndex, newValue) }
                                    }
                                    saved.exceptionOrNull()?.let { failure ->
                                        val at = rows.indexOfFirst { it === updated }
                                        if (at >= 0) rows[at] = oldRow
                                        if (selectedRow === updated) selectedRow = oldRow
                                        snackbarHostState.showSnackbar(String.format(editFailedText, failure.message.orEmpty()))
                                        return@launch
                                    }
                                    val result = snackbarHostState.showSnackbar(
                                        message = fieldUpdatedText,
                                        actionLabel = undoLabel,
                                        duration = SnackbarDuration.Long
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        // Undo: write the old value back, and only revert the screen if that worked.
                                        val undone = withContext(Dispatchers.IO) {
                                            runCatching { LedgerEditService.edit(AppDatabase.getDatabase(context), row.memberId, row.groupId, columnIndex, oldValue) }
                                        }
                                        val failure = undone.exceptionOrNull()
                                        if (failure == null) {
                                            val at = rows.indexOfFirst { it === updated }
                                            if (at >= 0) rows[at] = oldRow
                                            if (selectedRow === updated) selectedRow = oldRow
                                        } else {
                                            snackbarHostState.showSnackbar(String.format(editFailedText, failure.message.orEmpty()))
                                        }
                                    }
                                }
                            }
                            editingCell = null
                        }, modifier = Modifier.height(36.dp)) { Text(saveLabel, color = MaroonPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
        }
    }
}


private fun formatLedgerNumber(value: Long): String = NumberFormat.getNumberInstance(Locale("en", "IN")).format(value)

private fun ledgerEndDate(start:String?,months:Int):String {
    val parsed=listOf("dd-MMM-yyyy","yyyy-MM-dd","dd-MM-yyyy").firstNotNullOfOrNull { p->runCatching{SimpleDateFormat(p,Locale.ENGLISH).apply{isLenient=false}.parse(start.orEmpty())}.getOrNull() } ?: return "-"
    return SimpleDateFormat("dd-MMM-yyyy",Locale.ENGLISH).format(Calendar.getInstance().apply{time=parsed;add(Calendar.MONTH,months)}.time)
}

private fun shareLedgerCsv(context: android.content.Context, rows: List<LedgerSheetRow>, sheetColumns: List<SheetColumn>, downloadedToTemplate: String, downloadFailedTemplate: String) {
    fun csv(value: String) = "\"${value.replace("\"", "\"\"")}\""
    val content = buildString {
        appendLine(sheetColumns.joinToString(",") { csv(it.title) })
        rows.forEach { row -> appendLine(row.cells().joinToString(",") { csv(it) }) }
    }
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
        val fileName="Ledger-${SimpleDateFormat("yyyyMMdd-HHmm",Locale.US).format(Date())}.csv"
        val result=CsvDownloadHelper.save(context,fileName,content)
        withContext(Dispatchers.Main){android.widget.Toast.makeText(context,result.fold({String.format(downloadedToTemplate, it)},{String.format(downloadFailedTemplate, it.message)}),android.widget.Toast.LENGTH_LONG).show()}
    }
}

@Composable
private fun LedgerTotal(label: String, value: Long, color: Color) {
    Column(horizontalAlignment = Alignment.End) {
        Text(label, fontSize = 7.sp, color = TextGray)
        Text((if (value < 0) "-₹" else "₹") + formatLedgerNumber(kotlin.math.abs(value)), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (value < 0) AccentRed else color)
    }
}

@Composable
private fun ResizableHeaderCell(column: SheetColumn, width: Dp, onDrag: (Float) -> Unit) {
    Box(Modifier.width(width).fillMaxHeight().drawBehind {
        val stroke = 1.dp.toPx()
        val borderX = size.width - (stroke / 2f)
        val borderY = size.height - (stroke / 2f)
        drawLine(color = DividerGray, start = Offset(borderX, 0f), end = Offset(borderX, size.height), strokeWidth = stroke)
        drawLine(color = DividerGray, start = Offset(0f, borderY), end = Offset(size.width, borderY), strokeWidth = stroke)
    }) {
        Text(column.title, Modifier.align(Alignment.CenterStart).padding(horizontal = 8.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box(
            Modifier.align(Alignment.CenterEnd).width(12.dp).fillMaxHeight().pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount -> change.consume(); onDrag(dragAmount) }
            }, contentAlignment = Alignment.Center
        ) { Box(Modifier.width(2.dp).fillMaxHeight().background(MaroonPrimary.copy(alpha = .35f))) }
    }
}

@Composable
private fun LedgerDataCell(
    value: String,
    width: Dp,
    index: Int,
    rowSelected: Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onHistoryClick: () -> Unit = {},
    historyExpanded: Boolean = false,
    historyEntries: List<PaymentHistoryEntry> = emptyList(),
    onDismissHistory: () -> Unit = {},
    historyEmptyText: String = "",
    labourColumnTitle: String = "",
    dateColumnTitle: String = ""
) {
    // Column order: 0 citsNo, 1 chitName, 2 members, 3 month, 4 chitValue, 5 startDate,
    // 6 endDate, 7 customerCode, 8 serial, 9 oldCode, 10 mobile, 11 customerName, 12 address,
    // 13 city, 14 labour, 15 collection, 16 settlement, 17 balance, 18 delivery.
    val isMoney = index == 4 || index >= 15
    val color = when {
        value.startsWith("-") -> AccentRed
        index == 16 -> Color(0xFF285A9B)
        index == 18 && value != "₹0" -> AccentGreen
        else -> Color(0xFF242528)
    }
    val rowColor = if (rowSelected) MaroonPrimary.copy(alpha = .11f) else Color.White
    Box(
        Modifier.width(width).fillMaxHeight().background(rowColor).drawBehind {
            val stroke = if (rowSelected) 1.5.dp.toPx() else 1.dp.toPx()
            val borderColor = if (rowSelected) MaroonPrimary.copy(alpha = 0.42f) else DividerGray
            val borderX = size.width - (stroke / 2f)
            val borderY = size.height - (stroke / 2f)
            drawLine(color = borderColor, start = Offset(borderX, 0f), end = Offset(borderX, size.height), strokeWidth = stroke)
            drawLine(color = borderColor, start = Offset(0f, borderY), end = Offset(size.width, borderY), strokeWidth = stroke)
        }.pointerInput(index, value) {
            detectTapGestures(onTap = { onTap() }, onDoubleTap = { onDoubleTap() })
        },
        contentAlignment = if (isMoney) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        if (index == 15) {
            // Collection Amount only: a small history icon opens a mini-ledger dropdown -
            // anchored right below this icon (same open/close animation as every other
            // dropdown in the app), not a full-screen dialog. Its own clickable is a child
            // drawn after the cell's own tap-gesture modifier above, so it reliably grabs the
            // tap first without touching that gesture detector or the grid's border-drawing
            // logic at all.
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Box(
                        Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onHistoryClick),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, tint = MaroonPrimary.copy(alpha = .65f), modifier = Modifier.size(12.dp))
                    }
                    if (historyExpanded) {
                        Popup(
                            alignment = Alignment.BottomStart,
                            offset = IntOffset(0, with(LocalDensity.current) { 6.dp.roundToPx() }),
                            onDismissRequest = onDismissHistory,
                            properties = PopupProperties()
                        ) {
                            MiniLedgerHistoryCard(historyEntries, historyEmptyText, labourColumnTitle, dateColumnTitle)
                        }
                    }
                }
                Spacer(Modifier.width(3.dp))
                Text(value, fontSize = 10.sp, color = color, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
            }
        } else {
            Text(value, Modifier.padding(horizontal = 7.dp), fontSize = 10.sp, color = color, fontWeight = if (isMoney) FontWeight.Medium else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = if (isMoney) TextAlign.End else TextAlign.Start)
        }
    }
}

// Tooltip-style caret pointing up at the history icon it came from, so it's obvious which
// row a floating mini-ledger belongs to - card background matches the ledger's own page
// background (MaroonBackground) rather than a tinted surface, with its own shadow to lift it
// off the table, plus real column divider lines so it reads as a small ledger, not a plain list.
@Composable
private fun MiniLedgerHistoryCard(
    entries: List<PaymentHistoryEntry>,
    emptyText: String,
    labourColumnTitle: String,
    dateColumnTitle: String
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.85f, animationSpec = tween(160)),
        exit = fadeOut(tween(90))
    ) {
        Column {
            Canvas(Modifier.padding(start = 5.dp).size(width = 14.dp, height = 7.dp)) {
                drawPath(
                    Path().apply {
                        moveTo(0f, size.height)
                        lineTo(size.width / 2f, 0f)
                        lineTo(size.width, size.height)
                        close()
                    },
                    color = MaroonBackground
                )
            }
            Surface(
                // Popup content isn't width-constrained by its anchor, so M3's Divider (which
                // defaults to fillMaxWidth) was stretching this card edge-to-edge across the
                // whole screen. IntrinsicSize.Min forces the card to size to its own fixed
                // Labour/Date/Amount columns instead - just wide enough for those 3 cells.
                modifier = Modifier
                    .width(IntrinsicSize.Min)
                    .shadow(6.dp, RoundedCornerShape(10.dp), clip = false, ambientColor = MaroonPrimary.copy(alpha = .14f), spotColor = MaroonPrimary.copy(alpha = .20f)),
                shape = RoundedCornerShape(10.dp),
                color = MaroonBackground
            ) {
                Column {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                        MiniLedgerCell(labourColumnTitle, 72.dp, bold = true)
                        MiniLedgerCell(dateColumnTitle, 72.dp, bold = true)
                        MiniLedgerCell("₹", 64.dp, bold = true, alignEnd = true, showDivider = false)
                    }
                    Divider(color = DividerGray)
                    if (entries.isEmpty()) {
                        Text(emptyText, color = TextGray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp))
                    } else {
                        // Its own bounded, independently-scrolling column - if a member has a
                        // lot of collections, this list scrolls in place instead of growing
                        // the popup past the screen.
                        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                            entries.forEach { entry ->
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                    MiniLedgerCell(entry.collectedBy ?: "-", 72.dp)
                                    MiniLedgerCell(entry.dateLabel, 72.dp)
                                    MiniLedgerCell(entry.amountLabel, 64.dp, color = AccentGreen, alignEnd = true, showDivider = false)
                                }
                                Divider(color = DividerGray.copy(alpha = .5f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniLedgerCell(
    text: String,
    width: Dp,
    bold: Boolean = false,
    color: Color = Color(0xFF242528),
    alignEnd: Boolean = false,
    showDivider: Boolean = true
) {
    Text(
        text,
        modifier = Modifier
            .width(width)
            .then(
                if (showDivider) Modifier.drawBehind {
                    val stroke = 1.dp.toPx()
                    drawLine(DividerGray, Offset(size.width - stroke / 2f, 0f), Offset(size.width - stroke / 2f, size.height), stroke)
                } else Modifier
            )
            .padding(end = 8.dp),
        fontSize = 10.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
        color = if (bold) TextGray else color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start
    )
}
