package com.cyrusublerman.distaut.editor

import androidx.compose.foundation.Image
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
internal val ToolbarHeight = 3 * F
internal val StatusHeight = 2 * F
internal val SidebarWidth = 30 * F
internal val NodeHeight = 3 * F
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
            val render = state.lastRenderDurationMillis
                ?.let { " · ${"%.1f".format(it)} MS" }
                .orEmpty()
            "READY · REV ${state.project.revision} · " +
                "${state.project.effects.size} NODES$render · LOG $diagnosticCount"
        }
    }
    val inverted = state.error != null
    Box(
        Modifier
            .fillMaxWidth()
            .height(StatusHeight)
            .background(if (inverted) UiText else UiBackground)
            .border(BorderWidth, UiBorder, RectangleShape)
            .clickable(onClick = clear)
            .padding(horizontal = F),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text.uppercase(),
            color = if (inverted) UiInverseText else UiText,
            fontSize = 10.sp,
            maxLines = 1,
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

@androidx.compose.runtime.Composable
internal fun Viewport(state: EditorUiState, modifier: Modifier) {
    BoxWithConstraints(
        modifier
            .background(UiBackground)
            .border(BorderWidth, UiBorder, RectangleShape)
    ) {
        val imageHeight = (maxHeight - StatusHeight).coerceAtLeast(8 * F)
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(imageHeight)
                    .background(UiMuted),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = if (state.showSource) state.sourceBitmap
                else state.renderedBitmap
                if (bitmap == null) {
                    Text(
                        "NO SOURCE\nSELECT AN IMAGE FROM THE TOP BAR",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Image(
                        bitmap.asImageBitmap(),
                        if (state.showSource) "Source image" else "Rendered output",
                        Modifier.fillMaxSize().padding(F),
                        contentScale = ContentScale.Fit,
                    )
                }
                if (state.rendering || state.operationInProgress) {
                    CircularProgressIndicator(
                        Modifier.width(3 * F),
                        color = UiText,
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(StatusHeight)
                    .background(UiBackground)
                    .border(BorderWidth, UiBorder, RectangleShape)
                    .padding(horizontal = F),
                contentAlignment = Alignment.CenterStart,
            ) {
                val source = state.project.source
                Text(
                    buildString {
                        append(if (state.showSource) "SOURCE" else "OUTPUT")
                        append(" · ")
                        append(source?.displayName ?: "NO SOURCE")
                        source?.let { append(" · ${it.width} × ${it.height}") }
                        state.lastRenderDurationMillis?.let {
                            append(" · ${"%.1f".format(it)} MS")
                        }
                    },
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

internal operator fun Int.times(value: Dp): Dp = value * this
