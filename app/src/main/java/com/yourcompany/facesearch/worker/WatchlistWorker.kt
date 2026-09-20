package com.yourcompany.facesearch.worker

import android.R
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yourcompany.facesearch.MainActivity
import com.yourcompany.facesearch.data.WatchlistDatabase
import com.yourcompany.facesearch.data.cache.SearchCacheManager
import com.yourcompany.facesearch.network.FaceSearchRepository
import java.io.File

class WatchlistWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "WatchlistWorker"
        private const val CHANNEL_ID = "watchlist_surveillance_channel"
        const val WORK_NAME = "com.yourcompany.facesearch.watchlist.WORK"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "WatchlistWorker starting background surveillance crawl...")
        val dao = WatchlistDatabase.getInstance(context).watchlistDao()
        val targets = dao.getAllTargets()

        if (targets.isEmpty()) {
            Log.d(TAG, "No watchlist targets found.")
            return Result.success()
        }

        val repository = FaceSearchRepository(context)

        for (target in targets) {
            try {
                val imageFile = File(target.probeImagePath)
                if (!imageFile.exists()) {
                    Log.w(TAG, "Probe image file missing for target: ${target.name}")
                    continue
                }

                val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                if (bitmap == null) {
                    Log.w(TAG, "Failed to decode bitmap for target: ${target.name}")
                    continue
                }

                // Get old cached hits before crawl
                val oldHits = SearchCacheManager.getCachedResults(context, target.probeHash) ?: emptyList()
                val oldLinks = oldHits.mapNotNull { it.link }.toSet()

                Log.d(TAG, "Executing silent crawl for target: ${target.name}")
                val newHits = repository.performFaceSearch(
                    bitmap = bitmap,
                    keywordHint = target.name,
                    searchMode = "PRECISION"
                )

                // Save new hits to cache
                SearchCacheManager.saveResults(context, target.probeHash, newHits)

                // Intelligence Diffing Engine: check for footprint expansion
                val newUniqueLinks = newHits.mapNotNull { it.link }.toSet()
                val brandNewLinks = newUniqueLinks.filter { it !in oldLinks }
                val footprintExpanded = brandNewLinks.isNotEmpty() || (newHits.size > oldHits.size && oldHits.isNotEmpty())

                Log.d(TAG, "Target ${target.name}: oldHits=${oldHits.size}, newHits=${newHits.size}, expanded=$footprintExpanded")

                dao.updateCheckStats(target.id, System.currentTimeMillis(), newHits.size)

                if (footprintExpanded || (target.lastResultCount == 0 && newHits.isNotEmpty())) {
                    dispatchAlert(target.name, target.id, newHits.size - oldHits.size)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error crawling target ${target.name}", e)
            }
        }

        return Result.success()
    }

    @SuppressLint("MissingPermission")
    private fun dispatchAlert(targetName: String, targetId: Long, newHitsCount: Int) {
        val notificationManager = NotificationManagerCompat.from(context)

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Watchlist Surveillance Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when a monitored target's digital footprint expands"
        }
        notificationManager.createNotificationChannel(channel)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("watchlist_target_id", targetId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            targetId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_menu_search)
            .setContentTitle("New digital footprint detected")
            .setContentText("Target: $targetName${if (newHitsCount > 0) " (+$newHitsCount new leads)" else ""}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Surveillance update: New digital footprint detected for $targetName. Tap to open updated intelligence dossier."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            notificationManager.notify(targetId.toInt(), notification)
            Log.d(TAG, "Dispatched watchlist alert notification for $targetName")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException posting notification (POST_NOTIFICATIONS permission needed)", e)
        }
    }
}
