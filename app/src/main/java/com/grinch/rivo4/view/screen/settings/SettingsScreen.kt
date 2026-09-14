package com.grinch.rivo4.view.screen.settings

import com.grinch.rivo4.controller.util.RivoText
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.grinch.rivo4.PATREON_URL
import com.grinch.rivo4.PLAY_STORE_URL
import com.grinch.rivo4.R
import com.grinch.rivo4.controller.util.PreferenceManager
import com.grinch.rivo4.controller.util.SettingsBackupCodec
import com.grinch.rivo4.controller.util.SettingsBackupDocument
import com.grinch.rivo4.controller.util.getAppVersion
import com.grinch.rivo4.controller.util.openLink
import com.grinch.rivo4.view.components.RivoDialog
import com.grinch.rivo4.view.components.RivoDialogAction
import com.grinch.rivo4.view.components.RivoDivider
import com.grinch.rivo4.view.components.RivoExpressiveCard
import com.grinch.rivo4.view.components.RivoListItem
import com.grinch.rivo4.view.components.RivoSwitchListItem
import com.grinch.rivo4.view.components.RivoConfirmationDialog
import com.grinch.rivo4.view.components.ad.IS_ADS_SUPPORTED
import com.grinch.rivo4.view.theme.RivoMaterialShapes
import com.grinch.rivo4.view.theme.rememberRivoMorphShape
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.*
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import org.koin.compose.koinInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun SettingsScreen(
    navigator: DestinationsNavigator
) {
    val context = LocalContext.current
    val prefs = koinInject<PreferenceManager>()
    val settingsState by prefs.settingsChanged.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = androidx.compose.ui.platform.LocalResources.current
    val appInfo = getAppVersion(context)
    val logoMorph = rememberRivoMorphShape(RivoMaterialShapes.Cookie12Sided, RivoMaterialShapes.Circle) { 0.2f }

    var enableAds by remember(settingsState) { mutableStateOf(prefs.getBoolean(PreferenceManager.KEY_ENABLE_ADS, true)) }
    var showDisableAdsDialog by remember { mutableStateOf(false) }
    var settingsBackupBusy by remember { mutableStateOf(false) }
    var pendingSettingsRestore by remember { mutableStateOf<SettingsBackupDocument?>(null) }

    val exportSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            settingsBackupBusy = true
            scope.launch {
                val count = withContext(Dispatchers.IO) {
                    runCatching {
                        val snapshot = prefs.settingsBackupSnapshot()
                        val document = SettingsBackupCodec.encode(snapshot, context.packageName)
                        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                            writer.write(document)
                        } ?: error("Cannot open destination")
                        snapshot.size
                    }.getOrNull()
                }
                settingsBackupBusy = false
                snackbarHostState.showSnackbar(
                    count?.let { resources.getString(R.string.settings_backup_preferences_exported, it) }
                        ?: resources.getString(R.string.settings_backup_preferences_export_failed)
                )
            }
        }
    }

    val importSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            settingsBackupBusy = true
            scope.launch {
                val document = withContext(Dispatchers.IO) {
                    runCatching {
                        val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: error("Cannot open backup")
                        SettingsBackupCodec.decode(content)
                    }.getOrNull()
                }
                settingsBackupBusy = false
                if (document == null) {
                    snackbarHostState.showSnackbar(resources.getString(R.string.settings_backup_preferences_invalid))
                } else {
                    pendingSettingsRestore = document
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surface
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // App Info Banner
            item {
                RivoExpressiveCard(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.extraLarge)
                        .clickable { navigator.navigate(AboutScreenDestination) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(56.dp),
                            shape = logoMorph,
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shadowElevation = 3.dp
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(11.dp)) {
                                Image(
                                    painter = painterResource(R.drawable.logo),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.about_app_display_name),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ) {
                                    Text(
                                        text = "v${appInfo.first}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.settings_top_card_subtext),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowForwardIos,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // 1. Personalization & Display
            item {
                RivoExpressiveCard(
                    title = RivoText.get(com.grinch.rivo4.R.string.ui_personalization_display_336),
                    icon = Icons.Outlined.Palette
                ) {
                    RivoListItem(
                        headline = RivoText.get(com.grinch.rivo4.R.string.ui_theme_appearance_282),
                        supporting = RivoText.get(com.grinch.rivo4.R.string.ui_material_you_color_palette_amoled_dark_mode_animations_337),
                        leadingIcon = Icons.Outlined.Palette,
                        onClick = { navigator.navigate(InterfaceScreenDestination) }
                    )
                    RivoDivider(Modifier.padding(horizontal = 16.dp))
                    RivoListItem(
                        headline = RivoText.get(com.grinch.rivo4.R.string.ui_navigation_bar_284),
                        supporting = RivoText.get(com.grinch.rivo4.R.string.ui_floating_bar_style_blur_effect_roundness_tab_layout_285),
                        leadingIcon = Icons.Outlined.Dock,
                        onClick = { navigator.navigate(BottomNavScreenDestination) }
                    )
                    RivoDivider(Modifier.padding(horizontal = 16.dp))
                    RivoListItem(
                        headline = RivoText.get(com.grinch.rivo4.R.string.ui_avatars_contact_cards_286),
                        supporting = RivoText.get(com.grinch.rivo4.R.string.ui_11_avatar_shapes_contact_photos_initials_cards_287),
                        leadingIcon = Icons.Outlined.AccountCircle,
                        onClick = { navigator.navigate(AvatarSettingsScreenDestination) }
                    )
                    RivoDivider(Modifier.padding(horizontal = 16.dp))
                    RivoListItem(
                        headline = stringResource(R.string.settings_sound_vibration_headline),
                        supporting = stringResource(R.string.settings_sound_vibration_supporting),
                        leadingIcon = Icons.Outlined.VolumeUp,
                        onClick = { navigator.navigate(SoundVibrationScreenDestination) }
                    )
                }
            }

            // 2. Calling & Behavior
            item {
                RivoExpressiveCard(
                    title = RivoText.get(com.grinch.rivo4.R.string.ui_calling_behavior_338),
                    icon = Icons.Outlined.Phone
                ) {
                    RivoListItem(
                        headline = stringResource(R.string.settings_call_settings_headline),
                        supporting = stringResource(R.string.settings_call_settings_supporting),
                        leadingIcon = Icons.Outlined.SimCard,
                        onClick = { navigator.navigate(CallAccountsScreenDestination) }
                    )
                    RivoDivider(Modifier.padding(horizontal = 16.dp))
                    RivoListItem(
                        headline = stringResource(R.string.settings_swipe_actions_title),
                        supporting = stringResource(R.string.settings_swipe_actions_supporting),
                        leadingIcon = Icons.Outlined.Swipe,
                        onClick = { navigator.navigate(SwipeActionsScreenDestination) }
                    )
                    RivoDivider(Modifier.padding(horizontal = 16.dp))
                    RivoListItem(
                        headline = stringResource(R.string.call_recordings_title),
                        supporting = RivoText.get(com.grinch.rivo4.R.string.ui_auto_recording_shizuku_internal_audio_saved_recordings_339),
                        leadingIcon = Icons.Outlined.FiberManualRecord,
                        onClick = { navigator.navigate(CallRecordingsScreenDestination()) }
                    )
                    RivoDivider(Modifier.padding(horizontal = 16.dp))
                    RivoListItem(
                        headline = RivoText.get(com.grinch.rivo4.R.string.ui_call_analytics_insights_340),
                        supporting = RivoText.get(com.grinch.rivo4.R.string.ui_talk_time_leaderboard_peak_hours_distribution_341),
                        leadingIcon = Icons.Outlined.Analytics,
                        onClick = { navigator.navigate(CallAnalyticsScreenDestination()) }
                    )
                }
            }

            // 3. Call Protection & Security
            item {
                RivoExpressiveCard(
                    title = RivoText.get(com.grinch.rivo4.R.string.ui_call_protection_security_342),
                    icon = Icons.Outlined.Security
                ) {
                    val appLockEnabled = remember(settingsState) { prefs.isAppLockEnabled() }
                    RivoListItem(
                        headline = RivoText.get(com.grinch.rivo4.R.string.ui_app_lock_244),
                        supporting = if (appLockEnabled) RivoText.get(com.grinch.rivo4.R.string.ui_enabled_face_fingerprint_pin_343) else RivoText.get(com.grinch.rivo4.R.string.ui_protect_app_with_biometrics_or_pin_344),
                        leadingIcon = Icons.Outlined.Lock,
                        onClick = { navigator.navigate(AppLockScreenDestination) }
                    )
                    RivoDivider(Modifier.padding(horizontal = 16.dp))
                    val secretCode = remember(settingsState) {
                        prefs.getString(PreferenceManager.KEY_SECRET_DIALPAD_CODE, PreferenceManager.DEFAULT_SECRET_DIALPAD_CODE) ?: PreferenceManager.DEFAULT_SECRET_DIALPAD_CODE
                    }
                    RivoListItem(
                        headline = RivoText.get(com.grinch.rivo4.R.string.ui_private_storage_88),
                        supporting = RivoText.get(com.grinch.rivo4.R.string.ui_secret_dialpad_vault_stored_only_in_app_memory_345, (secretCode).toString()),
                        leadingIcon = Icons.Outlined.FolderShared,
                        onClick = { navigator.navigate(PrivateContactsScreenDestination) }
                    )
                    RivoDivider(Modifier.padding(horizontal = 16.dp))
                    RivoListItem(
                        headline = stringResource(R.string.settings_blocked_numbers_headline),
                        supporting = stringResource(R.string.settings_blocked_numbers_supporting),
                        leadingIcon = Icons.Outlined.Block,
                        onClick = { navigator.navigate(BlockedNumbersScreenDestination) }
                    )
                    RivoDivider(Modifier.padding(horizontal = 16.dp))
                    RivoListItem(
                        headline = stringResource(R.string.fake_call_title),
                        supporting = stringResource(R.string.fake_call_subtitle),
                        leadingIcon = Icons.Outlined.PhoneCallback,
                        onClick = { navigator.navigate(FakeCallSchedulerScreenDestination) }
                    )
                    RivoDivider(Modifier.padding(horizontal = 16.dp))
                    RivoListItem(
                        headline = RivoText.get(com.grinch.rivo4.R.string.ui_permissions_app_setup_202),
                        supporting = RivoText.get(com.grinch.rivo4.R.string.ui_review_granted_permissions_and_system_capabilities_346),
                        leadingIcon = Icons.Outlined.VerifiedUser,
                        onClick = { navigator.navigate(PermissionsChecklistScreenDestination) }
                    )
                }
            }

            // 4. Contacts & Data
            item {
                RivoExpressiveCard(
                    title = stringResource(R.string.settings_contacts_management_title),
                    icon = Icons.Outlined.ManageAccounts
                ) {
                    RivoListItem(
                        headline = stringResource(R.string.settings_contact_management_headline),
                        supporting = stringResource(R.string.settings_contact_management_supporting),
                        leadingIcon = Icons.Outlined.ManageAccounts,
                        onClick = { navigator.navigate(ContactManagementScreenDestination) }
                    )
                    RivoDivider(Modifier.padding(horizontal = 16.dp))
                    RivoListItem(
                        headline = stringResource(R.string.settings_manage_visibility),
                        supporting = stringResource(R.string.settings_manage_visibility_supporting),
                        leadingIcon = Icons.Outlined.Visibility,
                        onClick = { navigator.navigate(ContactVisibilityScreenDestination) }
                    )
                    RivoDivider(Modifier.padding(horizontal = 16.dp))
                    RivoListItem(
                        headline = stringResource(R.string.settings_backup_restore_headline),
                        supporting = stringResource(R.string.settings_backup_restore_supporting),
                        leadingIcon = Icons.Outlined.Backup,
                        onClick = { navigator.navigate(BackupRestoreScreenDestination) }
                    )
                }
            }

            // 5. Support & About
            item {
                RivoExpressiveCard(
                    title = RivoText.get(com.grinch.rivo4.R.string.ui_support_about_347),
                    icon = Icons.Outlined.HelpOutline
                ) {
                    if (IS_ADS_SUPPORTED) {
                        RivoSwitchListItem(
                            headline = RivoText.get(com.grinch.rivo4.R.string.ui_display_banner_ads_348),
                            supporting = RivoText.get(com.grinch.rivo4.R.string.ui_show_non_intrusive_banner_ads_inside_lists_to_support_developm_349),
                            leadingIcon = Icons.Outlined.AdUnits,
                            checked = enableAds,
                            onCheckedChange = { checked ->
                                if (!checked) {
                                    showDisableAdsDialog = true
                                } else {
                                    enableAds = true
                                    prefs.setBoolean(PreferenceManager.KEY_ENABLE_ADS, true)
                                }
                            }
                        )
                        RivoDivider(Modifier.padding(horizontal = 16.dp))
                    }
                    RivoListItem(
                        headline = RivoText.get(com.grinch.rivo4.R.string.ui_rate_on_google_play_350),
                        supporting = RivoText.get(com.grinch.rivo4.R.string.ui_support_rivo_on_google_play_store_351),
                        leadingIcon = Icons.Default.Star,
                        onClick = { openLink(context, PLAY_STORE_URL) }
                    )
                    RivoDivider(Modifier.padding(horizontal = 16.dp))
                    RivoListItem(
                        headline = RivoText.get(com.grinch.rivo4.R.string.ui_about_rivo_352),
                        supporting = RivoText.get(com.grinch.rivo4.R.string.ui_version_open_source_licenses_contributors_353),
                        leadingIcon = Icons.Outlined.Info,
                        onClick = { navigator.navigate(AboutScreenDestination) }
                    )
                }
            }

            item {
                com.grinch.rivo4.view.components.ad.BannerAd()
            }

            item {
                RivoExpressiveCard(title = stringResource(R.string.caller_title), icon = Icons.Outlined.Search) {
                    RivoListItem(headline = stringResource(R.string.caller_title),
                        supporting = stringResource(R.string.caller_settings_summary),
                        leadingIcon = Icons.Outlined.Search,
                        onClick = { navigator.navigate(CallerIdentificationScreenDestination) })
                }
            }

            item {
                RivoExpressiveCard(
                    title = stringResource(R.string.settings_backup_preferences_title),
                    icon = Icons.Outlined.SettingsBackupRestore
                ) {
                    Text(
                        text = stringResource(R.string.settings_backup_preferences_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    RivoListItem(
                        headline = stringResource(R.string.settings_backup_preferences_export),
                        supporting = stringResource(R.string.settings_backup_preferences_export_supporting),
                        leadingIcon = Icons.Outlined.FileUpload,
                        enabled = !settingsBackupBusy,
                        onClick = { exportSettingsLauncher.launch("rivo_personal_settings.json") }
                    )
                    RivoDivider(Modifier.padding(horizontal = 16.dp))
                    RivoListItem(
                        headline = stringResource(R.string.settings_backup_preferences_restore),
                        supporting = stringResource(R.string.settings_backup_preferences_restore_supporting),
                        leadingIcon = Icons.Outlined.Restore,
                        enabled = !settingsBackupBusy,
                        onClick = { importSettingsLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) }
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.about_copyright),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                )
            }
        }

        if (showDisableAdsDialog) {
            RivoDialog(
                onDismissRequest = { showDisableAdsDialog = false },
                title = stringResource(R.string.ads_disable_dialog_title),
                icon = Icons.Outlined.Favorite,
                confirmAction = RivoDialogAction(
                    label = stringResource(R.string.ads_disable_dialog_confirm),
                    onClick = {
                        enableAds = false
                        prefs.setBoolean(PreferenceManager.KEY_ENABLE_ADS, false)
                        showDisableAdsDialog = false
                    }
                ),
                dismissAction = RivoDialogAction(
                    label = stringResource(R.string.ads_disable_dialog_keep),
                    onClick = { showDisableAdsDialog = false }
                )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.ads_disable_dialog_body),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        onClick = {
                            openLink(context, PATREON_URL)
                            showDisableAdsDialog = false
                        }
                    ) {
                        Icon(Icons.Outlined.VolunteerActivism, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.patreon_prompt_confirm))
                    }
                }
            }
        }

        pendingSettingsRestore?.let { backup ->
            RivoConfirmationDialog(
                onDismissRequest = { pendingSettingsRestore = null },
                onConfirm = {
                    pendingSettingsRestore = null
                    settingsBackupBusy = true
                    scope.launch {
                        val restored = withContext(Dispatchers.IO) {
                            runCatching { prefs.restoreSettingsBackup(backup.settings) }.getOrDefault(false)
                        }
                        settingsBackupBusy = false
                        snackbarHostState.showSnackbar(
                            if (restored) {
                                resources.getString(R.string.settings_backup_preferences_restored, backup.settings.size)
                            } else {
                                resources.getString(R.string.settings_backup_preferences_restore_failed)
                            }
                        )
                    }
                },
                title = stringResource(R.string.settings_backup_preferences_confirm_title),
                message = stringResource(R.string.settings_backup_preferences_confirm_message, backup.settings.size),
                confirmLabel = stringResource(R.string.settings_backup_preferences_restore),
                dismissLabel = stringResource(R.string.action_cancel),
                icon = Icons.Outlined.Restore
            )
        }
    }
}
