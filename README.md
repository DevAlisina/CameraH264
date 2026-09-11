# H.264 Camera Streamer for Android 14

An Android application compatible with **Android 14 (API 34)** that requests Camera permissions, encodes live video in real-time using hardware-accelerated **H.264 (AVC/x264)** via `MediaCodec`, and streams the compressed video stream directly to a network port.

Designed with automated **GitHub Actions CI/CD** so you do **not** need to compile on your local machine—pushing to GitHub automatically compiles the APK and attaches it to the **Releases** tab for direct download.

---

## Features

- **Android 14 Ready**: Fully configured for Android 14 (Target SDK 34, Compile SDK 34) with modern `ActivityResultContracts` runtime permission handling.
- **Hardware-Accelerated H.264**: Uses Android's `MediaCodec` with zero-copy Camera2 input surfaces (`COLOR_FormatSurface`) for low CPU usage, high frame rates (30 FPS), and low latency.
- **Dual Streaming Modes**:
  - **Server Mode (Default)**: The phone listens on a specified port (e.g. `8080`). Viewers (PC, VLC, ffplay, scripts) connect to the phone's IP and port. Supports multiple simultaneous clients.
  - **Client (Push) Mode**: The phone connects to a remote server/port (e.g. `192.168.1.100:8080`) and pushes the H.264 stream.
- **Instant Client Sync**: Caches SPS/PPS (Sequence & Picture Parameter Sets) and automatically requests an IDR Keyframe whenever a new client connects, eliminating video corruption and startup delay.
- **Camera Controls**:
  - Toggle between Back and Front cameras.
  - Configurable resolutions (1080p, 720p, 480p).
  - Configurable bitrates (1 Mbps, 2 Mbps, 4 Mbps, 8 Mbps).
- **Live Metrics Dashboard**: Real-time FPS, bitrate, transferred data counters, and connected client counts.

---

## How to Push to GitHub & Download Compiled APK

Now that your local repository is initialized, follow these steps to push to your GitHub account:

### 1. Repository Info
Remote origin is configured for: `git@github.com:DevAlisina/CameraH264.git`

### 2. Push to GitHub
Run the following command inside this repository directory (`/root/CameraH264Streamer`):

```bash
cd /root/CameraH264Streamer
git push -u origin main
```

### 3. Automatic Compilation & Releases
As soon as you push to `main` (or push a tag like `git tag v1.0.0 && git push origin v1.0.0`):
1. GitHub Actions automatically starts the workflow defined in `.github/workflows/build-and-release.yml`.
2. It sets up JDK 17, installs Gradle 8.5, and compiles:
   - `CameraH264Streamer-debug.apk` (Signed with Android debug key, ready to install directly).
   - `CameraH264Streamer-release-unsigned.apk`.
3. Go to the **Releases** tab on your GitHub repository page:
   - Click on the latest release.
   - Download **`CameraH264Streamer-debug.apk`** and install it directly onto your Android 14 phone!
4. You can also view the workflow progress anytime under the **Actions** tab on GitHub.

---

## How to View the Stream on PC / Mac / Linux

### Option 1: View via `ffplay` (Recommended - Lowest Latency)
Open your terminal on your PC (ensure PC and phone are on the same Wi-Fi or LAN network):

```bash
ffplay -fflags nobuffer -flags low_delay -framedrop -f h264 tcp://<PHONE_IP>:8080
```
*(Replace `<PHONE_IP>` with the IP displayed in the app, e.g., `192.168.1.50`)*

### Option 2: View via VLC Media Player
1. Open VLC on your PC.
2. Go to **Media** -> **Open Network Stream...** (or `Ctrl + N`).
3. Enter URL:
   ```
   tcp/h264://<PHONE_IP>:8080
   ```
4. Click **Play**.

### Option 3: View via USB Cable (No Wi-Fi Needed, Zero Latency)
If your phone is connected via USB with USB Debugging enabled:
```bash
# 1. Forward the port from Android device to your computer's localhost
adb forward tcp:8080 tcp:8080

# 2. Play stream from localhost
ffplay -fflags nobuffer -flags low_delay -f h264 tcp://127.0.0.1:8080
```

### Option 4: Save Stream to File Using FFmpeg
```bash
ffmpeg -i tcp://<PHONE_IP>:8080 -c copy output.mp4
```

---

## Architecture & Code Structure

```
CameraH264Streamer/
├── .github/workflows/
│   └── build-and-release.yml    # Automated CI/CD workflow for GitHub compilation & releases
├── app/
│   ├── build.gradle.kts         # App module config (targetSdk 34, minSdk 26, Java 17)
│   └── src/main/
│       ├── AndroidManifest.xml  # Permissions (Camera, Internet, Network state)
│       ├── java/com/camerastreamer/app/
│       │   ├── MainActivity.kt               # UI handling & Android 14 permissions
│       │   ├── camera/
│       │   │   └── CameraCaptureManager.kt   # Camera2 capture to preview and encoder surfaces
│       │   ├── encoder/
│       │   │   └── H264Encoder.kt            # Hardware AVC/x264 MediaCodec encoder
│       │   ├── network/
│       │   │   ├── H264Server.kt             # Multi-client TCP server
│       │   │   ├── H264ClientSender.kt       # TCP push sender
│       │   │   └── NetworkUtils.kt           # Local IP detection & formatters
│       │   └── ui/
│       │       └── AutoFitTextureView.kt     # Aspect-ratio preserving TextureView
│       └── res/                              # Layouts, vector drawables, themes, strings
├── build.gradle.kts             # Root Gradle build script
├── settings.gradle.kts          # Repository & project inclusions
└── README.md
```
