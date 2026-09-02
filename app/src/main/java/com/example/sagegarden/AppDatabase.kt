package com.example.sagegarden

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PlantEntity::class, WateringEvent::class, IrrigationPathEntity::class, GrowthPhotoEntity::class, CareLogEntity::class, SunZoneEntity::class, WaterFlowRateEntity::class, SageChatMessageEntity::class, ExtraPhotoEntity::class, LocationPhotoEntity::class, ManualZoneScheduleEntity::class], version = 28, exportSchema = false)
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
    abstract fun extraPhotoDao(): ExtraPhotoDao
    abstract fun locationPhotoDao(): LocationPhotoDao
    abstract fun manualZoneScheduleDao(): ManualZoneScheduleDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "garden_mapper.db"
                )
                    .addMigrations(MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, migration23To24(context), migration24To25(context), MIGRATION_25_26, migration26To27(context), MIGRATION_27_28)
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

val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Backs the phone/desktop sync feature's last-write-wins merge — existing rows default to
        // 0 (oldest possible), so the very first sync's incoming records always win over them,
        // which is harmless since a freshly-migrated device has nothing to lose data to yet.
        db.execSQL("ALTER TABLE plants ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE care_log ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE plants ADD COLUMN soilPh TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `extra_photos` (
                `id` TEXT NOT NULL,
                `plantId` TEXT NOT NULL,
                `uri` TEXT NOT NULL,
                `label` TEXT NOT NULL DEFAULT '',
                `addedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_extra_photos_plantId` ON `extra_photos` (`plantId`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `location_photos` (
                `id` TEXT NOT NULL,
                `location` TEXT NOT NULL,
                `uri` TEXT NOT NULL,
                `label` TEXT NOT NULL DEFAULT '',
                `takenAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_location_photos_location` ON `location_photos` (`location`)")
    }
}

val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE plants ADD COLUMN category TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * First step of multi-garden sharing's local Room scoping (see GardenMembershipClient.kt) — adds
 * gardenId to the two tables that are actually synced across devices (plants, care_log; see
 * gardenSync.ts, which only ever handles these two collections). Every other table (zones, photos,
 * irrigation paths, chat history) stays device-global for now — none of them were ever part of the
 * phone/desktop sync payload either, so this doesn't reduce anything that worked before.
 * Existing rows are backfilled to this device's own install ID, which is exactly the gardenId
 * `effectiveGardenId()` resolves to until the user explicitly switches to a different garden — so
 * this migration is invisible to anyone not using sharing yet.
 */
fun migration23To24(context: Context) = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val installId = getOrCreateInstallId(context)
        db.execSQL("ALTER TABLE plants ADD COLUMN gardenId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE care_log ADD COLUMN gardenId TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE plants SET gardenId = ?", arrayOf(installId))
        db.execSQL("UPDATE care_log SET gardenId = ?", arrayOf(installId))
    }
}

/**
 * Second step of multi-garden sharing's local Room scoping — extends gardenId to watering history,
 * water flow rates (cost/usage tracking), the sun map, and custom-map irrigation drawings, all of
 * which stayed device-global in migration23To24 because they were never part of the cloud sync
 * payload. That's still true here (none of this syncs to the server) — this migration only fixes
 * these tables being visible/editable regardless of which garden is active locally, addressed after
 * a user with two shared gardens found the first garden's watering history and sun map bleeding
 * into the second garden's view. Existing rows are backfilled to this device's own install ID, same
 * as migration23To24, so a device not using sharing sees no change.
 */
fun migration24To25(context: Context) = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val installId = getOrCreateInstallId(context)

        db.execSQL("ALTER TABLE watering_events ADD COLUMN gardenId TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE watering_events SET gardenId = ?", arrayOf(installId))

        db.execSQL("ALTER TABLE sun_zones ADD COLUMN gardenId TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE sun_zones SET gardenId = ?", arrayOf(installId))

        db.execSQL("ALTER TABLE irrigation_paths ADD COLUMN gardenId TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE irrigation_paths SET gardenId = ?", arrayOf(installId))

        // water_flow_rates' primary key changes from (zone, outlet) to (gardenId, zone, outlet) — a
        // device sharing a second garden could otherwise have that garden's "Front" zone flow-rate
        // upsert silently overwrite its own default garden's "Front" zone row, since REPLACE keys
        // off the primary key alone. SQLite can't alter a primary key in place, so recreate the table.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `water_flow_rates_new` (
                `gardenId` TEXT NOT NULL DEFAULT '', `zone` TEXT NOT NULL, `outlet` TEXT NOT NULL, `litersPerMinute` REAL NOT NULL,
                PRIMARY KEY(`gardenId`, `zone`, `outlet`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "INSERT INTO water_flow_rates_new (gardenId, zone, outlet, litersPerMinute) SELECT ?, zone, outlet, litersPerMinute FROM water_flow_rates",
            arrayOf(installId)
        )
        db.execSQL("DROP TABLE water_flow_rates")
        db.execSQL("ALTER TABLE water_flow_rates_new RENAME TO water_flow_rates")
    }
}

val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE plants ADD COLUMN photoThumbnailBase64 TEXT")
    }
}

/**
 * Extends gardenId scoping (see migration24To25) to extra photos, growth timeline photos, and
 * progress (location) photos — none of these were ever part of the cloud sync payload, so like the
 * earlier per-garden tables this stayed device-global by oversight rather than deliberate choice.
 * Confirmed as a real bug 2026-08-29: a near-empty test garden's "Back up now" reported 5 growth
 * photos and 7 progress photos that didn't belong to it — buildBackupPayload was reading these three
 * tables completely unscoped (getAllOnce(), every garden combined). location_photos is the most
 * pressing of the three since its key is a bare location NAME (e.g. "Back garden"), which two
 * different gardens can legitimately both use — without gardenId that's a guaranteed cross-garden
 * leak, not just a rare id-collision one. Existing rows are backfilled to this device's own install
 * ID, same as every earlier gardenId migration, so a device not using sharing sees no change.
 */
fun migration26To27(context: Context) = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val installId = getOrCreateInstallId(context)

        db.execSQL("ALTER TABLE extra_photos ADD COLUMN gardenId TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE extra_photos SET gardenId = ?", arrayOf(installId))

        db.execSQL("ALTER TABLE growth_photos ADD COLUMN gardenId TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE growth_photos SET gardenId = ?", arrayOf(installId))

        db.execSQL("ALTER TABLE location_photos ADD COLUMN gardenId TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE location_photos SET gardenId = ?", arrayOf(installId))
    }
}

val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `manual_zone_schedules` (
                `id` TEXT NOT NULL,
                `zone` TEXT NOT NULL,
                `gardenId` TEXT NOT NULL DEFAULT '',
                `daysOfWeek` TEXT NOT NULL,
                `durationMinutes` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_manual_zone_schedules_zone` ON `manual_zone_schedules` (`zone`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_manual_zone_schedules_gardenId` ON `manual_zone_schedules` (`gardenId`)")
    }
}