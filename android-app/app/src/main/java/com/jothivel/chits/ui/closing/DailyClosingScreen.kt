package com.jothivel.chits.ui.closing

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Divider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jothivel.chits.R
import com.jothivel.chits.data.local.AgentCashSummary
import com.jothivel.chits.data.local.AppDatabase
import com.jothivel.chits.data.local.CashService
import com.jothivel.chits.data.local.CollectionService
import com.jothivel.chits.data.local.DailyCashSummary
import com.jothivel.chits.data.local.DueLoader
import com.jothivel.chits.data.local.entity.CashHandoverEntity
import com.jothivel.chits.ui.BrandTopBar
import com.jothivel.chits.ui.components.AmountKeypadField
import com.jothivel.chits.ui.components.ConfirmBottomSheet
import com.jothivel.chits.ui.components.PremiumInputField
import com.jothivel.chits.ui.theme.AccentGreen
import com.jothivel.chits.ui.theme.AccentRed
import com.jothivel.chits.ui.theme.DividerGray
import com.jothivel.chits.ui.theme.MaroonBackground
import com.jothivel.chits.ui.theme.MaroonPrimary
import com.jothivel.chits.ui.theme.TextGray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private fun rupees(paise: Long): String = (if (paise < 0) "-₹" else "₹") + NumberFormat.getNumberInstance(Locale("en", "IN")).format(kotlin.math.abs(paise) / 100)

private data class ClosingData(
    val summary: DailyCashSummary,
    val agents: List<AgentCashSummary>,
    val handoversToday: List<CashHandoverEntity>,
    val stillDuePaise: Long
)

/**
 * End-of-day summary: what was collected today (office cash, agent cash, UPI, bank), what was paid
 * out, and the cash each agent still holds. A handover can be recorded or reversed here.
 */
