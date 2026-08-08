package com.yourcompany.facesearch.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PhotoCaptureActions(
    hasPhoto: Boolean,
    isLoading: Boolean,
    onCapturePhotoClick: () -> Unit,
    onSelectGalleryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onCapturePhotoClick,
            modifier = Modifier.weight(1f),
            enabled = !isLoading
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = "Take Photo")
            Spacer(modifier = Modifier.width(4.dp))
            Text(if (!hasPhoto) "Camera" else "New", maxLines = 1)
        }

        OutlinedButton(
            onClick = onSelectGalleryClick,
            modifier = Modifier.weight(1f),
            enabled = !isLoading
        ) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = "Pick from Gallery")
            Spacer(modifier = Modifier.width(4.dp))
            Text("Gallery", maxLines = 1)
        }
    }
}
