# Hound

**Website:** [hound.straylabs.in](https://hound.straylabs.in)

A LAN file server and client for Android. Run an HTTP file server from your phone, or browse and transfer files from another Hound server on the same network.

**Package:** `com.straylabs.hound`
**Minimum Android:** 8.0 (API 26)
**Target SDK:** 34

---

## Features

### Server Mode

- Starts an HTTP file server on the local network (default port 8080, configurable)
- Serves any folder on the device filesystem
- Web portal accessible from any browser on the network
  - Gallery view and list view toggle
  - Download individual files
  - Upload files via drag-and-drop or file picker
  - Delete files
  - Images, videos, and audio rendered inline
- Optional HTTP Basic Auth (username + password)
- Runs as a foreground service with a persistent notification showing the server URL
- Server stops automatically when the app is killed

### Client Mode

- Browse any Hound server by entering its IP and port
- Supports HTTP Basic Auth if the server requires it — credentials saved across sessions
- File browser with grid layout
- Inline media preview:
  - Images rendered via Coil
  - Video playback via ExoPlayer (Media3)
  - Audio playback via ExoPlayer (Media3)
- Download files to a configurable folder (defaults to Downloads)
- Upload files from the device to the server
- Upload resumes where it left off if interrupted

### General

- Transfer history tracked per session (uploads and downloads)
- Notifications for server status, download progress, upload progress, and completion
- Storage access: `MANAGE_EXTERNAL_STORAGE` on Android 11+, legacy permissions on Android 9/10

---

## Server API

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/` or `/<path>` | HTML directory listing (web portal) |
| `GET` | `/api/list?path=<path>` | JSON file listing |
| `PUT` | `/upload?path=<relative/path>` | Upload a file (raw body) |
| `DELETE` | `/delete?path=<relative/path>` | Delete a file |
| `GET` | `/.app-icon` | Returns the app icon (used by the web portal) |

**JSON list response:**
```json
{
  "path": "subdir",
  "files": [
    { "name": "file.txt", "size": 1024, "isDirectory": false, "modified": 1700000000000, "path": "subdir/file.txt" }
  ]
}
```

---

## Tech Stack

| Library | Version | Purpose |
|---------|---------|---------|
| NanoHTTPD | 2.3.1 | Embedded HTTP server |
| OkHttp | 4.12.0 | HTTP client |
| Coil | 2.6.0 | Image loading in Compose |
| Media3 / ExoPlayer | 1.3.1 | Video and audio playback |
| ZXing Core | 3.5.3 | QR code generation |
| ZXing Android Embedded | 4.3.0 | QR code scanning |
| Jetpack Compose BOM | 2024.02.00 | UI framework |
| Material3 | — | Design system |
| androidx.documentfile | 1.0.1 | SAF document access |

---

## Build

Requires Android Studio Hedgehog or later, JDK 17.

```bash
./gradlew assembleDebug
```

Release build (minified + resource-shrunk):

```bash
./gradlew assembleRelease
```

---

## Permissions

| Permission | Reason |
|------------|--------|
| `INTERNET` | HTTP server and client |
| `FOREGROUND_SERVICE` | Keep server running in background |
| `FOREGROUND_SERVICE_DATA_SYNC` | Foreground service type for file transfers |
| `MANAGE_EXTERNAL_STORAGE` | Access any folder on Android 11+ |
| `READ/WRITE_EXTERNAL_STORAGE` | Legacy storage access on Android 9/10 |
| `POST_NOTIFICATIONS` | Transfer and server status notifications |
| `ACCESS_WIFI_STATE` | Detect local IP address |
| `ACCESS_NETWORK_STATE` | Network connectivity checks |
| `WAKE_LOCK` | Keep CPU active during transfers |

---

## Security

- Path traversal protection on all server endpoints via canonical path validation
- Basic Auth support (server-side enforcement, client-side credential storage)
- Cleartext HTTP traffic allowed only on local network (configured via `network_security_config.xml`)
# Hound---File-transfer
