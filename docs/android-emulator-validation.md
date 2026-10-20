# Phoenix Link Historical Runtime Validation

These are historical checks from before this companion was separated into its
own repository. They are not a fresh qualification of the current public APK.
See [release validation](releases/v0.3.0-preview.1.md) for the current checks.

## Scope

Local Android 15 x86_64 emulator, loopback TLS connection, synthetic gradients and
a moving test-pattern app. No physical phones or games were operated. Nothing was
published. Test APKs, credentials, screenshots and logs stay outside tracked files.
Validation date: 2026-10-13.

## Passing Checks

- Full .NET suite: 827 passed, seven opt-in Screen Studio live tests skipped,
  zero failures. This includes 47 focused LAN protocol and live TLS tests.
- Four Android JVM unit tests passed.
- Eight general emulator checks: Raw pixel roundtrip and controls; rejected pin/token
  followed by successful reconnect; Stop on the UI thread; overlay RGBA rendering,
  original/result toggle, Apply and Stop controls; immediate overlay cleanup;
  encrypted connection storage; portrait/landscape snapshots; single-app capture.
- Two real-Phoenix DLSS5 emulator checks: native-size frame exchange and live app capture.
  Disabled Phoenix DLSS5 returns exact original RGBA bytes. Enabled Phoenix DLSS5 changes
  output pixels at 257 x 255; one recorded run changed 173,668 bytes. The first
  enabled evaluation in that run took 1.44 seconds, not a steady-state benchmark.
- Windows Pyrowave encode/decode at 257 x 255 and 1280 x 720. Gradient RGB mean
  absolute error was 0.3815 and 0.3999 respectively on an 8-bit scale.

## Fixed During Testing

1. Windows Schannel rejected the self-signed certificate's ephemeral key. The
   server now imports its fresh certificate into a temporary user key container
   without persisting an identity file. Disposal releases the container.
2. Invalid session data escaped the server's connection error handler and stopped
   acceptance of new clients. Rejected authentication and malformed requests now
   close that connection while leaving the listener available.
3. Android Stop closed TLS from the UI thread; sending close-notify triggered
   `NetworkOnMainThreadException`. Cancellation now closes the underlying socket;
   TLS and native cleanup run on the network thread.
4. Overlay windows could outlive immediate startup cancellation because registration
   precedes view attachment. Cleanup now tracks registered windows, removes them
   immediately, tolerates repeated Stop, and ignores late rendered frames.
5. Codec startup errors now distinguish Vulkan device, encoder and decoder failure.

## Not Passing Or Not Proven

- Android Pyrowave is not certified. SwiftShader Vulkan 1.1 could not create the
  required device. Lavapipe Vulkan 1.3 created a device but rejected the encoder;
  its subgroup range is 8..8, while the pinned encoder requires 16, 32 or 64.
- An early host-graphics emulator exited during a Pyrowave attempt. Its cause was
  not established; subsequent checks used repeatable software rendering backends.
- Emulator checks do not establish Motorola GPU support, real-content quality,
  touch routing, sustained Wi-Fi bandwidth, heat, battery life or useful frame rate.
- Permission grants were automated for testing. Consent used Android's real
  single-app picker. Permission denial/revocation and long lifecycle stress remain
  additional work. Protected overlays remain protected in production; screenshot
  tests only unprotect the credential-free settings activity inside instrumentation.

## Live Face Evidence

An additional real-Phoenix DLSS5 capture test loads the original, unenhanced CoD face
image supplied externally to a separate Android test app. It uses
native 1080 x 2400 capture, Raw transport and one Phoenix DLSS5 evaluation per frame.
It saves the original screen, the live touch-through overlay (80% opacity), and
the actual returned 100% Phoenix DLSS5 frame. The face test and the eight general
emulator checks passed after correcting navigation-bar inset alignment.
The captured native-resolution Raw roundtrip was about 4.4 seconds per frame in
this emulator. This evidence is not a claim of fluid interaction or phone latency.

The test temporarily removes screenshot protection only from its known face
overlay, restores it before cleanup, and never enables that exception in normal
app operation. Comparison crops are identical and are not retouched.

