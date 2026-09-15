package com.grinch.rivo4.view.screen.settings

import com.grinch.rivo4.controller.util.RivoText
import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.grinch.rivo4.R
import com.grinch.rivo4.controller.recording.*
import com.grinch.rivo4.controller.shizuku.ScrcpyAudioCodec
import com.grinch.rivo4.controller.shizuku.ScrcpyAudioSource
import com.grinch.rivo4.controller.CallRecorder
import com.grinch.rivo4.controller.shizuku.ShizukuConnectionManager
import com.grinch.rivo4.controller.util.OemPermissionHelper
import com.grinch.rivo4.controller.util.PreferenceManager
import com.grinch.rivo4.controller.util.formatDateHeader
import com.grinch.rivo4.controller.util.openLink
import com.grinch.rivo4.view.components.LocalRivoSurfaceStyle
import com.grinch.rivo4.view.components.RivoConfirmationDialog
import com.grinch.rivo4.view.components.RivoDivider
import com.grinch.rivo4.view.components.RivoExpressiveCard
import com.grinch.rivo4.view.components.RivoLeadingIconTile
import com.grinch.rivo4.view.components.RivoSectionHeader
import com.grinch.rivo4.view.components.RivoSelectListItem
import com.grinch.rivo4.view.components.RivoSurfaceStyle
import com.grinch.rivo4.view.components.RivoSwitchListItem
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.sin

enum class DateFilterPreset {
    ALL,
    TODAY,
    LAST_7_DAYS,
    THIS_MONTH,
    CUSTOM
}

