# Implementation status

Updated: 2026-10-12. Branch: `feat/node-graph-editor`.

## Node graph implemented

- The default workspace shows a real node canvas and image viewer. Nodes have persisted positions, typed image/mask sockets and editable wires. Touch-friendly input selection is also available in the inspector.
- Execution follows a validated directed acyclic graph, not the order of the effects list. Shared branches execute once per request; unreachable nodes are skipped and intermediate values are released after their last consumer.
- Mix has two image inputs and an optional mask. Luminance Mask, Edge Mask and Invert Mask produce explicit mask values.
- The viewer can inspect any image or mask. Export independently follows the designated image output.
- Connections, parameters, node positions, removal, output changes and the branching example are undoable. Missing sockets are permitted while authoring and reported when a requested output depends on them.
- Recipe v4 and project manifest v3 preserve topology and positions. Legacy ordered documents migrate to chains without upgrading their pixel algorithms.
- Graph memory preflight accounts for peak live branches plus scratch space. Cycles, incompatible ports, dangling references and mask export targets are rejected.
- CI verifies the APK signature and native library alignment in addition to tests, lint and assembly.

## Corrections retained from 0.4.0

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
- CI actions use Node 24 and pinned release commits on Ubuntu 24.04. The build uses Gradle 9.7.1, AGP 9.3.3, Kotlin/Compose compiler 2.4.21 and SDK Build Tools 36.0.0. Android modules have migrated to built-in Kotlin; wrapper scripts and the wrapper JAR are updated, and the distribution SHA-256 is pinned.

## Validation

The PR runs Kotlin/Android unit tests, lint and debug APK assembly. Regression fixtures cover tone reproduction, alpha, spatial scale and phase, cancellation/cache behaviour, gesture history, source metadata/pixel agreement, export snapshots, unresolved recipes, portable transfer, checksums, archive entries, PNG chunks and EXIF transforms. Refer to the PR checks for the exact current result.

Physical tablet responsiveness, stylus behaviour, lifecycle killing by a real OS, colour-managed displays and maximum-image memory limits still require device validation. Robolectric tests do not replace those checks.

## Explicit limits and subsequent work

The graph is functional but deliberately small: six existing image effects plus four graph utilities, one source, and one final output. Groups, multiple sources, vector fields, batch outputs, general region/tile execution and GPU acceleration remain subsequent work. Cross-request downstream caching is not yet implemented; shared branches are memoised within each render. Canvas layout changes are persisted without reprocessing pixels. The graph can expand to fill the workspace. See [reference assessment](node-editor-references.md).

Full-resolution images that exceed available memory are rejected with a smaller-export instruction; streaming PNG does not make the image-processing kernels themselves tiled. Downsampled dither/quantisation remains approximate even when coordinate units agree. Use full-resolution inspection where the memory budget permits.

Processing remains encoded sRGB RGBA8. The corrected alpha contract is versioned; this release does not silently switch legacy recipes to linear-light processing.
