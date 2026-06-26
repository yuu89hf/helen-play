package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "video_progress")
data class VideoProgress(
    @PrimaryKey val uri: String,
    val title: String,
    val position: Long,
    val duration: Long,
    val lastAccessed: Long = System.currentTimeMillis()
)
