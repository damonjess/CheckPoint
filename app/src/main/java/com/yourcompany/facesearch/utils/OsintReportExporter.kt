package com.yourcompany.facesearch.utils

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.yourcompany.facesearch.data.local.ScanResultEntity
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object OsintReportExporter {

    fun exportToCsv(context: Context, target: String, results: List<ScanResultEntity>): File? {
        return try {
            val fileName = "checkpoint_report_${target}_${System.currentTimeMillis()}.csv"
            val file = File(context.getExternalFilesDir(null), fileName)

            FileWriter(file).use { writer ->
                writer.append("Target,Platform,ProfileUrl,Timestamp\n")
                results.forEach { entity ->
                    val dateFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entity.timestamp))
                    writer.append("\"${entity.queryTarget}\",\"${entity.platform}\",\"${entity.profileUrl}\",\"$dateFormatted\"\n")
                }
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    fun exportToPdf(context: Context, target: String, results: List<ScanResultEntity>): File? {
        return try {
            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas: Canvas = page.canvas

            val paint = Paint().apply {
                color = Color.BLACK
                textSize = 10f
            }

            val titlePaint = Paint().apply {
                color = Color.parseColor("#006400")
                textSize = 16f
                isFakeBoldText = true
            }

            var yPos = 50f
            canvas.drawText("CHECKPOINT OSINT RECONNAISSANCE DOSSIER", 50f, yPos, titlePaint)
            yPos += 30f

            paint.textSize = 11f
            canvas.drawText("Target Handle: $target", 50f, yPos, paint)
            yPos += 18f
            
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            canvas.drawText("Audit Timestamp: $dateStr", 50f, yPos, paint)
            yPos += 18f
            
            canvas.drawText("Total Verified Assets: ${results.size}", 50f, yPos, paint)
            yPos += 30f

            canvas.drawText("Discovered Platform Linkages:", 50f, yPos, titlePaint)
            yPos += 20f

            results.forEachIndexed { index, entity ->
                if (yPos > 780f) return@forEachIndexed
                val lineText = "${index + 1}. [${entity.platform.uppercase()}] ${entity.profileUrl}"
                canvas.drawText(lineText, 50f, yPos, paint)
                yPos += 18f
            }

            pdfDocument.finishPage(page)

            val fileName = "checkpoint_dossier_${target}_${System.currentTimeMillis()}.pdf"
            val file = File(context.getExternalFilesDir(null), fileName)
            pdfDocument.writeTo(file.outputStream())
            pdfDocument.close()
            file
        } catch (e: Exception) {
            null
        }
    }

    fun exportToPdfWithGraphSummary(
        context: Context, 
        target: String, 
        results: List<ScanResultEntity>,
        verifiedCount: Int,
        socialCount: Int,
        leadCount: Int
    ): File? {
        return try {
            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas: Canvas = page.canvas

            val paint = Paint().apply {
                color = Color.BLACK
                textSize = 10f
            }

            val titlePaint = Paint().apply {
                color = Color.parseColor("#006400") // Tactical dark green
                textSize = 16f
                isFakeBoldText = true
            }

            val headerPaint = Paint().apply {
                color = Color.parseColor("#0F172A")
                textSize = 12f
                isFakeBoldText = true
            }

            var yPos = 50f
            canvas.drawText("CHECKPOINT OSINT RECONNAISSANCE DOSSIER", 50f, yPos, titlePaint)
            yPos += 30f

            paint.textSize = 11f
            canvas.drawText("Target Handle / Query: $target", 50f, yPos, paint)
            yPos += 18f
            
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            canvas.drawText("Audit Timestamp: $dateStr", 50f, yPos, paint)
            yPos += 30f

            // --- NETWORK GRAPH TOPOLOGY SUMMARY BOX ---
            canvas.drawText("IDENTITY NETWORK TOPOLOGY SUMMARY", 50f, yPos, headerPaint)
            yPos += 20f

            paint.textSize = 10f
            canvas.drawText("• Total Indexed Assets: ${results.size}", 65f, yPos, paint)
            yPos += 16f
            canvas.drawText("• Verified Biometric Matches: $verifiedCount", 65f, yPos, paint)
            yPos += 16f
            canvas.drawText("• Direct Social Handlers: $socialCount", 65f, yPos, paint)
            yPos += 16f
            canvas.drawText("• Unverified Visual Leads: $leadCount", 65f, yPos, paint)
            yPos += 30f

            // --- PLATFORM LINKAGES ---
            canvas.drawText("Discovered Platform Linkages:", 50f, yPos, headerPaint)
            yPos += 20f

            results.forEachIndexed { index, entity ->
                if (yPos > 780f) return@forEachIndexed
                val lineText = "${index + 1}. [${entity.platform.uppercase()}] ${entity.profileUrl}"
                canvas.drawText(lineText, 50f, yPos, paint)
                yPos += 18f
            }

            pdfDocument.finishPage(page)

            val fileName = "checkpoint_dossier_network_${target}_${System.currentTimeMillis()}.pdf"
            val file = File(context.getExternalFilesDir(null), fileName)
            pdfDocument.writeTo(file.outputStream())
            pdfDocument.close()
            file
        } catch (e: Exception) {
            null
        }
    }

    fun generateExportIntent(context: Context, target: String, format: String, results: List<ScanResultEntity>): Intent? {
        val file = if (format == "pdf") {
            exportToPdf(context, target, results)
        } else {
            exportToCsv(context, target, results)
        }

        return file?.let {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                it
            )
            Intent(Intent.ACTION_SEND).apply {
                type = if (format == "pdf") "application/pdf" else "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    fun generateNetworkReportIntent(context: Context, target: String, results: List<ScanResultEntity>, verifiedCount: Int, socialCount: Int, leadCount: Int): Intent? {
        val file = exportToPdfWithGraphSummary(
            context = context,
            target = target,
            results = results,
            verifiedCount = verifiedCount,
            socialCount = socialCount,
            leadCount = leadCount
        )

        return file?.let {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                it
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
