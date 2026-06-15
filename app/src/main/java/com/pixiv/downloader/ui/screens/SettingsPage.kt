package com.pixiv.downloader.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pixiv.downloader.model.DarkMode
import com.pixiv.downloader.ui.theme.PresetColors
import com.pixiv.downloader.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 账户 ──
        Text("账户", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

        val loggedIn = viewModel.isLoggedIn
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (loggedIn) Icons.Filled.Lock else Icons.Filled.LockOpen,
                contentDescription = null,
                tint = if (loggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (loggedIn) "已登录 Pixiv" else "未登录",
                style = MaterialTheme.typography.bodyMedium,
                color = if (loggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { viewModel.showLoginDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (loggedIn) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (loggedIn) "重新登录" else "登录 Pixiv")
            }
            OutlinedButton(onClick = { viewModel.openCookieEditor() }) {
                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("编辑 Cookie")
            }
        }

        HorizontalDivider()

        // ── 外观 ──
        Text("外观", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

        // 动态取色
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(12.dp))
                Text("动态取色 (Material You)", style = MaterialTheme.typography.bodyLarge)
            }
            Switch(
                checked = viewModel.dynamicColor,
                onCheckedChange = { viewModel.updateDynamicColor(it) }
            )
        }

        // 手动颜色选择
        if (!viewModel.dynamicColor) {
            ColorPickerGrid(
                currentColor = viewModel.customColor,
                onColorSelected = { viewModel.setCustomColor(it) },
                onClear = { viewModel.clearCustomColor() }
            )
        }

        // 深色模式
        Text("深色模式", style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            DarkMode.entries.forEach { mode ->
                SegmentedButton(
                    selected = viewModel.darkMode == mode,
                    onClick = { viewModel.updateDarkMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = DarkMode.entries.indexOf(mode),
                        count = DarkMode.entries.size
                    )
                ) {
                    Text(mode.label)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 关于 ──
        HorizontalDivider()
        Text("关于", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            "Pixiv 下载器 v1.0\n基于 SNI 绕过技术直连 Pixiv，无需代理。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))
    }

    // ── 对话框 ──
    if (viewModel.showLoginDialog) {
        LoginDialog(
            onDismiss = { viewModel.onLoginDialogDismiss() },
            onLoginSuccess = { viewModel.onWebViewLoginSuccess(it) }
        )
    }
    if (viewModel.showCookieEditor) {
        CookieEditorDialog(
            text = viewModel.cookieEditText,
            onSave = { viewModel.saveCookieEdit(it) },
            onDismiss = { viewModel.dismissCookieEditor() }
        )
    }
}

// ════════════════════════════════════════════════════════════
//  WebView 登录
// ════════════════════════════════════════════════════════════

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginDialog(
    onDismiss: () -> Unit,
    onLoginSuccess: (Map<String, String>) -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var currentUrl by remember { mutableStateOf("") }
    var loginDetected by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("登录 Pixiv", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                }
                if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    url?.let { currentUrl = it }
                                }
                                override fun onPageFinished(view: WebView, url: String) {
                                    isLoading = false
                                    currentUrl = url
                                    if (!loginDetected && url.startsWith("https://www.pixiv.net/") && !url.contains("login")) {
                                        loginDetected = true
                                        extractCookiesFromWebView { cookies ->
                                            if (cookies.isNotEmpty() && cookies.containsKey("PHPSESSID")) {
                                                onLoginSuccess(cookies)
                                            }
                                        }
                                    }
                                }
                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    super.doUpdateVisitedHistory(view, url, isReload)
                                    if (url != null && !loginDetected &&
                                        url.startsWith("https://www.pixiv.net/") && !url.contains("login")) {
                                        loginDetected = true
                                        extractCookiesFromWebView { cookies ->
                                            if (cookies.isNotEmpty() && cookies.containsKey("PHPSESSID")) {
                                                onLoginSuccess(cookies)
                                            }
                                        }
                                    }
                                }
                            }
                            loadUrl("https://accounts.pixiv.net/login?lang=zh&return_to=https://www.pixiv.net/")
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                if (currentUrl.isNotEmpty()) {
                    Text(
                        currentUrl.take(80),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

private fun extractCookiesFromWebView(onResult: (Map<String, String>) -> Unit) {
    try {
        val cookieManager = CookieManager.getInstance()
        val cookieStr = cookieManager.getCookie("https://www.pixiv.net") ?: ""
        val cookies = cookieStr.split(";")
            .map { it.trim() }.filter { it.isNotEmpty() }
            .associate {
                val eq = it.indexOf('=')
                if (eq > 0) it.substring(0, eq) to it.substring(eq + 1)
                else it to ""
            }
        onResult(cookies)
    } catch (e: Exception) {
        onResult(emptyMap())
    }
}

// ════════════════════════════════════════════════════════════
//  Cookie 编辑器
// ════════════════════════════════════════════════════════════

@Composable
private fun CookieEditorDialog(
    text: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var editText by remember { mutableStateOf(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Cookie, contentDescription = null) },
        title = { Text("编辑 Cookie (JSON)") },
        text = {
            OutlinedTextField(
                value = editText,
                onValueChange = { editText = it },
                modifier = Modifier.fillMaxWidth().height(280.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                shape = RoundedCornerShape(8.dp)
            )
        },
        confirmButton = { Button(onClick = { onSave(editText) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ════════════════════════════════════════════════════════════
//  颜色选择器
// ════════════════════════════════════════════════════════════

@Composable
private fun ColorPickerGrid(
    currentColor: Color?,
    onColorSelected: (Color) -> Unit,
    onClear: () -> Unit
) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text("选择主题色", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            modifier = Modifier.height(120.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(PresetColors) { (color, _) ->
                val isSelected = currentColor?.toArgb() == color.toArgb()
                Box(
                    modifier = Modifier
                        .size(44.dp).clip(CircleShape).background(color)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                            shape = CircleShape
                        )
                        .clickable { onColorSelected(color) },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) Icon(Icons.Filled.Check, contentDescription = "已选", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
        TextButton(onClick = onClear) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("恢复动态取色")
        }
    }
}
