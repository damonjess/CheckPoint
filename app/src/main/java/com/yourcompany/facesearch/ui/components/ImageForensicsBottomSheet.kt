package com.yourcompany.facesearch.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourcompany.facesearch.util.ExifForensicsData
import com.yourcompany.facesearch.util.ImageForensicsExtractor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageForensicsBottomSheet(
    forensicsData: ExifForensicsData? = null,
    uri: Uri? = null,
    bitmap: Bitmap? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val resolvedData = remember(forensicsData, uri, bitmap) {
        when {
            forensicsData != null -> forensicsData
            uri != null -> ImageForensicsExtractor.extract(context, uri)
            bitmap != null -> {
                try {
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    ImageForensicsExtractor.extract(ByteArrayInputStream(stream.toByteArray()))
                } catch (_: Exception) {
                    ExifForensicsData()
                }
            }
            else -> ExifForensicsData()
        }
    }
    val data = resolvedData

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color(0xFF6750A4), modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("Advanced Image Forensics", fontWeight = FontWeight.Black, fontSize = 20.sp, color = Color.Black)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("EXIF Metadata & Device Fingerprints extracted from probe", fontSize = 13.sp, color = Color.Gray)

            Spacer(modifier = Modifier.height(24.dp))

            ForensicsItem(title = "Camera Make / Model", value = "${data.cameraMake} ${data.cameraModel}")
            ForensicsItem(title = "Original Timestamp", value = data.dateTimeOriginal, icon = Icons.Default.Schedule)
            ForensicsItem(title = "Software / Processing", value = data.software)
            
            val gpsStr = if (data.latitude != null && data.longitude != null) {
                "Lat: ${data.latitude}, Lon: ${data.longitude}${if (data.altitude != null) " (Alt: ${data.altitude}m)" else ""}"
            } else {
                "Not Present (No GPS EXIF)"
            }
            
            ForensicsItem(
                title = "GPS Coordinates",
                value = gpsStr,
                icon = Icons.Default.LocationOn,
                onClick = if (data.latitude != null && data.longitude != null) {
                    {
                        val gmmIntentUri = Uri.parse("geo:${data.latitude},${data.longitude}?q=${data.latitude},${data.longitude}(Captured Image Location)")
                        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                        try {
                            context.startActivity(mapIntent)
                        } catch (_: Exception) {}
                    }
                } else null
            )

            ForensicsItem(title = "Exposure Time", value = data.exposureTime)
            ForensicsItem(title = "Aperture (F-Number)", value = data.fNumber)
            ForensicsItem(title = "ISO Speed", value = data.iso)
            ForensicsItem(title = "Focal Length", value = data.focalLength)
            ForensicsItem(title = "Orientation", value = data.orientation)

            if (data.rawAttributes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Color.LightGray)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Raw EXIF Tags (${data.rawAttributes.size})", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.DarkGray)
                Spacer(modifier = Modifier.height(8.dp))
                data.rawAttributes.forEach { (tag, value) ->
                    Text("$tag: $value", fontSize = 12.sp, color = Color.DarkGray, modifier = Modifier.padding(vertical = 2.dp))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun ForensicsItem(
    title: String,
    value: String,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null
) {
    val modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)
        .let { if (onClick != null) it.then(Modifier.clickable { onClick() }) else it }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F7)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = Color(0xFF6750A4), modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    value,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (onClick != null) Color(0xFF1D4ED8) else Color.Black
                )
            }
            if (onClick != null) {
                Icon(Icons.Default.LocationOn, contentDescription = "Open Map", tint = Color(0xFF1D4ED8), modifier = Modifier.size(16.dp))
            }
        }
    }
}
