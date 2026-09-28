package com.example.sagegarden

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PlantDao {
    @Query("SELECT * FROM plants WHERE gardenId = :gardenId ORDER BY name ASC")
    fun getAll(gardenId: String): Flow<List<PlantEntity>>

    @Query("SELECT * FROM plants WHERE gardenId = :gardenId")
    suspend fun getAllOnceForGarden(gardenId: String): List<PlantEntity>

    // Matches by id alone (deliberately, for deep links/notifications that only ever carry a bare
    // plant id, never a gardenId) — now that (gardenId, id) is the primary key, more than one row can
    // share an id across two different gardens synced onto the same device (see PlantEntity's doc
    // comment). ORDER BY + LIMIT 1 makes that case deterministic (most recently touched wins) rather
    // than an unspecified row, though the only real fix for a genuine collision is not hitting one —
    // this is a pragmatic tie-break, not a guarantee of picking the "right" garden's plant.
    @Query("SELECT * FROM plants WHERE id = :id ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getById(id: String): PlantEntity?

    // Unambiguous — use this instead of getById(id) whenever gardenId is known. See resolvePlantById
    // in MainActivity.kt for the "prefer active garden, fall back to bare id" pattern every screen
    // that resolves a plant by a route/deep-link id (not already holding the full entity) should use.
    @Query("SELECT * FROM plants WHERE gardenId = :gardenId AND id = :id")
    suspend fun getByIdForGarden(gardenId: String, id: String): PlantEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plant: PlantEntity)

    // Scoped by gardenId, not just id — a bare `WHERE id = :id` would delete every garden's row
    // sharing that id now that (gardenId, id) is the primary key (see PlantEntity's doc comment).
    // Both call sites already know the plant's real gardenId by the time they delete it.
    @Query("DELETE FROM plants WHERE gardenId = :gardenId AND id = :id")
    suspend fun deleteById(gardenId: String, id: String)

    @Query("DELETE FROM plants")
    suspend fun deleteAll()

    @Query("DELETE FROM plants WHERE gardenId = :gardenId")
    suspend fun deleteForGarden(gardenId: String)

    @Query("SELECT * FROM plants")
    suspend fun getAllOnce(): List<PlantEntity>
}
