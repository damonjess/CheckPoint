package com.yourcompany.facesearch.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yourcompany.facesearch.data.EnrolledFace
import com.yourcompany.facesearch.data.EnrolledFaceStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnrollScreen(
    viewModel: EnrollViewModel,
    onBack: () -> Unit,
    onCapturePhotoClick: () -> Unit
) {
    val context = LocalContext.current
    var people by remember { mutableStateOf(EnrolledFaceStore.getAll(context)) }

    fun refresh() { people = EnrolledFaceStore.getAll(context) }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Enrolled People") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            viewModel.pendingBitmap?.let { bitmap ->
                PendingEnrollmentCard(
                    bitmap = bitmap,
                    isSaving = viewModel.isSaving,
                    errorMessage = viewModel.errorMessage,
                    onSave = { name -> viewModel.saveEnrollment(context, name) { refresh() } },
                    onDiscard = { viewModel.discardPending() }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Button(onClick = onCapturePhotoClick, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Person")
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (people.isEmpty()) {
                Text(
                    text = "No one enrolled yet. Add a person so search has someone to match against.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn {
                    items(people, key = { it.id }) { person ->
                        EnrolledPersonRow(
                            person = person,
                            onDelete = {
                                EnrolledFaceStore.removeFace(context, person.id)
                                refresh()
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingEnrollmentCard(
    bitmap: Bitmap,
    isSaving: Boolean,
    errorMessage: String?,
    onSave: (String) -> Unit,
    onDiscard: () -> Unit
) {
    var name by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            errorMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row {
                TextButton(onClick = onDiscard, enabled = !isSaving) { Text("Discard") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { onSave(name.trim()) }, enabled = !isSaving && name.isNotBlank()) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun EnrolledPersonRow(person: EnrolledFace, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Person, contentDescription = null)
            Spacer(modifier = Modifier.width(12.dp))
            Text(person.name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Remove")
            }
        }
    }
}