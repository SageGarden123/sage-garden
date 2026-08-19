package com.example.dansgardenmapper

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PlantEntity::class, WateringEvent::class, IrrigationPathEntity::class, GrowthPhotoEntity::class], version = 12, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun plantDao(): PlantDao
    abstract fun wateringEventDao(): WateringEventDao
    abstract fun irrigationPathDao(): IrrigationPathDao
    abstract fun growthPhotoDao(): GrowthPhotoDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "garden_mapper.db"
                )
                    .addMigrations(MIGRATION_10_11, MIGRATION_11_12)
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

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `growth_photos` (
                `id` TEXT NOT NULL,
                `plantId` TEXT NOT NULL,
                `uri` TEXT NOT NULL,
                `takenAt` INTEGER NOT NULL,
                `label` TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_growth_photos_plantId` ON `growth_photos` (`plantId`)")
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE plants ADD COLUMN isIndoor INTEGER NOT NULL DEFAULT 0")
    }
}