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
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    var proxy by remember { mutableStateOf(repository.value("proxy")) }
    var token by remember { mutableStateOf(repository.value("token")) }
    var message by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
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
            listOf("online" to R.string.caller_online, "google" to R.string.caller_google,
                "ipqs" to R.string.caller_ipqs, "spam" to R.string.caller_show_spam).forEach { (key, title) ->
                val checked = remember(revision) { repository.option(key, key == "spam") }
                val configured = key !in listOf("google", "ipqs") || repository.value("available").split(',').contains(key)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(title), modifier = Modifier.weight(1f))
                    Switch(checked = checked, onCheckedChange = { repository.toggle(key, it) }, enabled = checked || configured)
                }
            }
            OutlinedTextField(proxy, { proxy = it }, label = { Text(stringResource(R.string.caller_proxy)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text(stringResource(R.string.caller_token)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(enabled = !testing, onClick = {
                repository.configure("proxy", proxy); repository.configure("token", token)
                repository.configure("available", "")
                testing = true
                scope.launch {
                    message = try { resources.getString(R.string.caller_connected, repository.verify()) }
                        catch (_: Exception) { resources.getString(R.string.caller_connection_failed) }
                    testing = false
                }
            }) { Text(stringResource(R.string.caller_verify)) }
            Text(message)
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
