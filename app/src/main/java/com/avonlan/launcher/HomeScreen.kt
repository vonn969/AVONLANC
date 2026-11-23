package com.avonlan.launcher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    settings: AvonlanSettings,
    favorites: List<UiApp?>,
    onOpen: (String) -> Unit,
    onPick: (Int) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier
) {
    val s = settings.textSize.scale
    val onBg = MaterialTheme.colorScheme.onBackground

    val hAlign: Alignment.Horizontal = when (settings.align) {
        HomeAlign.LEFT -> Alignment.Start
        HomeAlign.CENTER -> Alignment.CenterHorizontally
        HomeAlign.RIGHT -> Alignment.End
    }
    val tAlign = when (settings.align) {
        HomeAlign.LEFT -> TextAlign.Start
        HomeAlign.CENTER -> TextAlign.Center
        HomeAlign.RIGHT -> TextAlign.End
    }

    var time by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }

    LaunchedEffect(settings.clock24) {
        val timeFmt = SimpleDateFormat(
            if (settings.clock24) "HH:mm" else "hh:mm a",
            Locale.getDefault()
        )
        val dateFmt = SimpleDateFormat("EEEE, dd MMMM", Locale.getDefault())
        while (true) {
            val now = Date()
            time = timeFmt.format(now)
            date = dateFmt.format(now)
            delay(60_000L - System.currentTimeMillis() % 60_000L + 50L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(modifier)
            .systemBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = hAlign
    ) {
        Text(
            time,
            fontSize = (76f * s).sp,
            fontWeight = FontWeight.Light,
            textAlign = tAlign,
            color = onBg
        )

        Spacer(Modifier.height(4.dp))

        Text(
            date,
            fontSize = (14f * s).sp,
            textAlign = tAlign,
            color = onBg.copy(alpha = 0.6f)
        )

        Spacer(Modifier.height(40.dp))

        favorites.forEachIndexed { index, app ->
            Text(
                text = app?.label ?: "+",
                fontSize = (22f * s).sp,
                fontWeight = FontWeight.Light,
                textAlign = tAlign,
                color = onBg.copy(alpha = if (app == null) 0.3f else 1f),
                modifier = Modifier
                    .pointerInput(app?.packageName) {
                        detectTapGestures(
                            onTap = {
                                if (app != null) onOpen(app.packageName) else onPick(index)
                            },
                            onLongPress = { onPick(index) }
                        )
                    }
                    .padding(vertical = 8.dp)
            )
        }

        Spacer(Modifier.height(32.dp))

        Text(
            "SETTINGS",
            fontSize = (12f * s).sp,
            color = onBg.copy(alpha = 0.45f),
            modifier = Modifier
                .clickable(onClick = onSettings)
                .padding(12.dp)
        )
    }
}
