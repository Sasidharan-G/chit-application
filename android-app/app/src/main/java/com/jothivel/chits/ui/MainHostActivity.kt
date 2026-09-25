package com.jothivel.chits.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jothivel.chits.R
import com.jothivel.chits.data.firebase.SessionGuard
import kotlinx.coroutines.delay
import com.jothivel.chits.ui.base.BaseActivity
import com.jothivel.chits.ui.settings.CsvImportActivity
import com.jothivel.chits.ui.theme.JothiVelChitsTheme
import com.jothivel.chits.ui.theme.OffWhite
import kotlinx.coroutines.launch
import com.jothivel.chits.utils.DataBackupHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainHostActivity : BaseActivity() {

    private companion object {
        const val IDLE_LOCK_MS = 3 * 60 * 1000L
    }

    private val backupDbLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-sqlite3")) { uri ->
        uri?.let {
            lifecycleScope.launch {
                val result = DataBackupHelper.backupDatabaseToUri(this@MainHostActivity, it)
                if (result.isSuccess) {
                    com.jothivel.chits.utils.AppPreferences(this@MainHostActivity).setLastBackupAt()
                    Toast.makeText(this@MainHostActivity, getString(R.string.host_backup_saved), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainHostActivity, getString(R.string.host_backup_failed, result.exceptionOrNull()?.message.orEmpty()), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val exportCsvLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let {
            lifecycleScope.launch {
                val result = DataBackupHelper.exportDataToCsv(this@MainHostActivity, it)
                if (result.isSuccess) {
                    Toast.makeText(this@MainHostActivity, getString(R.string.host_export_ok), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainHostActivity, getString(R.string.host_export_failed, result.exceptionOrNull()?.message.orEmpty()), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val restoreDbLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            lifecycleScope.launch {
                val result = DataBackupHelper.restoreDatabaseFromUri(this@MainHostActivity, it)
                if (result.isSuccess) {
                    Toast.makeText(this@MainHostActivity, getString(R.string.host_restore_ok), Toast.LENGTH_LONG).show()
                    val intent = Intent(this@MainHostActivity, com.jothivel.chits.ui.auth.LoginActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent)
                    finish()
                } else {
                    Toast.makeText(this@MainHostActivity, getString(R.string.host_restore_failed, result.exceptionOrNull()?.message.orEmpty()), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val importCsvLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val intent = Intent(this, CsvImportActivity::class.java)
            intent.putExtra(CsvImportActivity.EXTRA_CSV_URI, it.toString())
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (com.jothivel.chits.utils.AppPreferences(this).getUserRole() == com.jothivel.chits.utils.AppPreferences.ROLE_ADMIN) {
            com.jothivel.chits.data.firebase.FirebaseSyncService.start(this)
        }

        startSessionHeartbeat()
        showMainContent()
    }

    // Idle lock: after a few minutes in the background the app asks for the PIN again. Before this, the
    // session flag lived as long as the process, so a phone left (or lost) with the app in the recents
    // list stayed open indefinitely.
    private var backgroundedAt = 0L

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) backgroundedAt = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        val away = if (backgroundedAt == 0L) 0L else System.currentTimeMillis() - backgroundedAt
        backgroundedAt = 0L
        if (away > IDLE_LOCK_MS) {
            com.jothivel.chits.ui.auth.LoginActivity.isSessionActive = false
            startActivity(Intent(this, com.jothivel.chits.ui.auth.LoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        com.jothivel.chits.data.firebase.FirebaseSyncService.stop()
    }

    // One login, one phone (see SessionGuard): while the app is on screen this phone checks in every
    // minute. If another phone has taken the account over in the meantime, this one is logged out.
    private fun startSessionHeartbeat() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val beat = withContext(Dispatchers.IO) { SessionGuard.heartbeat(applicationContext) }
                    if (beat is SessionGuard.Beat.Lost) {
                        logout(releaseSession = false, message = beat.message)
                        break
                    }
                    delay(SessionGuard.HEARTBEAT_MS)
                }
            }
        }
    }

    private fun logout(releaseSession: Boolean, message: String? = null) {
        // Agent sessions cache identity + PIN hash + assigned groups locally so login still works
        // offline (see AppPreferences.saveAgentSession) - a logout must wipe that cache, or the next
        // person to use this device could still see the previous agent's name/phone/assigned groups,
        // and a deactivated agent could keep logging back in offline via the stale cache.
        com.jothivel.chits.utils.AppPreferences(this).clearAgentSession()
        com.jothivel.chits.ui.auth.LoginActivity.isSessionActive = false
        lifecycleScope.launch {
            // Free the account for other phones first (needs the sign-in), then leave Firebase so the
            // next person on this device never inherits this session.
            withContext(Dispatchers.IO) {
                if (releaseSession) SessionGuard.release(applicationContext)
                com.jothivel.chits.data.firebase.FirebaseSetup.signOut()
            }
            if (message != null) Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            startActivity(Intent(this@MainHostActivity, com.jothivel.chits.ui.auth.LoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            finish()
        }
    }

    private fun showMainContent() {
        setContent {
            JothiVelChitsTheme {
                MainHostScreen(
                    onLogout = { logout(releaseSession = true) },
                    onBackupDatabase = {
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        backupDbLauncher.launch("ChitsBackup_$timestamp.db")
                    },
                    onRestoreDatabase = {
                        restoreDbLauncher.launch(arrayOf("*/*"))
                    },
                    onExportCsv = {
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        exportCsvLauncher.launch("ChitsData_$timestamp.csv")
                    },
                    onImportCsv = {
                        importCsvLauncher.launch(arrayOf(
                            "text/csv",
                            "text/comma-separated-values",
                            "application/vnd.ms-excel",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "*/*"
                        ))
                    }
                )
            }
        }
    }
}
