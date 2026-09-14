package com.yourcompany.facesearch.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourcompany.facesearch.data.ProfileConfidence
import com.yourcompany.facesearch.data.ProfileLeadStrength
import com.yourcompany.facesearch.data.ProfileStatus
import com.yourcompany.facesearch.data.PublicProfileLead
import com.yourcompany.facesearch.ui.components.InAppWebViewSheet

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
    val context = LocalContext.current
    val profile = viewModel.profile
    var activeInAppUrl by remember { mutableStateOf<String?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val scrollState = rememberLazyListState()
    val leadsCount = viewModel.leads.size
    var filterMode by remember { mutableStateOf(ProfileFilter.ALL) }

    val filteredLeads = remember(viewModel.leads, filterMode) {
        when (filterMode) {
            ProfileFilter.ALL -> viewModel.leads
            ProfileFilter.FOUND -> viewModel.leads.filter {
                it.status == ProfileStatus.LIKELY_EXISTS
            }
            ProfileFilter.STRONG -> viewModel.leads.filter {
                it.status == ProfileStatus.LIKELY_EXISTS && it.confidence == ProfileConfidence.STRONG
            }
            ProfileFilter.UNCHECKED -> viewModel.leads.filter {
                it.status == ProfileStatus.UNCHECKED || it.status == ProfileStatus.CHECKING
            }
        }
    }

    LaunchedEffect(leadsCount) {
        if (leadsCount > 0) {
            scrollState.animateScrollToItem(index = 9)
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
                    if (viewModel.leads.isNotEmpty()) {
                        IconButton(onClick = { showExportDialog = true }) {
                            Icon(Icons.Default.Download, contentDescription = "Export results")
                        }
                    }
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
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F7F3)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Your local identity card", fontWeight = FontWeight.Bold)
                        Text(
                            "Add only details that belong to you. They are stored in this app's private storage and are used to build manually reviewable public-profile routes. No platform is logged into or scraped.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Identity fields
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
                    label = "Known usernames or handles",
                    value = profile.handles,
                    placeholder = "Comma-separated, e.g. alexsmith, @alex_s",
                    onChange = { value -> viewModel.updateProfile { it.copy(handles = value) } },
                    onDone = { focusManager.clearFocus() }
                )
            }
            item {
                IdentityField(
                    label = "Email address (for OSINT)",
                    value = profile.email,
                    placeholder = "e.g. alex@example.com — used for Gravatar, breach checks",
                    keyboardType = KeyboardType.Email,
                    onChange = { value -> viewModel.updateProfile { it.copy(email = value) } },
                    onDone = { focusManager.clearFocus() }
                )
            }
            item {
                IdentityField(
                    label = "Phone number (optional, for OSINT)",
                    value = profile.phone,
                    placeholder = "e.g. +44 7xxx — used for reverse phone lookup",
                    keyboardType = KeyboardType.Phone,
                    onChange = { value -> viewModel.updateProfile { it.copy(phone = value) } },
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
                    keyboardType = KeyboardType.Uri,
                    onChange = { value -> viewModel.updateProfile { it.copy(website = value) } },
                    onDone = {
                        focusManager.clearFocus()
                        viewModel.saveAndGenerate()
                    }
                )
            }

            // Generate button
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

            // Status message
            item {
                Text(
                    text = viewModel.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (leadsCount > 0) Color(0xFF2E7D32) else Color.DarkGray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Check All button + progress
            if (leadsCount > 0) {
                item {
                    Button(
                        onClick = { viewModel.checkAllLeads() },
                        enabled = !viewModel.isChecking,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (viewModel.isChecking) "Checking... (${viewModel.checkProgress}/${viewModel.checkTotal})"
                            else "Check All Profiles (HTTP)",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (viewModel.isChecking) {
                    item {
                        LinearProgressIndicator(
                            progress = if (viewModel.checkTotal > 0)
                                viewModel.checkProgress.toFloat() / viewModel.checkTotal
                            else 0f,
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = Color(0xFF1565C0)
                        )
                    }
                }

                // Filter chips
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(
                            selected = filterMode == ProfileFilter.ALL,
                            onClick = { filterMode = ProfileFilter.ALL },
                            label = { Text("All (${viewModel.leads.size})") }
                        )
                        FilterChip(
                            selected = filterMode == ProfileFilter.FOUND,
                            onClick = { filterMode = ProfileFilter.FOUND },
                            label = {
                                val count = viewModel.leads.count { it.status == ProfileStatus.LIKELY_EXISTS }
                                Text("Found ($count)")
                            }
                        )
                        FilterChip(
                            selected = filterMode == ProfileFilter.STRONG,
                            onClick = { filterMode = ProfileFilter.STRONG },
                            label = {
                                val count = viewModel.leads.count {
                                    it.status == ProfileStatus.LIKELY_EXISTS &&
                                    it.confidence == ProfileConfidence.STRONG
                                }
                                Text("Strong ($count)")
                            }
                        )
                        FilterChip(
                            selected = filterMode == ProfileFilter.UNCHECKED,
                            onClick = { filterMode = ProfileFilter.UNCHECKED },
                            label = {
                                val count = viewModel.leads.count {
                                    it.status == ProfileStatus.UNCHECKED || it.status == ProfileStatus.CHECKING
                                }
                                Text("Pending ($count)")
                            }
                        )
                    }
                }
            }

            // Web search queries
            if (viewModel.webQueries.isNotEmpty()) {
                item {
                    Text("MANUAL WEB SEARCH", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black, color = Color(0xFF2E7D32))
                    Text(
                        "Search public profiles directly in the app via DuckDuckGo.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                items(viewModel.webQueries.size, key = { it }) { index ->
                    OutlinedButton(
                        onClick = {
                            val url = viewModel.webQueries[index]
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                activeInAppUrl = url
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null)
                        Text(
                            "Web Search ${index + 1}: ${viewModel.webQueries[index].substringAfter("q=").take(40)}...",
                            modifier = Modifier.padding(start = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Email OSINT queries
            if (viewModel.emailQueries.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("EMAIL OSINT", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black, color = Color(0xFF7B1FA2))
                    Text(
                        "Gravatar lookup, breach checks, and email-based searches.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                items(viewModel.emailQueries.size, key = { it }) { index ->
                    val (label, url) = viewModel.emailQueries[index]
                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                activeInAppUrl = url
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null)
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            // Phone OSINT queries
            if (viewModel.phoneQueries.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("PHONE OSINT", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black, color = Color(0xFF7B1FA2))
                    Text(
                        "Reverse phone lookup and caller ID search.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                items(viewModel.phoneQueries.size, key = { it }) { index ->
                    val (label, url) = viewModel.phoneQueries[index]
                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                activeInAppUrl = url
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null)
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            // Profile leads
            if (filteredLeads.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("PROFILE ROUTES (${filteredLeads.size})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black, color = Color(0xFFF57F17))
                    Text(
                        "Provided-handle routes are stronger than name variants. HTTP-checked results show confidence levels. A route does not prove an account exists or belongs to you.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                items(filteredLeads.size, key = { index -> filteredLeads[index].url }) { index ->
                    ProfileLeadCard(
                        lead = filteredLeads[index],
                        onOpen = {
                            val url = filteredLeads[index].url
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                activeInAppUrl = url
                            }
                        }
                    )
                }
            }
        }

        // Export dialog
        if (showExportDialog) {
            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                title = { Text("Export Results") },
                text = {
                    viewModel.exportText?.let { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 15,
                            overflow = TextOverflow.Ellipsis
                        )
                    } ?: Text("Generating export...")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.exportText?.let { text ->
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, text)
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Export results"))
                            }
                        }
                    ) { Text("Share") }
                },
                dismissButton = {
                    TextButton(onClick = { showExportDialog = false }) { Text("Close") }
                }
            )
        }

        activeInAppUrl?.let { url ->
            InAppWebViewSheet(
                url = url,
                onDismiss = { activeInAppUrl = null }
            )
        }
    }
}

enum class ProfileFilter { ALL, FOUND, STRONG, UNCHECKED }

@Composable
private fun IdentityField(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    onDone: () -> Unit = {},
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Done,
            keyboardType = keyboardType
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() })
    )
}

