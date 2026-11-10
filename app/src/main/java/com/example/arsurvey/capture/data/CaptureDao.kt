package com.example.arsurvey.capture.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertObject(obj: CapturedObjectEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhoto(photo: CapturedPhotoEntity)

    /** All objects with their photos, newest first — the catalog observes this. */
    @Transaction
    @Query("SELECT * FROM captured_objects ORDER BY createdAtEpochMs DESC")
    fun observeAllObjects(): Flow<List<ObjectWithPhotos>>

    @Transaction
    @Query("SELECT * FROM captured_objects WHERE id = :objectId")
    suspend fun getObject(objectId: String): ObjectWithPhotos?
}
