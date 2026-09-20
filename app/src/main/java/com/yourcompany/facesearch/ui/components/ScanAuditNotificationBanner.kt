package com.yourcompany.facesearch.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourcompany.facesearch.domain.DifferentialAuditor

@Composable
fun ScanAuditNotificationBanner(
    auditReport: DifferentialAuditor.AuditReport?,
    modifier: Modifier = Modifier
) {
    val hasNewFindings = auditReport?.newlyDiscovered?.isNotEmpty() == true

    AnimatedVisibility(
        visible = hasNewFindings,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier
    ) {
        val newCount = auditReport?.newlyDiscovered?.size ?: 0
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .background(Color(0xFF0A1F0A), shape = RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF00FF66), shape = RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "⚡ INTELLIGENCE DELTA DETECTED",
                        color = Color(0xFF00FF66),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "+$newCount NEW",
                        color = Color(0xFF00E5FF),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = "Discovered $newCount new profile linkage(s) since your last baseline audit.",
                    color = Color(0xFFB0BEC5),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }
    }
}
