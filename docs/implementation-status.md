# Implementation status

Updated: 2026-10-09. Branch: `fix/editor-correctness-and-persistence`.

## Corrections implemented

- Source asset, decoded bitmap and pixel buffer are bound together. Source-changing undo/redo reopens the matching asset. Superseded source loads and initial restoration cannot replace a newer request.
- Export captures one immutable project and uses the full enabled stack independently of the preview-through target. Export/self-check do not invalidate preview generations.
- Spatial parameters use oriented document pixels. Preview approximation is labelled; full-resolution detail, zoom, pan and a before/after wipe are available.
- Corrected Bayer dithering, associated-alpha filtering and continuous RGBA opacity are versioned. Existing unversioned recipes retain their legacy image arithmetic until explicitly upgraded.
- Blur uses sliding-window sums. Source conversion is reused, conversion scratch is one row, prefix caching is bounded, and PNG encoding streams rows without another full-resolution bitmap.
- Source loading chooses preview dimensions within a conservative memory budget. Full-resolution jobs exceeding the budget fail before allocation; reduced export sizes remain selectable.
- Autosave has serial ownership, immutable snapshots and unique atomic temporary writes. Opening a project checkpoints it; foreground/background lifecycle transitions request a checkpoint.
- New `.distaut` projects package a manifest and source image with a checked checksum. Old project JSON and recipes remain readable; source replacement/relinking remains undoable.
- Unresolved effects retain an explicit envelope and their original payload, so type-name collisions cannot accidentally resolve v1 nodes.
- BitmapFactory fallback handles all eight EXIF orientations and updates oriented dimensions.
- Slider gestures form one undo transaction. Integer/decimal/choice/toggle controls, numeric entry, reset, import warning details and explicit algorithm updates are available.
- Touch controls have larger layout targets; short-window layout no longer sums incompatible minimum heights. Diagnostics and recipe file actions live under Tools.
- Explicit storage cleanup preserves current/undo/redo sources and warns that old JSON-only projects may need relinking. No automatic asset garbage collection occurs.
- CI no longer requests Android's removed `tools` SDK package.

## Validation

The PR runs Kotlin/Android unit tests, lint and debug APK assembly. Regression fixtures cover tone reproduction, alpha, spatial scale and phase, cancellation/cache behaviour, gesture history, source metadata/pixel agreement, export snapshots, unresolved recipes, portable transfer, checksums, archive entries, PNG chunks and EXIF transforms. Refer to the PR checks for the exact current result.

Physical tablet responsiveness, stylus behaviour, lifecycle killing by a real OS, colour-managed displays and maximum-image memory limits still require device validation. Robolectric tests do not replace those checks.

## Explicit limits and subsequent work

The effect pipeline remains a linear stack. Typed graph execution, branching/masks, groups, a node canvas, vector fields, general region/tile execution and GPU acceleration are separate architectural features, not included in this corrective change.

Full-resolution images that exceed available memory are rejected with a smaller-export instruction; streaming PNG does not make the image-processing kernels themselves tiled. Downsampled dither/quantisation remains approximate even when coordinate units agree. Use full-resolution inspection where the memory budget permits.

Processing remains encoded sRGB RGBA8. The corrected alpha contract is versioned; this release does not silently switch legacy recipes to linear-light processing.
