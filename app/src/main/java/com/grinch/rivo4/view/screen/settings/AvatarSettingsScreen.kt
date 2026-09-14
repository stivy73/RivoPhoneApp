package com.grinch.rivo4.view.screen.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.grinch.rivo4.R
import com.grinch.rivo4.controller.util.PreferenceManager
import com.grinch.rivo4.view.components.*
import com.ramcosta.composedestinations.annotation.*
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import org.koin.compose.koinInject

@Destination<RootGraph>
@Composable
fun AvatarSettingsScreen(navigator: DestinationsNavigator) {
    val prefs = koinInject<PreferenceManager>()
    val revision by prefs.settingsChanged.collectAsState()
    val shape = remember(revision) { prefs.getInt(PreferenceManager.KEY_AVATAR_SHAPE, 0) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_group_avatars)) },
        navigationIcon = { IconButton(onClick = { navigator.navigateUp() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
        } }) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            val labels = listOf(R.string.settings_interface_avatar_shape_squircle, R.string.settings_interface_avatar_shape_circle,
                R.string.settings_interface_avatar_shape_square, R.string.settings_interface_avatar_shape_cookie,
                R.string.settings_interface_avatar_shape_clover, R.string.settings_interface_avatar_shape_arch,
                R.string.settings_interface_avatar_shape_pill, R.string.settings_interface_avatar_shape_gem,
                R.string.settings_interface_avatar_shape_sunny, R.string.settings_interface_avatar_shape_heart,
                R.string.settings_interface_avatar_shape_burst)
            RivoSelectListItem(headline = stringResource(R.string.settings_interface_avatar_shape),
                options = labels.mapIndexed { index, label -> stringResource(label) to index }, selectedValue = shape,
                onValueChange = { prefs.setInt(PreferenceManager.KEY_AVATAR_SHAPE, it) })
            val switches = listOf(
                Triple(PreferenceManager.KEY_SHOW_PICTURE, R.string.settings_interface_show_picture, true),
                Triple(PreferenceManager.KEY_SHOW_FIRST_LETTER, R.string.settings_interface_show_first_letter, true),
                Triple(PreferenceManager.KEY_COLORFUL_AVATARS, R.string.settings_interface_colorful_avatars, true),
                Triple(PreferenceManager.KEY_GRADIENT_AVATARS, R.string.settings_interface_gradient_avatars, false),
                Triple(PreferenceManager.KEY_SHOW_CALL_SCREEN_AVATAR, R.string.settings_interface_call_screen_avatar, true),
                Triple(PreferenceManager.KEY_HIDE_AVATAR_WITH_BACKGROUND, R.string.settings_interface_hide_avatar_with_bg, false),
                Triple(PreferenceManager.KEY_SHOW_CARDS, R.string.settings_interface_use_cards, true)
            )
            switches.forEach { (key, label, default) ->
                RivoSwitchListItem(headline = stringResource(label), checked = remember(revision) { prefs.getBoolean(key, default) },
                    onCheckedChange = { prefs.setBoolean(key, it) })
            }
        }
    }
}
