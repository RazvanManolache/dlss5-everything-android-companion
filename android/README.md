# Phoenix Link for Android

Implementation preview for **Android 14+ / ARM64**. Raw capture and real
Phoenix DLSS5 output have passed emulator tests, including follow-active-app capture.
ARM64 installation, encrypted pairing persistence and Raw/Pyrowave LAN roundtrips
have also been checked on a phone. Broad device compatibility and sustained performance
are not certified. See the [validation scope](../docs/android-emulator-validation.md).

Phoenix Link sends an app's screen to a PC running DLSS5 Everything, receives the
Phoenix DLSS5 result, and displays it in an overlay. Phoenix DLSS5 inference stays on the PC.
Both **Raw RGBA** and **Pyrowave** transport are implemented in the client and server.
The same pinned native codec bridge is compiled for Windows and Android.

## Local Setup

1. Use the updated Windows app with `Runtime/codec/dlss5-pyrowave.exe` and
   `Runtime/codec/phoenix-pyrowave.dll` alongside its normal runtime. Download the
   normal ReShade/Phoenix DLSS5 components and select a compatible model on the PC.
2. Open the PC's **Streamer** page, select its private IPv4 address and **Start streamer**.
   Restrict any firewall exception to the client and your private network.
3. Install the signed ARM64 APK from this project's GitHub Releases on the phone.
   It uses a dedicated release identity; it is not a Play Store release. A local
   debug build uses a different key and may need uninstalling before this update.
4. Enter the PC address and port, then paste its certificate SHA-256 fingerprint
   and pairing token. The PC's **Export pairing** file can serve as a private
   reference; the Android UI currently has no file importer. The PC generates new
   credentials every time its server starts.
5. Choose **Single app** or **Follow active app**. Single-app mode needs **Overlay
   permission**; follow mode needs the optional **Accessibility permission** instead.
   Select **Pyrowave** or **Raw RGBA**, the capture long
   edge, frame-rate limit and presentation mode. The default is native capture
   (4096-pixel long-edge cap), 15 FPS and Pyrowave at a nominal 100 Mbit/s ceiling
   per direction. Smaller capture sizes are explicit bandwidth/processing tradeoffs.
6. Tap **Start session** at the top. For Single app, grant notification permission
   if desired and select **one app** in Android's screen-sharing picker, not the
   entire display. For Follow active app, approve the disclosure and start follow
   mode. Merely enabling Accessibility does not start streaming.
7. Open the floating panel to toggle original/result visibility, change Phoenix DLSS5
   controls, apply the staged settings, or stop. Stop is available even when the
   floating panel is collapsed. The notification and main app also have Stop
   controls. Sliders do not send updates until **Apply settings**. If Android's
   accessibility shortcut is assigned to Phoenix DLSS5, it opens the app while idle
   and expands the controls during an active session.

Connection data is saved encrypted with Android Keystore, excluded from backups.
The app does not log tokens, store incoming/outgoing images, send analytics or
forward touch input to the PC. Forget connection removes the saved pairing data;
stopping the PC server revokes its token. Android's secure-window flags prevent
screenshots of credentials and output overlays.

## Overlay Boundaries

Android 14's app-only sharing isolates the selected app from other app windows.
Full-display sharing cannot reliably exclude our own overlay through the public
API: it can produce black/obscured capture or feedback. The app warns before the
picker and protects its output windows; it cannot programmatically prove that the
user selected an app instead of a whole display. Whole-display overlay enhancement
is **not** supported by this preview. DRM/secure surfaces cannot be captured.

**Touch-through** caps overlay opacity at Android's maximum permitted obscuring
opacity (normally 80%). The result is blended with the underlying app so touches
can pass through. It is not a fully opaque replacement. **View only** permits an
opaque result but consumes touches over the output; use the floating or notification
Stop control to return. System restrictions are not bypassed with accessibility,
input injection, hidden APIs or root.

**Follow active app** uses Android 14's window-screenshot API, which excludes
accessibility overlays from the captured window. Its trusted accessibility
overlay can show the result at 100% opacity while leaving app touches unchanged.
It pauses for protected windows, lock screens, system permission UI and Phoenix DLSS5's
own settings. Each window change invalidates older results; each window keeps its
physical placement. Accessibility access alone does not send any pixels.

The result is aspect-fitted to the display, not calibrated to arbitrary split-screen
window coordinates. Rotation resizes the existing virtual display/surface and
opens a fresh network/codec session; it does not reuse a projection consent token
to create a second virtual display. OEM behavior and exact framing need validation.

## Transport

