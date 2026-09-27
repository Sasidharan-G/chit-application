package com.jothivel.chits.ui.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jothivel.chits.R
import com.jothivel.chits.data.firebase.AdminAccount
import com.jothivel.chits.data.firebase.AdminPinSync
import com.jothivel.chits.data.firebase.AutoCloudSync
import com.jothivel.chits.data.firebase.CloudAccount
import com.jothivel.chits.data.firebase.FirebaseSetup
import com.jothivel.chits.data.firebase.FirebaseSyncService
import com.jothivel.chits.ui.theme.AccentGreen
import com.jothivel.chits.ui.theme.AccentRed
import com.jothivel.chits.ui.theme.DividerGray
import com.jothivel.chits.ui.theme.MaroonPrimary
import com.jothivel.chits.ui.theme.TextGray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings row for the app's one fixed cloud account ([AdminAccount]) - there is nothing to type any
 * more, so this is a one-tap switch: "Connect" signs this phone in, "Disconnect this phone" signs it
 * out and turns cloud sync off here. Cloud sync, restore and labour management do nothing while off.
 */
@Composable
fun CloudAccountSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val connected by produceState(false, refreshKey, busy) {
        value = if (busy) value else withContext(Dispatchers.IO) {
            CloudAccount.isConfigured(context) && FirebaseSetup.connectAdmin(context) is FirebaseSetup.AdminSignIn.Connected
        }
    }
    val connectedToast = stringResource(R.string.cloud_account_ok)
    val pinAdoptedToast = stringResource(R.string.cloud_account_pin_adopted)
    val disconnectedToast = stringResource(R.string.cloud_account_disconnected)
    val noAccountToast = stringResource(R.string.cloud_account_no_account)

    fun connect() {
        if (!AdminAccount.hasCredentials) {
            Toast.makeText(context, noAccountToast, Toast.LENGTH_LONG).show()
            return
        }
        busy = true
        scope.launch {
            CloudAccount.setEnabled(context, true)
            val result = withContext(Dispatchers.IO) { FirebaseSetup.connectAdmin(context) }
            when (result) {
                is FirebaseSetup.AdminSignIn.Connected -> {
                    // Same PIN everywhere: send this phone's PIN up, or take the one already saved.
                    val pin = withContext(Dispatchers.IO) { AdminPinSync.sync(context) }
                    FirebaseSyncService.start(context)
                    AutoCloudSync.requestCheck(context)
                    Toast.makeText(context, if (pin == AdminPinSync.Result.Adopted) pinAdoptedToast else connectedToast, Toast.LENGTH_LONG).show()
                }
                is FirebaseSetup.AdminSignIn.Failed -> {
                    CloudAccount.setEnabled(context, false)
                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                }
                FirebaseSetup.AdminSignIn.NotConfigured -> Unit
            }
            busy = false
            refreshKey++
        }
    }

    fun disconnect() {
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) {
                FirebaseSyncService.stop()
                CloudAccount.setEnabled(context, false)
                FirebaseSetup.signOut()
            }
            Toast.makeText(context, disconnectedToast, Toast.LENGTH_SHORT).show()
            busy = false
            showDisconnectConfirm = false
            refreshKey++
        }
    }

    Surface(
        Modifier.fillMaxWidth().height(56.dp).clickable(enabled = !busy) { if (connected) showDisconnectConfirm = true else connect() },
        shape = RoundedCornerShape(10.dp), color = Color.White, border = BorderStroke(1.dp, DividerGray.copy(alpha = .8f))
    ) {
        Row(Modifier.padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Cloud, null, tint = MaroonPrimary, modifier = Modifier.size(18.dp))
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(stringResource(R.string.cloud_account_title), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (connected) stringResource(R.string.cloud_account_connected, AdminAccount.EMAIL) else stringResource(R.string.cloud_account_not_connected),
                    fontSize = 9.sp, color = if (connected) AccentGreen else AccentRed
                )
            }
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.ChevronRight, null, tint = TextGray, modifier = Modifier.size(17.dp))
        }
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { if (!busy) showDisconnectConfirm = false },
            icon = { Icon(Icons.Default.Cloud, null, tint = MaroonPrimary) },
            title = { Text(stringResource(R.string.cloud_account_title)) },
            text = { Text(stringResource(R.string.cloud_account_disconnect_confirm, AdminAccount.EMAIL)) },
            confirmButton = {
                Button(onClick = ::disconnect, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = AccentRed)) {
                    Text(stringResource(R.string.cloud_account_disconnect))
                }
            },
            dismissButton = { TextButton(onClick = { showDisconnectConfirm = false }, enabled = !busy) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}
