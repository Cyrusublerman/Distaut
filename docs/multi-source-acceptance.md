# Multi-source release acceptance

Version: 0.6.0-dev, version code 6. This is the first implementation increment from the full plan, covering graph generalisation, multiple assets and named outputs. It does not establish feature parity with Arcbrush or the other precedents.

## Try the build

1. Open a landscape photo. Use + IMAGE to add a portrait photo. Both source cards must appear on the graph.
2. Choose EXAMPLE. The graph connects the first photo to Blur and Edge Mask, the second to Posterise, and all three branches to Mix. Use FIT to frame it.
3. Inspect Mix. Set MIX B to 1. Disconnect MASK temporarily to see all of image B. Switch ALIGN IMAGE B between fit, fill, stretch and native; the output canvas keeps A's dimensions. Undo restores the mask wire and settings.
4. Choose EXPORT: Posterised, then EXPORT. The PNG must contain the second image branch at its own dimensions. Viewing Edge Mask must not change that export selection.
5. Choose EXPORT: Edge mask and export again. This PNG must be a greyscale mask at the first source's dimensions.
6. Select a source and use REPLACE IMAGE. Undo must restore both its metadata and rendered pixels. Redo must reinstate the replacement.
7. Save a portable project, close/reopen it, and check both sources, connections, named targets, target selection and node positions. A fresh app installation with the project should not need the original photo-provider paths.
8. Select a node, choose INSPECT and SAVE NAMED OUTPUT. Rename it, select it, remove it, then undo. The original Final target remains available.

## Automated evidence

- Model tests: source deletion and undo retention, explicit output-port validation, unique output names, named mask selection.
- Render tests: differently sized sources, fit/fill/stretch/native placement, per-source document dimensions, associated-alpha interpolation, selected mask extent, unreachable sources, legacy Mix compatibility.
- Persistence tests: both packaged images survive transfer, a corrupt second image cannot publish the first, old single-source archives still open.
- Editor tests: two sources, named export independent of viewer, replacement undo and portable reopen. Compose tests exercise canvas wiring, movement, responsive inspector and named-output controls.
- CI runs the complete module tests, Android lint, assembly, APK signature verification and 16 KiB alignment verification. Use the PR check for exact status, not this checklist as a pass claim.

## Remaining gates

Physical Android installation/upgrade, touch responsiveness, provider permission loss, OS process death, stylus use and memory ceilings need real-device checks. Later plan milestones add a float/colour engine, persistent graph caching, groups, richer image/mask/compositing nodes, painting, procedural tools, batch/time and optional remote/AI execution. The existing public development signing key is retained for upgrade continuity.
