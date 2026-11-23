package com.avonlan.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.exp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AppIcon(pkg: String, icons: IconSource, size: Dp) {
    var bitmap by remember(pkg) { mutableStateOf(icons.cached(pkg)) }

    LaunchedEffect(pkg) {
        if (bitmap == null) {
            bitmap = withContext(Dispatchers.Default) { icons.load(pkg) }
        }
    }

    Box(modifier = Modifier.size(size)) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
internal fun AppCell(
    app: UiApp,
    s: Float,
    icons: IconSource,
    onTap: () -> Unit,
    onLong: () -> Unit,
    modifier: Modifier = Modifier
) {
    val onBg = MaterialTheme.colorScheme.onBackground

    Row(
        modifier = modifier
            .pointerInput(app.packageName) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLong() }
                )
            }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app.packageName, icons, 36.dp)
        Spacer(Modifier.width(14.dp))
        Text(
            text = app.label,
            fontSize = (14f * s).sp,
            color = onBg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
internal fun AppRow(
    pair: List<UiApp>,
    s: Float,
    icons: IconSource,
    onTap: (UiApp) -> Unit,
    onLong: (UiApp) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth()) {
        pair.forEach { app ->
            AppCell(
                app = app,
                s = s,
                icons = icons,
                onTap = { onTap(app) },
                onLong = { onLong(app) },
                modifier = Modifier.weight(1f)
            )
        }
        if (pair.size == 1) Spacer(Modifier.weight(1f))
    }
}

@Composable
internal fun LetterHouse(
    letter: Char,
    apps: List<UiApp>,
    s: Float,
    icons: IconSource,
    modifier: Modifier = Modifier
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val anim = remember(letter) { Animatable(0f) }

    LaunchedEffect(letter) {
        anim.animateTo(1f, tween(160))
    }

    Column(
        modifier = modifier.graphicsLayer {
            alpha = anim.value
            translationY = (1f - anim.value) * 14.dp.toPx()
        }
    ) {
        Text(
            text = letter.toString(),
            fontSize = (16f * s).sp,
            fontWeight = FontWeight.Medium,
            color = onBg.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        apps.chunked(2).forEach { pair ->
            AppRow(
                pair = pair,
                s = s,
                icons = icons,
                onTap = {},
                onLong = {}
            )
        }
    }
}

@Composable
internal fun AlphabetBar(
    letters: List<Char>,
    activeLetter: Char?,
    dragging: Boolean,
    touchY: Float,
    itemH: Dp,
    s: Float,
    onTouch: (Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val density = LocalDensity.current
    val itemPx = with(density) { itemH.toPx() }
    val ampPx = with(density) { 36.dp.toPx() }
    val wave by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = tween(180),
        label = "wave"
    )

    Column(
        modifier = modifier
            .width(72.dp)
            .height(itemH * letters.size)
            .pointerInput(letters) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onTouch(down.position.y)
                    drag(down.id) { change ->
                        onTouch(change.position.y)
                        change.consume()
                    }
                    onRelease()
                }
            },
        horizontalAlignment = Alignment.End
    ) {
        letters.forEachIndexed { i, l ->
            val center = (i + 0.5f) * itemPx
            val d = (touchY - center) / itemPx
            val shift = ampPx * wave * exp(-(d * d) / 6f)
            val isActive = dragging && l == activeLetter

            Box(
                modifier = Modifier
                    .height(itemH)
                    .width(24.dp)
                    .offset { IntOffset(-shift.toInt(), 0) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = l.toString(),
                    fontSize = (12f * s).sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    color = if (isActive) onBg else onBg.copy(alpha = 0.55f)
                )
            }
        }
    }
}
