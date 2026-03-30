package com.increxa.blescanner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [BleDevice::class, BleScanResult::class, BleSession::class],
    version = 4,
    exportSchema = false
)
abstract class BleDatabase : RoomDatabase() {

    abstract fun bleDao(): BleDao

    companion object {
        @Volatile
        private var INSTANCE: BleDatabase? = null

        fun getInstance(context: Context): BleDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BleDatabase::class.java,
                    "ble_scanner.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
