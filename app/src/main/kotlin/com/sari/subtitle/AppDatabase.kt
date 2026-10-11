package com.sari.subtitle

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TranslationEntity::class, TranslationMemoryEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun translationDao(): TranslationDao
    abstract fun translationMemoryDao(): TranslationMemoryDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS translation_memory (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceHash TEXT NOT NULL, sourceText TEXT NOT NULL, translatedText TEXT NOT NULL, sourceLanguage TEXT NOT NULL, targetLanguage TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_translation_memory_sourceHash_sourceLanguage_targetLanguage ON translation_memory(sourceHash, sourceLanguage, targetLanguage)")
            }
        }

        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "sari_subtitles.db"
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
