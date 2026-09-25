package com.jothivel.chits.ui.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jothivel.chits.R
import com.jothivel.chits.data.firebase.AdminPinSync
import com.jothivel.chits.data.firebase.AutoCloudSync
import com.jothivel.chits.data.firebase.FirebaseSyncService
import com.jothivel.chits.data.firebase.CloudAccount
import com.jothivel.chits.data.firebase.FirebaseSetup
import com.jothivel.chits.ui.components.ConfirmBottomSheet
import com.jothivel.chits.ui.components.PremiumInputField
import com.jothivel.chits.ui.theme.AccentGreen
import com.jothivel.chits.ui.theme.AccentRed
import com.jothivel.chits.ui.theme.DividerGray
import com.jothivel.chits.ui.theme.MaroonPrimary
import com.jothivel.chits.ui.theme.TextGray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings row where the admin connects this device to the shared Firebase project with the admin
 * email + password created in the Firebase console. Every new phone has to be given both - no password
 * is built into the app, and the app never changes it. Without a connection, cloud sync, restore and
 * labour management do nothing.
 */
@Composable
fun CloudAccountSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    var showSheet by remember { mutableStateOf(false) }
    val email by produceState<String?>(null, refreshKey) { value = withContext(Dispatchers.IO) { CloudAccount.email(context) } }
    val connectedToast = stringResource(R.string.cloud_account_ok)
    val pinAdoptedToast = stringResource(R.string.cloud_account_pin_adopted)
    val disconnectedToast = stringResource(R.string.cloud_account_disconnected)

    Surface(
        Modifier.fillMaxWidth().height(56.dp).clickable { showSheet = true },
        shape = RoundedCornerShape(10.dp), color = Color.White, border = BorderStroke(1.dp, DividerGray.copy(alpha = .8f))
    ) {
        Row(Modifier.padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Cloud, null, tint = MaroonPrimary, modifier = Modifier.size(18.dp))
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(stringResource(R.string.cloud_account_title), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (email.isNullOrBlank()) stringResource(R.string.cloud_account_not_connected) else stringResource(R.string.cloud_account_connected, email.orEmpty()),
                    fontSize = 9.sp, color = if (email.isNullOrBlank()) AccentRed else AccentGreen
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = TextGray, modifier = Modifier.size(17.dp))
        }
    }

    if (showSheet) {
        var emailInput by remember { mutableStateOf(email.orEmpty()) }
        var passwordInput by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        ConfirmBottomSheet(
            show = true,
            onDismiss = { showSheet = false },
            title = stringResource(R.string.cloud_account_title),
            message = stringResource(R.string.cloud_account_hint),
            confirmLabel = stringResource(R.string.cloud_account_connect),
            cancelLabel = stringResource(R.string.common_cancel),
            confirmEnabled = !busy && emailInput.isNotBlank() && passwordInput.length >= 6,
            onConfirm = {
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        CloudAccount.save(context, emailInput, passwordInput)
                        FirebaseSetup.connectAdmin(context)
                    }
                    busy = false
                    when (result) {
                        is FirebaseSetup.AdminSignIn.Connected -> {
                            // Same PIN on every phone: send this phone's PIN up, or take the one already saved.
                            val pin = withContext(Dispatchers.IO) { AdminPinSync.sync(context) }
                            // No restart needed: agent collections start arriving now, and waiting changes may go up.
                            FirebaseSyncService.start(context)
                            AutoCloudSync.requestCheck(context)
                            val message = if (pin == AdminPinSync.Result.Adopted) pinAdoptedToast else connectedToast
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            showSheet = false
                        }
                        is FirebaseSetup.AdminSignIn.Failed -> {
                            withContext(Dispatchers.IO) { CloudAccount.clear(context) }
                            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        }
                        FirebaseSetup.AdminSignIn.NotConfigured -> Unit
                    }
                    refreshKey++
                }
            },
            content = {
                PremiumInputField(emailInput, { emailInput = it.trim() }, stringResource(R.string.cloud_account_email), Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                Spacer(Modifier.height(8.dp))
                PremiumInputField(
                    passwordInput, { passwordInput = it }, stringResource(R.string.cloud_account_password), Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation()
                )
                if (!email.isNullOrBlank()) TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { FirebaseSyncService.stop(); CloudAccount.clear(context); FirebaseSetup.signOut() }
                        Toast.makeText(context, disconnectedToast, Toast.LENGTH_SHORT).show()
                        showSheet = false
                        refreshKey++
                    }
                }) { Text(stringResource(R.string.cloud_account_disconnect), color = AccentRed, fontSize = 12.sp) }
            }
        )
    }
}
