package com.pixiv.downloader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pixiv.downloader.model.DownloadState
import com.pixiv.downloader.viewmodel.MainViewModel

@Composable
fun SettingsScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader("下载设置")

        OutlinedTextField(
            value = viewModel.inputUrl,
            onValueChange = { viewModel.inputUrl = it },
            label = { Text("Pixiv URL") },
            placeholder = { Text("https://www.pixiv.net/artworks/123456") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) }
        )

        Text(
            "— 或直接输入 ID —",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = viewModel.illustId,
                onValueChange = { viewModel.illustId = it },
                label = { Text("作品 ID") },
                placeholder = { Text("仅数字") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) }
            )
            OutlinedTextField(
                value = viewModel.userId,
                onValueChange = { viewModel.userId = it },
                label = { Text("画师 ID") },
                placeholder = { Text("仅数字") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) }
            )
        }

        OutlinedTextField(
            value = viewModel.maxPages,
            onValueChange = { viewModel.maxPages = it.filter { c -> c.isDigit() } },
            label = { Text("最大下载数 (0 = 全部)") },
            placeholder = { Text("0") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Numbers, contentDescription = null) }
        )

        // 下载按钮
        val state = viewModel.downloadState
        val isDownloading = state is DownloadState.Downloading

        Button(
            onClick = { viewModel.startDownload() },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = !isDownloading
        ) {
            if (isDownloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
                Text("下载中...")
            } else {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("开始下载", style = MaterialTheme.typography.titleMedium)
            }
        }

        // 完成提示
        when (val s = state) {
            is DownloadState.Completed -> {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("下载完成: ${s.illustCount} 个作品, ${s.imageCount} 张图片")
                    }
                }
            }
            is DownloadState.Error -> {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(12.dp))
                        Text(s.message)
                    }
                }
            }
            else -> {}
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}
