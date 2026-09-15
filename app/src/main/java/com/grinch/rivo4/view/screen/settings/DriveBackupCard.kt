package com.grinch.rivo4.view.screen.settings

import android.app.Activity
import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.grinch.rivo4.R
import com.grinch.rivo4.controller.backup.*
import com.grinch.rivo4.view.components.RivoExpressiveCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

@Composable
fun DriveBackupCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { RecordingBackup.prefs(context) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var busy by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var authEpoch by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val connected = remember(revision) { prefs.contains("accountId") }
    val status = remember(revision) { prefs.getString("status", "READY").orEmpty() }
    fun failure(e: Exception) {
        if (authEpoch == RecordingBackup.epoch(context)) prefs.edit().putString("status", (e as? DriveFailure)?.reason?.name ?: "AUTH").apply()
    }
    fun complete(grant: DriveGrant) {
        scope.launch {
            try {
                val token = grant.token?.takeIf { it.isNotBlank() } ?: throw DriveFailure(BackupError.AUTH)
                RecordingBackup.connect(context, token, authEpoch)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure(e) }
            finally { busy = false }
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && authEpoch == RecordingBackup.epoch(context)) {
            try { complete(DriveAuth.result(context, result.data)) } catch (e: Exception) { failure(e); busy = false }
        } else { busy = false }
    }
    RivoExpressiveCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.drive_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.drive_description))
            if (!DriveAuth.available) {
                Text(stringResource(R.string.drive_cloud_required))
            } else {
                Text(stringResource(when (status) {
                    "QUEUED" -> R.string.drive_queued
                    "UPLOADING" -> R.string.drive_uploading
                    "RESTORING" -> R.string.drive_restoring
                    "DONE" -> R.string.drive_done
                    "AUTH" -> R.string.drive_auth_error
                    "NETWORK" -> R.string.drive_network_error
                    "QUOTA" -> R.string.drive_quota_error
                    "CONFIGURATION" -> R.string.drive_config_error
                    "STORAGE" -> R.string.drive_storage_error
                    "INTEGRITY" -> R.string.drive_integrity_error
                    "UNKNOWN" -> R.string.drive_unknown_error
                    else -> if (connected) R.string.drive_ready else R.string.drive_not_connected
                }))
                if (connected) {
                    Text(prefs.getString("accountEmail", "").orEmpty())
                    Text(stringResource(R.string.drive_folder))
                }
                Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    busy = true
                    authEpoch = RecordingBackup.epoch(context)
                    scope.launch {
                        try {
                            val grant = try { withTimeout(30_000) { DriveAuth.request(context, prefs.getString("accountEmail", null)) } }
                                catch (_: TimeoutCancellationException) { throw DriveFailure(BackupError.NETWORK) }
                            if (authEpoch != RecordingBackup.epoch(context)) { busy = false; return@launch }
                            val resolution = grant.resolution
                            if (resolution != null) launcher.launch(IntentSenderRequest.Builder(resolution.intentSender).build())
                            else complete(grant)
                        } catch (e: CancellationException) { busy = false; throw e }
                        catch (e: Exception) { failure(e); busy = false }
                    }
                }) { Text(stringResource(if (busy) R.string.drive_connecting else if (connected) R.string.drive_reconnect else R.string.drive_connect)) }
                if (connected) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.drive_automatic), Modifier.weight(1f))
                        Switch(checked = prefs.getBoolean("automatic", false), onCheckedChange = {
                            prefs.edit().putBoolean("automatic", it).putString("status", "READY").apply()
                            if (!it) androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag(RecordingBackup.WORK)
                            RecordingBackup.periodic(context)
                            if (it) RecordingBackup.scheduleAutomatic(context)
                        })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.drive_wifi), Modifier.weight(1f))
                        Switch(checked = prefs.getBoolean("wifi", true), onCheckedChange = {
                            prefs.edit().putBoolean("wifi", it).putString("status", "READY").apply()
                            // Replace pending requests so their network constraint matches the new setting.
                            androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag(RecordingBackup.WORK)
                            RecordingBackup.periodic(context)
                            RecordingBackup.scheduleAutomatic(context)
                        })
                    }
                    Text(stringResource(R.string.drive_pending, prefs.getInt("pending", 0)))
                    TextButton(onClick = { showFiles = true }) { Text(stringResource(R.string.drive_file_status)) }
                    val last = prefs.getLong("lastSuccess", 0)
                    if (last > 0) Text(stringResource(R.string.drive_last, DateFormat.getDateTimeInstance().format(Date(last))))
                    OutlinedButton(enabled = status !in setOf("QUEUED", "UPLOADING", "RESTORING"), onClick = { RecordingBackup.enqueue(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.drive_backup_now))
                    }
                    OutlinedButton(enabled = status !in setOf("QUEUED", "UPLOADING", "RESTORING"), onClick = { confirmRestore = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.drive_restore))
                    }
                    TextButton(onClick = { confirmDisconnect = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.drive_disconnect))
                    }
                }
            }
        }
    }
    if (showFiles) {
        val rows by produceState<List<Pair<String, String>>>(emptyList(), revision) {
            value = withContext(Dispatchers.IO) {
                RecordingBackup.localFiles(context).map { file ->
                    file.name to runCatching { prefs.getString("file_${BackupPolicy.identity(file.name, BackupPolicy.digest(file))}", "QUEUED").orEmpty() }.getOrDefault("ERROR").let { phase ->
                        if (phase == "UPLOADING" && prefs.getString("status", "") != "UPLOADING") "QUEUED" else phase
                    }
                }
            }
        }
        AlertDialog(onDismissRequest = { showFiles = false },
            title = { Text(stringResource(R.string.drive_file_status)) },
            text = { androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(rows.size) { index ->
                    val (name, phase) = rows[index]
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(name)
                        Text(stringResource(when (phase) {
                            "COPIED" -> R.string.drive_done
                            "UPLOADING" -> R.string.drive_uploading
                            "ERROR" -> R.string.drive_unknown_error
                            else -> R.string.drive_queued
                        }))
                    }
                }
            } }, confirmButton = { TextButton(onClick = { showFiles = false }) { Text(stringResource(R.string.drive_close)) } })
    }
    if (confirmRestore) AlertDialog(onDismissRequest = { confirmRestore = false },
        title = { Text(stringResource(R.string.drive_restore)) }, text = { Text(stringResource(R.string.drive_restore_confirm)) },
        confirmButton = { TextButton(onClick = { confirmRestore = false; RecordingBackup.enqueue(context, restore = true) }) { Text(stringResource(R.string.drive_restore)) } },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.drive_cancel)) } })
    if (confirmDisconnect) AlertDialog(onDismissRequest = { confirmDisconnect = false },
        title = { Text(stringResource(R.string.drive_disconnect)) }, text = { Text(stringResource(R.string.drive_disconnect_confirm)) },
        confirmButton = { TextButton(onClick = { confirmDisconnect = false; RecordingBackup.disconnect(context) }) { Text(stringResource(R.string.drive_disconnect)) } },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.drive_cancel)) } })
}
