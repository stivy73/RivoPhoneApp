package com.grinch.rivo4.view.screen

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.grinch.rivo4.R
import com.grinch.rivo4.controller.ContactsViewModel
import com.grinch.rivo4.controller.identification.BusinessPlace
import com.grinch.rivo4.controller.identification.CallerIdentification
import com.grinch.rivo4.controller.util.PreferenceManager
import com.grinch.rivo4.controller.util.makeCall
import com.grinch.rivo4.controller.util.formatPhoneNumber
import com.grinch.rivo4.view.components.*
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.BusinessPlaceDetailsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.ContactDetailsScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinActivityViewModel

@OptIn(ExperimentalPermissionsApi::class)
@Destination<RootGraph>
@Composable
fun SearchScreen(
    navController: NavController,
    navigator: DestinationsNavigator
) {
    val permState = rememberPermissionState(Manifest.permission.READ_CONTACTS)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showButton by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 2
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            ContactSearchContent(
                navigator = navigator,
                isGranted = permState.status == PermissionStatus.Granted,
                onRequestPermission = { permState.launchPermissionRequest() },
                listState = listState
            )

            ScrollToTopButton(
                visible = showButton,
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(0)
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactSearchContent(
    navigator: DestinationsNavigator,
    isGranted: Boolean,
    onRequestPermission: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState
) {
    if (!isGranted) {
        PermissionDeniedView(
            icon = Icons.Default.Person,
            title = stringResource(R.string.search_contacts_permission_title),
            description = stringResource(R.string.search_contacts_permission_description),
            onGrantClick = onRequestPermission
        )
        return
    }

    val contactsVM: ContactsViewModel = koinActivityViewModel()
    val contacts by contactsVM.allContacts.collectAsState()
    val prefs = koinInject<PreferenceManager>()
    val callerIdentification = koinInject<CallerIdentification>()
    val identificationRevision by callerIdentification.revision.collectAsState()
    val callLauncher = rememberCallLauncher()
    val settingsState by prefs.settingsChanged.collectAsState()
    val roundness = remember(settingsState) { prefs.getInt(PreferenceManager.KEY_CARD_ROUNDNESS, 28).coerceAtLeast(1) }

    var query by remember { mutableStateOf("") }
    var businessResults by remember { mutableStateOf<List<BusinessPlace>>(emptyList()) }
    var businessLoading by remember { mutableStateOf(false) }

    LaunchedEffect(query, identificationRevision) {
        businessResults = emptyList()
        businessLoading = false
        if (!callerIdentification.businessSearchEnabled() || query.trim().length < 3) return@LaunchedEffect
        delay(550)
        businessLoading = true
        businessResults = runCatching { callerIdentification.searchBusinesses(query) }.getOrDefault(emptyList())
        businessLoading = false
    }

    BackHandler(enabled = query.isNotEmpty()) {
        query = ""
    }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    val filteredContacts = remember(query, contacts) {
        if (query.isBlank()) emptyList()
        else contacts.filter {
            val cleanQuery = query.replace(" ", "")
            val matchesName = it.name.contains(query, ignoreCase = true)
            val matchesNickname = it.nickname?.contains(query, ignoreCase = true) ?: false
            val matchesNumber = it.phoneNumbers.any { number -> number.replace(" ", "").contains(cleanQuery) }
            matchesName || matchesNickname || matchesNumber
        }.take(50)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(roundness.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 0.dp
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text(stringResource(R.string.search_contacts_placeholder)) },
                leadingIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                singleLine = true
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = when {
                    contacts.isEmpty() && query.isBlank() -> 0
                    query.isBlank() -> 1
                    filteredContacts.isEmpty() && businessResults.isEmpty() && !businessLoading -> 2
                    else -> 3
                },
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                label = "SearchContentState"
            ) { state ->
                when (state) {
                    0 -> RivoLoadingIndicatorView()
                    1 -> {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(roundness.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.size(120.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Search,
                                        null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                            Text(
                                stringResource(R.string.search_contacts_title),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                stringResource(R.string.search_type_to_start),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                    2 -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    stringResource(R.string.search_no_results_title),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    stringResource(R.string.search_no_results_hint),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    3 -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 100.dp)
                        ) {
                            item {
                                RivoSectionHeader(title = stringResource(R.string.search_results_header), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            }

                            item {
                                com.grinch.rivo4.view.components.ad.BannerAd()
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            itemsIndexed(filteredContacts) { index, contact ->
                                val isFirst = index == 0
                                val isLast = index == filteredContacts.size - 1

                                Surface(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    shape = when {
                                        roundness <= 0 -> androidx.compose.ui.graphics.RectangleShape
                                        isFirst && isLast -> RoundedCornerShape(roundness.dp)
                                        isFirst -> RoundedCornerShape(topStart = roundness.dp, topEnd = roundness.dp)
                                        isLast -> RoundedCornerShape(bottomStart = roundness.dp, bottomEnd = roundness.dp)
                                        else -> androidx.compose.ui.graphics.RectangleShape
                                    },
                                    color = MaterialTheme.colorScheme.surfaceContainerLow
                                ) {
                                    Column(
                                        modifier = Modifier.padding(
                                            top = if (isFirst) 8.dp else 0.dp,
                                            bottom = if (isLast) 8.dp else 0.dp
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 16.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                RivoListItem(
                                                    headline = contact.name,
                                                    supporting = buildString {
                                                        contact.nickname?.let { append("$it • ") }
                                                        contact.phoneNumbers.firstOrNull()?.let { append(formatPhoneNumber(it)) }
                                                    }.ifEmpty { null },
                                                    avatarName = contact.name,
                                                    photoUri = contact.photoUri,
                                                    onClick = {
                                                        navigator.navigate(ContactDetailsScreenDestination(contactId = contact.id))
                                                    }
                                                )
                                            }
                                            contact.phoneNumbers.firstOrNull()?.let { num ->
                                                IconButton(
                                                    onClick = { 
                                                        callLauncher.dial(num, contact)
                                                    },
                                                    modifier = Modifier.padding(end = 8.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Rounded.Call,
                                                        null,
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        }
                                        if (!isLast) {
                                            RivoDivider(
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                            )
                                        }
                                    }
                                }
                            }
                            if (businessLoading) {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(12.dp))
                                        Text(stringResource(R.string.business_searching))
                                    }
                                }
                            }
                            if (businessResults.isNotEmpty()) {
                                item {
                                    RivoSectionHeader(
                                        title = stringResource(R.string.business_results_header),
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                        icon = Icons.Default.Business
                                    )
                                }
                                itemsIndexed(businessResults) { _, business ->
                                    RivoListItem(
                                        headline = business.name,
                                        supporting = listOfNotNull(business.type, business.address).joinToString(" • ").ifBlank { null },
                                        supporting2 = business.phone,
                                        leadingIcon = Icons.Default.Business,
                                        trailingIcon = Icons.Default.ChevronRight,
                                        onClick = {
                                            navigator.navigate(BusinessPlaceDetailsScreenDestination(
                                                name = business.name,
                                                phone = business.phone,
                                                address = business.address,
                                                type = business.type,
                                                mapsUri = business.mapsUri
                                            ))
                                        },
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
