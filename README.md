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
- portable `.distaut` project save and reopen, including source assets;
- versioned recipe v3 save/load, v2 read compatibility and conservative SiteBoy recipe v1 import;
- snapshot-based PNG export with full-resolution or bounded sizes;
- document-space spatial parameters and labelled approximate previews;
- full-resolution detail, pan/zoom, before/after wipe and numeric adjustment;
- one undo step per slider gesture;
- cancellable preview rendering with stale-result rejection;
- versioned Kotlin CPU reference effects: greyscale, invert, posterise, ordered dither, pixelate and box blur.

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

## Project and recipe compatibility

Use SAVE to create a portable `.distaut` ZIP package containing the source image and project manifest. Existing JSON-only projects still open, but their original source must remain accessible or be relinked through Tools. Recipes are image-independent and now use schema 3. Older builds must not be used to edit new projects/recipes.

Old effects retain their legacy algorithm and composition semantics. Select **UPDATE TO CORRECTED ALGORITHM** in an effect to adopt the corrected dither/alpha behaviour; this is undoable. Newly added effects use corrected algorithms by default. Unresolved imported effects stay disabled and preserve their original payload through saves.

**VIEW** previews the stack through an effect. Export always uses the complete enabled stack. Spatial sizes are in original, oriented image pixels; fit previews approximate operations that do not commute with downsampling. Use **100%** for full-resolution inspection when memory permits.

Full-resolution processing has a conservative allocation preflight. If an image exceeds the budget, select a smaller maximum export size in Canvas. PNG encoding is streamed, but the kernels still process an in-memory image. See [implementation status](docs/implementation-status.md) for remaining architectural and device-validation work.
