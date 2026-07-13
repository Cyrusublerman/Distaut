# Distaut

Native Android tablet implementation of the DISTORT deterministic image-effect authoring system.

This repository owns the Android application, editor interface, project persistence, recipe compatibility, rendering backends and device validation. The SiteBoy DISTORT implementation remains the browser source-lineage reference.

The current bootstrap implements a narrow vertical slice: declarative effect definitions, ordered project state, undo/redo commands, an adaptive Compose editor shell, Android image import, a cancellable Kotlin CPU renderer and a deterministic greyscale fixture.
