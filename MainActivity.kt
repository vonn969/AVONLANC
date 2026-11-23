package com.avonlan.launcher

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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp

data class LauncherApp(
    val name: String,
    val packageName: String
)

data class UiApp(
    val packageName: String,
    val label: String
)

enum class Screen { HOME, DRAWER, SETTINGS }

class IconSource(
    val cached: (String) -> ImageBitmap?,
    val load: (String) -> ImageBitmap?
)

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
    val updateSettings: (AvonlanSettings) -> Unit,
    val icons: IconSource
)

class MainActivity : ComponentActivity() {

    private lateinit var store: AvonlanStore

    private var allApps by mutableStateOf(emptyList<LauncherApp>())
    private var settings by mutableStateOf(AvonlanSettings())
    private var favorites by mutableStateOf(List(FAVORITE_SLOTS) { "" })
    private var hidden by mutableStateOf(emptySet<String>())
    private var renames by mutableStateOf(emptyMap<String, String>())
    private var homeTick by mutableIntStateOf(0)

    private val iconCache = ConcurrentHashMap<String, ImageBitmap>()

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
            updateSettings = { updateSettings(it) },
            icons = IconSource(
                cached = { iconCache[it] },
                load = { loadIcon(it) }
            )
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

    private fun loadIcon(pkg: String): ImageBitmap? {
        iconCache[pkg]?.let { return it }
        val bitmap = runCatching {
            packageManager.getApplicationIcon(pkg).toBitmap(108, 108).asImageBitmap()
        }.getOrNull()
        if (bitmap != null) iconCache[pkg] = bitmap
        return bitmap
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

private fun letterOf(label: String): Char {
    val c = label.trim().firstOrNull()?.uppercaseChar() ?: '#'
    return if (c in 'A'..'Z') c else '#'
}

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

@Composable
private fun AppIcon(pkg: String, icons: IconSource, size: Dp) {
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
private fun AppCell(
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
private fun AppRow(
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
private fun LetterHouse(
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
private fun AlphabetBar(
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

@Composable
fun DrawerScreen(
    apps: List<UiApp>,
    settings: AvonlanSettings,
    pickMode: Boolean,
    icons: IconSource,
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
    val density = LocalDensity.current

    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(220),
        label = "drawerIn"
    )

    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }

    val sections = remember(filtered, query) {
        if (query.isNotBlank()) {
            listOf('\u0000' to filtered)
        } else {
            filtered
                .groupBy { letterOf(it.label) }
                .toSortedMap(compareBy<Char> { if (it == '#') Char.MAX_VALUE else it })
                .map { (letter, list) -> letter to list }
        }
    }

    val firstIndex = remember(sections) {
        val map = LinkedHashMap<Char, Int>()
        var index = 0
        sections.forEach { (letter, list) ->
            map[letter] = index
            index += 1 + (list.size + 1) / 2
        }
        map
    }
    val letters = remember(firstIndex) { firstIndex.keys.toList() }
    val showBar = query.isBlank() && letters.size > 1

    var dragging by remember { mutableStateOf(false) }
    var shownLetter by remember { mutableStateOf<Char?>(null) }
    var touchY by remember { mutableFloatStateOf(0f) }

    val houseAlpha by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = tween(150),
        label = "houseAlpha"
    )

    LaunchedEffect(query) {
        dragging = false
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
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 48.dp.toPx()
            }
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

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val count = letters.size
            val itemH = if (count > 0) minOf(20.dp, maxHeight / count) else 20.dp
            val barPx = with(density) { (itemH * count).toPx() }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - houseAlpha }
                    .padding(end = if (showBar) 56.dp else 0.dp)
            ) {
                sections.forEach { (letter, list) ->
                    if (letter != '\u0000') {
                        item(key = "header_$letter") {
                            Text(
                                text = letter.toString(),
                                fontSize = (16f * s).sp,
                                fontWeight = FontWeight.Medium,
                                color = onBg.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)
                            )
                        }
                    }

                    items(list.chunked(2), key = { it[0].packageName }) { pair ->
                        AppRow(
                            pair = pair,
                            s = s,
                            icons = icons,
                            onTap = { onSelect(it) },
                            onLong = { onLongPressApp(it) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }

            val houseLetter = shownLetter
            val houseApps = sections.firstOrNull { it.first == houseLetter }?.second

            if (showBar && houseAlpha > 0.01f && houseLetter != null && houseApps != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(end = 88.dp, top = 8.dp)
                        .graphicsLayer { alpha = houseAlpha }
                ) {
                    LetterHouse(
                        letter = houseLetter,
                        apps = houseApps,
                        s = s,
                        icons = icons
                    )
                }
            }

            if (showBar) {
                AlphabetBar(
                    letters = letters,
                    activeLetter = shownLetter,
                    dragging = dragging,
                    touchY = touchY,
                    itemH = itemH,
                    s = s,
                    onTouch = { y ->
                        val itemPx = barPx / count
                        val index = (y / itemPx).toInt().coerceIn(0, count - 1)
                        val l = letters[index]
                        touchY = y.coerceIn(0f, barPx)
                        dragging = true
                        if (l != shownLetter) {
                            shownLetter = l
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            firstIndex[l]?.let { idx ->
                                scope.launch { listState.scrollToItem(idx) }
                            }
                        }
                    },
                    onRelease = { dragging = false },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )

                if (houseAlpha > 0.01f && houseLetter != null) {
                    val barTop = (maxHeight - itemH * count) / 2
                    val bubbleY = barTop + with(density) { touchY.toDp() } - 24.dp

                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-76).dp, y = bubbleY)
                            .size(48.dp)
                            .graphicsLayer { alpha = houseAlpha },
                        shape = CircleShape,
                        color = onBg.copy(alpha = 0.14f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(houseLetter.toString(), fontSize = 22.sp, color = onBg)
                        }
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
