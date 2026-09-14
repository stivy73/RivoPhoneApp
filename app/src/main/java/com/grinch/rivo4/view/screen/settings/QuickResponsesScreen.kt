package com.grinch.rivo4.view.screen.settings

import com.grinch.rivo4.controller.util.RivoText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.grinch.rivo4.R
import com.grinch.rivo4.controller.util.PreferenceManager
import com.grinch.rivo4.view.components.RivoDialog
import com.grinch.rivo4.view.components.RivoDivider
import com.grinch.rivo4.view.components.RivoExpressiveCard
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun QuickResponsesScreen(
    navigator: DestinationsNavigator
) {
    val prefs = koinInject<PreferenceManager>()
    var responses by remember { mutableStateOf(prefs.getQuickResponses().toMutableList()) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editingText by remember { mutableStateOf("") }
    var isAddingNew by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    fun save(newResponses: List<String>) {
        responses = newResponses.toMutableList()
        prefs.setQuickResponses(newResponses)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(RivoText.get(com.grinch.rivo4.R.string.ui_quick_responses_290), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showResetConfirm = true }) {
                        Icon(Icons.Outlined.RestartAlt, contentDescription = RivoText.get(com.grinch.rivo4.R.string.ui_reset_to_defaults_354))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingText = ""
                    isAddingNew = true
                },
                shape = CircleShape
            ) {
                Icon(Icons.Default.Add, contentDescription = RivoText.get(com.grinch.rivo4.R.string.ui_add_response_355))
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(48.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                contentColor = MaterialTheme.colorScheme.primary
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Quickreply, contentDescription = null, modifier = Modifier.size(24.dp))
                                }
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = RivoText.get(com.grinch.rivo4.R.string.ui_in_call_quick_decline_messages_356),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = RivoText.get(com.grinch.rivo4.R.string.ui_tap_the_message_button_during_an_incoming_call_to_decline_and__357),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            item {
                RivoExpressiveCard(
                    title = RivoText.get(com.grinch.rivo4.R.string.ui_canned_responses_358, (responses.size).toString()),
                    icon = Icons.Outlined.Message
                ) {
                    if (responses.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = RivoText.get(com.grinch.rivo4.R.string.ui_no_quick_responses_configured_359),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        responses.forEachIndexed { index, responseText ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = responseText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        editingIndex = index
                                        editingText = responseText
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = RivoText.get(com.grinch.rivo4.R.string.ui_edit_360),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        val updated = responses.toMutableList()
                                        updated.removeAt(index)
                                        save(updated)
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = RivoText.get(com.grinch.rivo4.R.string.ui_delete_156),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            if (index < responses.size - 1) {
                                RivoDivider(Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (isAddingNew) {
        RivoDialog(
            onDismissRequest = { isAddingNew = false },
            title = RivoText.get(com.grinch.rivo4.R.string.ui_new_quick_response_361),
            icon = Icons.Outlined.AddComment,
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = editingText.trim()
                        if (trimmed.isNotEmpty()) {
                            val updated = responses.toMutableList()
                            updated.add(trimmed)
                            save(updated)
                        }
                        isAddingNew = false
                    },
                    enabled = editingText.isNotBlank()
                ) {
                    Text(RivoText.get(com.grinch.rivo4.R.string.ui_add_362))
                }
            },
            dismissButton = {
                TextButton(onClick = { isAddingNew = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        ) {
            OutlinedTextField(
                value = editingText,
                onValueChange = { editingText = it },
                label = { Text(RivoText.get(com.grinch.rivo4.R.string.ui_message_text_363)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                maxLines = 3
            )
        }
    }

    if (editingIndex != null) {
        val index = editingIndex!!
        RivoDialog(
            onDismissRequest = { editingIndex = null },
            title = RivoText.get(com.grinch.rivo4.R.string.ui_edit_quick_response_364),
            icon = Icons.Outlined.Edit,
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = editingText.trim()
                        if (trimmed.isNotEmpty()) {
                            val updated = responses.toMutableList()
                            updated[index] = trimmed
                            save(updated)
                        }
                        editingIndex = null
                    },
                    enabled = editingText.isNotBlank()
                ) {
                    Text(RivoText.get(com.grinch.rivo4.R.string.ui_save_365))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingIndex = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        ) {
            OutlinedTextField(
                value = editingText,
                onValueChange = { editingText = it },
                label = { Text(RivoText.get(com.grinch.rivo4.R.string.ui_message_text_363)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                maxLines = 3
            )
        }
    }

    if (showResetConfirm) {
        RivoDialog(
            onDismissRequest = { showResetConfirm = false },
            title = RivoText.get(com.grinch.rivo4.R.string.ui_reset_to_defaults_354),
            icon = Icons.Outlined.RestartAlt,
            confirmButton = {
                TextButton(
                    onClick = {
                        save(PreferenceManager.DEFAULT_QUICK_RESPONSES)
                        showResetConfirm = false
                    }
                ) {
                    Text(RivoText.get(com.grinch.rivo4.R.string.ui_reset_366))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        ) {
            Text(
                text = RivoText.get(com.grinch.rivo4.R.string.ui_restore_original_preset_quick_responses_367),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
