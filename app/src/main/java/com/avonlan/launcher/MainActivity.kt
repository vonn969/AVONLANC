package com.avonlan.launcher

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
