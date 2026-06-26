package com.example

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.content.pm.ActivityInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.data.VideoProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

enum class DragType {
    SEEK, VOLUME, BRIGHTNESS
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerView(
    video: VideoItem,
    viewModel: VideoPlayerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    // Setup ExoPlayer
    val exoPlayer = remember(video.uri) {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(video.uri)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = false // Pause initially -> hold to play!
        }
    }

    // Playback state states
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var isBuffering by remember { mutableStateOf(false) }

    // Read VM States
    val volume by viewModel.volume.collectAsState()
    val brightness by viewModel.brightness.collectAsState()
    val volumeHUDVisible by viewModel.volumeHUDVisible.collectAsState()
    val brightnessHUDVisible by viewModel.brightnessHUDVisible.collectAsState()
    val seekHUDVisible by viewModel.seekHUDVisible.collectAsState()
    val seekHUDPosition by viewModel.seekHUDPosition.collectAsState()
    val controlsVisible by viewModel.controlsVisible.collectAsState()
    val resumeProposal by viewModel.resumeProposal.collectAsState()

    var showHoldToPlayIndicator by remember { mutableStateOf(false) }
    var userIsHolding by remember { mutableStateOf(false) }
    var dragStartOnLeft by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableStateOf(0f) }
    var showHoldOverlay by remember { mutableStateOf(false) }
    var holdCompleteTriggered by remember { mutableStateOf(false) }
    var isDraggingGesture by remember { mutableStateOf(false) }

    // Initialize brightness and volume from system/activity on start
    LaunchedEffect(Unit) {
        activity?.window?.let { window ->
            val attrs = window.attributes
            val currentBrightness = if (attrs.screenBrightness >= 0f) {
                attrs.screenBrightness
            } else {
                try {
                    val systemBrightness = android.provider.Settings.System.getInt(
                        context.contentResolver,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS
                    )
                    systemBrightness / 255f
                } catch (e: Exception) {
                    0.5f
                }
            }
            viewModel.initializeBrightness(currentBrightness)
        }

        try {
            val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val currentVol = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat()
            val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).toFloat()
            if (maxVol > 0f) {
                viewModel.initializeVolume(currentVol / maxVol)
            }
        } catch (e: Exception) {
            // fallback
        }
    }

    // Synchronize volume index with player and system AudioManager
    LaunchedEffect(volume) {
        exoPlayer.volume = volume
        try {
            val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val targetVol = (volume * maxVol).toInt()
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, targetVol, 0)
        } catch (e: Exception) {
            // fallback
        }
    }

    // Synchronize brightness with Activity window
    LaunchedEffect(brightness) {
        activity?.window?.let { window ->
            val layoutParams = window.attributes
            layoutParams.screenBrightness = brightness.coerceIn(0.01f, 1.0f)
            window.attributes = layoutParams
        }
    }

    var isLandscapeOverride by remember { mutableStateOf<Boolean?>(null) }

    // LaunchedEffect to manage the 2.5-second hold progress countdown
    LaunchedEffect(userIsHolding, isDraggingGesture, isPlaying) {
        if (userIsHolding && !isDraggingGesture && !isPlaying) {
            showHoldOverlay = true
            holdProgress = 0f
            holdCompleteTriggered = false
            val startTime = System.currentTimeMillis()
            while (userIsHolding && !isDraggingGesture && !holdCompleteTriggered && !isPlaying) {
                val elapsed = System.currentTimeMillis() - startTime
                holdProgress = (elapsed.toFloat() / 2500f).coerceIn(0f, 1f)
                if (elapsed >= 2500L) {
                    holdCompleteTriggered = true
                    showHoldOverlay = false
                    exoPlayer.play()
                    break
                }
                delay(16) // ~60fps updates
            }
        } else {
            showHoldOverlay = false
            holdProgress = 0f
        }
    }

    // Synchronize manual orientation changes with activity
    LaunchedEffect(isLandscapeOverride) {
        isLandscapeOverride?.let { overrideToLandscape ->
            activity?.requestedOrientation = if (overrideToLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }

    // ExoPlayer event listener
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                duration = exoPlayer.duration.coerceAtLeast(0L)
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (isLandscapeOverride == null && videoSize.width > 0 && videoSize.height > 0) {
                    val isLandscapeVideo = videoSize.width > videoSize.height
                    activity?.requestedOrientation = if (isLandscapeVideo) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Periodic position updater and auto-saver
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosition = exoPlayer.currentPosition
            duration = exoPlayer.duration.coerceAtLeast(0L)
            if (duration > 0) {
                viewModel.saveVideoProgress(video.uri, video.title, currentPosition, duration)
            }
            delay(500)
        }
    }

    // Handle initial seek on resume proposal accept
    val onResumeAction: (VideoProgress) -> Unit = { savedProgress ->
        viewModel.onResumeAccepted(savedProgress) { targetPos ->
            exoPlayer.seekTo(targetPos)
            currentPosition = targetPos
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("video_player_screen")
    ) {
        // 1. Core ExoPlayer AndroidView
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Main Gesture Controller Layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(video.uri) {
                    awaitPointerEventScope {
                        while (true) {
                            // Touch Down (Press)
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startPosition = down.position
                            var dragDetected = false
                            var currentDragType: DragType? = null

                            // Record values at start of gesture
                            val initialVolume = volume
                            val initialBrightness = brightness
                            val initialSeekPosition = currentPosition
                            var lastSeekTime = 0L

                            userIsHolding = true
                            isDraggingGesture = false

                            var pointerId = down.id

                            do {
                                val event = awaitPointerEvent()
                                val dragEvent = event.changes.firstOrNull { it.id == pointerId } ?: break

                                if (dragEvent.pressed) {
                                    val dragCurrentPosition = dragEvent.position
                                    val dx = dragCurrentPosition.x - startPosition.x
                                    val dy = dragCurrentPosition.y - startPosition.y

                                    // Check if drag threshold is met (15dp)
                                    val threshold = 15f * density
                                    if (!dragDetected && (dx.absoluteValue > threshold || dy.absoluteValue > threshold)) {
                                        dragDetected = true
                                        isDraggingGesture = true
                                        currentDragType = if (dx.absoluteValue > dy.absoluteValue) {
                                            DragType.SEEK
                                        } else {
                                            if (startPosition.x < size.width * 0.33f) {
                                                dragStartOnLeft = true
                                                DragType.VOLUME
                                            } else if (startPosition.x > size.width * 0.67f) {
                                                dragStartOnLeft = false
                                                DragType.VOLUME
                                            } else {
                                                DragType.BRIGHTNESS
                                            }
                                        }
                                    }

                                    if (dragDetected) {
                                        when (currentDragType) {
                                            DragType.SEEK -> {
                                                if (duration > 0) {
                                                    val fraction = dx / size.width
                                                    val seekDelta = (fraction * duration).toLong()
                                                    val targetPos = (initialSeekPosition + seekDelta).coerceIn(0L, duration)
                                                    viewModel.updateSeekHUD(targetPos)
                                                    
                                                    // Pause playback while seeking to make seek visual updates buttery smooth and highly responsive
                                                    if (exoPlayer.isPlaying) {
                                                        exoPlayer.pause()
                                                    }
                                                    
                                                    // Throttle seek messages sent to ExoPlayer to keep the playback thread completely responsive
                                                    val now = System.currentTimeMillis()
                                                    if (now - lastSeekTime > 40L) {
                                                        exoPlayer.seekTo(targetPos)
                                                        lastSeekTime = now
                                                    }
                                                    currentPosition = targetPos
                                                }
                                            }
                                            DragType.VOLUME -> {
                                                val fraction = -dy / size.height
                                                viewModel.updateVolume(initialVolume + fraction)
                                            }
                                            DragType.BRIGHTNESS -> {
                                                val fraction = -dy / size.height
                                                viewModel.updateBrightness(initialBrightness + fraction)
                                            }
                                            null -> {}
                                        }
                                    }
                                    dragEvent.consume()
                                }
                            } while (event.changes.any { it.pressed })

                            // Touch Up (Release)
                            userIsHolding = false
                            isDraggingGesture = false

                            if (dragDetected) {
                                if (currentDragType == DragType.SEEK && duration > 0) {
                                    exoPlayer.seekTo(currentPosition)
                                }
                                viewModel.hideSeekHUD()
                            } else {
                                // Tap triggers seek HUD toggle for exactly 2 seconds if hold was incomplete
                                if (!holdCompleteTriggered) {
                                    viewModel.showControlsTemporarily(2000L)
                                }
                            }
                        }
                    }
                }
        )

        // 3. Immersive Touch-and-Hold Live Glow Overlay (determinate 2.5s hold progress)
        AnimatedVisibility(
            visible = showHoldOverlay && !seekHUDVisible && !volumeHUDVisible && !brightnessHUDVisible && !holdCompleteTriggered,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { 1.0f },
                            color = Color.White.copy(alpha = 0.15f),
                            modifier = Modifier.size(80.dp),
                            strokeWidth = 6.dp
                        )
                        CircularProgressIndicator(
                            progress = { holdProgress },
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(80.dp),
                            strokeWidth = 6.dp
                        )
                        Text(
                            text = "${(holdProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                    Text(
                        text = "Hold 2.5s to play",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White
                    )
                }
            }
        }

        // 4. Paused State Center Indicator (only when user is NOT holding and controls are visible)
        AnimatedVisibility(
            visible = !userIsHolding && controlsVisible && !isBuffering,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.65f)),
                    shape = RoundedCornerShape(28.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .clickable { viewModel.showControlsTemporarily() }
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.TouchApp,
                            contentDescription = "Hold to Play",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "PAUSED",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        Text(
                            text = "Hold anywhere for 2.5s to play\nTap to show controls",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }

        // 5. Loading / Buffering Spinner
        if (isBuffering) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        // 6. MPV Style Gesture HUD Overlays (Volume, Brightness, Seek)
        // Center: Brightness HUD (Since drag is in the middle)
        AnimatedVisibility(
            visible = brightnessHUDVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.Center)
        ) {
            GestureHUD(
                icon = Icons.Default.Brightness5,
                value = brightness,
                label = "Brightness"
            )
        }

        // Left/Right Side: Volume HUD (depends on where the gesture started)
        AnimatedVisibility(
            visible = volumeHUDVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(if (dragStartOnLeft) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(
                    start = if (dragStartOnLeft) 24.dp else 0.dp,
                    end = if (dragStartOnLeft) 0.dp else 24.dp
                )
        ) {
            GestureHUD(
                icon = if (volume == 0f) Icons.Default.VolumeMute else Icons.Default.VolumeUp,
                value = volume,
                label = "Volume"
            )
        }

        // Center: Seek HUD
        AnimatedVisibility(
            visible = seekHUDVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            val formattedProgress = formatDuration(seekHUDPosition)
            val formattedTotal = formatDuration(duration)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "Seek",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Seek Position",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                    }
                    Text(
                        text = "$formattedProgress / $formattedTotal",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 7. MPV Bottom Bar & Top Bar Controls (Visible when not holding and controls are active)
        AnimatedVisibility(
            visible = !userIsHolding && controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Library",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = video.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1
                        )
                        if (video.isLocal) {
                            Text(
                                text = "Local File",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray
                            )
                        } else {
                            Text(
                                text = "Stream Source",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            val currentlyLandscape = isLandscapeOverride ?: (activity?.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE || activity?.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                            isLandscapeOverride = !currentlyLandscape
                        },
                        modifier = Modifier.testTag("orientation_toggle_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ScreenRotation,
                            contentDescription = "Toggle Orientation",
                            tint = Color.White
                        )
                    }
                }

                // Bottom Seekbar & Navigation HUD
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Time indicators
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    if (exoPlayer.isPlaying) {
                                        exoPlayer.pause()
                                    } else {
                                        exoPlayer.play()
                                    }
                                },
                                modifier = Modifier.size(36.dp).testTag("play_pause_button")
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Text(
                                text = formatDuration(currentPosition),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                        }
                        Text(
                            text = formatDuration(duration),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                    }

                    // Seek slider
                    Slider(
                        value = if (duration > 0) currentPosition.toFloat() else 0f,
                        onValueChange = { value ->
                            scope.launch {
                                exoPlayer.seekTo(value.toLong())
                                currentPosition = value.toLong()
                            }
                        },
                        valueRange = 0f..(if (duration > 0) duration.toFloat() else 1f),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.24f)
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("video_seekbar")
                    )
                }
            }
        }

        // 8. Continue / Restart Prompt Overlay (when reopening the video)
        if (resumeProposal != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .pointerInput(Unit) { detectTapGestures { } }, // Blocks touches from reaching background player
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(28.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                    modifier = Modifier
                        .widthIn(max = 400.dp)
                        .padding(24.dp)
                        .testTag("resume_dialog")
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Resume playback?",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF1E293B)
                            )
                        )

                        Text(
                            text = "You left off at ${formatDuration(resumeProposal!!.position)}. Would you like to continue where you were?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF64748B)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { onResumeAction(resumeProposal!!) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("resume_continue_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF1E293B),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Text(
                                    "Continue Watching",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                )
                            }

                            OutlinedButton(
                                onClick = { viewModel.onResumeDeclined() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("resume_restart_button"),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFF1E293B)
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCBD5E1)),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Text(
                                    "Restart from Beginning",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Visual HUD Helper Composable for gestures (Volume, Brightness)
@Composable
fun GestureHUD(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: Float,
    label: String,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f)),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.width(64.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )

            // Vertical Progress Indicator
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(120.dp)
                    .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
                    .clip(RoundedCornerShape(2.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(value)
                        .background(MaterialTheme.colorScheme.primary)
                        .align(Alignment.BottomCenter)
                )
            }

            Text(
                text = "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
        }
    }
}

// Utility to format time ms to HH:MM:SS or MM:SS
fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
