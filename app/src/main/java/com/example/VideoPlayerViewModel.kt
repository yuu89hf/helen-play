package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.VideoProgress
import com.example.data.VideoProgressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VideoItem(
    val title: String,
    val uri: String,
    val isLocal: Boolean = false
)

class VideoPlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: VideoProgressRepository
    
    init {
        val database = AppDatabase.getDatabase(application)
        repository = VideoProgressRepository(database.videoProgressDao())
    }

    // List of predefined streamable videos
    val demoVideos = listOf(
        VideoItem("Big Buck Bunny", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"),
        VideoItem("Sintel", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4"),
        VideoItem("Tears of Steel", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4"),
        VideoItem("Elephants Dream", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4")
    )

    // UI States
    private val _currentVideo = MutableStateFlow<VideoItem?>(null)
    val currentVideo: StateFlow<VideoItem?> = _currentVideo.asStateFlow()

    private val _history = MutableStateFlow<List<VideoProgress>>(emptyList())
    val history: StateFlow<List<VideoProgress>> = _history.asStateFlow()

    // Resume / restart prompt state
    private val _resumeProposal = MutableStateFlow<VideoProgress?>(null)
    val resumeProposal: StateFlow<VideoProgress?> = _resumeProposal.asStateFlow()

    // Gesture Overlay States
    private val _volume = MutableStateFlow(0.5f) // 0f to 1f
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _brightness = MutableStateFlow(0.5f) // 0f to 1f
    val brightness: StateFlow<Float> = _brightness.asStateFlow()

    private val _volumeHUDVisible = MutableStateFlow(false)
    val volumeHUDVisible: StateFlow<Boolean> = _volumeHUDVisible.asStateFlow()

    private val _brightnessHUDVisible = MutableStateFlow(false)
    val brightnessHUDVisible: StateFlow<Boolean> = _brightnessHUDVisible.asStateFlow()

    private val _seekHUDVisible = MutableStateFlow(false)
    val seekHUDVisible: StateFlow<Boolean> = _seekHUDVisible.asStateFlow()

    private val _seekHUDPosition = MutableStateFlow(0L)
    val seekHUDPosition: StateFlow<Long> = _seekHUDPosition.asStateFlow()

    private val _controlsVisible = MutableStateFlow(true)
    val controlsVisible: StateFlow<Boolean> = _controlsVisible.asStateFlow()

    private var dismissVolumeJob: Job? = null
    private var dismissBrightnessJob: Job? = null
    private var dismissControlsJob: Job? = null

    init {
        loadHistory()
    }

    fun loadHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            _history.value = repository.getAllHistory()
        }
    }

    fun selectVideo(video: VideoItem?) {
        viewModelScope.launch {
            if (video == null) {
                _currentVideo.value = null
                _resumeProposal.value = null
                return@launch
            }

            // Check if there is saved progress for this video
            val saved = repository.getProgress(video.uri)
            if (saved != null && saved.position > 5000L) { // Only propose if progress is > 5 seconds
                _resumeProposal.value = saved
            } else {
                _resumeProposal.value = null
            }

            _currentVideo.value = video
            showControlsTemporarily()
        }
    }

    fun loadCustomUrl(url: String, title: String = "Custom Stream") {
        if (url.isNotBlank()) {
            val resolvedTitle = if (title.isBlank()) "Custom Stream" else title
            selectVideo(VideoItem(resolvedTitle, url, isLocal = false))
        }
    }

    fun loadLocalFile(uri: String, title: String) {
        selectVideo(VideoItem(title, uri, isLocal = true))
    }

    fun onResumeAccepted(progress: VideoProgress, seekToPosition: (Long) -> Unit) {
        viewModelScope.launch {
            seekToPosition(progress.position)
            _resumeProposal.value = null
        }
    }

    fun onResumeDeclined() {
        _resumeProposal.value = null
    }

    fun saveVideoProgress(uri: String, title: String, position: Long, duration: Long) {
        if (duration <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            val progress = VideoProgress(
                uri = uri,
                title = title,
                position = position,
                duration = duration,
                lastAccessed = System.currentTimeMillis()
            )
            repository.saveProgress(progress)
            loadHistory()
        }
    }

    fun deleteHistoryItem(uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteProgress(uri)
            loadHistory()
        }
    }

    // Gesture Handlers
    fun initializeVolume(newVal: Float) {
        _volume.value = newVal.coerceIn(0f, 1f)
    }

    fun initializeBrightness(newVal: Float) {
        _brightness.value = newVal.coerceIn(0f, 1f)
    }

    fun updateVolume(newVal: Float) {
        _volume.value = newVal.coerceIn(0f, 1f)
        _volumeHUDVisible.value = true
        
        dismissVolumeJob?.cancel()
        dismissVolumeJob = viewModelScope.launch {
            delay(1500)
            _volumeHUDVisible.value = false
        }
    }

    fun updateBrightness(newVal: Float) {
        _brightness.value = newVal.coerceIn(0f, 1f)
        _brightnessHUDVisible.value = true

        dismissBrightnessJob?.cancel()
        dismissBrightnessJob = viewModelScope.launch {
            delay(1500)
            _brightnessHUDVisible.value = false
        }
    }

    fun updateSeekHUD(position: Long) {
        _seekHUDPosition.value = position
        _seekHUDVisible.value = true
    }

    fun hideSeekHUD() {
        _seekHUDVisible.value = false
    }

    fun toggleControls() {
        _controlsVisible.value = !_controlsVisible.value
        if (_controlsVisible.value) {
            showControlsTemporarily()
        }
    }

    fun showControlsTemporarily(durationMs: Long = 4000L) {
        _controlsVisible.value = true
        dismissControlsJob?.cancel()
        dismissControlsJob = viewModelScope.launch {
            delay(durationMs)
            _controlsVisible.value = false
        }
    }
}
