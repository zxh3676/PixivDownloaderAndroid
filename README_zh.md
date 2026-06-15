# Pixiv 下载器 (Android)

[English](README.md) | [中文](README_zh.md)

基于 SNI 绕过技术直连 Pixiv 的 Android 图片下载器。无需代理。

## 功能

- **SNI 绕过** — 不依赖任何 HTTP 库，纯 `SSLSocket` 直连 Pixiv CDN
- **双标签页** — 下载设置 / 实时日志
- **WebView 登录** — 应用内登录并自动保存 Cookie
- **原图反推** — 当原图链接被抹除时，从缩略图时间戳重建原图 URL + 格式盲测
- **批量下载** — 支持画师全部作品下载，可限制数量
- **归档保存** — 自动按 `画师名_画师ID` 文件夹归档到 `Pictures/PixivDownloader/`
- **Material You 动态取色** — Android 12+ 自动主题色
- **深色模式** — 跟随系统 / 深色 / 浅色

## 构建

```bash
# Debug
./gradlew assembleDebug

# Release（使用 debug keystore 签名）
./gradlew assembleRelease
```

APK 输出：`app/build/outputs/apk/release/app-release.apk`

## 技术栈

| 层 | 选型 |
|------|------|
| UI | Jetpack Compose + Material 3 |
| 网络 | 裸 `SSLSocket` + 裸 HTTP/1.1（无第三方网络库） |
| 图片存储 | MediaStore (API 29+) / 传统文件 |
| JSON | `org.json`（Android SDK 内置） |
| 构建 | Gradle 8.14 + AGP 8.5.0 |

## SNI 绕过原理

核心思路：

1. 跳过 DNS，直连 CDN 节点 IP
2. `SSLSocketFactory.createSocket()` 无参创建 → 不设 `server_name`
3. TLS 握手时不发送 SNI 扩展
4. 发送裸 HTTP/1.1 请求，靠 `Host` 头路由
