package com.example.dansgardenmapper

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [PlantEntity::class, WateringEvent::class, IrrigationPathEntity::class], version = 9, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun plantDao(): PlantDao
    abstract fun wateringEventDao(): WateringEventDao
    abstract fun irrigationPathDao(): IrrigationPathDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "garden_mapper.db"
                )
                    .fallbackToDestructiveMigration(true)
                    .build().also { INSTANCE = it }
            }
        }
    }
}

class Converters {
    @androidx.room.TypeConverter
    fun fromPhotoList(list: List<String>): String = list.joinToString("\u001F")

    @androidx.room.TypeConverter
    fun toPhotoList(data: String): List<String> =
        if (data.isBlank()) emptyList() else data.split("\u001F")
}