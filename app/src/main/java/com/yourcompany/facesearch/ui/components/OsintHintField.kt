package com.yourcompany.facesearch.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yourcompany.facesearch.ui.Amber

import androidx.compose.ui.graphics.Color

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
        label = { Text("OSINT TARGET HINT (Name, City, ID)", color = Color.DarkGray) },
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text("e.g. John Doe Facebook", color = Color.Gray) },
        singleLine = true,
        enabled = isEnabled,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.Black,
            unfocusedTextColor = Color.Black,
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            focusedBorderColor = Amber,
            focusedLabelColor = Color.Black,
            unfocusedLabelColor = Color.DarkGray
        )
    )
}