@Destination<RootGraph>
@Composable
fun CallRecordingsScreen(
    navigator: DestinationsNavigator,
    initialShowList: Boolean = false,
    initialCallerLabel: String? = null
) {
    CallRecordingsContent(
        showTopBar = true,
        initialShowList = initialShowList,
        initialCallerLabel = initialCallerLabel,
        onBack = { navigator.navigateUp() }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallRecordingsContent(
    showTopBar: Boolean = false,
    initialShowList: Boolean = false,
    initialCallerLabel: String? = null,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val prefs = koinInject<PreferenceManager>()
    val settingsState by prefs.settingsChanged.collectAsState()

    var showingRecordingsList by remember { mutableStateOf(initialShowList) }
    var fromDateMillis by remember { mutableStateOf<Long?>(null) }
    var toDateMillis by remember { mutableStateOf<Long?>(null) }
    var datePreset by remember { mutableStateOf(DateFilterPreset.ALL) }
    var showFromDatePicker by remember { mutableStateOf(false) }
    var showToDatePicker by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var statusTick by remember { mutableIntStateOf(0) }
    var recordings by remember { mutableStateOf<List<File>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<File?>(null) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }
    var selectedFilterNumber by remember(initialCallerLabel) { mutableStateOf(initialCallerLabel) }

    BackHandler(enabled = showingRecordingsList && !initialShowList) {
        showingRecordingsList = false
    }

    // Shizuku & Recording Preference States
    val shizukuAvailable = remember(settingsState, refreshKey, statusTick) { ShizukuConnectionManager.isAvailable() }
    val shizukuPermissionGranted = remember(settingsState, refreshKey, statusTick) { ShizukuConnectionManager.hasPermission(context) }

    // Auto-refresh recordings when returning to the screen
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                refreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var callRecordingEnabled by remember(settingsState) {
        mutableStateOf(prefs.getBoolean(PreferenceManager.KEY_CALL_RECORDING, true))
    }
    var autoRecordEnabled by remember(settingsState) {
        mutableStateOf(prefs.getBoolean(PreferenceManager.KEY_CALL_RECORDING_AUTO, false))
    }
    var autoRecordFilter by remember(settingsState) {
        mutableIntStateOf(prefs.getInt(PreferenceManager.KEY_CALL_RECORDING_FILTER, PreferenceManager.RECORD_FILTER_ALL))
    }
    var minDurationFilter by remember(settingsState) { mutableIntStateOf(prefs.getInt("call_recording_min_duration", 0)) }
    var bitrate by remember(settingsState) { mutableIntStateOf(prefs.getInt("call_recording_bitrate", 16000)) }

    val recorderState by CallRecorder.state.collectAsState()
    val savedRevision by CallRecorder.recordingsChanged.collectAsState()
    var sourceKey by remember(settingsState) { mutableStateOf(prefs.getString("call_recording_source", "voice-call") ?: "voice-call") }
    var codecKey by remember(settingsState) { mutableStateOf(prefs.getString("call_recording_codec", "opus") ?: "opus") }
    LaunchedEffect(Unit) { while (true) { delay(1000); statusTick++ } }

    val shareTitle = stringResource(R.string.call_recordings_share)

    // Inline Media Player State
    var activePlayingFile by remember { mutableStateOf<File?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(0) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(activePlayingFile) {
        if (activePlayingFile != null) {
            val mp = MediaPlayer()
            val reportPlaybackError = {
                isPlaying = false
                android.widget.Toast.makeText(context, RivoText.get(R.string.recorder_player_error), android.widget.Toast.LENGTH_LONG).show()
            }
            try {
                mp.setOnCompletionListener { isPlaying = false; currentPositionMs = durationMs }
                mp.setOnErrorListener { _, _, _ -> reportPlaybackError(); true }
                mp.setDataSource(context, Uri.fromFile(activePlayingFile))
                mp.prepare()
                mp.playbackParams = mp.playbackParams.setSpeed(playbackSpeed)
                mp.start()
                mediaPlayer = mp
                isPlaying = true
                durationMs = mp.duration
            } catch (_: Exception) {
                reportPlaybackError()
            }
            onDispose {
                runCatching { mp.stop() }
                mp.release()
                mediaPlayer = null
                isPlaying = false
            }
        } else {
            onDispose { }
        }
    }

    LaunchedEffect(isPlaying, activePlayingFile) {
        while (isPlaying && activePlayingFile != null) {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    currentPositionMs = mp.currentPosition
                    durationMs = mp.duration
                } else {
                    isPlaying = false
                }
            }
            delay(250)
        }
    }

    LaunchedEffect(savedRevision, showingRecordingsList, refreshKey) {
        recordings = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CallRecorder.listRecordings(context) }
    }

    val effectiveFrom = remember(fromDateMillis, toDateMillis) {
        if (fromDateMillis != null && toDateMillis != null && fromDateMillis!! > toDateMillis!!) toDateMillis else fromDateMillis
    }
    val effectiveTo = remember(fromDateMillis, toDateMillis) {
        if (fromDateMillis != null && toDateMillis != null && fromDateMillis!! > toDateMillis!!) fromDateMillis else toDateMillis
    }

    val filteredRecordings = remember(recordings, effectiveFrom, effectiveTo, selectedFilterNumber) {
        recordings.filter { file ->
            val timestamp = file.lastModified()
            val matchesFrom = effectiveFrom == null || timestamp >= effectiveFrom
            val matchesTo = effectiveTo == null || timestamp <= effectiveTo
            val callerLabel = file.nameWithoutExtension
                .substringBeforeLast('_')
                .substringBeforeLast('_')
            val matchesFilter = selectedFilterNumber == null || callerLabel.equals(selectedFilterNumber, ignoreCase = true)
            matchesFrom && matchesTo && matchesFilter
        }
    }

    val uniqueCallerLabels = remember(recordings) {
        recordings.map { file ->
            file.nameWithoutExtension
                .substringBeforeLast('_')
                .substringBeforeLast('_')
        }.distinct().sorted()
    }

    val groupedRecordings = remember(filteredRecordings) {
        filteredRecordings
            .sortedByDescending { it.lastModified() }
            .groupBy { formatDateHeader(context, it.lastModified()) }
    }

    Scaffold(
        topBar = {
            if (showTopBar) {
                TopAppBar(
                    title = {
                        Text(
                            text = if (showingRecordingsList) RivoText.get(com.grinch.rivo4.R.string.ui_saved_call_recordings_369) else RivoText.get(com.grinch.rivo4.R.string.ui_call_recording_settings_370),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        if (onBack != null || (showingRecordingsList && !initialShowList)) {
                            IconButton(onClick = {
                                if (showingRecordingsList && !initialShowList) {
                                    showingRecordingsList = false
                                } else {
                                    onBack?.invoke()
                                }
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                            }
                        }
                    },
                    actions = {
                        if (showingRecordingsList && recordings.isNotEmpty()) {
                            IconButton(onClick = { showDeleteAllConfirm = true }) {
                                Icon(
                                    Icons.Outlined.DeleteSweep,
                                    contentDescription = RivoText.get(com.grinch.rivo4.R.string.ui_delete_all_recordings_371),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        IconButton(onClick = { refreshKey++ }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = RivoText.get(com.grinch.rivo4.R.string.ui_refresh_recordings_372))
                        }
                    }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // View Mode 1: Saved Call Recordings List
            if (showingRecordingsList) {
                // Filter Card (From -> To Date Range, Date Presets, Contacts)
                item {
                    RivoExpressiveCard(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Header Row: Filter title, count badge, and (if active) Reset button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.FilterList,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = RivoText.get(com.grinch.rivo4.R.string.ui_filter_recordings_373),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (recordings.isNotEmpty()) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                        ) {
                                            Text(
                                                text = "${filteredRecordings.size}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }

                                val hasActiveFilter = fromDateMillis != null || toDateMillis != null || selectedFilterNumber != null
                                if (hasActiveFilter) {
                                    TextButton(
                                        onClick = {
                                            fromDateMillis = null
                                            toDateMillis = null
                                            datePreset = DateFilterPreset.ALL
                                            selectedFilterNumber = null
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.RestartAlt,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(RivoText.get(com.grinch.rivo4.R.string.ui_reset_366), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }

                            // Interactive "From -> To" Date Range Selectors
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // FROM Pill Button
                                Surface(
                                    onClick = { showFromDatePicker = true },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (fromDateMillis != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(
                                        1.dp,
                                        if (fromDateMillis != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.Transparent
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.CalendarToday,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (fromDateMillis != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = RivoText.get(com.grinch.rivo4.R.string.ui_from_375),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = if (fromDateMillis != null) formatDateHeader(context, fromDateMillis!!) else RivoText.get(com.grinch.rivo4.R.string.ui_start_date_376),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = if (fromDateMillis != null) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (fromDateMillis != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }

                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )

                                // TO Pill Button
                                Surface(
                                    onClick = { showToDatePicker = true },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (toDateMillis != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(
                                        1.dp,
                                        if (toDateMillis != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.Transparent
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Event,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (toDateMillis != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = RivoText.get(com.grinch.rivo4.R.string.ui_to_377),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = if (toDateMillis != null) formatDateHeader(context, toDateMillis!!) else RivoText.get(com.grinch.rivo4.R.string.ui_end_date_378),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = if (toDateMillis != null) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (toDateMillis != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }

                            // Quick Date Presets Row
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                item {
                                    FilterChip(
                                        selected = datePreset == DateFilterPreset.ALL,
                                        onClick = {
                                            fromDateMillis = null
                                            toDateMillis = null
                                            datePreset = DateFilterPreset.ALL
                                        },
                                        label = { Text(RivoText.get(com.grinch.rivo4.R.string.ui_all_dates_379)) },
                                        leadingIcon = if (datePreset == DateFilterPreset.ALL) {
                                            { Icon(Icons.Outlined.Done, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                        } else null,
                                        shape = CircleShape
                                    )
                                }
                                item {
                                    FilterChip(
                                        selected = datePreset == DateFilterPreset.TODAY,
                                        onClick = {
                                            val start = Calendar.getInstance().apply {
                                                set(Calendar.HOUR_OF_DAY, 0)
                                                set(Calendar.MINUTE, 0)
                                                set(Calendar.SECOND, 0)
                                                set(Calendar.MILLISECOND, 0)
                                            }.timeInMillis
                                            val end = Calendar.getInstance().apply {
                                                set(Calendar.HOUR_OF_DAY, 23)
                                                set(Calendar.MINUTE, 59)
                                                set(Calendar.SECOND, 59)
                                                set(Calendar.MILLISECOND, 999)
                                            }.timeInMillis
                                            fromDateMillis = start
                                            toDateMillis = end
                                            datePreset = DateFilterPreset.TODAY
                                        },
                                        label = { Text(RivoText.get(com.grinch.rivo4.R.string.ui_today_380)) },
                                        leadingIcon = if (datePreset == DateFilterPreset.TODAY) {
                                            { Icon(Icons.Outlined.Done, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                        } else null,
                                        shape = CircleShape
                                    )
                                }
                                item {
                                    FilterChip(
                                        selected = datePreset == DateFilterPreset.LAST_7_DAYS,
                                        onClick = {
                                            val start = Calendar.getInstance().apply {
                                                add(Calendar.DAY_OF_YEAR, -6)
                                                set(Calendar.HOUR_OF_DAY, 0)
                                                set(Calendar.MINUTE, 0)
                                                set(Calendar.SECOND, 0)
                                                set(Calendar.MILLISECOND, 0)
                                            }.timeInMillis
                                            val end = Calendar.getInstance().apply {
                                                set(Calendar.HOUR_OF_DAY, 23)
                                                set(Calendar.MINUTE, 59)
                                                set(Calendar.SECOND, 59)
                                                set(Calendar.MILLISECOND, 999)
                                            }.timeInMillis
                                            fromDateMillis = start
                                            toDateMillis = end
                                            datePreset = DateFilterPreset.LAST_7_DAYS
                                        },
                                        label = { Text(RivoText.get(com.grinch.rivo4.R.string.ui_last_7_days_381)) },
                                        leadingIcon = if (datePreset == DateFilterPreset.LAST_7_DAYS) {
                                            { Icon(Icons.Outlined.Done, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                        } else null,
                                        shape = CircleShape
                                    )
                                }
                                item {
                                    FilterChip(
                                        selected = datePreset == DateFilterPreset.THIS_MONTH,
                                        onClick = {
                                            val start = Calendar.getInstance().apply {
                                                set(Calendar.DAY_OF_MONTH, 1)
                                                set(Calendar.HOUR_OF_DAY, 0)
                                                set(Calendar.MINUTE, 0)
                                                set(Calendar.SECOND, 0)
                                                set(Calendar.MILLISECOND, 0)
                                            }.timeInMillis
                                            val end = Calendar.getInstance().apply {
                                                set(Calendar.HOUR_OF_DAY, 23)
                                                set(Calendar.MINUTE, 59)
                                                set(Calendar.SECOND, 59)
                                                set(Calendar.MILLISECOND, 999)
                                            }.timeInMillis
                                            fromDateMillis = start
                                            toDateMillis = end
                                            datePreset = DateFilterPreset.THIS_MONTH
                                        },
                                        label = { Text(RivoText.get(com.grinch.rivo4.R.string.ui_this_month_13)) },
                                        leadingIcon = if (datePreset == DateFilterPreset.THIS_MONTH) {
                                            { Icon(Icons.Outlined.Done, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                        } else null,
                                        shape = CircleShape
                                    )
                                }
                                if (datePreset == DateFilterPreset.CUSTOM) {
                                    item {
                                        FilterChip(
                                            selected = true,
                                            onClick = {},
                                            label = { Text(RivoText.get(com.grinch.rivo4.R.string.ui_custom_range_382)) },
                                            leadingIcon = { Icon(Icons.Outlined.Done, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                            shape = CircleShape
                                        )
                                    }
                                }
                            }

                            // Caller / Contact Filter Chips Row
                            if (uniqueCallerLabels.size > 1) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = RivoText.get(com.grinch.rivo4.R.string.ui_contact_383),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        item {
                                            FilterChip(
                                                selected = selectedFilterNumber == null,
                                                onClick = { selectedFilterNumber = null },
                                                label = { Text(RivoText.get(com.grinch.rivo4.R.string.ui_all_contacts_384)) },
                                                leadingIcon = if (selectedFilterNumber == null) {
                                                    { Icon(Icons.Outlined.Done, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                                } else null,
                                                shape = CircleShape
                                            )
                                        }
                                        items(uniqueCallerLabels) { label ->
                                            FilterChip(
                                                selected = selectedFilterNumber == label,
                                                onClick = {
                                                    selectedFilterNumber = if (selectedFilterNumber == label) null else label
                                                },
                                                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                leadingIcon = if (selectedFilterNumber == label) {
                                                    { Icon(Icons.Outlined.Done, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                                } else null,
                                                shape = CircleShape
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (filteredRecordings.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 36.dp, horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Outlined.MicNone,
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = if (recordings.isEmpty()) stringResource(R.string.call_recordings_empty) else RivoText.get(com.grinch.rivo4.R.string.ui_no_matching_recordings_385),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = if (recordings.isEmpty()) stringResource(R.string.call_recordings_empty_description)
                                else RivoText.get(com.grinch.rivo4.R.string.ui_no_recordings_match_your_selected_date_or_contact_filter_386),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            if (recordings.isNotEmpty() && (fromDateMillis != null || toDateMillis != null || selectedFilterNumber != null)) {
                                Spacer(Modifier.height(14.dp))
                                Button(
                                    onClick = {
                                        fromDateMillis = null
                                        toDateMillis = null
                                        datePreset = DateFilterPreset.ALL
                                        selectedFilterNumber = null
                                    },
                                    shape = CircleShape
                                ) {
                                    Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(RivoText.get(com.grinch.rivo4.R.string.ui_reset_filters_387))
                                }
                            }
                        }
                    }
                } else {
                    groupedRecordings.forEach { (dateHeader, filesInGroup) ->
                        item {
                            RivoSectionHeader(
                                title = dateHeader,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }
                        items(filesInGroup, key = { it.absolutePath }) { file ->
                            val isCurrentActive = activePlayingFile?.absolutePath == file.absolutePath

                            CallRecordEntryCard(
                                file = file,
                                isCurrentActive = isCurrentActive,
                                isPlaying = isCurrentActive && isPlaying,
                                currentPositionMs = if (isCurrentActive) currentPositionMs else 0,
                                durationMs = if (isCurrentActive) durationMs else 0,
                                playbackSpeed = playbackSpeed,
                                onCardClick = {
                                    if (isCurrentActive) {
                                        mediaPlayer?.let { mp ->
                                            if (mp.isPlaying) {
                                                mp.pause()
                                                isPlaying = false
                                            } else {
                                                mp.start()
                                                isPlaying = true
                                            }
                                        }
                                    } else {
                                        activePlayingFile = file
                                    }
                                },
                                onPlayPauseClick = {
                                    if (isCurrentActive) {
                                        mediaPlayer?.let { mp ->
                                            if (mp.isPlaying) {
                                                mp.pause()
                                                isPlaying = false
                                            } else {
                                                mp.start()
                                                isPlaying = true
                                            }
                                        }
                                    } else {
                                        activePlayingFile = file
                                    }
                                },
                                onSeekTo = { posMs ->
                                    if (isCurrentActive) {
                                        currentPositionMs = posMs
                                        mediaPlayer?.seekTo(posMs)
                                    }
                                },
                                onRewind10 = {
                                    if (isCurrentActive) {
                                        val newPos = (currentPositionMs - 10000).coerceAtLeast(0)
                                        currentPositionMs = newPos
                                        mediaPlayer?.seekTo(newPos)
                                    }
                                },
                                onForward10 = {
                                    if (isCurrentActive) {
                                        val newPos = (currentPositionMs + 10000).coerceAtMost(durationMs)
                                        currentPositionMs = newPos
                                        mediaPlayer?.seekTo(newPos)
                                    }
                                },
                                onSpeedChange = { newSpeed ->
                                    playbackSpeed = newSpeed
                                    mediaPlayer?.let { mp ->
                                        runCatching { mp.playbackParams = mp.playbackParams.setSpeed(newSpeed) }
                                    }
                                },
                                onShareClick = { CallRecorder.share(context, file, shareTitle) },
                                onDeleteClick = { pendingDelete = file }
                            )
                        }
                    }
                }
            } else {
                // View Mode 2: Main Settings & Quality Page

                // 1. Shizuku ADB Service Banner (at top, regular tile color, not red!)
                item {
                    RivoExpressiveCard(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RivoLeadingIconTile(
                                    icon = if (shizukuAvailable && shizukuPermissionGranted) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(if (shizukuAvailable && shizukuPermissionGranted) R.string.recorder_shizuku_ready
                                            else if (shizukuAvailable) R.string.recorder_shizuku_permission
                                            else if (ShizukuConnectionManager.getPackageName(context) != null) R.string.recorder_shizuku_stopped
                                            else R.string.recorder_shizuku_missing),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(R.string.recorder_prerequisite),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (!shizukuAvailable || !shizukuPermissionGranted) {
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (!shizukuAvailable) {
                                        Button(
                                            onClick = {
                                                val launch = ShizukuConnectionManager.getPackageName(context)?.let { context.packageManager.getLaunchIntentForPackage(it) }
                                                if (launch != null) context.startActivity(launch) else openLink(context, "https://shizuku.rikka.app/")
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = CircleShape
                                        ) {
                                            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(stringResource(R.string.recorder_open_shizuku))
                                        }
                                    }
                                    if (shizukuAvailable) {
                                        FilledTonalButton(
                                            onClick = {
                                                ShizukuConnectionManager.requestPermission()
                                                refreshKey++
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = CircleShape
                                        ) {
                                            Icon(Icons.Outlined.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(RivoText.get(com.grinch.rivo4.R.string.ui_grant_permission_388))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    RivoExpressiveCard {
                        RivoSelectListItem(
                            headline = stringResource(R.string.recorder_source),
                            supporting = stringResource(ScrcpyAudioSource.fromKey(sourceKey).descriptionResId),
                            leadingIcon = Icons.Outlined.Mic,
                            options = ScrcpyAudioSource.entries.filter { Build.VERSION.SDK_INT >= it.minApi && (it.maxApi == null || Build.VERSION.SDK_INT <= it.maxApi) }
                                .map { stringResource(it.titleResId) to it.ordinal },
                            selectedValue = ScrcpyAudioSource.fromKey(sourceKey).ordinal,
                            onValueChange = { sourceKey = ScrcpyAudioSource.entries[it].cliKey; prefs.setString("call_recording_source", sourceKey) }
                        )
                        RivoSelectListItem(
                            headline = stringResource(R.string.recorder_codec),
                            supporting = stringResource(R.string.recorder_prerequisite),
                            leadingIcon = Icons.Outlined.HighQuality,
                            options = ScrcpyAudioCodec.entries.map { stringResource(it.titleResId) to it.ordinal },
                            selectedValue = ScrcpyAudioCodec.fromKey(codecKey).ordinal,
                            onValueChange = {
                                codecKey = ScrcpyAudioCodec.entries[it].cliKey; prefs.setString("call_recording_codec", codecKey)
                                bitrate = ScrcpyAudioCodec.fromKey(codecKey).defaultBitRate
                                prefs.setInt("call_recording_bitrate", bitrate)
                            }
                        )
                        RivoSelectListItem(
                            headline = stringResource(R.string.recorder_folder),
                            supporting = stringResource(R.string.recorder_storage_hint),
                            leadingIcon = Icons.Outlined.Folder,
                            options = listOf(
                                stringResource(R.string.recorder_storage_auto) to 0,
                                stringResource(R.string.recorder_storage_app) to 1,
                                stringResource(R.string.recorder_storage_internal) to 2,
                                stringResource(R.string.recorder_storage_public) to 3
                            ),
                            selectedValue = prefs.getInt("call_recording_folder", 0),
                            onValueChange = { prefs.setInt("call_recording_folder", it) }
                        )
                        val folderPath = remember(settingsState, refreshKey) {
                            runCatching { CallRecorder.getRecordingsDirectory(context).absolutePath }.getOrNull()
                        }
                        Text(if (folderPath != null) stringResource(R.string.recorder_folder_path, folderPath)
                            else stringResource(R.string.recorder_error_storage))
                        Text(stringResource(R.string.recorder_diagnostics), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(RecordingStrings.phase(recorderState.phase)))
                        recorderState.error?.let { Text(stringResource(RecordingStrings.error(it)), color = MaterialTheme.colorScheme.error) }
                        Text(stringResource(R.string.recorder_diagnostic_values, "4.0", sourceKey, codecKey, bitrate))
                    }
                }

                // 2. Direct Internal Storage Access Card (only shown when permission not granted)
                item {
                    val hasStoragePermission = remember(refreshKey) { CallRecorder.hasStoragePermission(context) }
                    val manageStorageLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.StartActivityForResult()
                    ) {
                        refreshKey++
                    }
                    val requestPermissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) {
                        refreshKey++
                    }

                    if (!hasStoragePermission) {
                        RivoExpressiveCard(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RivoLeadingIconTile(
                                        icon = Icons.Outlined.FolderSpecial,
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = RivoText.get(com.grinch.rivo4.R.string.ui_direct_storage_access_recommended_389),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = stringResource(R.string.recorder_files_permission_hint, CallRecorder.DIRECTORY_NAME),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                            try {
                                                val intent = Intent(
                                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                                    Uri.parse("package:${context.packageName}")
                                                )
                                                manageStorageLauncher.launch(intent)
                                            } catch (e: Exception) {
                                                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                                manageStorageLauncher.launch(intent)
                                            }
                                        } else {
                                            requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = CircleShape
                                ) {
                                    Icon(Icons.Outlined.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(RivoText.get(com.grinch.rivo4.R.string.ui_grant_storage_access_390))
                                }
                            }
                        }
                    }
                }

                // 2.5 OEM / Xiaomi Optimization Card
                if (OemPermissionHelper.isOemDevice()) {
                    item {
                        val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
                        val isIgnoringBattery = remember(refreshKey) {
                            powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                        }
                        var oemDismissed by remember(refreshKey) {
                            mutableStateOf(prefs.getBoolean("oem_opt_dismissed", false))
                        }

                        if (!oemDismissed && !isIgnoringBattery) {
                            RivoExpressiveCard(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(4.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RivoLeadingIconTile(
                                            icon = Icons.Outlined.PhoneAndroid,
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = RivoText.get(com.grinch.rivo4.R.string.ui_optimization_391, (OemPermissionHelper.getOemBrandDisplayName()).toString()),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                text = stringResource(R.string.oem_recording_guide_subtitle),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                oemDismissed = true
                                                prefs.setBoolean("oem_opt_dismissed", true)
                                            }
                                        ) {
                                            Icon(
                                                Icons.Outlined.Close,
                                                contentDescription = RivoText.get(com.grinch.rivo4.R.string.ui_dismiss_183),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(8.dp))

                                    // Vertical action rows instead of squeezed horizontal buttons
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        if (OemPermissionHelper.isXiaomi() || OemPermissionHelper.isOppo() || OemPermissionHelper.isRealme() || OemPermissionHelper.isVivo() || OemPermissionHelper.isMeizu()) {
                                            OemActionRow(
                                                icon = Icons.Outlined.Mic,
                                                title = stringResource(R.string.oem_perm_bg_recording),
                                                subtitle = stringResource(R.string.oem_perm_bg_recording_desc),
                                                onClick = { OemPermissionHelper.openOemPermissions(context) }
                                            )
                                        }
                                        OemActionRow(
                                            icon = Icons.Outlined.RocketLaunch,
                                            title = stringResource(R.string.oem_perm_autostart),
                                            subtitle = stringResource(R.string.oem_perm_autostart_desc),
                                            onClick = { OemPermissionHelper.openAutostartSettings(context) }
                                        )
                                        OemActionRow(
                                            icon = Icons.Outlined.BatteryChargingFull,
                                            title = stringResource(R.string.oem_perm_battery),
                                            subtitle = stringResource(R.string.oem_perm_battery_desc),
                                            onClick = { OemPermissionHelper.openBatterySaverSettings(context) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Recording Controls Section
                item {
                    RivoSectionHeader(
                        title = RivoText.get(com.grinch.rivo4.R.string.ui_recording_controls_394),
                        icon = Icons.Outlined.SettingsVoice
                    )
                    Spacer(Modifier.height(4.dp))
                    RivoExpressiveCard(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        RivoSwitchListItem(
                            headline = RivoText.get(com.grinch.rivo4.R.string.ui_enable_call_recording_395),
                            supporting = RivoText.get(com.grinch.rivo4.R.string.ui_allow_recording_calls_and_show_record_button_during_active_cal_396),
                            leadingIcon = Icons.Outlined.Mic,
                            checked = callRecordingEnabled,
                            onCheckedChange = {
                                callRecordingEnabled = it
                                prefs.setBoolean(PreferenceManager.KEY_CALL_RECORDING, it)
                            }
                        )
                        RivoDivider(Modifier.padding(horizontal = 16.dp))
                        RivoSwitchListItem(
                            headline = RivoText.get(com.grinch.rivo4.R.string.ui_auto_record_calls_397),
                            supporting = RivoText.get(com.grinch.rivo4.R.string.ui_automatically_record_calls_as_soon_as_they_connect_398),
                            leadingIcon = Icons.Outlined.PlayCircleOutline,
                            checked = autoRecordEnabled,
                            onCheckedChange = {
                                autoRecordEnabled = it
                                prefs.setBoolean(PreferenceManager.KEY_CALL_RECORDING_AUTO, it)
                            }
                        )
                        if (autoRecordEnabled) {
                            RivoDivider(Modifier.padding(horizontal = 16.dp))
                            RivoSelectListItem(
                                headline = RivoText.get(com.grinch.rivo4.R.string.ui_auto_record_filter_399),
                                supporting = when (autoRecordFilter) {
                                    PreferenceManager.RECORD_FILTER_INCOMING_ONLY -> RivoText.get(com.grinch.rivo4.R.string.ui_recording_incoming_calls_only_400)
                                    PreferenceManager.RECORD_FILTER_OUTGOING_ONLY -> RivoText.get(com.grinch.rivo4.R.string.ui_recording_outgoing_calls_only_401)
                                    PreferenceManager.RECORD_FILTER_UNKNOWN_ONLY -> RivoText.get(com.grinch.rivo4.R.string.ui_recording_unknown_numbers_only_402)
                                    PreferenceManager.RECORD_FILTER_CONTACTS_ONLY -> RivoText.get(com.grinch.rivo4.R.string.ui_recording_saved_contacts_only_403)
                                    else -> RivoText.get(com.grinch.rivo4.R.string.ui_recording_all_calls_404)
                                },
                                leadingIcon = Icons.Outlined.FilterList,
                                options = listOf(
                                    RivoText.get(com.grinch.rivo4.R.string.ui_all_calls_405) to PreferenceManager.RECORD_FILTER_ALL,
                                    RivoText.get(com.grinch.rivo4.R.string.ui_incoming_calls_only_406) to PreferenceManager.RECORD_FILTER_INCOMING_ONLY,
                                    RivoText.get(com.grinch.rivo4.R.string.ui_outgoing_calls_only_407) to PreferenceManager.RECORD_FILTER_OUTGOING_ONLY,
                                    RivoText.get(com.grinch.rivo4.R.string.ui_unknown_numbers_only_408) to PreferenceManager.RECORD_FILTER_UNKNOWN_ONLY,
                                    RivoText.get(com.grinch.rivo4.R.string.ui_saved_contacts_only_409) to PreferenceManager.RECORD_FILTER_CONTACTS_ONLY
                                ),
                                selectedValue = autoRecordFilter,
                                onValueChange = {
                                    autoRecordFilter = it
                                    prefs.setInt(PreferenceManager.KEY_CALL_RECORDING_FILTER, it)
                                }
                            )
                        }
                    }
                }

                // 5. Audio Quality & Filters Section
                item {
                    RivoSectionHeader(
                        title = RivoText.get(com.grinch.rivo4.R.string.ui_audio_quality_filters_410),
                        icon = Icons.Outlined.GraphicEq
                    )
                    Spacer(Modifier.height(4.dp))
                    RivoExpressiveCard(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        RivoSelectListItem(
                            headline = RivoText.get(com.grinch.rivo4.R.string.ui_audio_bitrate_411),
                            supporting = RivoText.get(com.grinch.rivo4.R.string.ui_higher_bitrate_yields_clearer_audio_output_412),
                            leadingIcon = Icons.Outlined.HighQuality,
                            options = listOf(
                                "8 kbps" to 8000,
                                "16 kbps" to 16000,
                                "24 kbps" to 24000,
                                "32 kbps" to 32000,
                                "48 kbps" to 48000,
                                RivoText.get(com.grinch.rivo4.R.string.ui_64_kbps_compact_418) to 64000,
                                RivoText.get(com.grinch.rivo4.R.string.ui_96_kbps_balanced_419) to 96000,
                                RivoText.get(com.grinch.rivo4.R.string.ui_128_kbps_high_quality_420) to 128000,
                                RivoText.get(com.grinch.rivo4.R.string.ui_192_kbps_ultra_421) to 192000,
                                RivoText.get(com.grinch.rivo4.R.string.ui_256_kbps_maximum_422) to 256000
                            ),
                            selectedValue = bitrate,
                            onValueChange = {
                                bitrate = it
                                prefs.setInt("call_recording_bitrate", it)
                            }
                        )
                        RivoDivider(Modifier.padding(horizontal = 16.dp))
                        RivoSelectListItem(
                            headline = RivoText.get(com.grinch.rivo4.R.string.ui_minimum_duration_filter_423),
                            supporting = RivoText.get(com.grinch.rivo4.R.string.ui_discard_ultra_short_calls_below_threshold_424),
                            leadingIcon = Icons.Outlined.Timer,
                            options = listOf(
                                RivoText.get(com.grinch.rivo4.R.string.ui_record_all_calls_425) to 0,
                                RivoText.get(com.grinch.rivo4.R.string.ui_ignore_calls_3s_426) to 3,
                                RivoText.get(com.grinch.rivo4.R.string.ui_ignore_calls_5s_427) to 5,
                                RivoText.get(com.grinch.rivo4.R.string.ui_ignore_calls_10s_428) to 10
                            ),
                            selectedValue = minDurationFilter,
                            onValueChange = {
                                minDurationFilter = it
                                prefs.setInt("call_recording_min_duration", it)
                            }
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { file ->
        RivoConfirmationDialog(
            onDismissRequest = { pendingDelete = null },
            onConfirm = {
                if (activePlayingFile?.absolutePath == file.absolutePath) {
                    activePlayingFile = null
                }
                CallRecorder.delete(file)
                pendingDelete = null
                refreshKey++
            },
            title = stringResource(R.string.call_recordings_delete_title),
            message = stringResource(R.string.call_recordings_delete_message),
            confirmLabel = stringResource(R.string.action_delete),
            icon = Icons.Default.Delete,
            isDestructive = true
        )
    }

    if (showDeleteAllConfirm) {
        RivoConfirmationDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            onConfirm = {
                activePlayingFile = null
                recordings.forEach { file -> CallRecorder.delete(file) }
                showDeleteAllConfirm = false
                refreshKey++
            },
            title = RivoText.get(com.grinch.rivo4.R.string.ui_delete_all_recordings_429),
            message = RivoText.get(com.grinch.rivo4.R.string.ui_all_recordings_will_be_permanently_removed_from_your_device_th_430, (recordings.size).toString()),
            confirmLabel = stringResource(R.string.action_delete),
            icon = Icons.Outlined.DeleteSweep,
            isDestructive = true
        )
    }

    if (showFromDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = fromDateMillis ?: System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showFromDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { utcMillis ->
                            val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                                timeInMillis = utcMillis
                            }
                            val localCal = Calendar.getInstance().apply {
                                set(Calendar.YEAR, utcCal.get(Calendar.YEAR))
                                set(Calendar.MONTH, utcCal.get(Calendar.MONTH))
                                set(Calendar.DAY_OF_MONTH, utcCal.get(Calendar.DAY_OF_MONTH))
                                set(Calendar.HOUR_OF_DAY, 0)
                                set(Calendar.MINUTE, 0)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }
                            fromDateMillis = localCal.timeInMillis
                            datePreset = DateFilterPreset.CUSTOM
                        }
                        showFromDatePicker = false
                    }
                ) {
                    Text(stringResource(R.string.action_done))
                }
            },
            dismissButton = {
                TextButton(onClick = { showFromDatePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        ) {
            DatePicker(
                state = datePickerState,
                title = {
                    Text(
                        text = RivoText.get(com.grinch.rivo4.R.string.ui_select_start_date_from_431),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)
                    )
                }
            )
        }
    }

    if (showToDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = toDateMillis ?: System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showToDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { utcMillis ->
                            val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                                timeInMillis = utcMillis
                            }
                            val localCal = Calendar.getInstance().apply {
                                set(Calendar.YEAR, utcCal.get(Calendar.YEAR))
                                set(Calendar.MONTH, utcCal.get(Calendar.MONTH))
                                set(Calendar.DAY_OF_MONTH, utcCal.get(Calendar.DAY_OF_MONTH))
                                set(Calendar.HOUR_OF_DAY, 23)
                                set(Calendar.MINUTE, 59)
                                set(Calendar.SECOND, 59)
                                set(Calendar.MILLISECOND, 999)
                            }
                            toDateMillis = localCal.timeInMillis
                            datePreset = DateFilterPreset.CUSTOM
                        }
                        showToDatePicker = false
                    }
                ) {
                    Text(stringResource(R.string.action_done))
                }
            },
            dismissButton = {
                TextButton(onClick = { showToDatePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        ) {
            DatePicker(
                state = datePickerState,
                title = {
                    Text(
                        text = RivoText.get(com.grinch.rivo4.R.string.ui_select_end_date_to_432),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)
                    )
                }
            )
        }
    }
}

/**
 * MD3 Expressive Call Record Entry Card & Inline Player Component
 */
@Composable
fun CallRecordEntryCard(
    file: File,
    isCurrentActive: Boolean,
    isPlaying: Boolean,
    currentPositionMs: Int,
    durationMs: Int,
    playbackSpeed: Float,
    onCardClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onRewind10: () -> Unit,
    onForward10: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onShareClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val durationFormatted = remember(file, durationMs, isCurrentActive) {
        if (isCurrentActive && durationMs > 0) {
            formatTimeMs(durationMs)
        } else {
            val dur = getAudioDurationMs(context, file)
            if (dur > 0) formatTimeMs(dur.toInt()) else null
        }
    }
    val dateFormatted = remember(file) {
        SimpleDateFormat("MMM dd, h:mm a", Locale.getDefault()).format(Date(file.lastModified()))
    }
    val sizeFormatted = remember(file) {
        val kb = file.length() / 1024
        if (kb > 1024) String.format(Locale.US, "%.1f MB", kb / 1024f) else "$kb KB"
    }

    RivoExpressiveCard(
        modifier = modifier,
        containerColor = if (isCurrentActive) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Collapsed Header: Leading icon + info + single Play/Pause button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { onCardClick() }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RivoLeadingIconTile(
                    icon = if (isCurrentActive && isPlaying) Icons.AutoMirrored.Outlined.VolumeUp else Icons.Outlined.MicNone,
                    selected = isCurrentActive,
                    containerColor = if (isCurrentActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (isCurrentActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                )

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.nameWithoutExtension,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(2.dp))

                    Text(
                        text = buildString {
                            append(dateFormatted)
                            if (durationFormatted != null) append(" • $durationFormatted")
                            append(" • $sizeFormatted")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Single prominent Play/Pause button
                Surface(
                    onClick = onPlayPauseClick,
                    shape = RoundedCornerShape(16.dp),
                    color = if (isCurrentActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isCurrentActive && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = RivoText.get(com.grinch.rivo4.R.string.ui_play_pause_435),
                            tint = if (isCurrentActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Expanded Player Section
            AnimatedVisibility(
                visible = isCurrentActive,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Time Labels + Wavy Progress Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTimeMs(currentPositionMs),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(38.dp),
                            textAlign = TextAlign.Start
                        )

                        WavyAudioSlider(
                            value = if (durationMs > 0) currentPositionMs.toFloat() / durationMs.toFloat() else 0f,
                            onValueChange = { fraction ->
                                onSeekTo((fraction * durationMs).toInt())
                            },
                            isPlaying = isPlaying,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                        )

                        Text(
                            text = formatTimeMs(durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(38.dp),
                            textAlign = TextAlign.End
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Transport Controls Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Speed Pill
                        AssistChip(
                            onClick = {
                                val newSpeed = when (playbackSpeed) {
                                    1.0f -> 1.25f
                                    1.25f -> 1.5f
                                    1.5f -> 2.0f
                                    else -> 1.0f
                                }
                                onSpeedChange(newSpeed)
                            },
                            label = { Text("${playbackSpeed}x", style = MaterialTheme.typography.labelSmall) },
                            shape = CircleShape,
                            modifier = Modifier.height(34.dp)
                        )

                        Spacer(Modifier.width(12.dp))

                        // Rewind 10s
                        Surface(
                            onClick = onRewind10,
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.size(50.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Replay10,
                                    contentDescription = RivoText.get(com.grinch.rivo4.R.string.ui_rewind_10s_437),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Spacer(Modifier.width(12.dp))

                        // Center Play/Pause Hero Button
                        Surface(
                            onClick = onPlayPauseClick,
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                            modifier = Modifier.size(width = 64.dp, height = 52.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = RivoText.get(com.grinch.rivo4.R.string.ui_play_pause_435),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        Spacer(Modifier.width(12.dp))

                        // Forward 10s
                        Surface(
                            onClick = onForward10,
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.size(50.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Forward10,
                                    contentDescription = RivoText.get(com.grinch.rivo4.R.string.ui_forward_10s_438),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Bottom Action Bar: Share & Delete (separated from playback controls)
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onShareClick,
                            modifier = Modifier.weight(1f),
                            shape = CircleShape
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(RivoText.get(com.grinch.rivo4.R.string.ui_share_439))
                        }
                        OutlinedButton(
                            onClick = onDeleteClick,
                            modifier = Modifier.weight(1f),
                            shape = CircleShape,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(RivoText.get(com.grinch.rivo4.R.string.ui_delete_156))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Custom MD3 Expressive Swirly/Wavy Audio Progress Slider
 */
@Composable
fun WavyAudioSlider(
    value: Float, // 0.0f to 1.0f
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    handleColor: Color = MaterialTheme.colorScheme.primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "WavyPhaseTransition")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (isPlaying) (2 * Math.PI).toFloat() else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "WavyPhaseAnimation"
    )

    val density = LocalDensity.current
    val amplitudePx = with(density) { 3.5.dp.toPx() }
    val wavelengthPx = with(density) { 20.dp.toPx() }
    val strokeWidthPx = with(density) { 3.5.dp.toPx() }
    val handleHeightPx = with(density) { 18.dp.toPx() }

    Box(
        modifier = modifier
            .height(32.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onValueChange(fraction)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onValueChange(fraction)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val centerY = size.height / 2f
            val currentX = (value.coerceIn(0f, 1f) * width)

            // 1. Active Wavy Line (0 to currentX)
            if (currentX > 0f) {
                val path = Path()
                path.moveTo(0f, centerY)
                var x = 0f
                val step = 3.dp.toPx()
                while (x <= currentX) {
                    val y = centerY + amplitudePx * sin((x / wavelengthPx) * 2 * Math.PI + phase).toFloat()
                    path.lineTo(x, y)
                    x += step
                }
                drawPath(
                    path = path,
                    color = activeColor,
                    style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                )
            }

            // 2. Vertical Scrubber Handle | (at currentX)
            drawLine(
                color = handleColor,
                start = Offset(currentX, centerY - handleHeightPx / 2f),
                end = Offset(currentX, centerY + handleHeightPx / 2f),
                strokeWidth = strokeWidthPx * 1.15f,
                cap = StrokeCap.Round
            )

            // 3. Inactive Straight Line (currentX to width)
            if (currentX < width) {
                drawLine(
                    color = inactiveColor,
                    start = Offset(currentX, centerY),
                    end = Offset(width, centerY),
                    strokeWidth = strokeWidthPx,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

/**
 * Full-width OEM action row with icon, title, subtitle, and trailing arrow.
 * Replaces squeezed horizontal button layout.
 */
@Composable
private fun OemActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun getAudioDurationMs(context: Context, file: File): Long {
    return runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.fromFile(file))
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally { retriever.release() }
    }.getOrDefault(0L)
}

private fun formatTimeMs(ms: Int): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.US, "%d:%02d", min, sec)
}
