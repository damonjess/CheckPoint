package com.yourcompany.facesearch.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.yourcompany.facesearch.data.local.ScanResultDao
import com.yourcompany.facesearch.data.local.ScanResultEntity
import com.yourcompany.facesearch.security.SqlCipherKeyManager
import net.sqlcipher.database.SupportFactory

@Database(entities = [WatchlistTarget::class, ScanResultEntity::class], version = 2, exportSchema = false)
abstract class WatchlistDatabase : RoomDatabase() {
    abstract fun watchlistDao(): WatchlistDao
    abstract fun scanResultDao(): ScanResultDao

    companion object {
        @Volatile
        private var INSTANCE: WatchlistDatabase? = null

        fun getInstance(context: Context): WatchlistDatabase {
            return INSTANCE ?: synchronized(this) {
                // Initialize SQLCipher native library
                net.sqlcipher.database.SQLiteDatabase.loadLibs(context)

                // Retrieve hardware-secured passphrase
                val keyManager = SqlCipherKeyManager(context)
                val passphraseBytes = keyManager.getOrCreatePassphrase()
                val factory = SupportFactory(passphraseBytes)

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WatchlistDatabase::class.java,
                    "checkpoint_secure_vault.db"
                )
                    .openHelperFactory(factory)
                    .fallbackToDestructiveMigration()
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}
