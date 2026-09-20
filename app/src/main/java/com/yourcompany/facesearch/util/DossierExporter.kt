package com.yourcompany.facesearch.util

import android.content.Context
import android.content.Intent
import com.yourcompany.facesearch.ui.models.WebMatchDisplay

object DossierExporter {
    fun exportToCsv(context: Context, matches: List<WebMatchDisplay>) {
        val csvBuilder = StringBuilder()
        csvBuilder.append("Title,Source,Score,ProfileUrl\n")
        matches.forEach { match ->
            val title = "\"${match.displayName.replace("\"", "\"\"")}\""
            val source = "\"${match.source.replace("\"", "\"\"")}\""
            val score = match.score
            val url = "\"${match.profileUrl.replace("\"", "\"\"")}\""
            csvBuilder.append("$title,$source,$score,$url\n")
        }

        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, csvBuilder.toString())
            type = "text/csv"
        }
        context.startActivity(Intent.createChooser(sendIntent, "Export Dossier (CSV)"))
    }

    fun exportToJson(context: Context, matches: List<WebMatchDisplay>) {
        val jsonBuilder = StringBuilder()
        jsonBuilder.append("[\n")
        matches.forEachIndexed { index, match ->
            jsonBuilder.append("  {\n")
            jsonBuilder.append("    \"title\": \"${match.displayName.replace("\"", "\\\"")}\",\n")
            jsonBuilder.append("    \"source\": \"${match.source.replace("\"", "\\\"")}\",\n")
            jsonBuilder.append("    \"score\": ${match.score},\n")
            jsonBuilder.append("    \"profileUrl\": \"${match.profileUrl.replace("\"", "\\\"")}\"\n")
            jsonBuilder.append("  }${if (index < matches.size - 1) "," else ""}\n")
        }
        jsonBuilder.append("]\n")

        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, jsonBuilder.toString())
            type = "application/json"
        }
        context.startActivity(Intent.createChooser(sendIntent, "Export Dossier (JSON)"))
    }
}
