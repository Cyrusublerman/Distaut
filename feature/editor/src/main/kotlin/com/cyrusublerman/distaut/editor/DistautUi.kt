package com.cyrusublerman.distaut.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val UiBackground = Color(0xFFF5F2EA)
internal val UiText = Color(0xFF171717)
internal val UiMuted = Color(0xFFD8D3C8)
internal val UiBorder = Color(0xFF171717)
internal val UiInverseText = Color(0xFFF5F2EA)

internal val F = 14.dp
internal val ToolbarHeight = 48.dp
internal val StatusHeight = 2 * F
internal val SidebarWidth = 30 * F
internal val NodeHeight = 48.dp
internal val BorderWidth = 1.dp

@androidx.compose.runtime.Composable
internal fun ToolCell(
    label: String,
    width: Dp,
    enabled: Boolean = true,
    active: Boolean = false,
    alignStart: Boolean = false,
    onClick: () -> Unit,
) {
    val background = when {
        active -> UiText
        enabled -> UiBackground
        else -> UiMuted
    }
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(background)
            .border(BorderWidth, UiBorder, RectangleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = F),
        contentAlignment = if (alignStart) Alignment.CenterStart else Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) UiInverseText else UiText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@androidx.compose.runtime.Composable
internal fun StatusStrip(
    state: EditorUiState,
    diagnosticCount: Int,
    clear: () -> Unit,
) {
    val text = when {
        state.error != null -> "ERROR · ${state.error}"
        state.operationInProgress -> state.message ?: "WORKING"
        state.rendering -> "RENDERING PREVIEW"
        state.message != null -> state.message
        else -> {
            if (state.stalePreview) "LAST SUCCESSFUL PREVIEW · CURRENT SETTINGS NOT RENDERED"
            else if (state.saved) "READY · PROJECT SAVED" else "READY · UNSAVED CHANGES"
        }
    }
    val inverted = state.error != null
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = StatusHeight)
            .background(if (inverted) UiText else UiBackground)
            .border(BorderWidth, UiBorder, RectangleShape)
            .clickable(onClick = clear)
            .padding(horizontal = F),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            color = if (inverted) UiInverseText else UiText,
            fontSize = 12.sp,
            maxLines = 3,
        )
    }
}

@androidx.compose.runtime.Composable
internal fun BlockTitle(title: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(StatusHeight)
            .background(UiBackground)
            .border(BorderWidth, UiBorder, RectangleShape)
            .padding(horizontal = F),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@androidx.compose.runtime.Composable
internal fun PartitionButton(
    label: String,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(ToolbarHeight)
            .background(
                when {
                    active -> UiText
                    enabled -> UiBackground
                    else -> UiMuted
                }
            )
            .border(BorderWidth, UiBorder, RectangleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = F),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            label,
            color = if (active) UiInverseText else UiText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@androidx.compose.runtime.Composable
internal fun QuietMessage(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ToolbarHeight)
            .border(BorderWidth, UiBorder, RectangleShape)
            .padding(F),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, fontSize = 10.sp)
    }
}

@androidx.compose.runtime.Composable
internal fun KeyValue(label: String, value: String) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(ToolbarHeight)) {
        val labelWidth = 9 * F
        val availableWidth = maxWidth
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .width(labelWidth)
                    .fillMaxHeight()
                    .border(BorderWidth, UiBorder, RectangleShape)
                    .padding(horizontal = F),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                Modifier
                    .width((availableWidth - labelWidth).coerceAtLeast(8 * F))
                    .fillMaxHeight()
                    .border(BorderWidth, UiBorder, RectangleShape)
                    .padding(horizontal = F),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(value, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun Viewport(state: EditorUiState, modifier: Modifier, viewModel: EditorViewModel) {
    var zoom by remember(state.sourceBitmap, state.fullDetail) { mutableFloatStateOf(1f) }
    var pan by remember(state.sourceBitmap) { mutableStateOf(Offset.Zero) }
    var wipe by remember { mutableFloatStateOf(.5f) }
    Column(modifier.clipToBounds().background(UiBackground)) {
        Row(Modifier.fillMaxWidth().height(ToolbarHeight)) {
            TextButton(onClick = { zoom = 1f; pan = Offset.Zero; viewModel.setFullDetail(false) }, enabled = !state.operationInProgress) { Text("FIT") }
            TextButton(onClick = { zoom = 1f; pan = Offset.Zero; viewModel.setFullDetail(true) }, enabled = !state.operationInProgress) { Text("100%") }
            TextButton(onClick = { viewModel.setCompare(!state.compare) }) { Text(if (state.compare) "CLOSE A/B" else "A/B") }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            val bitmap = if (state.showSource) state.sourceBitmap else state.renderedBitmap
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            val fit = if (bitmap != null) minOf(widthPx / bitmap.width, heightPx / bitmap.height).coerceAtLeast(.0001f) else 1f
            val nativeScale = if (state.fullDetail) 1f / fit else 1f
            val displayScale = zoom * nativeScale
            Canvas(Modifier.fillMaxSize()) {
                val cell = 16.dp.toPx()
                for (y in 0..(size.height / cell).toInt()) for (x in 0..(size.width / cell).toInt()) {
                    drawRect(if ((x + y) % 2 == 0) UiBackground else UiMuted,
                        topLeft = Offset(x * cell, y * cell), size = Size(cell, cell))
                }
            }
            Box(Modifier.fillMaxSize().pointerInput(bitmap, nativeScale) {
                detectTransformGestures { _, drag, scale, _ ->
                    zoom = (zoom * scale).coerceIn(.1f, 32f)
                    val limitX = widthPx * maxOf(1f, zoom * nativeScale)
                    val limitY = heightPx * maxOf(1f, zoom * nativeScale)
                    pan = Offset((pan.x + drag.x).coerceIn(-limitX, limitX), (pan.y + drag.y).coerceIn(-limitY, limitY))
                }
            }, contentAlignment = Alignment.Center) {
                if (bitmap == null) Text(if (state.project.source == null) "OPEN AN IMAGE" else "SOURCE UNAVAILABLE · USE TOOLS TO RELINK", Modifier.padding(F))
                else {
                    Image(bitmap.asImageBitmap(), "Image output", Modifier.fillMaxSize().graphicsLayer {
                        scaleX = displayScale; scaleY = displayScale; translationX = pan.x; translationY = pan.y
                    }, contentScale = ContentScale.Fit)
                    if (state.compare && state.sourceBitmap != null) {
                        Image(state.sourceBitmap.asImageBitmap(), "Original image on the left", Modifier.fillMaxSize()
                            .drawWithContent { clipRect(right = size.width * wipe) { this@drawWithContent.drawContent() } }
                            .graphicsLayer { scaleX = displayScale; scaleY = displayScale; translationX = pan.x; translationY = pan.y },
                            contentScale = ContentScale.Fit)
                    }
                }
                if (state.rendering || state.operationInProgress) CircularProgressIndicator(Modifier.width(3 * F), color = UiText)
            }
        }
        if (state.compare) Slider(wipe, onValueChange = { wipe = it }, Modifier.fillMaxWidth().height(48.dp))
        Text(
            when {
                state.stalePreview -> "LAST SUCCESSFUL RESULT"
                state.fullDetail -> "FULL RESOLUTION · PINCH TO ZOOM"
                else -> "FIT PREVIEW · SPATIAL EFFECTS APPROXIMATED"
            }, Modifier.fillMaxWidth().padding(horizontal = F), fontSize = 11.sp, maxLines = 2,
        )
    }
}

internal operator fun Int.times(value: Dp): Dp = value * this
