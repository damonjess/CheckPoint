package com.yourcompany.facesearch.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class ConfirmViewMode {
    MASKED_FULL,
    ORIGINAL_FULL,
    FACE_CROP
}

@Composable
fun FaceSearchConfirmScreen(
    croppedBitmap: Bitmap,
    sceneBitmap: Bitmap? = null,
    nameHint: String?,
    searchMode: SearchMode = SearchMode.FREE,
    onConfirm: () -> Unit,
    onTinEyeExactSearch: () -> Unit,
    onCancel: () -> Unit
) {
    var selectedMode by remember { mutableStateOf(ConfirmViewMode.MASKED_FULL) }

    // Pre-calculate translucent masked full scene bitmap
    val maskedSceneBitmap = remember(sceneBitmap, croppedBitmap) {
        val target = sceneBitmap ?: croppedBitmap
        createTranslucentMaskedBitmap(target)
    }

    val displayBitmap = when (selectedMode) {
        ConfirmViewMode.MASKED_FULL -> maskedSceneBitmap ?: croppedBitmap
        ConfirmViewMode.ORIGINAL_FULL -> sceneBitmap ?: croppedBitmap
        ConfirmViewMode.FACE_CROP -> croppedBitmap
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = when (searchMode) {
                SearchMode.AGGRESSIVE -> "Confirm Aggressive Search"
                SearchMode.ADULT -> "Confirm Adult Scan"
                else -> "Confirm Face Search"
            },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = when (searchMode) {
                SearchMode.AGGRESSIVE -> Color.Red
                SearchMode.ADULT -> Color(0xFFE53935)
                else -> MaterialTheme.colorScheme.onSurface
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Mode Selector Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            FilterChip(
                selected = selectedMode == ConfirmViewMode.MASKED_FULL,
                onClick = { selectedMode = ConfirmViewMode.MASKED_FULL },
                label = { Text("Gray Mask", fontSize = 12.sp) }
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = selectedMode == ConfirmViewMode.ORIGINAL_FULL,
                onClick = { selectedMode = ConfirmViewMode.ORIGINAL_FULL },
                label = { Text("Full Photo", fontSize = 12.sp) }
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = selectedMode == ConfirmViewMode.FACE_CROP,
                onClick = { selectedMode = ConfirmViewMode.FACE_CROP },
                label = { Text("Face Crop", fontSize = 12.sp) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Main Image Frame with ContentScale.Fit so full picture is 100% visible
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = displayBitmap.asImageBitmap(),
                contentDescription = "Target face",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                alignment = Alignment.Center
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Target: ${if (nameHint.isNullOrBlank()) "Anonymous" else nameHint}",
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onConfirm,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = when (searchMode) {
                    SearchMode.AGGRESSIVE -> Color.Red
                    SearchMode.ADULT -> Color(0xFFE53935)
                    else -> Color(0xFFFFB000)
                }
            ),
            enabled = true
        ) {
            Text(
                text = when (searchMode) {
                    SearchMode.AGGRESSIVE -> "🔥 LAUNCH AGGRESSIVE SCAN"
                    SearchMode.ADULT -> "🔞 LAUNCH ADULT SCAN"
                    else -> "Launch In-App Search"
                },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        if (searchMode != SearchMode.AGGRESSIVE) {
            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onTinEyeExactSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4)),
                enabled = true
            ) {
                Text("Run TinEye Exact-Image Search", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        TextButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Cancel", fontSize = 16.sp)
        }
    }
}

/**
 * Creates a translucent gray mask overlay on the full picture, keeping the head clear
 * while dimming the background/clothing so the user can inspect what is grayed out.
 */
private fun createTranslucentMaskedBitmap(source: Bitmap): Bitmap {
    val safe = if (source.config == Bitmap.Config.HARDWARE || source.config == null) {
        source.copy(Bitmap.Config.ARGB_8888, true)
    } else source

    val width = safe.width
    val height = safe.height
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)

    // 1. Draw full original picture
    canvas.drawBitmap(safe, 0f, 0f, null)

    // 2. Build facial oval in center/upper portion
    val path = Path()
    val faceOval = RectF(
        width * 0.22f,
        height * 0.08f,
        width * 0.78f,
        height * 0.62f
    )
    path.addOval(faceOval, Path.Direction.CW)

    // 3. Clip OUTSIDE the face oval and draw translucent gray mask
    canvas.save()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        canvas.clipOutPath(path)
    } else {
        @Suppress("DEPRECATION")
        canvas.clipPath(path, Region.Op.DIFFERENCE)
    }
    canvas.drawColor(AndroidColor.argb(160, 60, 60, 60))
    canvas.restore()

    return output
}
