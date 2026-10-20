# Third-party notices

The companion's original code is under the [MIT license](LICENSE), separated from
[DLSS5 Everything](https://github.com/RazvanManolache/DLSS5-Everything). Existing
copyright notices are retained. Dependencies keep their respective licenses.

## Native codec distributed in the APK

| Component | Pinned revision | License |
| --- | --- | --- |
| [Pyrowave](https://github.com/Themaister/pyrowave) | `a0d2e71e98b85f6a23f62407df21a39e9745b875` | [MIT](source/codec/licenses/Pyrowave.txt) |
| [Granite](https://github.com/Themaister/Granite), including muglm | `fb178c8080d163419e8d20f10715c61c53c1ec9b` | [MIT](source/codec/licenses/Granite.txt) |
| [Volk](https://github.com/zeux/volk) | `47cddf7ed97b94118a08aacb548a411188e016cc` | [MIT](source/codec/licenses/Volk.txt) |
| [Vulkan-Headers](https://github.com/KhronosGroup/Vulkan-Headers) | `6802bb4733b63ed5efd3adb308a6c885ef180ea1` | [Upstream declarations](source/codec/licenses/Vulkan-Headers.txt), [Apache-2.0](source/codec/licenses/Apache-2.0.txt) and [MIT](source/codec/licenses/MIT.txt) |

See the retained [copyright and revision index](source/codec/licenses/NOTICE.txt).
The wrapper adds bounded frame envelopes, RGBA conversion and JNI integration.
Coefficient validation follows the upstream bitstream specification and algorithm.
Android builds generate a local shader/capability variant for compatible mobile
subgroup operations; `PyrowaveMobile.cmake` records those changes. The pinned
upstream checkout is not edited. Steam is not required; no Steam client code is included.

The APK contains these notices in its assets. The build also copies the selected
Android NDK's `NOTICE` and `NOTICE.toolchain` into APK assets for its native runtime
and toolchain components. Preserve applicable notices when redistributing.

## Build and test dependencies

- Gradle wrapper scripts/JAR: Apache-2.0; original script notices and the complete
  [license text](source/codec/licenses/Apache-2.0.txt) are retained. Gradle 8.11.1
  is downloaded and checked against the wrapper's pinned SHA-256.
- Android Gradle Plugin 8.5.2, SDK/NDK and CMake are obtained externally under their
  respective upstream terms. They are not bundled as SDKs in this repository.
- JUnit 4.13.2 and Hamcrest: test-only dependencies, not in the release APK.
- The optional Windows emulator-test host references a separately checked-out
  DLSS5 Everything project; it is not compiled into the Android application.

## External PC processing

[DLSS5 Phoenix](https://github.com/RazvanManolache/dlss5-phoenix-addon) runs in the
separate Windows host. This repository and APK do not distribute its inference
runtime, NVIDIA DLLs, models, weights, games or captured content. Those components
retain their own licenses and terms. These notices grant no rights to vendor models.

The fictional README image is reused from DLSS5 Everything; see its
[provenance](docs/demo-image.md). It contains no personal phone data.
