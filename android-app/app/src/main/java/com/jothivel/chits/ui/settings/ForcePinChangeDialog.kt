package com.jothivel.chits.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import com.jothivel.chits.R
import com.jothivel.chits.ui.theme.AccentRed
import com.jothivel.chits.ui.theme.MaroonPrimary
import com.jothivel.chits.utils.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** True for PINs anyone would try first: repeated digits or a straight run up or down. */
internal fun isWeakPin(pin: String): Boolean {
    if (pin.length != 4 || !pin.all(Char::isDigit)) return true
    if (pin.toSet().size == 1) return true
    val steps = pin.zipWithNext { a, b -> b - a }
    return steps.all { it == 1 } || steps.all { it == -1 } || pin == "1234"
}

/**
 * Shown over the admin app for as long as this device still uses the factory PIN 1234 - it cannot be
 * dismissed, so no install stays open to everyone who knows the default. (The setup screen that used to
 * ask for a PIN on first launch is permanently disabled, which left every fresh install on 1234 forever.)
 */
@Composable
fun ForcePinChangeDialog(onDone: () -> Unit) {
    val context = LocalContext.current
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val errNew = stringResource(R.string.settings_change_pin_error_new)
    val errMismatch = stringResource(R.string.settings_change_pin_error_mismatch)
    val errWeak = stringResource(R.string.force_pin_weak)
    val errFailed = stringResource(R.string.settings_change_pin_error_wrong)

    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.force_pin_title)) },
        text = {
            Column {
                Text(stringResource(R.string.force_pin_message), fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) { newPin = it; error = null } },
                    label = { Text(stringResource(R.string.settings_change_pin_new)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) { confirmPin = it; error = null } },
                    label = { Text(stringResource(R.string.settings_change_pin_confirm)) },
                    singleLine = true,
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
            Button(
                onClick = {
                    if (saving) return@Button
                    when {
                        newPin.length != 4 -> error = errNew
                        newPin != confirmPin -> error = errMismatch
                        isWeakPin(newPin) -> error = errWeak
                        else -> {
                            saving = true
                            scope.launch {
                                val changed = withContext(Dispatchers.IO) { AppPreferences(context).changePin("1234", newPin) }
                                saving = false
                                if (changed) onDone() else error = errFailed
                            }
                        }
                    }
                },
                enabled = !saving,
                colors = ButtonDefaults.buttonColors(containerColor = MaroonPrimary)
            ) { Text(stringResource(R.string.settings_change_pin_save)) }
        }
    )
}
