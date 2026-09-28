package com.example.sagegarden

import androidx.room.Entity

// Composite primary key (gardenId, id) — not `id` alone. A plant id like "P0001" is assigned by
// plantIdPrefixForGarden, whose letter-prefix scheme is per-DEVICE local state: it stops one
// device's OWN gardens from colliding with each other, but two DIFFERENT devices each starting
// their own "original default" garden at "P" independently produces identical raw ids for
// completely unrelated plants. Once both gardens are known to (and synced onto) the same device —
// e.g. your own garden plus a shared garden you were invited to — the old id-alone primary key let
// Room's REPLACE-based upsert (PlantDao.upsert) silently reparent one garden's row onto another's
// same-id row, corrupting the local cache (see feedback_plant_id_cross_garden_collision — this is
// the same bug class recurring via a cross-device vector instead of the originally-fixed
// cross-garden-on-one-device vector). See migration29To30 for the table-recreation migration.
@Entity(tableName = "plants", primaryKeys = ["gardenId", "id"])
data class PlantEntity(
    val id: String,
    val name: String,
    val sci: String,
    val location: String,
    val sun: String,
    val water: String,
    val soil: String,
    val soilPh: String = "",
    val category: String = "",
    val frost: String,
    val native: String,
    val pollinator: String,
    val source: String,
    val date: String,
    val qty: Int,
    val notes: String,
    val wateringSystem: String,
    val lat: Double?,
    val lng: Double?,
    val photoUri: String?,
    val photoUris: List<String> = emptyList(),
    // Small base64 JPEG (~150px) cached alongside a local (content://) photoUri only — Dropbox/http
    // photoUris don't need this, since a plain HTTPS link already works cross-device. Lets a member
    // viewing a garden owner who uses local photo storage still see at least a thumbnail, since the
    // owner's content:// URI is meaningless off their own device. See feature-visibility: this is
    // generated once when the photo is set (FormScreen), not recomputed on every sync.
    val photoThumbnailBase64: String? = null,
    val mapX: Double? = null,  // 0.0–1.0 fraction across the custom map image
    val mapY: Double? = null,  // 0.0–1.0 fraction down the custom map image
    val lastWateredDate: Long? = null,      // epoch millis, UTC midnight of the date picked
    val wateringFrequencyDays: Int? = null, // how often this plant should be watered
    val manualWateringOnly: Boolean = false, // true = hand-watered, shown in amber on custom maps, not part of a path
    val isIndoor: Boolean = false, // true = indoor plant, exempt from rain-based reminder skipping
    val summerWateringFrequencyDays: Int? = null, // overrides wateringFrequencyDays in Dec/Jan/Feb
    val winterWateringFrequencyDays: Int? = null,  // overrides wateringFrequencyDays in Jun/Jul/Aug
    val lastFertilisedDate: Long? = null,
    val fertiliseFrequencyDays: Int? = null,
    val lastPrunedDate: Long? = null,
    val pruneFrequencyDays: Int? = null,
    val lastFedDate: Long? = null,
    val feedFrequencyDays: Int? = null,
    val updatedAt: Long = 0L, // last local write time — used only by GardenSyncClient's last-write-wins merge
    val gardenId: String = "" // which garden this plant belongs to — blank means "not yet stamped", filled in by PlantViewModel.saveSync on first write
)