package com.pixiv.downloader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pixiv.downloader.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val logs = viewModel.logs
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboardManager.current
    val dateFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // 把所有日志拼成一段可选中的纯文本
    val fullLogText = remember(logs) {
        if (logs.isEmpty()) ""
        else logs.joinToString("\n") { entry ->
            val time = dateFmt.format(Date(entry.timestamp))
            val tag = when (entry.level) {
                "ERROR" -> "ERR"
                "WARN" -> "WRN"
                else -> "INF"
            }
            "$time [$tag] ${entry.message}"
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "下载日志",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            if (logs.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = {
                        clipboardManager.setText(AnnotatedString(fullLogText))
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "复制全部")
                    }
                    IconButton(onClick = { viewModel.clearLogs() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "清空日志")
                    }
                }
            }
        }

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "暂无日志",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ) {
                SelectionContainer {
                    Text(
                        text = fullLogText,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight
                    )
                }
            }
        }
    }
}
