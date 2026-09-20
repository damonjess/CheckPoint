package com.yourcompany.facesearch.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourcompany.facesearch.data.model.OsintPlugin

@Composable
fun PluginLoaderScreen(
    plugins: List<OsintPlugin>,
    onRunPlugin: (OsintPlugin, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedPlugin by remember { mutableStateOf<OsintPlugin?>(null) }
    var targetInput by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "LOADED OSINT PLUGINS",
            fontWeight = FontWeight.Black,
            fontSize = 16.sp,
            color = Color(0xFF00FF66),
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(plugins) { plugin ->
                Card(
                    onClick = { selectedPlugin = plugin },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(plugin.name, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.Monospace)
                            Text("v${plugin.version}", fontSize = 10.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(plugin.description, fontSize = 12.sp, color = Color(0xFF94A3B8))
                    }
                }
            }
        }

        selectedPlugin?.let { plugin ->
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = targetInput,
                onValueChange = { targetInput = it },
                placeholder = { Text(plugin.argPlaceholder) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    onRunPlugin(plugin, targetInput)
                    selectedPlugin = null
                    targetInput = ""
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006400))
            ) {
                Text("Execute Plugin Module", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }
}
