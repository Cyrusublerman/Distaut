# Node editor references and implementation direction

Reviewed 2026-10-12. This assessment distinguishes documented capabilities from code inspected; these desktop/web applications have not been built or benchmarked on the Android tablet. No third-party source has been copied into this change.

## Decision

Use a real typed, non-destructive graph as Distaut's document and execution model, with a touch-oriented canvas as its primary editor. Retain Kotlin/Compose and the tested CPU pixel kernels for this first Android implementation. A node canvas alone is insufficient: branching, merging, masks, independent viewing and export, persistence and undo must follow the same topology.

The current implementation delivers that foundation. It does not claim feature or performance parity with the references. Desktop GPU timings cannot be carried over to a mobile GPU, and interface appearance is not evidence of how an application was developed.

## Arcbrush

The [official product page](https://arcbrush.com/) describes a native C++ desktop application with typed pins, downstream re-evaluation, per-node viewing, viewer locking, A/B comparison, portable graphs and CPU/GPU execution. Its broader brush, deformation, batch and colour-management features set a useful long-term product benchmark.

Apply now: visible typed connections, separate viewer/export selection, branch-and-merge examples, fit/arrange and persistent canvas positions. Next: avoid processing on layout edits, cache unchanged branches within a strict memory budget, improve connection feedback, then add searchable nodes, reroutes and groups. Brush authoring and scene-referred float colour require their own document and pixel contracts.

## Shadow

[Shadow](https://github.com/glenzli/shadow) is a development-preview photography workspace with RAW, masks, local adjustments and shared human/AI editing services. Its README states that headless coverage and some platform acceptance remain incomplete. The architecture is much larger than the node panel alone: C++ image services, Rust domain/persistence layers and Qt/QML desktop interaction.

Its most useful lesson is keeping original assets, edit recipes, previews, history and export consistent. Distaut should preserve that separation while using a free-form image-processing graph. Shadow is not a drop-in Android canvas. Its README identifies GPL-3.0-or-later licensing.

## Node IP

[Node IP](https://github.com/bhagatsaurabh/node-ip) is a compact JavaScript editor with image/channel sockets and a Layer node that accepts multiple inputs. The inspected Layer implementation illustrates dynamic blend inputs and only schedules output rendering when connected to that output.

Apply the clear socket and merge interaction, while keeping Android touch targets and explicit cycle/type validation. Browser canvas code is not a native Compose component. Its repository identifies Apache licensing.

## Pictonode

[Pictonode](https://github.com/TeamPictonode/pictonode) separates GIMP and web frontends from processing infrastructure. Its shared node library defines templates, links, metadata, an output target and serialisation, illustrating why the graph should be independent of the UI.

The inspected shared TypeScript library still contains an unfinished node-unlink implementation; the README also says the hosted web app is unavailable. It is an architectural reference rather than a ready-to-adopt production engine. Its README identifies AGPLv3 licensing.

## Cascade

[Cascade](https://github.com/ttddee/Cascade) documents Qt/C++, Vulkan image processing, 32-bit linear colour, OpenImageIO/OpenColorIO, batch processing and GLSL/ISF effects. It provides a useful target for the later rendering architecture, particularly explicit colour and shader contracts.

Retaining a backend-independent graph now permits later CPU/GPU implementations. Porting its desktop UI, Vulkan resource management and image I/O would be a separate project. Float colour and GPU execution must be validated against reference pixels rather than added as untested substitutions. Repository metadata identifies GPLv3 licensing.

## PyImageCUDA Studio

[PyImageCUDA Studio](https://github.com/offerrall/pyimagecuda-studio) documents a visual editor and headless project API, parameter validation and reusable variables. It requires NVIDIA hardware and uses Python/PySide6 with a separate CUDA engine. Its README still lists undo/redo and memory pooling as roadmap work, despite broader performance claims.

Apply the separation between editable graph, reusable template and execution request. Future Distaut batch processing should reuse the same renderer and file format. CUDA is not a backend for the target Android tablet. Its repository identifies MIT licensing.

## Acceptance for the current Android change

- A source fans out into independently adjustable image branches and a mask branch.
- Mix consumes both images and the mask; list order does not determine rendering.
- Inspecting a mask does not change the exported image.
- Graph links and positions survive recipe/project round trips and undo/redo.
- Cycles and incompatible sockets are rejected; missing required inputs produce a clear render error.
- Shared ancestors execute once per render; memory checks account for live branches.
- Existing ordered projects preserve their pixel algorithms when migrated.

Subsequent priorities are bounded incremental caching, more useful image/mask/generator nodes, multiple source assets, tiled execution, GPU parity, groups and template/batch authoring. Physical tablet gesture and memory checks remain necessary.
