package com.yourcompany.facesearch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun TerminalConsoleScreen(
    onExecuteCommand: suspend (String) -> String,
    onClose: () -> Unit
) {
    var commandInput by remember { mutableStateOf("") }
    var isExecuting by remember { mutableStateOf(false) }
    val consoleLogs = remember { mutableStateListOf<TerminalLogEntry>(
        TerminalLogEntry("CheckPoint OSINT Terminal v1.0. Connected.", LogType.SYSTEM),
        TerminalLogEntry("Type 'help' for commands, or enter a username directly (e.g. 'john_doe' or '@john_doe').", LogType.INFO),
        TerminalLogEntry("Examples:\n  • john_doe\n  • sherlock john_doe\n  • blackbird -u john_doe", LogType.INFO)
    )}
    
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    fun submitCommand() {
        if (commandInput.isNotBlank() && !isExecuting) {
            val cmd = commandInput.trim()
            consoleLogs.add(TerminalLogEntry("root@checkpoint:~# $cmd", LogType.COMMAND))
            val statusLog = TerminalLogEntry("[*] Executing OSINT search for '$cmd'...", LogType.INFO)
            consoleLogs.add(statusLog)
            commandInput = ""
            isExecuting = true
            coroutineScope.launch {
                listState.animateScrollToItem(consoleLogs.size)
                val result = onExecuteCommand(cmd)
                consoleLogs.remove(statusLog)
                consoleLogs.add(TerminalLogEntry(result, LogType.SUCCESS))
                isExecuting = false
                listState.animateScrollToItem(consoleLogs.size)
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0A0F0D) // Deep hacker terminal dark
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(12.dp)
        ) {
            // Terminal Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "root@checkpoint:~#",
                    color = Color(0xFF00FF66),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
                TextButton(onClick = onClose) {
                    Text("Exit Terminal", color = Color(0xFFFF5555))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Scrollable Log Output Window
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF050806), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(consoleLogs) { log ->
                        val textColor = when (log.type) {
                            LogType.SYSTEM -> Color(0xFF00E5FF)
                            LogType.INFO -> Color(0xFFCCCCCC)
                            LogType.COMMAND -> Color(0xFFFFFF00)
                            LogType.SUCCESS -> Color(0xFF00FF66)
                            LogType.ERROR -> Color(0xFFFF5555)
                        }
                        
                        val annotatedText = parseTerminalLinks(log.text)
                        
                        ClickableText(
                            text = annotatedText,
                            style = TextStyle(
                                color = textColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            ),
                            onClick = { offset ->
                                annotatedText.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                    .firstOrNull()?.let { annotation ->
                                        try {
                                            uriHandler.openUri(annotation.item)
                                        } catch (_: Exception) {}
                                    }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Command Input Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = commandInput,
                    onValueChange = { commandInput = it },
                    placeholder = { Text("Enter command or username (e.g. john_doe)", color = Color.DarkGray) },
                    singleLine = true,
                    enabled = !isExecuting,
                    textStyle = LocalTextStyle.current.copy(
                        color = Color(0xFF00FF66),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00FF66),
                        unfocusedBorderColor = Color.DarkGray,
                        cursorColor = Color(0xFF00FF66)
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = { submitCommand() }
                    ),
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = { submitCommand() },
                    enabled = !isExecuting && commandInput.isNotBlank(),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color(0xFF00FF66),
                        disabledContainerColor = Color.DarkGray
                    )
                ) {
                    if (isExecuting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Send, contentDescription = "Execute", tint = Color.Black)
                    }
                }
            }
        }
    }
}

fun parseTerminalLinks(text: String): AnnotatedString {
    // Regex pattern to match standard http/https web links
    val urlPattern = "https?://[\\w.-]+(?:\\.[\\w.-]+)+[/#?]?.*?(?=\\s|$)".toRegex()
    
    return buildAnnotatedString {
        var lastIndex = 0
        urlPattern.findAll(text).forEach { match ->
            val startIndex = match.range.first
            val endIndex = match.range.last + 1
            
            // Append normal text leading up to the link
            append(text.substring(lastIndex, startIndex))
            
            // Add clickable annotation and styling for the link
            pushStringAnnotation(tag = "URL", annotation = match.value)
            pushStyle(
                SpanStyle(
                    color = Color(0xFF00E5FF), // Bright cyan for links
                    textDecoration = TextDecoration.Underline
                )
            )
            append(match.value)
            pop() // Pop style
            pop() // Pop annotation
            
            lastIndex = endIndex
        }
        // Append any remaining text after the last link
        append(text.substring(lastIndex))
    }
}

data class TerminalLogEntry(val text: String, val type: LogType)
enum class LogType { SYSTEM, INFO, COMMAND, SUCCESS, ERROR }
