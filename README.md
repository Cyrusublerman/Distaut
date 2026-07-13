# Distaut

Distaut is the native Android tablet implementation of the DISTORT image-effect authoring system.

The SiteBoy DISTORT implementation is source lineage and behaviour evidence. Distaut owns Android interaction, project persistence, recipe compatibility, effect validation, mobile rendering and later native/GPU acceleration.

## Current implementation

The current vertical slice includes Android image import, durable source retention, adaptive Compose layouts, ordered effect stacks, declarative controls, undo/redo, autosave, project save/reopen, recipe v2, conservative SiteBoy v1 import, Kotlin CPU rendering for greyscale/invert/posterise, opacity composition and full-resolution PNG export.

Unknown or semantically incompatible recipe nodes are retained as disabled opaque nodes rather than silently discarded.

## Validation

The pure Kotlin model, recipe, project-codec and renderer sources compile with `kotlinc`, and the standalone smoke suite passes. The full Android build still requires Gradle/Android Studio or GitHub Actions validation.
