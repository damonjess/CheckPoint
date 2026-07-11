package com.yourcompany.facesearch.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Result states for the check-in screen. Mirrors your FaceSearchOutcome
 * but scoped to a single expected employee match rather than a list.
 */
sealed class CheckInUiState {
    object Idle : CheckInUiState()
    object Loading : CheckInUiState()
    data class Success(val employee: EmployeeMatch) : CheckInUiState()
    object NoMatch : CheckInUiState()
    data class Error(val message: String) : CheckInUiState()
}

data class EmployeeMatch(
    val name: String,
    val department: String,
    val directoryUrl: String,
    val photoUrl: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInScreen(
    capturedBitmap: Bitmap?,
    uiState: CheckInUiState,
    onCapturePhotoClick: () -> Unit,
    onOpenDirectoryClick: (String) -> Unit,
    onRetryClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Front Desk Check-In") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Spacer(modifier = Modifier.height(16.dp))

            // Preview of captured photo, or a placeholder circle
            PhotoPreview(bitmap = capturedBitmap)

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onCapturePhotoClick,
                modifier = Modifier.fillMaxWidth(0.7f),
                enabled = uiState !is CheckInUiState.Loading
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (capturedBitmap == null) "Take Photo" else "Retake Photo")
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Result area — swaps based on state
            when (uiState) {
                is CheckInUiState.Idle -> {
                    Text(
                        text = "Take a photo to check in",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is CheckInUiState.Loading -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Looking up employee...")
                }

                is CheckInUiState.Success -> {
                    EmployeeResultCard(
                        employee = uiState.employee,
                        onOpenDirectoryClick = onOpenDirectoryClick
                    )
                }

                is CheckInUiState.NoMatch -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "No employee match found",
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "Please try again or check in with reception staff.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = onRetryClick) {
                                Text("Try Again")
                            }
                        }
                    }
                }

                is CheckInUiState.Error -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                uiState.message,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = onRetryClick) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoPreview(bitmap: Bitmap?) {
    Box(
        modifier = Modifier
            .size(180.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Captured photo",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = "No photo taken",
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmployeeResultCard(
    employee: EmployeeMatch,
    onOpenDirectoryClick: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Welcome, ${employee.name}",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = employee.department,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(onClick = { onOpenDirectoryClick(employee.directoryUrl) }) {
                Text("View Directory Profile")
            }
        }
    }
}
