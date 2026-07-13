# Distaut

Distaut is the native Android tablet implementation of the DISTORT image-effect authoring system.

The SiteBoy DISTORT implementation is source lineage and behaviour evidence. Distaut owns Android interaction, project persistence, effect validation, mobile rendering, and later native/GPU acceleration.

## Current implementation

The repository begins with one complete interactive rendering path:

- Android Photo Picker image import;
- adaptive expanded/compact Compose editor;
- ordered effect stack model;
- declarative effect registry;
- deterministic greyscale effect;
- Kotlin sequential pixel renderer;
- preview cancellation and stale-result rejection;
- undoable project-state commands;
- JVM smoke tests for the model and renderer.

Project save/reopen and PNG export are the next vertical-slice tasks.

## Build

Open the repository in Android Studio with JDK 17. The project currently uses Gradle 8.9, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, compile SDK 35, and minimum SDK 26.

A Gradle wrapper binary is not included in this bootstrap because it could not be generated in the current execution environment. Generate it once with:

```bash
gradle wrapper --gradle-version 8.9
```

Then run:

```bash
./gradlew test assembleDebug
```

## Source lineage

- Source application: `Cyrusublerman/SiteBoy`
- PKL project: `03_PROJECTS/Tools/distort_android/`
- Product model: ordered deterministic effect stack, portable recipes, preview/final rendering, explicit effect semantics

## Repository boundary

Distaut does not copy the SiteBoy DOM, browser worker, WebGPU/WebGL, or fixed cache architecture. It ports product contracts and validated effect behaviour.
