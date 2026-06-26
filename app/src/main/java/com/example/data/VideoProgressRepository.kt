package com.example.data

class VideoProgressRepository(private val dao: VideoProgressDao) {
    suspend fun getProgress(uri: String): VideoProgress? = dao.getProgress(uri)
    
    suspend fun saveProgress(progress: VideoProgress) = dao.saveProgress(progress)
    
    suspend fun deleteProgress(uri: String) = dao.deleteProgress(uri)
    
    suspend fun getAllHistory(): List<VideoProgress> = dao.getAllHistory()
}
