package com.grinch.rivo4.view.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.grinch.rivo4.R
import com.grinch.rivo4.view.components.RivoExpressiveCard
import com.grinch.rivo4.view.components.RivoListItem
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.ContactEditScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

/** Detail for a transient Google Places result; nothing is stored until the user saves a contact. */
@Destination<RootGraph>
@Composable
fun BusinessPlaceDetailsScreen(
    name: String,
    phone: String? = null,
    address: String? = null,
    type: String? = null,
    mapsUri: String? = null,
    navigator: DestinationsNavigator
) {
    val context = LocalContext.current
    val openUri: (String) -> Unit = { uri -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) } }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.business_details_title)) },
                navigationIcon = {
                    IconButton(onClick = navigator::navigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                RivoExpressiveCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Icon(Icons.Default.Business, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        type?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.business_details_source), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            phone?.let { number ->
                item {
                    RivoListItem(
                        headline = stringResource(R.string.business_phone),
                        supporting = number,
                        leadingIcon = Icons.Default.Call,
                        onClick = { openUri("tel:${Uri.encode(number)}") }
                    )
                }
            }
            address?.let { placeAddress ->
                item {
                    RivoListItem(
                        headline = stringResource(R.string.business_address),
                        supporting = placeAddress,
                        leadingIcon = Icons.Default.LocationOn,
                        onClick = {
                            val target = mapsUri ?: "https://www.google.com/maps/search/?api=1&query=${Uri.encode(placeAddress)}"
                            openUri(target)
                        }
                    )
                }
            }
            item {
                Button(
                    onClick = {
                        navigator.navigate(ContactEditScreenDestination(
                            initialName = name,
                            initialPhone = phone,
                            initialAddress = address
                        ))
                    },
                    enabled = !phone.isNullOrBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PersonAdd, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.business_save_contact))
                }
            }
            if (phone.isNullOrBlank()) item {
                Text(stringResource(R.string.business_phone_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
