# Implementation status

Updated: 2026-07-13

Implemented: multi-module Android/Compose structure, declarative effect registry, immutable project state, undo/redo, durable image import, autosave, project JSON, recipe v2, conservative SiteBoy v1 import, unsupported-node preservation, greyscale/invert/posterise Kotlin kernels, opacity composition, cancellation and full-resolution PNG export.

Validated: the pure Kotlin model, recipe, project-codec and renderer slice compiles with `kotlinc`; `smoke/SmokeTest.kt` passes.

Not yet validated: Gradle dependency resolution, Android compilation, emulator/tablet installation, process recreation, large-image memory behaviour and GitHub Actions.
