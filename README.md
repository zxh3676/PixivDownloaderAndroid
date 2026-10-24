# Pixiv Downloader (Android)

English | [中文](README_zh.md)

Android image downloader that connects directly to Pixiv via ECH (Encrypted Client Hello). No proxy or VPN required.

![](screenshots/IMG_20260812_161045.jpg)

## Features

- **ECH Direct Connection** — Metadata API (`www.pixiv.net`) rides Encrypted Client Hello (RFC 9580): the real domain is encrypted inside the TLS handshake, so censorship and Cloudflare's no-SNI rejection both miss it. Falls back to the legacy direct path automatically if ECH is unavailable
- **No-SNI Image Downloads** — `i.pximg.net` still connects through raw `SSLSocket` without SNI, routed by `Host` header
- **Dual Tabs** — Download settings / Real-time logs
- **WebView Login** — Login in-app and automatically save cookies; expired cookies (HTTP 401) degrade to anonymous requests automatically
- **Original Image Reconstruction** — When the original image link is stripped, rebuild the original URL from thumbnail timestamps + format blind test
- **Batch Download** — Supports downloading all works of an artist, with configurable limit
- **Archive Storage** — Automatically organizes downloads into `Pictures/PixivDownloader/ArtistName_ArtistId` folders
- **Material You Dynamic Color** — Automatic theme color on Android 12+
- **Dark Mode** — Follow system / Dark / Light

## Build

```bash
# Debug
./gradlew assembleDebug

# Release (uses your debug keystore — set keystorePath / keystorePassword /
# keyAlias / keyPassword in ~/.gradle/gradle.properties first)
./gradlew assembleRelease
```

ABI splits are enabled: one APK per architecture plus a universal build. Release output:

- `app/build/outputs/apk/release/app-arm64-v8a-release.apk` — most modern devices
- `app/build/outputs/apk/release/app-universal-release.apk` — all architectures

Debug APKs follow the same naming under `app/build/outputs/apk/debug/`.

The ECH library (`libpxdl_ech.so`, arm64-v8a / armeabi-v7a / x86_64) ships prebuilt and is packaged into the APK automatically. To modify it, cross-compile the Rust crate in `app/ech_jni/` with the Android NDK toolchain.

## Tech Stack

| Layer | Choice |
|-------|--------|
| UI | Jetpack Compose + Material 3 |
| Networking | Raw `SSLSocket` + HTTP/1.1 (images) · Rust ECH library via JNI (API) |
| ECH | Rust + rustls + aws-lc-rs, prebuilt `.so` per ABI |
| Image Storage | MediaStore (API 29+) / Legacy file |
| JSON | `org.json` (built into Android SDK) |
| Build | Gradle 8.14 + AGP 8.5.0 |

## How the Direct Connection Works

**Metadata API (`www.pixiv.net`) — ECH**

Pixiv now fronts its web/API with Cloudflare and rejects every no-SNI direct request at the origin, while censorship resets any TLS connection carrying a pixiv SNI. ECH (Encrypted Client Hello, RFC 9580) defeats both at once:

1. Fetch the ECHConfig list via DNS-over-HTTPS (TTL-cached, with Cloudflare edge-IP rotation)
2. TLS handshake presents only `cloudflare-ech.com` as the outer SNI; the real domain `www.pixiv.net` is HPKE-encrypted inside the ClientHello
3. Cloudflare decrypts the inner SNI and routes normally — the middle can neither see nor block the real domain
4. Requests send browser-like headers (UA / Accept / Accept-Language / Referer) to pass Cloudflare's bot check
5. If ECH is unavailable or the handshake fails, fall back to the legacy no-SNI path; an expired login cookie (HTTP 401) degrades to anonymous requests automatically

**Images (`i.pximg.net`) — legacy no-SNI bypass**

1. Skip DNS, connect directly to CDN node IP
2. `SSLSocketFactory.createSocket()` with no arguments → do not set `server_name`
3. Do not send SNI extension during TLS handshake
4. Send raw HTTP/1.1 request, routing via `Host` header