This proves a selected app's face pixels make the roundtrip and are displayed.
It does **not** prove automatic following of other apps: the current MediaProjection
session remains pinned to the selected app. Follow-active-app capture has a
separate validation below.

Repeat the face test and compose its three real images:

```powershell
./scripts/Test-AndroidEmulator.ps1 -Serial emulator-5582 -Backend phoenix -PackageRoot <private-compatible-host-package> -FaceDemo -FaceImage <private-fixture.jpg>
./scripts/Make-AndroidFaceComparison.ps1 -EvidenceDirectory <private-test-output-directory>
```

## General Tests

### Physical Phone Pyrowave: 2026-10-14

The Adreno 735 driver reported subgroup operations `0xbf`: clustered reductions
(`0x40`) were missing. Subgroup sizes 64..128, full subgroups, size control,
compute-stage size selection, shaderInt16 and 8-bit storage were present.
The Android compatibility build uses equivalent shuffle reductions in Pyrowave's
quantization and packing shaders and keeps its other capability checks.

Local GPU encode/decode passed three consecutive frames at each of 257 x 255,
1280 x 720 and native 1220 x 2712. Mean absolute RGB error on the gradients was
0.58..0.65 levels. Malformed test envelopes were rejected. Warm native local
roundtrips took about 420 ms; this still includes CPU conversion and copying.

The real phone-to-PC TLS/Pyrowave roundtrip passed repeated 257 x 255 and
1280 x 720 frames with two-way gradient RGB error about 0.59..0.62. A public face
sample drawn into a native 1220 x 2712 frame was sent through the same connection
with Phoenix DLSS5 disabled, then enabled. 937,398 of 1,299,600 face-region pixels
changed by more than six summed RGB levels. This is not a quality score.
Returned PNGs were saved only for that test sample; no phone screen was captured.

Pyrowave selection is saved only after the end-to-end checks pass. Physical
follow-mode composition, sustained latency, thermal behavior, and further devices
still require acceptance testing. The stream format remains compatible with the
unchanged Windows codec.

### Follow Active App: 2026-10-14

The isolated Android 15 emulator passed Raw follow-window capture, switching to
another app, protected-window rejection, recovery, hiding output over Phoenix DLSS5's
own settings, floating original/result controls, Apply, and floating Stop cleanup.
The Android accessibility shortcut now opens the app while idle and expands its
floating controls while streaming. Start and Stop are at the top of the main app;
collapsed floating controls also retain Stop. Accessibility permission alone
does not start a streaming session.

The real Phoenix DLSS5 face test passed at native 1080 x 2400, one iteration, Raw,
100% output opacity. Floating controls disabled Phoenix DLSS5 and restored the original,
then re-enabled it and returned a new processed frame. In the paired captures,
1,221,983 of 1,658,880 interior pixels differed by more than six summed RGB levels.
This is evidence of different output, not a quality score or historical-model
parity claim. Returned-frame PNGs were visually inspected; no private phone
content was captured. Sustained performance and physical-phone follow-mode
composition remain hardware acceptance checks.

The screenshot conversion now copies hardware-backed Android screenshots into
CPU-readable bitmaps before drawing into a software canvas. Previously that
operation could fail without producing a frame. The test runner also rebinds its
emulator-only accessibility service after instrumentation force-stops the app;
it does not enable services on physical phones.

Repeat with `Test-AndroidEmulator.ps1 -Serial emulator-5582 -Follow`, or add
`-Backend phoenix -PackageRoot <compatible-package> -FaceDemo -FaceImage <private-fixture.jpg>` for actual model
output and the three `follow-*.png` captures. Test hosts use per-run directories
so they do not overwrite or stop a separate phone receiver.

With a disposable emulator already booted and the Android SDK configured:

```powershell
./scripts/Test-AndroidEmulator.ps1 -Serial emulator-5582
./scripts/Test-AndroidEmulator.ps1 -Serial emulator-5582 -Backend phoenix -PackageRoot <private-compatible-host-package>
```

Add `-Pyrowave` to attempt compressed Android streaming. It fails explicitly on an
unsupported driver; it is not silently counted as a pass or switched to Raw.
The runner removes pairing material and stops its host/apps. The caller owns the
emulator's lifetime. [Client build and limitations](../android/README.md).
