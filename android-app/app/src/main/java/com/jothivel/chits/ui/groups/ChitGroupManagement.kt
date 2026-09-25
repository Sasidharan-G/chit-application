package com.jothivel.chits.ui.groups

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.jothivel.chits.R
import com.jothivel.chits.data.local.AppDatabase
import com.jothivel.chits.data.local.ChitAdminService
import com.jothivel.chits.data.local.entity.ChitGroupEntity
import com.jothivel.chits.data.local.entity.ChitMembershipEntity
import com.jothivel.chits.data.local.entity.InstallmentEntity
import com.jothivel.chits.ui.components.AmountKeypadField
import com.jothivel.chits.ui.components.BottomSheetPickerField
import com.jothivel.chits.ui.components.ConfirmBottomSheet
import com.jothivel.chits.ui.components.DateBottomSheetField
import com.jothivel.chits.ui.components.PremiumInputField
import com.jothivel.chits.ui.theme.AccentGreen
import com.jothivel.chits.ui.theme.AccentOrange
import com.jothivel.chits.ui.theme.AccentRed
import com.jothivel.chits.ui.theme.DividerGray
import com.jothivel.chits.ui.theme.MaroonPrimary
import com.jothivel.chits.ui.theme.TextGray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun rupees(paise: Long): String = java.text.NumberFormat.getNumberInstance(Locale("en", "IN")).format(paise / 100)

private fun isClosed(group: ChitGroupEntity) = group.status == "COMPLETED"

/** Edit / Close (or Reopen) buttons shown under the chit's details card. */
@Composable
fun ChitGroupActionsRow(group: ChitGroupEntity, onEdit: () -> Unit, onCloseOrReopen: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f).height(40.dp), shape = RoundedCornerShape(10.dp)) {
            Icon(Icons.Default.Edit, null, tint = MaroonPrimary, modifier = Modifier.size(16.dp))
            Text("  " + stringResource(R.string.group_edit_button), color = MaroonPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(onClick = onCloseOrReopen, modifier = Modifier.weight(1f).height(40.dp), shape = RoundedCornerShape(10.dp)) {
            Icon(if (isClosed(group)) Icons.Default.LockOpen else Icons.Default.Lock, null, tint = MaroonPrimary, modifier = Modifier.size(16.dp))
            Text(
                "  " + stringResource(if (isClosed(group)) R.string.group_reopen_button else R.string.group_close_button),
                color = MaroonPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** Edit-chit sheet: name, number, branch, start date and member count. Value / months are locked. */
@Composable
fun EditChitSheet(group: ChitGroupEntity, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(group.name.orEmpty()) }
    var registerNo by remember { mutableStateOf(group.registerNo.orEmpty()) }
    var branch by remember { mutableStateOf(group.branch.orEmpty()) }
    var startDate by remember { mutableStateOf(group.startDate.orEmpty()) }
    var members by remember { mutableStateOf(group.subscriberCount.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    val savedToast = stringResource(R.string.group_edit_saved)

    ConfirmBottomSheet(
        show = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.group_edit_title),
        message = stringResource(R.string.group_edit_value_note),
        confirmLabel = stringResource(R.string.common_save),
        cancelLabel = stringResource(R.string.common_cancel),
        onConfirm = {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        ChitAdminService.updateGroupDetails(
                            AppDatabase.getDatabase(context), group.id, name, registerNo, branch, startDate,
                            members.toIntOrNull() ?: 0
                        )
                    }
                }
                result.onSuccess {
                    Toast.makeText(context, savedToast, Toast.LENGTH_SHORT).show()
                    onSaved()
                }.onFailure {
                    // The sheet has already closed by the time we know; surface the reason and reopen is not
                    // needed - the message says exactly what to fix.
                    Toast.makeText(context, it.message ?: "Could not save", Toast.LENGTH_LONG).show()
                }
            }
        },
        content = {
            PremiumInputField(registerNo, { registerNo = it; error = null }, stringResource(R.string.chits_no_field), Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            PremiumInputField(name, { name = it }, stringResource(R.string.chits_name_field), Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            PremiumInputField(branch, { branch = it }, stringResource(R.string.chits_branch_field), Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            DateBottomSheetField(label = stringResource(R.string.chits_start_date_field), value = startDate, onValueChange = { startDate = it }, modifier = Modifier.fillMaxWidth().height(54.dp))
            Spacer(Modifier.height(8.dp))
            PremiumInputField(
                members, { members = it.filter(Char::isDigit).take(3) }, stringResource(R.string.chits_members_field), Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
    )
}

/** Close a finished chit (warning when members still owe money) or reopen a closed one. */
@Composable
fun CloseChitSheet(group: ChitGroupEntity, pendingMembers: Int, onDismiss: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reopening = isClosed(group)
    val closedToast = stringResource(R.string.group_closed_toast)
    val reopenedToast = stringResource(R.string.group_reopened_toast)
    ConfirmBottomSheet(
        show = true,
        onDismiss = onDismiss,
        title = stringResource(if (reopening) R.string.group_reopen_title else R.string.group_close_title),
        message = buildString {
            append(stringResource(if (reopening) R.string.group_reopen_message else R.string.group_close_message))
            if (!reopening && pendingMembers > 0) append("\n\n").append(stringResource(R.string.group_close_with_dues, pendingMembers))
        },
        confirmLabel = stringResource(
            when {
                reopening -> R.string.group_reopen_button
                pendingMembers > 0 -> R.string.group_close_anyway
                else -> R.string.group_close_button
            }
        ),
        cancelLabel = stringResource(R.string.common_cancel),
        isDestructive = !reopening && pendingMembers > 0,
        onConfirm = {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val db = AppDatabase.getDatabase(context)
                        if (reopening) ChitAdminService.reopenGroup(db, group.id) else ChitAdminService.closeGroup(db, group.id, force = true)
                    }
                }
                result.onSuccess {
                    Toast.makeText(context, if (reopening) reopenedToast else closedToast, Toast.LENGTH_SHORT).show()
                    onDone()
                }.onFailure { Toast.makeText(context, it.message ?: "Failed", Toast.LENGTH_LONG).show() }
            }
        }
    )
}

/** Confirmation before taking a member out of a chit; shows what they still owe. */
@Composable
fun RemoveMemberSheet(memberName: String, memberId: String, groupId: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owed by androidx.compose.runtime.produceState(initialValue = 0L, memberId, groupId) {
        value = withContext(Dispatchers.IO) { ChitAdminService.pendingDuePaise(AppDatabase.getDatabase(context), memberId, groupId) }
    }
    val removedToast = stringResource(R.string.group_removed_toast)
    ConfirmBottomSheet(
        show = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.group_remove_member_title, memberName),
        message = buildString {
            append(stringResource(R.string.group_remove_member_message))
            if (owed > 0) append("\n\n").append(stringResource(R.string.group_remove_member_dues, rupees(owed)))
        },
        confirmLabel = stringResource(R.string.group_remove_confirm),
        cancelLabel = stringResource(R.string.common_cancel),
        isDestructive = true,
        onConfirm = {
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { ChitAdminService.deactivateMember(AppDatabase.getDatabase(context), memberId, groupId) } }
                result.onSuccess {
                    Toast.makeText(context, removedToast, Toast.LENGTH_SHORT).show()
                    onDone()
                }.onFailure { Toast.makeText(context, it.message ?: "Failed", Toast.LENGTH_LONG).show() }
            }
        }
    )
}

