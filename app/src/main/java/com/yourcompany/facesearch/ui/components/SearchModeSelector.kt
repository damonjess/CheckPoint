package com.yourcompany.facesearch.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourcompany.facesearch.ui.Amber
import com.yourcompany.facesearch.ui.SearchMode

@Composable
fun SearchModeSelector(
    searchMode: SearchMode,
    debugMode: Boolean,
    isLoading: Boolean,
    onSearchModeChange: (SearchMode) -> Unit,
    onDebugModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "SEARCH ENGINE PROFILE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("DEBUG", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Amber)
                Switch(
                    checked = debugMode,
                    onCheckedChange = onDebugModeChange,
                    modifier = Modifier.scale(0.8f), // Slightly larger than original 0.6f for accessibility
                    thumbContent = if (debugMode) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize),
                            )
                        }
                    } else null
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SearchMode.entries.forEach { mode ->
                ModeChip(
                    label = mode.getLabel(),
                    icon = mode.getIcon(),
                    selected = searchMode == mode,
                    onClick = { onSearchModeChange(mode) },
                    enabled = !isLoading
                )
            }
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Amber.copy(alpha = 0.2f),
            selectedLabelColor = Amber,
            selectedLeadingIconColor = Amber
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            selectedBorderColor = Amber,
            selectedBorderWidth = 2.dp
        )
    )
}

private fun SearchMode.getLabel(): String = when (this) {
    SearchMode.PRECISION -> "Precision"
    SearchMode.BYPASS -> "Bypass"
    SearchMode.HYPER -> "Hyper"
    SearchMode.SOCIAL -> "Social"
    SearchMode.SOCIAL_OPTIMIZED -> "Social Opt"
    SearchMode.AGGRESSIVE -> "🔥 Aggressive"
    SearchMode.RAW -> "Raw"
    SearchMode.FREE -> "Free"
    SearchMode.DEEP_CRAWL -> "🕸️ Deep Crawl"
}

private fun SearchMode.getIcon(): ImageVector = when (this) {
    SearchMode.PRECISION -> Icons.Default.FilterCenterFocus
    SearchMode.BYPASS -> Icons.Default.Security
    SearchMode.HYPER -> Icons.Default.Bolt
    SearchMode.SOCIAL -> Icons.Default.People
    SearchMode.SOCIAL_OPTIMIZED -> Icons.Default.Person
    SearchMode.AGGRESSIVE -> Icons.Default.Bolt
    SearchMode.RAW -> Icons.Default.Image
    SearchMode.FREE -> Icons.Default.FilterCenterFocus
    SearchMode.DEEP_CRAWL -> Icons.Default.Public
}
