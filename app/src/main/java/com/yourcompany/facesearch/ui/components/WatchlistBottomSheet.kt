package com.yourcompany.facesearch.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourcompany.facesearch.data.WatchlistDatabase
import com.yourcompany.facesearch.data.WatchlistTarget
import com.yourcompany.facesearch.worker.WatchlistScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistBottomSheet(
    capturedBitmap: Bitmap? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { WatchlistDatabase.getInstance(context).watchlistDao() }
    
    var targets by remember { mutableStateOf<List<WatchlistTarget>>(emptyList()) }
    var newTargetName by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            targets = dao.getAllTargets()
        }
    }

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
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = Color(0xFF6750A4), modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Automated Watchlist", fontWeight = FontWeight.Black, fontSize = 20.sp, color = Color.Black)
                }
                IconButton(onClick = {
                    WatchlistScheduler.triggerImmediateCheck(context)
                    Toast.makeText(context, "Triggered immediate surveillance crawl", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Run Now", tint = Color(0xFF6750A4))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4))
            ) {
                Text("+ Add Current Target to Watchlist", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color.LightGray)
            Spacer(modifier = Modifier.height(16.dp))

            if (targets.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No watchlisted targets active.\nAdd targets to monitor footprint expansions.", color = Color.Gray, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(targets) { target ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F7)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(target.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.Black)
                                    IconButton(onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            dao.deleteTarget(target)
                                            val file = File(target.probeImagePath)
                                            if (file.exists()) file.delete()
                                            targets = dao.getAllTargets()
                                        }
                                        Toast.makeText(context, "Removed ${target.name}", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFD32F2F))
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                val dateStr = if (target.lastCheckedTimestamp > 0) {
                                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(target.lastCheckedTimestamp))
                                } else {
                                    "Never"
                                }
                                Text("Last Checked: $dateStr", fontSize = 12.sp, color = Color.Gray)
                                Text("Cached Hits: ${target.lastResultCount}", fontSize = 12.sp, color = Color.Gray)
                                Text("Polling Interval: Every ${target.pollingIntervalHours}h", fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }

        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("Add Target to Watchlist") },
                text = {
                    Column {
                        Text("Monitor this face for digital footprint expansions every 24 hours.", fontSize = 13.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = newTargetName,
                            onValueChange = { newTargetName = it },
                            label = { Text("Target Name / Alias") },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )
                        if (capturedBitmap == null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Warning: No captured photo available in current session.", color = Color.Red, fontSize = 12.sp)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newTargetName.isBlank()) {
                                Toast.makeText(context, "Please enter a target name", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (capturedBitmap == null) {
                                Toast.makeText(context, "No photo captured to watch", Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            scope.launch(Dispatchers.IO) {
                                try {
                                    val dir = File(context.filesDir, "watchlist_probes")
                                    if (!dir.exists()) dir.mkdirs()
                                    val imageFile = File(dir, "target_${System.currentTimeMillis()}.jpg")
                                    imageFile.outputStream().use { out ->
                                        capturedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                                    }

                                    val bytes = imageFile.readBytes()
                                    val probeHash = MessageDigest.getInstance("SHA-256")
                                        .digest(bytes)
                                        .joinToString("") { "%02x".format(it) }

                                    val newTarget = WatchlistTarget(
                                        name = newTargetName.trim(),
                                        probeImagePath = imageFile.absolutePath,
                                        probeHash = probeHash,
                                        pollingIntervalHours = 24L
                                    )
                                    dao.insertTarget(newTarget)
                                    targets = dao.getAllTargets()

                                    // Schedule periodic work
                                    WatchlistScheduler.schedulePeriodicCheck(context)

                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Added ${newTarget.name} to Watchlist", Toast.LENGTH_SHORT).show()
                                        showAddDialog = false
                                        newTargetName = ""
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Error adding target: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Add")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
