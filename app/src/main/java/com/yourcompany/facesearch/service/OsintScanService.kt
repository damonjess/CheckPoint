package com.yourcompany.facesearch.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.yourcompany.facesearch.R

class OsintScanService : Service() {

    companion object {
        const val CHANNEL_ID = "OsintScanChannel"
        const val NOTIFICATION_ID = 1337
        const val EXTRA_TARGET = "extra_target"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val target = intent?.getStringExtra(EXTRA_TARGET) ?: "Target"
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CheckPoint OSINT Active")
            .setContentText("Scanning target footprint for: $target")
            .setSmallIcon(R.drawable.ic_terminal)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OSINT Scan Background Execution",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
