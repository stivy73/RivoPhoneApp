package com.grinch.rivo4.view.screen.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.grinch.rivo4.R
import com.grinch.rivo4.controller.util.PreferenceManager
import com.grinch.rivo4.modal.data.SwipeActionType
import com.grinch.rivo4.view.components.*
import com.ramcosta.composedestinations.annotation.*
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import org.koin.compose.koinInject

@Destination<RootGraph>
@Composable
fun SwipeActionsScreen(navigator: DestinationsNavigator) {
    val prefs = koinInject<PreferenceManager>()
    val revision by prefs.settingsChanged.collectAsState()
    val enabled = remember(revision) { prefs.isSwipeActionsEnabled() }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_swipe_actions_title)) },
        navigationIcon = { IconButton(onClick = { navigator.navigateUp() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
        } }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            RivoSwitchListItem(headline = stringResource(R.string.settings_swipe_actions_enable), checked = enabled,
                onCheckedChange = prefs::setSwipeActionsEnabled)
            val options = SwipeActionType.entries.map { stringResource(it.titleRes) to it.id }
            RivoSelectListItem(headline = stringResource(R.string.settings_swipe_right_action), options = options,
                selectedValue = prefs.getSwipeRightAction(), onValueChange = prefs::setSwipeRightAction, enabled = enabled)
            RivoSelectListItem(headline = stringResource(R.string.settings_swipe_left_action), options = options,
                selectedValue = prefs.getSwipeLeftAction(), onValueChange = prefs::setSwipeLeftAction, enabled = enabled)
            Text(stringResource(R.string.settings_swipe_actions_tab_warning))
        }
    }
}
