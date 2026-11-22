package com.avonlan.launcher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback

data class LauncherApp(
    val name: String,
    val packageName: String
)

data class UiApp(
    val packageName: String,
    val label: String
)

enum class Screen { HOME, DRAWER, SETTINGS }

class LauncherActions(
    val openApp: (String) -> Unit,
    val openInfo: (String) -> Unit,
    val uninstall: (String) -> Unit,
    val setFavorite: (Int, String) -> Unit,
    val hideApp: (String) -> Unit,
    val unhideApp: (String) -> Unit,
    val renameApp: (String, String) -> Unit,
    val lock: () -> Unit,
    val requestAdmin: () -> Unit,
    val isAdminActive: () -> Boolean,
    val openLauncherSettings: () -> Unit,
    val updateSettings: (AvonlanSettings) -> Unit
)

class MainActivity : ComponentActivity() {

    private lateinit var store: AvonlanStore

    private var allApps by mutableStateOf(emptyList<LauncherApp>())
    private var settings by mutableStateOf(AvonlanSettings())
    private var favorites by mutableStateOf(List(FAVORITE_SLOTS) { "" })
    private var hidden by mutableStateOf(emptySet<String>())
    private var renames by mutableStateOf(emptyMap<String, String>())
    private var homeTick by mutableIntStateOf(0)

    private val adminComponent by lazy {
        ComponentName(this, AvonlanAdminReceiver::class.java)
    }

    private val actions by lazy {
        LauncherActions(
            openApp = { openApp(it) },
            openInfo = { openAppInfo(it) },
            uninstall = { uninstallApp(it) },
            setFavorite = { slot, pkg -> setFavorite(slot, pkg) },
            hideApp = { hideApp(it) },
            unhideApp = { unhideApp(it) },
            renameApp = { pkg, name -> renameApp(pkg, name) },
            lock = { lockOrRequest() },
            requestAdmin = { requestAdmin() },
            isAdminActive = { isAdminActive() },
            openLauncherSettings = { safeStart(Intent(Settings.ACTION_HOME_SETTINGS)) },
            updateSettings = { updateSettings(it) }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        store = AvonlanStore(this)
        settings = store.loadSettings()
        favorites = store.loadFavorites()
        hidden = store.loadHidden()
        renames = store.loadRenames()

        addOnNewIntentListener { homeTick++ }

        setContent {
            val dark = when (settings.themeMode) {
                AvonlanThemeMode.SYSTEM -> isSystemInDarkTheme()
                AvonlanThemeMode.DARK -> true
                AvonlanThemeMode.LIGHT -> false
            }

            SideEffect {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }

            LaunchedEffect(settings.showStatusBar) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                if (settings.showStatusBar) {
                    controller.show(WindowInsetsCompat.Type.statusBars())
                } else {
                    controller.hide(WindowInsetsCompat.Type.statusBars())
                }
            }

            AvonlanTheme(mode = settings.themeMode) {
                AvonlanRoot(
                    allApps = allApps,
                    renames = renames,
                    hidden = hidden,
                    favorites = favorites,
                    settings = settings,
                    homeTick = homeTick,
                    actions = actions
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            allApps = withContext(Dispatchers.Default) { loadApps() }
        }
    }

    private fun loadApps(): List<LauncherApp> {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val self = packageName

        return packageManager.queryIntentActivities(intent, 0)
            .map {
                LauncherApp(
                    name = it.loadLabel(packageManager).toString(),
                    packageName = it.activityInfo.packageName
                )
            }
            .filter { it.packageName != self }
            .distinctBy { it.packageName }
    }

    private fun safeStart(intent: Intent) {
        runCatching { startActivity(intent) }
    }

    private fun openApp(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { safeStart(it) }
    }

    private fun openAppInfo(pkg: String) {
        safeStart(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        )
    }

    private fun uninstallApp(pkg: String) {
        safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
    }

    private fun updateSettings(new: AvonlanSettings) {
        settings = new
        store.saveSettings(new)
    }

    private fun setFavorite(slot: Int, pkg: String) {
        val list = favorites.toMutableList()
        if (slot in list.indices) list[slot] = pkg
        favorites = list
        store.saveFavorites(list)
    }

    private fun hideApp(pkg: String) {
        hidden = hidden + pkg
        store.saveHidden(hidden)
    }

    private fun unhideApp(pkg: String) {
        hidden = hidden - pkg
        store.saveHidden(hidden)
    }

    private fun renameApp(pkg: String, name: String) {
        val clean = name.trim()
        renames = if (clean.isEmpty()) renames - pkg else renames + (pkg to clean)
        store.saveRename(pkg, clean)
    }

    private fun isAdminActive(): Boolean {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        return dpm != null && dpm.isAdminActive(adminComponent)
    }

    private fun requestAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
        }
        safeStart(intent)
    }

