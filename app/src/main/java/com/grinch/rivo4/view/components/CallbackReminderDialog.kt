package com.grinch.rivo4.view.components

import com.grinch.rivo4.controller.util.RivoText
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.grinch.rivo4.R
import com.grinch.rivo4.controller.reminder.CallbackReminderManager
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.Calendar

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CallbackReminderDialog(
    phoneNumber: String,
    contactName: String?,
    onDismissRequest: () -> Unit,
    onReminderScheduled: (() -> Unit)? = null,
    reminderManager: CallbackReminderManager = koinInject()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val tomorrowMorningMinutes = remember {
        val now = Calendar.getInstance()
        val tomorrow = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val diffMillis = tomorrow.timeInMillis - now.timeInMillis
        (diffMillis / (1000 * 60)).coerceAtLeast(15)
    }

    val presets = remember(tomorrowMorningMinutes) {
        listOf(
            RivoText.get(com.grinch.rivo4.R.string.ui_15_mins_115) to 15L,
            RivoText.get(com.grinch.rivo4.R.string.ui_30_mins_116) to 30L,
            RivoText.get(com.grinch.rivo4.R.string.ui_1_hour_117) to 60L,
            RivoText.get(com.grinch.rivo4.R.string.ui_3_hours_118) to 180L,
            RivoText.get(com.grinch.rivo4.R.string.ui_tomorrow_9_am_119) to tomorrowMorningMinutes
        )
    }

    var selectedMinutes by remember { mutableLongStateOf(15L) }
    var selectedLabel by remember { mutableStateOf(RivoText.get(com.grinch.rivo4.R.string.ui_15_mins_115)) }
    var noteText by remember { mutableStateOf("") }

    RivoDialog(
        onDismissRequest = onDismissRequest,
        title = RivoText.get(com.grinch.rivo4.R.string.ui_callback_reminder_120),
        icon = Icons.Outlined.Alarm,
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        reminderManager.scheduleReminder(
                            phoneNumber = phoneNumber,
                            contactName = contactName,
                            delayMinutes = selectedMinutes,
                            note = noteText.trim().ifEmpty { null }
                        )
                        Toast.makeText(
                            context,
                            RivoText.get(com.grinch.rivo4.R.string.ui_reminder_set_for_121, (selectedLabel).toString()),
                            Toast.LENGTH_SHORT
                        ).show()
                        onReminderScheduled?.invoke()
                        onDismissRequest()
                    }
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(RivoText.get(com.grinch.rivo4.R.string.ui_set_reminder_122))
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Text(
                text = RivoText.get(com.grinch.rivo4.R.string.ui_remind_to_call_phonenumber_in_123, (contactName?.ifBlank { null } ?: phoneNumber).toString()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { (label, minutes) ->
                    val isSelected = selectedMinutes == minutes
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            selectedMinutes = minutes
                            selectedLabel = label
                        },
                        label = { Text(label) },
                        leadingIcon = if (isSelected) {
                            {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                label = { Text(RivoText.get(com.grinch.rivo4.R.string.ui_note_optional_124)) },
                placeholder = { Text(RivoText.get(com.grinch.rivo4.R.string.ui_e_g_call_about_the_proposal_125)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}