/** Members who left the chit, each with a Rejoin button. Draws nothing when there are none. */
@Composable
fun LeftMembersCard(left: List<ChitMembershipEntity>, names: Map<String, String>, groupId: String, onChanged: () -> Unit) {
    if (left.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rejoinedToast = stringResource(R.string.group_rejoined_toast)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = Color.White, border = BorderStroke(1.dp, DividerGray)) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.group_left_members, left.size), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextGray)
            left.forEach { ms ->
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(names[ms.memberId] ?: ms.memberId, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { ChitAdminService.reactivateMember(AppDatabase.getDatabase(context), ms.memberId, groupId) } }
                            result.onSuccess {
                                Toast.makeText(context, rejoinedToast, Toast.LENGTH_SHORT).show()
                                onChanged()
                            }.onFailure { Toast.makeText(context, it.message ?: "Failed", Toast.LENGTH_LONG).show() }
                        }
                    }) { Text(stringResource(R.string.group_rejoin), color = MaroonPrimary, fontSize = 12.sp) }
                }
            }
        }
    }
}

/**
 * Month-by-month auction results: who won each installment and the prize. Setting a winner is what
 * later allows (and caps) a Delivery entry for that customer.
 */
@Composable
fun WinnersCard(
    group: ChitGroupEntity,
    installments: List<InstallmentEntity>,
    activeMembers: List<ChitMembershipEntity>,
    names: Map<String, String>,
    onChanged: () -> Unit
) {
    var setFor by remember { mutableStateOf<InstallmentEntity?>(null) }
    var clearFor by remember { mutableStateOf<InstallmentEntity?>(null) }
    val editable = group.status.isNullOrBlank() || group.status == "ACTIVE"

    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color.White, border = BorderStroke(1.dp, DividerGray)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.EmojiEvents, null, tint = MaroonPrimary, modifier = Modifier.size(18.dp))
                Text("  " + stringResource(R.string.group_winners_title), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            installments.sortedBy { it.installmentNo }.forEach { inst ->
                val winner = inst.winningMemberId?.takeIf { it.isNotBlank() }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.chits_month_number, inst.installmentNo), fontSize = 11.sp, color = TextGray, modifier = Modifier.weight(.9f))
                    if (winner != null) {
                        Column(Modifier.weight(2.2f)) {
                            Text(names[winner] ?: winner, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AccentGreen)
                            Text("₹${rupees((inst.payoutAmount ?: 0).toLong())} • ${inst.auctionDate.orEmpty()}", fontSize = 9.sp, color = TextGray)
                        }
                        if (editable) TextButton(onClick = { clearFor = inst }) { Text(stringResource(R.string.group_winner_clear), color = AccentRed, fontSize = 11.sp) }
                    } else {
                        Text(stringResource(R.string.group_winner_none), fontSize = 11.sp, color = TextGray, modifier = Modifier.weight(2.2f))
                        if (editable) TextButton(onClick = { setFor = inst }) { Text(stringResource(R.string.group_winner_set), color = MaroonPrimary, fontSize = 11.sp) }
                    }
                }
            }
        }
    }

    setFor?.let { inst ->
        val alreadyWon = installments.mapNotNull { it.winningMemberId?.takeIf { w -> w.isNotBlank() } }.toSet()
        SetWinnerSheet(group, inst, activeMembers.filter { it.memberId !in alreadyWon }, names, onDismiss = { setFor = null }, onSaved = { setFor = null; onChanged() })
    }
    clearFor?.let { inst ->
        ClearWinnerSheet(group.id, inst, onDismiss = { clearFor = null }, onDone = { clearFor = null; onChanged() })
    }
}

