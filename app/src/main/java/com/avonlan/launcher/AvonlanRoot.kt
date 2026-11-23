package com.avonlan.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

private fun toUi(app: LauncherApp, renames: Map<String, String>) =
    UiApp(app.packageName, renames[app.packageName] ?: app.name)

@Composable
fun AvonlanRoot(
    allApps: List<LauncherApp>,
    renames: Map<String, String>,
    hidden: Set<String>,
    favorites: List<String>,
    settings: AvonlanSettings,
    homeTick: Int,
    actions: LauncherActions
) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    var pickSlot by remember { mutableStateOf<Int?>(null) }
    var menuApp by remember { mutableStateOf<UiApp?>(null) }
    var renameTarget by remember { mutableStateOf<UiApp?>(null) }

    LaunchedEffect(homeTick) {
        screen = Screen.HOME
        pickSlot = null
        menuApp = null
        renameTarget = null
    }

    val visibleApps = remember(allApps, renames, hidden) {
        allApps
            .filter { it.packageName !in hidden }
            .map { toUi(it, renames) }
            .sortedBy { it.label.lowercase() }
    }

    val hiddenApps = remember(allApps, renames, hidden) {
        allApps
            .filter { it.packageName in hidden }
            .map { toUi(it, renames) }
            .sortedBy { it.label.lowercase() }
    }

    val favApps = remember(favorites, visibleApps) {
        favorites.map { pkg ->
            if (pkg.isBlank()) null else visibleApps.firstOrNull { it.packageName == pkg }
        }
    }

    BackHandler(enabled = screen != Screen.HOME) {
        screen = Screen.HOME
        pickSlot = null
    }

    val homeGestures = Modifier
        .pointerInput(settings.doubleTapLock) {
            detectTapGestures(
                onDoubleTap = { if (settings.doubleTapLock) actions.lock() },
                onLongPress = { screen = Screen.SETTINGS }
            )
        }
        .pointerInput(Unit) {
            var total = 0f
            detectVerticalDragGestures(
                onDragStart = { total = 0f },
                onVerticalDrag = { _, amount -> total += amount },
                onDragEnd = {
                    if (total < -80f) {
                        pickSlot = null
                        screen = Screen.DRAWER
                    }
                },
                onDragCancel = { total = 0f }
            )
        }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when (screen) {
            Screen.HOME -> HomeScreen(
                settings = settings,
                favorites = favApps,
                onOpen = { actions.openApp(it) },
                onPick = { index ->
                    pickSlot = index
                    screen = Screen.DRAWER
                },
                onSettings = { screen = Screen.SETTINGS },
                modifier = homeGestures
            )

            Screen.DRAWER -> DrawerScreen(
                apps = visibleApps,
                settings = settings,
                pickMode = pickSlot != null,
                icons = actions.icons,
                onSelect = { app ->
                    val slot = pickSlot
                    if (slot != null) {
                        actions.setFavorite(slot, app.packageName)
                        pickSlot = null
                    } else {
                        actions.openApp(app.packageName)
                    }
                    screen = Screen.HOME
                },
                onLongPressApp = { app ->
                    if (pickSlot == null) menuApp = app
                },
                onClearSlot = {
                    val slot = pickSlot
                    if (slot != null) actions.setFavorite(slot, "")
                    pickSlot = null
                    screen = Screen.HOME
                }
            )

            Screen.SETTINGS -> SettingsScreen(
                settings = settings,
                hiddenApps = hiddenApps,
                actions = actions
            )
        }
    }

    menuApp?.let { app ->
        AlertDialog(
            onDismissRequest = { menuApp = null },
            title = { Text(app.label) },
            text = {
                Column {
                    MenuItem("Ganti nama") {
                        renameTarget = app
                        menuApp = null
                    }
                    MenuItem("Sembunyikan") {
                        actions.hideApp(app.packageName)
                        menuApp = null
                    }
                    MenuItem("Info app") {
                        actions.openInfo(app.packageName)
                        menuApp = null
                    }
                    MenuItem("Uninstall") {
                        actions.uninstall(app.packageName)
                        menuApp = null
                    }
                }
            },
            confirmButton = {}
        )
    }

    renameTarget?.let { app ->
        var text by remember(app.packageName) { mutableStateOf(app.label) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Ganti nama") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.renameApp(app.packageName, text)
                    renameTarget = null
                }) { Text("Simpan") }
            },
            dismissButton = {
                TextButton(onClick = {
                    actions.renameApp(app.packageName, "")
                    renameTarget = null
                }) { Text("Reset") }
            }
        )
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.fillMaxWidth())
    }
}