    private fun lockOrRequest() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (dpm != null && dpm.isAdminActive(adminComponent)) {
            dpm.lockNow()
        } else {
            requestAdmin()
        }
    }
}

private fun toUi(app: LauncherApp, renames: Map<String, String>) =
    UiApp(app.packageName, renames[app.packageName] ?: app.name)

private fun <T> List<T>.cycle(current: T): T =
    this[(indexOf(current) + 1) % size]

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
                modifier = homeGestures
            )

            Screen.DRAWER -> DrawerScreen(
                apps = visibleApps,
                settings = settings,
                pickMode = pickSlot != null,
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

@Composable
fun HomeScreen(
    settings: AvonlanSettings,
    favorites: List<UiApp?>,
    onOpen: (String) -> Unit,
    onPick: (Int) -> Unit,
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
    }
}

private fun letterOf(label: String): Char {
    val c = label.trim().firstOrNull()?.uppercaseChar() ?: '#'
    return if (c.isLetter()) c else '#'
}

@Composable
fun DrawerScreen(
    apps: List<UiApp>,
    settings: AvonlanSettings,
    pickMode: Boolean,
    onSelect: (UiApp) -> Unit,
    onLongPressApp: (UiApp) -> Unit,
    onClearSlot: () -> Unit
) {
    val s = settings.textSize.scale
    val onBg = MaterialTheme.colorScheme.onBackground
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }

    val firstIndex = remember(filtered) {
        val map = LinkedHashMap<Char, Int>()
        filtered.forEachIndexed { i, app ->
            val c = letterOf(app.label)
            if (!map.containsKey(c)) map[c] = i
        }
        map
    }
    val letters = remember(firstIndex) { firstIndex.keys.toList() }
    val showBar = query.isBlank() && letters.size > 1

    var activeLetter by remember { mutableStateOf<Char?>(null) }
    var barHeight by remember { mutableIntStateOf(0) }

    val jump: (Float) -> Unit = { y ->
        if (letters.isNotEmpty() && barHeight > 0) {
            val i = (y / barHeight * letters.size).toInt().coerceIn(0, letters.size - 1)
            val l = letters[i]
            if (l != activeLetter) {
                activeLetter = l
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                firstIndex[l]?.let { idx -> scope.launch { listState.scrollToItem(idx) } }
            }
        }
    }

    LaunchedEffect(query) {
        listState.scrollToItem(0)
    }

    LaunchedEffect(query) {
        if (settings.autoLaunch && !pickMode && query.isNotBlank()) {
            delay(400)
            val matches = apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
            if (matches.size == 1) onSelect(matches[0])
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(24.dp))

        Text(
            if (pickMode) "PILIH APP FAVORIT" else "APPLICATIONS",
            fontSize = (18f * s).sp,
            color = onBg,
            fontWeight = FontWeight.Medium
        )

        if (pickMode) {
            TextButton(onClick = onClearSlot) {
                Text("Kosongkan slot ini", color = onBg.copy(alpha = 0.6f))
            }
        }

        Spacer(Modifier.height(16.dp))

        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            cursorBrush = SolidColor(onBg),
            textStyle = TextStyle(color = onBg, fontSize = (16f * s).sp),
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            "Search apps...",
                            fontSize = (16f * s).sp,
                            color = onBg.copy(alpha = 0.4f)
                        )
                    }
                    innerTextField()
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = if (showBar) 32.dp else 0.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    Text(
                        text = app.label,
                        fontSize = (18f * s).sp,
                        color = onBg,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(app.packageName) {
                                detectTapGestures(
                                    onTap = { onSelect(app) },
                                    onLongPress = { onLongPressApp(app) }
                                )
                            }
                            .padding(vertical = 12.dp)
                    )
                }
            }

            if (showBar) {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(32.dp)
                        .onSizeChanged { barHeight = it.height }
                        .pointerInput(letters) {
                            detectTapGestures(
                                onTap = { offset ->
                                    jump(offset.y)
                                    activeLetter = null
                                }
                            )
                        }
                        .pointerInput(letters) {
                            detectVerticalDragGestures(
                                onDragStart = { offset -> jump(offset.y) },
                                onVerticalDrag = { change, _ -> jump(change.position.y) },
                                onDragEnd = { activeLetter = null },
                                onDragCancel = { activeLetter = null }
                            )
                        },
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    letters.forEach { l ->
                        Text(
                            text = l.toString(),
                            fontSize = (11f * s).sp,
                            color = if (l == activeLetter) onBg else onBg.copy(alpha = 0.45f)
                        )
                    }
                }
            }

            activeLetter?.let { l ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(88.dp),
                    shape = MaterialTheme.shapes.large,
                    color = onBg.copy(alpha = 0.12f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(l.toString(), fontSize = 40.sp, color = onBg)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    settings: AvonlanSettings,
    hiddenApps: List<UiApp>,
    actions: LauncherActions
) {
    val s = settings.textSize.scale
    val onBg = MaterialTheme.colorScheme.onBackground

    fun onOff(value: Boolean) = if (value) "ON" else "OFF"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(24.dp))

        Text(
            "SETTINGS",
            fontSize = (18f * s).sp,
            fontWeight = FontWeight.Medium,
            color = onBg
        )

        Spacer(Modifier.height(16.dp))

        SettingRow("Tema", settings.themeMode.name, s) {
            actions.updateSettings(
                settings.copy(themeMode = AvonlanThemeMode.entries.cycle(settings.themeMode))
            )
        }

        SettingRow("Ukuran teks", settings.textSize.name, s) {
            actions.updateSettings(
                settings.copy(textSize = TextSize.entries.cycle(settings.textSize))
            )
        }

        SettingRow("Rata teks home", settings.align.name, s) {
            actions.updateSettings(
                settings.copy(align = HomeAlign.entries.cycle(settings.align))
            )
        }

        SettingRow("Format jam", if (settings.clock24) "24 JAM" else "12 JAM", s) {
            actions.updateSettings(settings.copy(clock24 = !settings.clock24))
        }

        SettingRow("Status bar", if (settings.showStatusBar) "TAMPIL" else "SEMBUNYI", s) {
            actions.updateSettings(settings.copy(showStatusBar = !settings.showStatusBar))
        }

        SettingRow("Double tap kunci layar", onOff(settings.doubleTapLock), s) {
            val enable = !settings.doubleTapLock
            actions.updateSettings(settings.copy(doubleTapLock = enable))
            if (enable && !actions.isAdminActive()) actions.requestAdmin()
        }

        Text(
            "Butuh izin Device Admin. Kalau mau uninstall AVONLAN, matiin dulu di Settings > Security > Device admin apps.",
            fontSize = (11f * s).sp,
            color = onBg.copy(alpha = 0.45f)
        )

        SettingRow("Auto-launch hasil search", onOff(settings.autoLaunch), s) {
            actions.updateSettings(settings.copy(autoLaunch = !settings.autoLaunch))
        }

        SettingRow("Ganti default launcher", "", s) {
            actions.openLauncherSettings()
        }

        if (hiddenApps.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))

            Text(
                "APP TERSEMBUNYI (tap buat tampilkan lagi)",
                fontSize = (12f * s).sp,
                color = onBg.copy(alpha = 0.6f)
            )

            hiddenApps.forEach { app ->
                SettingRow(app.label, "TAMPILKAN", s) {
                    actions.unhideApp(app.packageName)
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SettingRow(label: String, value: String, s: Float, onClick: () -> Unit) {
    val onBg = MaterialTheme.colorScheme.onBackground

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = (16f * s).sp, color = onBg)
        Text(value, fontSize = (14f * s).sp, color = onBg.copy(alpha = 0.6f))
    }
}