@Composable
private fun SetWinnerSheet(
    group: ChitGroupEntity,
    installment: InstallmentEntity,
    eligible: List<ChitMembershipEntity>,
    names: Map<String, String>,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<ChitMembershipEntity?>(null) }
    // Pre-filled from the printed schedule when the chit has one; always editable (the real
    // auction result is what counts).
    var payout by remember { mutableStateOf(((installment.payoutAmount ?: 0) / 100).takeIf { it > 0 }?.toString().orEmpty()) }
    var date by remember { mutableStateOf(SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).format(Date())) }
    val savedToast = stringResource(R.string.group_winner_saved_toast)

    ConfirmBottomSheet(
        show = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.group_winner_dialog_title, installment.installmentNo),
        message = if (eligible.isEmpty()) stringResource(R.string.group_winner_no_eligible) else null,
        confirmLabel = stringResource(R.string.group_winner_save),
        cancelLabel = stringResource(R.string.common_cancel),
        confirmEnabled = selected != null && (payout.toLongOrNull() ?: 0L) > 0,
        onConfirm = {
            val member = selected ?: return@ConfirmBottomSheet
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        ChitAdminService.setWinner(AppDatabase.getDatabase(context), group.id, installment.installmentNo, member.memberId, (payout.toLongOrNull() ?: 0L) * 100, date)
                    }
                }
                result.onSuccess {
                    Toast.makeText(context, savedToast, Toast.LENGTH_SHORT).show()
                    onSaved()
                }.onFailure { Toast.makeText(context, it.message ?: "Failed", Toast.LENGTH_LONG).show() }
            }
        },
        content = {
            BottomSheetPickerField(
                label = stringResource(R.string.group_winner_member),
                options = eligible,
                selectedOption = selected,
                optionKey = { it.memberId },
                optionLabel = { names[it.memberId] ?: it.memberId },
                onOptionSelected = { selected = it },
                modifier = Modifier.fillMaxWidth(),
                showSearch = true
            )
            Spacer(Modifier.height(8.dp))
            AmountKeypadField(stringResource(R.string.group_winner_payout), payout, { payout = it }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            DateBottomSheetField(label = stringResource(R.string.group_winner_date), value = date, onValueChange = { date = it }, modifier = Modifier.fillMaxWidth().height(54.dp))
        }
    )
}

@Composable
private fun ClearWinnerSheet(groupId: String, installment: InstallmentEntity, onDismiss: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf("") }
    val clearedToast = stringResource(R.string.group_winner_cleared_toast)
    ConfirmBottomSheet(
        show = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.group_winner_clear_title, installment.installmentNo),
        confirmLabel = stringResource(R.string.group_winner_clear),
        cancelLabel = stringResource(R.string.common_cancel),
        isDestructive = true,
        confirmEnabled = reason.trim().length >= 4,
        onConfirm = {
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { ChitAdminService.clearWinner(AppDatabase.getDatabase(context), groupId, installment.installmentNo, reason) } }
                result.onSuccess {
                    Toast.makeText(context, clearedToast, Toast.LENGTH_SHORT).show()
                    onDone()
                }.onFailure { Toast.makeText(context, it.message ?: "Failed", Toast.LENGTH_LONG).show() }
            }
        },
        content = {
            PremiumInputField(reason, { reason = it.take(100) }, stringResource(R.string.group_winner_reason), Modifier.fillMaxWidth(), leadingIcon = Icons.Default.Notes)
        }
    )
}

/** Small "Closed" tag for chit cards / headers. */
@Composable
fun ChitClosedTag() {
    Surface(shape = RoundedCornerShape(6.dp), color = AccentOrange.copy(alpha = .15f)) {
        Text(stringResource(R.string.group_status_closed), color = AccentOrange, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
