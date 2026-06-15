package com.pixiv.downloader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pixiv.downloader.ui.MainScreen
import com.pixiv.downloader.ui.theme.PixivDownloaderTheme
import com.pixiv.downloader.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: MainViewModel = viewModel()
            PixivDownloaderTheme(
                darkMode = viewModel.darkMode,
                dynamicColor = viewModel.dynamicColor,
                customColorSeed = viewModel.customColor
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }
}
