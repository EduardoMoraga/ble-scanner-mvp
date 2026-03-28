package com.increxa.blescanner

import android.app.Application
import com.increxa.blescanner.data.BleDatabase
import com.increxa.blescanner.data.BleRepository

class BleApplication : Application() {

    val database by lazy { BleDatabase.getInstance(this) }
    val repository by lazy { BleRepository(database.bleDao()) }
}
