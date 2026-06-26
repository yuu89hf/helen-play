package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface VideoProgressDao {
    @Query("SELECT * FROM video_progress WHERE uri = :uri LIMIT 1")
    suspend fun getProgress(uri: String): VideoProgress?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: VideoProgress)

    @Query("DELETE FROM video_progress WHERE uri = :uri")
    suspend fun deleteProgress(uri: String)

    @Query("SELECT * FROM video_progress ORDER BY lastAccessed DESC")
    suspend fun getAllHistory(): List<VideoProgress>
}
