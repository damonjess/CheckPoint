package com.yourcompany.facesearch.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourcompany.facesearch.network.NetworkProxyConfig
import com.yourcompany.facesearch.network.SerpApiKeyManager
import java.io.File

@Composable
fun ProxySettingsToggle(
    modifier: Modifier = Modifier
) {
    var proxyEnabled by remember { mutableStateOf(NetworkProxyConfig.isProxyEnabled) }

    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Route Traffic via Tor (SOCKS5)", fontWeight = FontWeight.Bold, color = Color.Black)
            Text(
                "Requires Orbot running locally on port 9050.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
        Switch(
            checked = proxyEnabled,
            onCheckedChange = { enabled ->
                proxyEnabled = enabled
                NetworkProxyConfig.isProxyEnabled = enabled
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    onOpenWatchlist: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var apiKeyText by remember { mutableStateOf(SerpApiKeyManager.getApiKey(context)) }
    
    val sharedPrefs = remember { context.getSharedPreferences("face_search_prefs", Context.MODE_PRIVATE) }
    
    val engines = listOf("Google", "Bing", "Yandex", "Sogou", "TinEye", "Search4faces")
    val engineStates = remember {
        mutableStateMapOf<String, Boolean>().apply {
            engines.forEach { engine ->
                put(engine, sharedPrefs.getBoolean("engine_enabled_$engine", true))
            }
        }
    }

    val cacheDir = File(context.cacheDir, "search_results_cache")
    val cacheFilesCount = remember(cacheDir) { if (cacheDir.exists()) cacheDir.listFiles()?.size ?: 0 else 0 }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFF6750A4), modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("OSINT Engine Settings", fontWeight = FontWeight.Black, fontSize = 20.sp, color = Color.Black)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Watchlist Surveillance Entry
            Text("Automated Background Watchlist", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.DarkGray)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    onDismiss()
                    onOpenWatchlist()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4))
            ) {
                Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Manage Surveillance Watchlist")
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = Color.LightGray)
            Spacer(modifier = Modifier.height(24.dp))

            // Tor Proxy Routing Toggle
            Text("Network Privacy & Routing", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.DarkGray)
            Spacer(modifier = Modifier.height(8.dp))
            ProxySettingsToggle()

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = Color.LightGray)
            Spacer(modifier = Modifier.height(24.dp))

            // SerpApi Key Section
            Text("SerpApi Google Lens Key", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.DarkGray)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKeyText,
                onValueChange = { apiKeyText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    SerpApiKeyManager.saveApiKey(context, apiKeyText)
                    Toast.makeText(context, "SerpApi Key Saved", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.align(Alignment.End),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Save Key")
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = Color.LightGray)
            Spacer(modifier = Modifier.height(24.dp))

            // Engine Toggles Section
            Text("Visual Search Engines", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.DarkGray)
            Spacer(modifier = Modifier.height(8.dp))
            engines.forEach { engine ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(engine, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.Black)
                    Switch(
                        checked = engineStates[engine] ?: true,
                        onCheckedChange = { enabled ->
                            engineStates[engine] = enabled
                            sharedPrefs.edit().putBoolean("engine_enabled_$engine", enabled).apply()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = Color.LightGray)
            Spacer(modifier = Modifier.height(24.dp))

            // Cache Management Section
            Text("Zero-Dependency JSON Cache", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.DarkGray)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Cached Probes: $cacheFilesCount files (24h TTL)", fontSize = 13.sp, color = Color.Gray)
                OutlinedButton(
                    onClick = {
                        if (cacheDir.exists()) {
                            cacheDir.deleteRecursively()
                            Toast.makeText(context, "Cache Cleared ($cacheFilesCount files removed)", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Cache is already empty", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD32F2F))
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear Cache")
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
