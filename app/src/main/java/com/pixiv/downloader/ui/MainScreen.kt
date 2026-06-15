package com.pixiv.downloader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.pixiv.downloader.ui.screens.LogScreen
import com.pixiv.downloader.ui.screens.SettingsPage
import com.pixiv.downloader.ui.screens.SettingsScreen
import com.pixiv.downloader.viewmodel.MainViewModel

private val tabs = listOf(
    TabItem("下载", Icons.Filled.Download),
    TabItem("日志", Icons.Filled.Terminal)
)

private data class TabItem(val title: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val showSettings = viewModel.showSettingsPage

    // 设置页处理系统返回键
    BackHandler(enabled = showSettings) {
        viewModel.showSettingsPage = false
    }

    Scaffold(
        topBar = {
            if (showSettings) {
                TopAppBar(
                    title = { Text("设置") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.showSettingsPage = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("Pixiv 下载器") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    actions = {
                        IconButton(onClick = { viewModel.showSettingsPage = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (!showSettings) {
                NavigationBar {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Icon(tab.icon, contentDescription = tab.title) },
                            label = { Text(tab.title) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        if (showSettings) {
            SettingsPage(
                viewModel = viewModel,
                modifier = Modifier.padding(innerPadding)
            )
        } else {
            when (selectedTab) {
                0 -> SettingsScreen(
                    viewModel = viewModel,
                    modifier = Modifier.padding(innerPadding)
                )
                1 -> LogScreen(
                    viewModel = viewModel,
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}
