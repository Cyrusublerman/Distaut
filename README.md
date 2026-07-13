# Distaut

Distaut is the native Android tablet implementation of the DISTORT image-effect authoring system.

The SiteBoy DISTORT implementation remains source lineage and behaviour evidence. Distaut owns Android interaction, persistence, recipe compatibility, mobile rendering and device validation.

## Current vertical slice

- Android Photo Picker image import with EXIF-aware orientation;
- durable source retention with app-managed fallback copies;
- adaptive Jetpack Compose editor layouts;
- ordered effect stacks with bypass, solo, reorder and removal;
- declarative effect definitions and generated integer controls;
- undo and redo;
- autosave and restoration;
- project save and reopen;
- recipe v2 save/load and conservative SiteBoy recipe v1 import;
- full-resolution PNG export;
- cancellable preview rendering with stale-result rejection;
- Kotlin CPU reference effects: greyscale, invert, posterise, ordered dither, pixelate and box blur.

## Toolchain

- JDK 17
- Gradle 8.9 through the committed Gradle wrapper
- Android Gradle Plugin 8.7.3
- Kotlin 2.0.21
- compile SDK 35
- minimum SDK 26

## Build

From the repository root:

```bash
./gradlew test :app:lintDebug :app:assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Continuous integration

The Android build workflow runs for pull requests and pushes to `main`. It:

1. verifies the committed Gradle wrapper;
2. provisions JDK 17 and Android API 35;
3. runs JVM and Android unit tests across all current modules;
4. runs Android lint;
5. assembles the debug APK;
6. uploads the APK and diagnostic reports as workflow artifacts.

The full workflow has been validated on GitHub Actions: wrapper verification, unit tests, lint, debug assembly and artifact upload all pass.

## Repository boundary

Distaut does not copy SiteBoy's DOM, browser-worker, WebGPU/WebGL or fixed-cache architecture. It ports product contracts and validated effect behaviour into an Android-specific application architecture.
