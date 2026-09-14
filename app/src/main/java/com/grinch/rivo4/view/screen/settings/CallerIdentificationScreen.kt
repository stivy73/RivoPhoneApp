package com.grinch.rivo4.view.screen.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.grinch.rivo4.R
import com.grinch.rivo4.controller.identification.CallerIdentification
import com.grinch.rivo4.controller.identification.CallerLabel
import com.grinch.rivo4.controller.identification.ProviderStatus
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun rememberCallerLabel(number: String): CallerLabel? {
    val repository = koinInject<CallerIdentification>()
    val revision by repository.revision.collectAsState()
    // Local resolution only. No network from recomposition or history scrolling.
    LaunchedEffect(number) { repository.local(number) }
    return remember(number, revision) { repository.label(number) }
}

@Composable
fun CallerProvenance(label: CallerLabel?) {
    val repository = koinInject<CallerIdentification>()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    if (label != null) {
        Text(repository.source(label), style = MaterialTheme.typography.labelSmall)
        if (label.source == "google") {
            (listOf("Google Maps" to label.url) + label.credits).filter { it.second.startsWith("https://") }.forEach { (name, url) ->
                TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) { Text(name) }
            }
        }
        if (label.spam) Text(stringResource(R.string.caller_spam), color = MaterialTheme.colorScheme.error)
        label.risk?.let { Text(stringResource(R.string.caller_risk, it), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun CallerActions(number: String) {
    var open by remember(number) { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.caller_actions))
    }
    if (open) CallerEditor(number, onDismiss = { open = false })
}

@Composable
private fun CallerEditor(initialNumber: String, onDismiss: () -> Unit) {
    val repository = koinInject<CallerIdentification>()
    val context = LocalContext.current
    var number by remember { mutableStateOf(initialNumber) }
    var name by remember { mutableStateOf(repository.customNames()[repository.normalize(initialNumber)].orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    val label = rememberCallerLabel(number)
    val revision by repository.revision.collectAsState()
    val lookupState = remember(number, revision) { repository.lookupState(number) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.caller_actions)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(number, { number = it }, label = { Text(stringResource(R.string.caller_number)) }, singleLine = true)
            Text(repository.display(label) ?: number)
            CallerProvenance(label)
            lookupState?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.caller_custom)) }, singleLine = true)
            if (invalid) Text(stringResource(R.string.caller_invalid))
            TextButton(onClick = { repository.identify(number, refresh = true) }, enabled = repository.option("online")) {
                Text(stringResource(R.string.caller_refresh))
            }
            TextButton(onClick = { if (repository.setCustom(number, "")) { name = "" } else invalid = true }) {
                Text(stringResource(R.string.caller_remove_custom))
            }
            TextButton(onClick = {
                val intent = android.content.Intent(android.content.Intent.ACTION_INSERT,
                    android.provider.ContactsContract.Contacts.CONTENT_URI).apply {
                    putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, number)
                    putExtra(android.provider.ContactsContract.Intents.Insert.NAME, name.ifBlank { label?.name.orEmpty() })
                }
                runCatching { context.startActivity(intent) }
            }) { Text(stringResource(R.string.contact_add_to_contacts)) }
        }
    }, confirmButton = {
        TextButton(onClick = { if (repository.setCustom(number, name)) onDismiss() else invalid = true }) {
            Text(stringResource(R.string.caller_save))
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun CallerIdentificationScreen(navigator: DestinationsNavigator) {
    val repository = koinInject<CallerIdentification>()
    val revision by repository.revision.collectAsState()
    var editNumber by remember { mutableStateOf<String?>(null) }
    var clear by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.caller_title)) }, navigationIcon = {
            IconButton(onClick = { navigator.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        })
    }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.caller_description))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.caller_online), Modifier.weight(1f))
                Switch(checked = remember(revision) { repository.option("online") }, onCheckedChange = { repository.toggle("online", it) })
            }
            ProviderCard("google", repository)
            ProviderCard("ipqs", repository)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.caller_show_spam), Modifier.weight(1f))
                Switch(checked = remember(revision) { repository.option("spam", true) }, onCheckedChange = { repository.toggle("spam", it) })
            }
            HorizontalDivider()
            Text(stringResource(R.string.caller_manage), style = MaterialTheme.typography.titleMedium)
            Button(onClick = { editNumber = "" }) { Text(stringResource(R.string.caller_add_custom)) }
            val names = remember(revision) { repository.customNames().toSortedMap() }
            names.forEach { (number, name) -> TextButton(onClick = { editNumber = number }) { Text("$name • $number") } }
            TextButton(onClick = { clear = true }) { Text(stringResource(R.string.caller_clear)) }
            Text(stringResource(R.string.caller_google_policy), style = MaterialTheme.typography.bodySmall)
        }
    }
    editNumber?.let { CallerEditor(it) { editNumber = null } }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text(stringResource(R.string.caller_clear)) },
        text = { Text(stringResource(R.string.caller_clear_confirm)) }, confirmButton = {
            TextButton(onClick = { repository.clearCache(); clear = false }) { Text(stringResource(R.string.caller_clear)) }
        }, dismissButton = { TextButton(onClick = { clear = false }) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
private fun ProviderCard(provider: String, repository: CallerIdentification) {
    val revision by repository.revision.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    // Deliberately not rememberSaveable: credentials never enter saved instance state.
    var key by remember { mutableStateOf("") }
    var remove by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf(false) }
    val state = remember(revision) { repository.status(provider) }
    LaunchedEffect(expanded) {
        if (expanded) key = try { repository.apiKey(provider) } catch (_: Exception) { localError = true; "" }
        else { key = "" }
    }
    val statusText = when (state) {
        ProviderStatus.NOT_CONFIGURED -> R.string.api_not_configured
        ProviderStatus.VERIFYING -> R.string.api_verifying
        ProviderStatus.CONFIGURED -> R.string.api_configured
        ProviderStatus.INVALID_KEY -> R.string.api_invalid
        ProviderStatus.API_DISABLED -> R.string.api_disabled
        ProviderStatus.BILLING -> R.string.api_billing
        ProviderStatus.QUOTA -> R.string.api_quota
        ProviderStatus.RESTRICTION -> R.string.api_restriction
        ProviderStatus.TIMEOUT -> R.string.api_timeout
        ProviderStatus.UNREACHABLE -> R.string.api_unreachable
        ProviderStatus.ERROR -> R.string.api_error
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (provider == "google") "Google Places" else "IPQualityScore", style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(if (provider == "google") R.string.api_google_description else R.string.api_ipqs_description))
            Text(stringResource(statusText), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(if (provider == "google") R.string.caller_google else R.string.caller_ipqs), Modifier.weight(1f))
                Switch(checked = remember(revision) { repository.option(provider) }, onCheckedChange = { repository.toggle(provider, it) }, enabled = !busy)
            }
            if (expanded) {
                ProviderKeyInput(key, { key = it; localError = false },
                    stringResource(if (provider == "google") R.string.api_google_key else R.string.api_ipqs_key), !busy)
                Button(enabled = !busy && key.isNotBlank(), onClick = {
                    busy = true
                    scope.launch { try { repository.saveAndVerify(provider, key) } finally { busy = false } }
                }) { Text(stringResource(R.string.api_verify)) }
                TextButton(onClick = {
                    if (!com.grinch.rivo4.controller.identification.ProviderLinks.open(context, provider))
                        android.widget.Toast.makeText(context, R.string.api_no_browser, android.widget.Toast.LENGTH_LONG).show()
                }) { Text(stringResource(R.string.api_get_key)) }
                Text(stringResource(if (provider == "google") R.string.api_google_help else R.string.api_ipqs_help), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.api_storage_help), style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !busy, onClick = { remove = true }) { Text(stringResource(R.string.api_remove)) }
                if (localError) Text(stringResource(R.string.api_error))
            }
        }
    }
    if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text(stringResource(R.string.api_remove)) },
        text = { Text(stringResource(R.string.api_remove_confirm)) }, confirmButton = {
            TextButton(onClick = {
                remove = false; busy = true
                scope.launch {
                    try { repository.removeKey(provider); key = "" }
                    catch (_: Exception) { localError = true }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.api_remove)) }
        }, dismissButton = { TextButton(onClick = { remove = false }) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
internal fun ProviderKeyInput(value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, onValueChange, singleLine = true, enabled = enabled,
        label = { Text(label) },
        visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false, keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
        trailingIcon = { TextButton(onClick = { visible = !visible }) {
            Text(stringResource(if (visible) R.string.api_hide else R.string.api_show))
        } }, modifier = Modifier.fillMaxWidth())
}
