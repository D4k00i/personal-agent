package com.personalagent

import android.app.Application
import com.personalagent.config.AppDatabase
import timber.log.Timber

class WorkerApp : Application() {

    companion object {
        lateinit var db: AppDatabase
    }

    override fun onCreate() {
        super.onCreate()

        // Plant Timber for structured logging.
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        db = AppDatabase.getInstance(this)
        Timber.i("Database initialised")

        Timber.i("WorkerApp initialised — version=%s", BuildConfig.VERSION_NAME)
    }
}