@Composable
fun DailyClosingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = CollectionService.todayKey()
    var refreshKey by remember { mutableIntStateOf(0) }

    val data by produceState<ClosingData?>(null, refreshKey) {
        value = withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val summary = CashService.dailySummary(db, today)
            val startOfDay = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(today)!!.time
            val todayLabel = SimpleDateFormat("dd-MMM-yy", Locale.ENGLISH).format(Date())
            ClosingData(
                summary = summary,
                agents = CashService.agentSummaries(db),
                handoversToday = db.cashHandoverDao().getAllSync().filter { it.handedAt >= startOfDay },
                stillDuePaise = DueLoader.loadAll(db)
                    .filter { it.breakdown.pendingPaise > 0 && it.breakdown.earliestDueDate.equals(todayLabel, true) }
                    .sumOf { it.breakdown.pendingPaise }
            )
        }
    }

    var handoverFor by remember { mutableStateOf<AgentCashSummary?>(null) }
    var reverseFor by remember { mutableStateOf<CashHandoverEntity?>(null) }

    val snapshot = data

    val handoverSavedToast = stringResource(R.string.closing_handover_saved)
    val reversedToast = stringResource(R.string.closing_handover_reversed)
    val shareChooser = stringResource(R.string.closing_share_chooser)
    val labels = ClosingLabels(
        officeCash = stringResource(R.string.closing_cash_office),
        agentCash = stringResource(R.string.closing_cash_agents),
        upi = stringResource(R.string.closing_upi),
        bank = stringResource(R.string.closing_bank),
        settlement = stringResource(R.string.closing_settlement),
        delivery = stringResource(R.string.closing_delivery),
        handovers = stringResource(R.string.closing_handover_today),
        title = stringResource(R.string.closing_title)
    )

    Column(Modifier.fillMaxSize().background(MaroonBackground)) {
        val shareAction: (() -> Unit)? = snapshot?.let { s ->
            {
                val text = shareText(labels, today, s.summary)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, shareChooser))
            }
        }
        BrandTopBar(stringResource(R.string.closing_title), back = onBack, action = Icons.Default.Share, onAction = shareAction)

        if (snapshot == null) return@Column
        val s = snapshot.summary

        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(SimpleDateFormat("EEEE, dd MMM yyyy", Locale.ENGLISH).format(Date()), fontSize = 11.sp, color = TextGray) }

            item {
                Card(stringResource(R.string.closing_section_collected)) {
                    Line(labels.officeCash, rupees(s.directCashPaise), AccentGreen)
                    Line(labels.agentCash, rupees(s.allCashCollectedPaise - s.directCashPaise), TextGray)
                    Line(labels.upi, rupees(s.upiPaise), Color(0xFF285A9B))
                    Line(labels.bank, rupees(s.bankPaise), MaroonPrimary)
                }
            }
            item {
                Card(stringResource(R.string.closing_section_payouts)) {
                    Line(labels.settlement, rupees(s.settlementPaise), Color(0xFF285A9B))
                    Line(labels.delivery, rupees(s.deliveryPaise), AccentGreen)
                }
            }

            // ── Agents holding cash ─────────────────────────────────────────────────────────
            item {
                Card(stringResource(R.string.closing_section_agents)) {
                    if (snapshot.agents.isEmpty()) Text(stringResource(R.string.closing_agents_none), fontSize = 11.sp, color = TextGray)
                    snapshot.agents.forEach { agent ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(agent.agentName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    stringResource(R.string.closing_agent_holds, rupees(agent.outstandingPaise).removePrefix("₹"), rupees(agent.collectedPaise).removePrefix("₹"), rupees(agent.handedOverPaise).removePrefix("₹")),
                                    fontSize = 9.sp, color = if (agent.outstandingPaise > 0) AccentRed else TextGray
                                )
                            }
                            if (agent.outstandingPaise > 0) TextButton(onClick = { handoverFor = agent }) {
                                Text(stringResource(R.string.closing_handover), color = MaroonPrimary, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            if (snapshot.handoversToday.isNotEmpty()) item {
                Card(stringResource(R.string.closing_handover_today)) {
                    snapshot.handoversToday.forEach { h ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(h.agentName, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text(rupees(h.amountPaise), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (h.status == "POSTED") AccentGreen else TextGray)
                            if (h.status == "POSTED") TextButton(onClick = { reverseFor = h }) { Text(stringResource(R.string.closing_handover_reverse), color = AccentRed, fontSize = 10.sp) }
                        }
                    }
                }
            }

            item { Line(stringResource(R.string.closing_still_due), rupees(snapshot.stillDuePaise), TextGray, plain = true) }
        }
    }

    handoverFor?.let { agent ->
        var amount by remember(agent.agentId) { mutableStateOf((agent.outstandingPaise / 100).toString()) }
        var handoverNote by remember(agent.agentId) { mutableStateOf("") }
        ConfirmBottomSheet(
            show = true,
            onDismiss = { handoverFor = null },
            title = stringResource(R.string.closing_handover_title, agent.agentName),
            confirmLabel = stringResource(R.string.common_save),
            cancelLabel = stringResource(R.string.common_cancel),
            confirmEnabled = (amount.toLongOrNull() ?: 0L) > 0,
            onConfirm = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            CashService.recordHandover(AppDatabase.getDatabase(context), UUID.randomUUID().toString(), agent.agentId, agent.agentName, (amount.toLongOrNull() ?: 0L) * 100, handoverNote)
                        }
                    }
                    result.onSuccess {
                        Toast.makeText(context, handoverSavedToast, Toast.LENGTH_SHORT).show()
                        handoverFor = null
                        refreshKey++
                    }.onFailure { Toast.makeText(context, it.message ?: "Failed", Toast.LENGTH_LONG).show() }
                }
            },
            content = {
                AmountKeypadField(stringResource(R.string.closing_handover_amount), amount, { amount = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                PremiumInputField(handoverNote, { handoverNote = it.take(100) }, stringResource(R.string.fin_notes), Modifier.fillMaxWidth(), leadingIcon = Icons.Default.Notes)
            }
        )
    }

    reverseFor?.let { entry ->
        var reason by remember(entry.id) { mutableStateOf("") }
        ConfirmBottomSheet(
            show = true,
            onDismiss = { reverseFor = null },
            title = stringResource(R.string.closing_handover_reverse_title),
            confirmLabel = stringResource(R.string.closing_handover_reverse),
            cancelLabel = stringResource(R.string.common_cancel),
            isDestructive = true,
            confirmEnabled = reason.trim().length >= 4,
            onConfirm = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { CashService.reverseHandover(AppDatabase.getDatabase(context), entry.id, reason) } }
                    result.onSuccess {
                        Toast.makeText(context, reversedToast, Toast.LENGTH_SHORT).show()
                        reverseFor = null
                        refreshKey++
                    }.onFailure { Toast.makeText(context, it.message ?: "Failed", Toast.LENGTH_LONG).show() }
                }
            },
            content = { PremiumInputField(reason, { reason = it.take(100) }, stringResource(R.string.fin_reason), Modifier.fillMaxWidth(), leadingIcon = Icons.Default.Notes) }
        )
    }
}

private data class ClosingLabels(
    val officeCash: String, val agentCash: String, val upi: String, val bank: String,
    val settlement: String, val delivery: String, val handovers: String, val title: String
)

private fun shareText(l: ClosingLabels, date: String, s: DailyCashSummary): String {
    return buildString {
        appendLine("Jothi Vel Chits - ${l.title} ($date)")
        appendLine("${l.officeCash}: ${rupees(s.directCashPaise)}")
        appendLine("${l.agentCash}: ${rupees(s.allCashCollectedPaise - s.directCashPaise)}")
        appendLine("${l.handovers}: ${rupees(s.agentHandoverPaise)}")
        appendLine("${l.upi}: ${rupees(s.upiPaise)}")
        appendLine("${l.bank}: ${rupees(s.bankPaise)}")
        appendLine("${l.settlement}: ${rupees(s.settlementPaise)}")
        append("${l.delivery}: ${rupees(s.deliveryPaise)}")
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color.White, border = BorderStroke(1.dp, DividerGray)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaroonPrimary)
            Divider(Modifier.padding(vertical = 6.dp), color = DividerGray)
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: String, color: Color, plain: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).let { if (plain) it.padding(horizontal = 4.dp) else it }, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 11.sp, color = TextGray)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
    }
}
