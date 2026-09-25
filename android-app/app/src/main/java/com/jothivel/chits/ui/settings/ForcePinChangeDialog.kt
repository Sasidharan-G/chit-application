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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jothivel.chits.R
import com.jothivel.chits.data.firebase.AdminPinSync
import com.jothivel.chits.data.firebase.CloudAccount
import com.jothivel.chits.data.firebase.FirebaseSetup
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
 * dismissed, so no install stays open to everyone who knows the default. The admin either chooses a PIN
 * (the very first phone) or, on an extra phone, signs in with the cloud email + password and the PIN
 * already in use, which this phone then adopts (see AdminPinSync).
 */
@Composable
fun ForcePinChangeDialog(onDone: () -> Unit) {
    val context = LocalContext.current
    var existingAccount by remember { mutableStateOf(false) }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var existingPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val errNew = stringResource(R.string.settings_change_pin_error_new)
    val errMismatch = stringResource(R.string.settings_change_pin_error_mismatch)
    val errWeak = stringResource(R.string.force_pin_weak)
    val errFailed = stringResource(R.string.settings_change_pin_error_wrong)
    val errWrongPin = stringResource(R.string.force_pin_existing_wrong)
    val errNoCloudPin = stringResource(R.string.force_pin_existing_none)
    val errOffline = stringResource(R.string.force_pin_existing_offline)
    val errFill = stringResource(R.string.force_pin_existing_fill)

    fun chooseNewPin() {
        when {
            newPin.length != 4 -> error = errNew
            newPin != confirmPin -> error = errMismatch
            isWeakPin(newPin) -> error = errWeak
            else -> {
                saving = true
                scope.launch {
                    // The first PIN of a phone is not pushed to the cloud on its own: see AppPreferences.changePin.
                    val changed = withContext(Dispatchers.IO) { AppPreferences(context).changePin("1234", newPin, share = false) }
                    saving = false
                    if (changed) onDone() else error = errFailed
                }
            }
        }
    }

    fun useExistingPin() {
        if (email.isBlank() || password.length < 6 || existingPin.length != 4) { error = errFill; return }
        saving = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                CloudAccount.save(context, email, password)
                when (val signIn = FirebaseSetup.connectAdmin(context)) {
                    is FirebaseSetup.AdminSignIn.Failed -> { CloudAccount.clear(context); signIn.message }
                    FirebaseSetup.AdminSignIn.NotConfigured -> errOffline
                    FirebaseSetup.AdminSignIn.Connected -> when (AdminPinSync.restoreOnNewPhone(context, existingPin)) {
                        AdminPinSync.Restore.Restored -> null
                        AdminPinSync.Restore.WrongPin -> errWrongPin
                        AdminPinSync.Restore.NoCloudPin -> errNoCloudPin
                        AdminPinSync.Restore.Unreachable -> errOffline
                    }
                }
            }
            saving = false
            if (outcome == null) onDone() else error = outcome
        }
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(if (existingAccount) R.string.force_pin_existing_title else R.string.force_pin_title)) },
        text = {
            Column {
                Text(stringResource(if (existingAccount) R.string.force_pin_existing_message else R.string.force_pin_message), fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                if (existingAccount) {
                    OutlinedTextField(
                        value = email, onValueChange = { email = it.trim(); error = null },
                        label = { Text(stringResource(R.string.cloud_account_email)) }, singleLine = true, enabled = !saving,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password, onValueChange = { password = it; error = null },
                        label = { Text(stringResource(R.string.cloud_account_password)) }, singleLine = true, enabled = !saving,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    PinField(existingPin, R.string.force_pin_existing_pin, !saving) { existingPin = it; error = null }
                } else {
                    PinField(newPin, R.string.settings_change_pin_new, !saving) { newPin = it; error = null }
                    Spacer(Modifier.height(8.dp))
                    PinField(confirmPin, R.string.settings_change_pin_confirm, !saving) { confirmPin = it; error = null }
                }
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = AccentRed, fontSize = 11.sp)
                }
                TextButton(onClick = { existingAccount = !existingAccount; error = null }, enabled = !saving) {
                    Text(stringResource(if (existingAccount) R.string.force_pin_back_to_new else R.string.force_pin_have_account), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (!saving) { if (existingAccount) useExistingPin() else chooseNewPin() } },
                enabled = !saving,
                colors = ButtonDefaults.buttonColors(containerColor = MaroonPrimary)
            ) { Text(stringResource(if (existingAccount) R.string.force_pin_existing_button else R.string.settings_change_pin_save)) }
        }
    )
}

@Composable
private fun PinField(value: String, label: Int, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) onChange(it) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth()
    )
}