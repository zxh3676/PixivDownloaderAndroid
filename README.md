# Pixiv Downloader (Android)

[English](README.md) | [中文](README_zh.md)

Android image downloader that connects directly to Pixiv using SNI bypass technology. No proxy required.

## Features

- **SNI Bypass** — Direct connection to Pixiv CDN using raw `SSLSocket`, no HTTP library dependencies
- **Dual Tabs** — Download settings / Real-time logs
- **WebView Login** — Login in-app and automatically save cookies
- **Original Image Reconstruction** — When the original image link is stripped, rebuild the original URL from thumbnail timestamps + format blind test
- **Batch Download** — Supports downloading all works of an artist, with configurable limit
- **Archive Storage** — Automatically organizes downloads into `Pictures/PixivDownloader/ArtistName_ArtistId` folders
- **Material You Dynamic Color** — Automatic theme color on Android 12+
- **Dark Mode** — Follow system / Dark / Light

## Build

```bash
# Debug
./gradlew assembleDebug

# Release (signed with debug keystore)
./gradlew assembleRelease
```

APK output: `app/build/outputs/apk/release/app-release.apk`

## Tech Stack

| Layer | Choice |
|-------|--------|
| UI | Jetpack Compose + Material 3 |
| Networking | Raw `SSLSocket` + raw HTTP/1.1 (no third-party network library) |
| Image Storage | MediaStore (API 29+) / Legacy file |
| JSON | `org.json` (built into Android SDK) |
| Build | Gradle 8.14 + AGP 8.5.0 |

## How SNI Bypass Works

Core approach:

1. Skip DNS, connect directly to CDN node IP
2. `SSLSocketFactory.createSocket()` with no arguments → do not set `server_name`
3. Do not send SNI extension during TLS handshake
4. Send raw HTTP/1.1 request, routing via `Host` header