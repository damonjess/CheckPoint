package com.yourcompany.facesearch.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yourcompany.facesearch.ui.Amber

@Composable
fun OsintHintField(
    value: String,
    onValueChange: (String) -> Unit,
    isEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("OSINT TARGET HINT (Name, City, ID)") },
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text("e.g. John Doe Facebook") },
        singleLine = true,
        enabled = isEnabled,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Amber,
            focusedLabelColor = Amber
        )
    )
}