@Composable
private fun ProfileLeadCard(lead: PublicProfileLead, onOpen: () -> Unit) {
    val provided = lead.strength == ProfileLeadStrength.PROVIDED_HANDLE
    val emailDerived = lead.strength == ProfileLeadStrength.EMAIL_DERIVED

    val statusColor = when (lead.status) {
        ProfileStatus.LIKELY_EXISTS -> when (lead.confidence) {
            ProfileConfidence.STRONG -> Color(0xFF2E7D32)
            ProfileConfidence.POSSIBLE -> Color(0xFFF57F17)
            ProfileConfidence.WEAK -> Color(0xFF9E9E9E)
        }
        ProfileStatus.NOT_FOUND -> Color(0xFFE53935)
        ProfileStatus.CHECKING -> Color(0xFF1976D2)
        ProfileStatus.UNCHECKED -> Color(0xFF9E9E9E)
        ProfileStatus.ERROR -> Color(0xFFE53935)
    }

    val statusText = when (lead.status) {
        ProfileStatus.LIKELY_EXISTS -> when (lead.confidence) {
            ProfileConfidence.STRONG -> "STRONG MATCH"
            ProfileConfidence.POSSIBLE -> "POSSIBLE"
            ProfileConfidence.WEAK -> "WEAK"
        }
        ProfileStatus.NOT_FOUND -> "NOT FOUND"
        ProfileStatus.CHECKING -> "CHECKING..."
        ProfileStatus.UNCHECKED -> "UNCHECKED"
        ProfileStatus.ERROR -> "ERROR"
    }

    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (lead.status == ProfileStatus.LIKELY_EXISTS) Color(0xFFF1F8E9) else Color.White
        ),
        border = if (lead.confidence == ProfileConfidence.STRONG && lead.status == ProfileStatus.LIKELY_EXISTS)
            androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF2E7D32))
        else null
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = lead.platform,
                    fontWeight = FontWeight.Black,
                    color = if (provided) Color(0xFF2E7D32)
                           else if (emailDerived) Color(0xFF7B1FA2)
                           else Color(0xFFF57F17)
                )
                Surface(
                    color = statusColor.copy(alpha = 0.15f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = statusText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            Text("@${lead.handle}", fontWeight = FontWeight.Bold)
            if (lead.pageTitle.isNotBlank() && lead.status == ProfileStatus.LIKELY_EXISTS) {
                Text(
                    "Title: ${lead.pageTitle}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF558B2F),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(lead.evidence, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
            if (lead.statusCode > 0) {
                Text(
                    "HTTP ${lead.statusCode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (lead.statusCode == 200) Color(0xFF2E7D32) else Color(0xFF9E9E9E)
                )
            }
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
