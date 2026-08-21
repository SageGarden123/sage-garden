package com.example.sagegarden

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PlantEntity::class, WateringEvent::class, IrrigationPathEntity::class, GrowthPhotoEntity::class, CareLogEntity::class, SunZoneEntity::class, WaterFlowRateEntity::class, SageChatMessageEntity::class], version = 19, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun plantDao(): PlantDao
    abstract fun wateringEventDao(): WateringEventDao
    abstract fun irrigationPathDao(): IrrigationPathDao
    abstract fun growthPhotoDao(): GrowthPhotoDao
    abstract fun careLogDao(): CareLogDao
    abstract fun sunZoneDao(): SunZoneDao
    abstract fun waterFlowRateDao(): WaterFlowRateDao
    abstract fun sageChatDao(): SageChatDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "garden_mapper.db"
                )
                    .addMigrations(MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19)
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

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE plants ADD COLUMN summerWateringFrequencyDays INTEGER")
        db.execSQL("ALTER TABLE plants ADD COLUMN winterWateringFrequencyDays INTEGER")
    }
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE plants ADD COLUMN lastFertilisedDate INTEGER")
        db.execSQL("ALTER TABLE plants ADD COLUMN fertiliseFrequencyDays INTEGER")
        db.execSQL("ALTER TABLE plants ADD COLUMN lastPrunedDate INTEGER")
        db.execSQL("ALTER TABLE plants ADD COLUMN pruneFrequencyDays INTEGER")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `care_log` (
                `id` TEXT NOT NULL, `plantId` TEXT NOT NULL, `type` TEXT NOT NULL,
                `date` INTEGER NOT NULL, `notes` TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_care_log_plantId` ON `care_log` (`plantId`)")
    }
}

val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sun_zones` (
                `id` TEXT NOT NULL, `category` TEXT NOT NULL, `pointsJson` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `water_flow_rates` (
                `zone` TEXT NOT NULL, `outlet` TEXT NOT NULL, `litersPerMinute` REAL NOT NULL,
                PRIMARY KEY(`zone`, `outlet`)
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE plants ADD COLUMN lastFedDate INTEGER")
        db.execSQL("ALTER TABLE plants ADD COLUMN feedFrequencyDays INTEGER")
    }
}

val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sage_chat_message` (
                `id` TEXT NOT NULL, `role` TEXT NOT NULL, `text` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Existing zones were all drawn on the uploaded custom map before "real map" zones existed.
        db.execSQL("ALTER TABLE sun_zones ADD COLUMN mapType TEXT NOT NULL DEFAULT 'custom'")
    }
}