See the [LAN protocol](https://github.com/RazvanManolache/DLSS5-Everything/blob/main/docs/lan-frame-service.md). Android uses protocol v2 with
explicit codec negotiation. The Python reference client remains a v1/raw example.

Pyrowave uses Vulkan GPU encoding/decoding on **both** endpoints, full-range BT.709
4:4:4 YUV and opaque SDR output. The phone needs compatible Vulkan features for
both encode and decode; Android version or chipset marketing alone is not proof.
Initialization is checked when starting a Pyrowave session. Failure is shown rather
than silently falling back. Select Raw explicitly if necessary.

The Android build includes a GPU compatibility path for drivers that support
subgroup shuffles but not clustered reductions. It compiles the two affected
upstream encoder shaders with 8/16-lane shuffle reductions, retaining the normal
shaders on GPUs with native clustered support. The pinned upstream checkout is
not edited, and the stream format stays unchanged. Other required GPU features
are still checked. Android builds now also need the NDK's host `glslc` shader tool.

On an Adreno 735 phone, local encode/decode passed at 257 x 255, 1280 x 720 and
1220 x 2712, including repeated frames and malformed-envelope rejection.
Two-way Wi-Fi tests passed against the existing Windows codec; a generated face
frame at native phone resolution also passed through real Phoenix DLSS5 inference and
returned different output. This is not a smooth-streaming or broad-device claim.

This first bridge uses CPU RGBA/YUV conversion and CPU upload/readback around the
GPU codec. It is **not zero-copy** and upstream sub-millisecond codec figures are
not end-to-end latency claims for this app. Phoenix DLSS5 inference, frame copies,
two compression passes, network traffic and Android composition all add latency.
The bitrate setting is a ceiling derived per frame from the selected FPS, not
adaptive congestion control. Each encoded frame is additionally capped at 8 MiB
of codec budget and 16 MiB of encoded envelope; the actual bitrate can be lower.
Native GPU calls on Android are synchronous and are not forcibly interruptible;
network transactions have a 130-second watchdog, including blocked socket writes.
Stop closes networking immediately, while native cleanup waits for outstanding
GPU work. A GPU-driver hang may require Android's Force stop.

Raw sends four bytes/pixel in each direction. For example, 1280 x 720 at 15 FPS
requires about 442 Mbit/s each way before overhead. Wi-Fi is a shared medium, so
two-way traffic, interference, battery consumption and heat matter. No Wi-Fi
throughput or sustained-FPS guarantee is made.

Capture is sampled at the chosen maximum FPS. The app retains only the latest
unsent capture and at most one submitted request; it does not accumulate a delayed
video queue. Every accepted/submitted frame awaits a result or a terminal error.
Dimension changes, failure or Stop can abort an in-flight frame; there is no replay
or exactly-once recovery. No audio streaming, camera capture, input forwarding,
automatic discovery, screen recording or unattended reconnect is implemented.
The phone adds a 32 MiB RGBA frame limit to bound Java heap use (3840 x 2160 fits),
even though the server allows 64 MiB. Choose a smaller capture size if needed.

## Build Without Running

Prerequisites: JDK 17, Android SDK platform 35, NDK `27.3.13750724`, SDK CMake
`3.22.1`, and Git. Set `JAVA_HOME` and `ANDROID_HOME` to your installed tools.

```powershell
./scripts/Build-Android.ps1
```

The dependency script checks out pinned upstream sources into ignored `.build-deps`.
The debug APK is copied to `local-build/PhoenixLink/PhoenixLink-preview.apk`.
The build-only script compiles artifacts/test sources; it does not execute tests
or initialize GPUs. See [full build and signing instructions](../docs/BUILD.md).
The wrapper, codec and dependency licenses accompany the APK. App source is
under the repository's MIT license; models/vendor runtimes are obtained separately.

## Emulator Validation

Android 15 x86_64 emulator checks passed for Raw frame roundtrips, live controls,
certificate/token rejection and recovery, UI-thread Stop, encrypted connection
storage, overlay RGBA rendering and visibility, immediate cleanup, portrait and
landscape UI snapshots, and the real single-app capture consent flow.
These are historical runtime checks, not a rerun of every check for each APK.
The current release's JVM/build results are recorded in its release notes.

The emulator also completed a real PC Phoenix DLSS5 roundtrip at 257 x 255: disabled
returned the original bytes exactly; enabled returned changed pixels without
resizing. Live app capture through the real backend also passed. These checks use
a synthetic moving test image, not a claim about visual quality in phone games.

Windows Pyrowave encode/decode passed at 257 x 255 and 1280 x 720, with RGB mean
absolute error below 0.41 on an 8-bit gradient. Android Pyrowave could not initialize
on SwiftShader Vulkan 1.1 or Lavapipe Vulkan 1.3; it reported actionable errors.
Lavapipe created a Vulkan device but could not initialize the encoder: its reported
subgroup size range is 8..8, while this upstream encoder requires 16, 32 or 64.
This is **not** an Android Pyrowave end-to-end pass or Motorola compatibility proof.

Repeat with an already-booted, disposable emulator, never a physical device:

```powershell
./scripts/Test-AndroidEmulator.ps1 -Serial emulator-5582
./scripts/Test-AndroidEmulator.ps1 -Serial emulator-5582 -Backend phoenix -PackageRoot <private-compatible-host-package>
```

The script builds a separate x86_64 APK, installs test-only instrumentation, and
uses a loopback-only host. It grants overlay/notification permissions in that
emulator, drives the real capture picker, and removes test credentials and stops
its apps/host when finished. Private results remain under ignored `local-build`;
it does not shut down an emulator it did not start. Add `-Pyrowave` to explicitly
attempt the GPU codec test; unsupported drivers fail that check rather than pass.
The default ARM64 phone APK remains separately built with `Build-Android.ps1`.

## Still Pending On Hardware

- Broader device installation, overlay and notification permission flows.
- Encoder/decoder support and color/malformed-frame checks on additional GPUs.
- Raw/Pyrowave end-to-end phone output and visual quality with real content.
- Single-app capture exclusion, original/result toggling, touch routing and opacity.
- Rotation, target visibility, task switching, lock screen and permission revocation.
- Stop during startup/transmission/inference, reconnect, process death and cleanup.
- UI screenshots across orientation/font scale, memory, thermal and sustained latency.

## Sources

- [Pyrowave source and Android build](https://github.com/Themaister/pyrowave)
- [Granite](https://github.com/Themaister/Granite), [Volk](https://github.com/zeux/volk), [Vulkan-Headers](https://github.com/KhronosGroup/Vulkan-Headers)
- [Android MediaProjection](https://developer.android.com/media/grow/media-projection)
- [Android overlay touch restrictions](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_NOT_TOUCHABLE)
- [Gradle](https://github.com/gradle/gradle)
- [Retained license notices](../source/codec/licenses/NOTICE.txt)
