package com.yourcompany.facesearch.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FaceSearchConfirmScreen(
    croppedBitmap: Bitmap,
    nameHint: String?,
    searchMode: SearchMode = SearchMode.FREE,
    onConfirm: () -> Unit,
    onTinEyeExactSearch: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Image(
            bitmap = croppedBitmap.asImageBitmap(),
            contentDescription = "Cropped Face",
            modifier = Modifier
                .size(240.dp)
                .clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Target: ${if (nameHint.isNullOrBlank()) "Anonymous" else nameHint}",
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
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
