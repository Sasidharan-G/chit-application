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
import com.jothivel.chits.data.firebase.AdminPin
import com.jothivel.chits.data.firebase.CloudAccount
import com.jothivel.chits.data.firebase.FirebaseSetup
import com.jothivel.chits.utils.AppPreferences
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
 * email and the app's own login PIN (the cloud password follows the PIN - see [AdminPin]). An account
 * still on the separate password it was created with asks for that once and is moved to the PIN.
 * Without a connection, cloud sync, restore and labour management do nothing.
 */
@Composable
fun CloudAccountSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    var showSheet by remember { mutableStateOf(false) }
    val email by produceState<String?>(null, refreshKey) { value = withContext(Dispatchers.IO) { CloudAccount.email(context) } }
    val connectedToast = stringResource(R.string.cloud_account_ok)
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
        var pinInput by remember { mutableStateOf("") }
        // Only asked when the account still has the separate password it was created with.
        var askOldPassword by remember { mutableStateOf(false) }
        var oldPasswordInput by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val wrongPinText = stringResource(R.string.cloud_account_pin_wrong)
        val oldPasswordNeededText = stringResource(R.string.cloud_account_old_password_needed)
        val notMovedTemplate = stringResource(R.string.cloud_account_password_not_moved)
        ConfirmBottomSheet(
            show = true,
            onDismiss = { showSheet = false },
            title = stringResource(R.string.cloud_account_title),
            message = stringResource(R.string.cloud_account_hint),
            confirmLabel = stringResource(R.string.cloud_account_connect),
            cancelLabel = stringResource(R.string.common_cancel),
            confirmEnabled = !busy && emailInput.isNotBlank() && pinInput.length == 4 && (!askOldPassword || oldPasswordInput.length >= 6),
            onConfirm = {
                busy = true
                scope.launch {
                    if (!withContext(Dispatchers.IO) { AppPreferences(context).verifyPin(pinInput) }) {
                        busy = false
                        Toast.makeText(context, wrongPinText, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    val usingOldPassword = askOldPassword && oldPasswordInput.isNotBlank()
                    val result = withContext(Dispatchers.IO) {
                        CloudAccount.save(context, emailInput, if (usingOldPassword) oldPasswordInput else AdminPin.cloudPassword(pinInput))
                        FirebaseSetup.connectAdmin(context)
                    }
                    when (result) {
                        is FirebaseSetup.AdminSignIn.Connected -> {
                            // Signed in with the old password: from now on the login PIN is the password.
                            val moved = !usingOldPassword || withContext(Dispatchers.IO) { AdminPin.alignCloudPassword(context, pinInput) }
                            busy = false
                            Toast.makeText(context, if (moved) connectedToast else notMovedTemplate, Toast.LENGTH_LONG).show()
                            showSheet = false
                            refreshKey++
                        }
                        is FirebaseSetup.AdminSignIn.Failed -> {
                            busy = false
                            withContext(Dispatchers.IO) { CloudAccount.clear(context) }
                            if (result.wrongPassword && !usingOldPassword) {
                                askOldPassword = true
                                Toast.makeText(context, oldPasswordNeededText, Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                            }
                            refreshKey++
                        }
                        FirebaseSetup.AdminSignIn.NotConfigured -> busy = false
                    }
                }
            },
            content = {
                PremiumInputField(emailInput, { emailInput = it.trim() }, stringResource(R.string.cloud_account_email), Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                Spacer(Modifier.height(8.dp))
                PremiumInputField(
                    pinInput, { if (it.length <= 4 && it.all(Char::isDigit)) pinInput = it }, stringResource(R.string.cloud_account_password), Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation()
                )
                if (askOldPassword) {
                    Spacer(Modifier.height(8.dp))
                    PremiumInputField(
                        oldPasswordInput, { oldPasswordInput = it }, stringResource(R.string.cloud_account_old_password), Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
                if (!email.isNullOrBlank()) TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { CloudAccount.clear(context); FirebaseSetup.signOut() }
                        Toast.makeText(context, disconnectedToast, Toast.LENGTH_SHORT).show()
                        showSheet = false
                        refreshKey++
                    }
                }) { Text(stringResource(R.string.cloud_account_disconnect), color = AccentRed, fontSize = 12.sp) }
            }
        )
    }
}
