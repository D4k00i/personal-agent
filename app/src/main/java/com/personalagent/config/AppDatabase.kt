package com.personalagent.config

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.personalagent.agent.PersonalTask
import com.personalagent.agent.PersonalTaskDao

/**
 * Room database for the Personal Phone Agent.
 *
 * Holds a single table ([PersonalTask]) accessed via [PersonalTaskDao].
 * The singleton instance is lazily initialised and stored in [com.personalagent.WorkerApp.db]
 * for global access from any component.
 *
 * Uses [fallbackToDestructiveMigration] during development to wipe the DB on
 * schema changes rather than requiring manual migration scripts. This will be
 * replaced with proper migrations before production release.
 *
 * @param version Current schema version. Increment when modifying entity classes.
 */
@Database(entities = [PersonalTask::class], version = 2)
abstract class AppDatabase : RoomDatabase() {

    /**
     * Provides the singleton DAO for task operations.
     */
    abstract fun personalTaskDao(): PersonalTaskDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Returns the thread-safe singleton database instance.
         *
         * The database file is named `personal_agent.db` and stored in the
         * app's default database directory.
         *
         * @param context Application or activity context.
         * @return The singleton [AppDatabase] instance.
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "personal_agent.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
