package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val viewModel: VideoPlayerViewModel = viewModel()
                val currentVideo by viewModel.currentVideo.collectAsState()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (currentVideo != null) {
                        BackHandler {
                            viewModel.selectVideo(null)
                        }
                        VideoPlayerView(
                            video = currentVideo!!,
                            viewModel = viewModel,
                            onBack = { viewModel.selectVideo(null) },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        LibraryView(
                            viewModel = viewModel,
                            onSelectVideo = { video ->
                                viewModel.selectVideo(video)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}
