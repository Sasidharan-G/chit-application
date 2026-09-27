package com.jothivel.chits.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jothivel.chits.R
import com.jothivel.chits.data.firebase.AdminAccount
import com.jothivel.chits.ui.theme.AccentRed
import com.jothivel.chits.ui.theme.MaroonPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Already use this app?" on the daily PIN login screen - for a reinstalled or brand new admin phone.
 * The cloud account is fixed ([AdminAccount]); only the admin's existing 4-digit PIN is asked, and it is
 * checked against the one already saved in the cloud (see [AdminAccount.restoreWithPin]) instead of
 * making the admin choose a fresh PIN and lose the one they already know.
 */
@Composable
fun RestoreWithPinDialog(onDismiss: () -> Unit, onRestored: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val errEnter = stringResource(R.string.restore_pin_enter)
    val errWrongPin = stringResource(R.string.force_pin_existing_wrong)
    val errNoCloudPin = stringResource(R.string.force_pin_existing_none)
    val errOffline = stringResource(R.string.force_pin_existing_offline)

    fun restore() {
        if (pin.length != 4) { error = errEnter; return }
        busy = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { AdminAccount.restoreWithPin(context, pin) }
            busy = false
            when (outcome) {
                AdminAccount.RestoreOutcome.Restored -> onRestored()
                AdminAccount.RestoreOutcome.WrongPin -> error = errWrongPin
                AdminAccount.RestoreOutcome.NoCloudPin -> error = errNoCloudPin
                AdminAccount.RestoreOutcome.Unreachable -> error = errOffline
                is AdminAccount.RestoreOutcome.ConnectFailed -> error = outcome.message
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.restore_pin_title)) },
        text = {
            Column {
                Text(stringResource(R.string.restore_pin_message), fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) { pin = it; error = null } },
                    label = { Text(stringResource(R.string.force_pin_existing_pin)) },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = AccentRed, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = ::restore, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = MaroonPrimary)) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                else Text(stringResource(R.string.force_pin_existing_button))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.common_cancel)) } }
    )
}
