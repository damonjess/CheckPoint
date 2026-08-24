package com.yourcompany.facesearch.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourcompany.facesearch.data.ProfileLeadStrength
import com.yourcompany.facesearch.data.PublicProfileLead

/**
 * Optional local identity-card workflow. It is intentionally independent from
 * capture and offline scan, and opens each possible profile only after a user
 * taps it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileDiscoveryScreen(
    viewModel: ProfileDiscoveryViewModel,
    onBack: () -> Unit
) {
    val profile = viewModel.profile
    val uriHandler = LocalUriHandler.current
    val focusManager = LocalFocusManager.current
    val scrollState = rememberLazyListState()
    val leadsCount = viewModel.leads.size

    LaunchedEffect(leadsCount) {
        if (leadsCount > 0) {
            // Scroll to show the first few results
            scrollState.animateScrollToItem(index = 7)
        }
    }

    Scaffold(
        containerColor = Color(0xFFFBFBFB),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("find my public profiles", fontWeight = FontWeight.Black) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.clearIdentityCard()
                        focusManager.clearFocus()
                    }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Clear local identity card")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        LazyColumn(
            state = scrollState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F7F3))) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Your local identity card", fontWeight = FontWeight.Bold)
                        Text(
                            "Add only details that belong to you. They are stored in this app's private storage and are used to build manually reviewable public-profile routes. No platform is logged into or scraped.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                IdentityField(
                    label = "Your name",
                    value = profile.fullName,
                    placeholder = "e.g. Alex Smith",
                    onChange = { value -> viewModel.updateProfile { it.copy(fullName = value) } },
                    onDone = { focusManager.clearFocus() }
                )
            }
            item {
                IdentityField(
                    label = "Aliases or past names",
                    value = profile.aliases,
                    placeholder = "Comma-separated, e.g. Alex S, A. Smith",
                    onChange = { value -> viewModel.updateProfile { it.copy(aliases = value) } },
                    onDone = { focusManager.clearFocus() }
                )
            }
            item {
                IdentityField(
                    label = "Known usernames or handle fragments",
                    value = profile.handles,
                    placeholder = "Comma-separated, e.g. alexsmith, @alex_s",
                    onChange = { value -> viewModel.updateProfile { it.copy(handles = value) } },
                    onDone = { focusManager.clearFocus() }
                )
            }
            item {
                IdentityField(
                    label = "City or region (optional)",
                    value = profile.city,
                    placeholder = "Used only as a local reference",
                    onChange = { value -> viewModel.updateProfile { it.copy(city = value) } },
                    onDone = { focusManager.clearFocus() }
                )
            }
            item {
                IdentityField(
                    label = "Personal website (optional)",
                    value = profile.website,
                    placeholder = "https://example.com",
                    onChange = { value -> viewModel.updateProfile { it.copy(website = value) } },
                    onDone = {
                        focusManager.clearFocus()
                        viewModel.saveAndGenerate()
                    }
                )
            }
            item {
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        viewModel.saveAndGenerate()
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(
                        if (leadsCount > 0) Icons.Default.Check else Icons.Default.Search,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (leadsCount > 0) "Update & Regenerate Leads" else "Generate Public Profile Leads",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            item {
                Text(
                    text = viewModel.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (leadsCount > 0) Color(0xFF2E7D32) else Color.DarkGray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (viewModel.webQueries.isNotEmpty()) {
                item {
                    Text("MANUAL WEB SEARCH", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black, color = Color(0xFF2E7D32))
                    Text(
                        "These searches open in your browser. Review any provider prompt yourself.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { uriHandler.openUri(viewModel.webQueries.first()) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null)
                        Text("Open Public Profile Web Search", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            if (viewModel.leads.isNotEmpty()) {
                item {
                    Text("PROFILE ROUTES TO REVIEW", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black, color = Color(0xFFF57F17))
                    Text(
                        "Provided-handle routes are stronger than name variants. A route does not prove an account exists or belongs to you.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                items(viewModel.leads.size, key = { index -> viewModel.leads[index].url }) { index ->
                    ProfileLeadCard(
                        lead = viewModel.leads[index],
                        onOpen = { uriHandler.openUri(viewModel.leads[index].url) }
                    )
                }
            }
        }
    }
}

@Composable
private fun IdentityField(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    onDone: () -> Unit = {}
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() })
    )
}

@Composable
private fun ProfileLeadCard(lead: PublicProfileLead, onOpen: () -> Unit) {
    val provided = lead.strength == ProfileLeadStrength.PROVIDED_HANDLE
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = lead.platform,
                fontWeight = FontWeight.Black,
                color = if (provided) Color(0xFF2E7D32) else Color(0xFFF57F17)
            )
            Text("@${lead.handle}", fontWeight = FontWeight.Bold)
            Text(lead.evidence, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
            Text(
                lead.url,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF1565C0),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
