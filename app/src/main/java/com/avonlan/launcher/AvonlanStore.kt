package com.avonlan.launcher

import android.content.Context

const val FAVORITE_SLOTS = 5

enum class TextSize(val scale: Float) {
    SMALL(0.85f),
    NORMAL(1f),
    LARGE(1.2f)
}

enum class HomeAlign { LEFT, CENTER, RIGHT }

data class AvonlanSettings(
    val themeMode: AvonlanThemeMode = AvonlanThemeMode.SYSTEM,
    val textSize: TextSize = TextSize.NORMAL,
    val align: HomeAlign = HomeAlign.CENTER,
    val clock24: Boolean = true,
    val showStatusBar: Boolean = true,
    val doubleTapLock: Boolean = false,
    val autoLaunch: Boolean = false
)

class AvonlanStore(context: Context) {

    private val sp = context.getSharedPreferences("avonlan_settings", Context.MODE_PRIVATE)

    private inline fun <reified T : Enum<T>> readEnum(key: String, default: T): T =
        runCatching { enumValueOf<T>(sp.getString(key, default.name) ?: default.name) }
            .getOrDefault(default)

    fun loadSettings() = AvonlanSettings(
        themeMode = readEnum("theme_mode", AvonlanThemeMode.SYSTEM),
        textSize = readEnum("text_size", TextSize.NORMAL),
        align = readEnum("align", HomeAlign.CENTER),
        clock24 = sp.getBoolean("clock24", true),
        showStatusBar = sp.getBoolean("show_status_bar", true),
        doubleTapLock = sp.getBoolean("double_tap_lock", false),
        autoLaunch = sp.getBoolean("auto_launch", false)
    )

    fun saveSettings(s: AvonlanSettings) {
        sp.edit()
            .putString("theme_mode", s.themeMode.name)
            .putString("text_size", s.textSize.name)
            .putString("align", s.align.name)
            .putBoolean("clock24", s.clock24)
            .putBoolean("show_status_bar", s.showStatusBar)
            .putBoolean("double_tap_lock", s.doubleTapLock)
            .putBoolean("auto_launch", s.autoLaunch)
            .apply()
    }

    fun loadFavorites(): List<String> {
        val raw = sp.getString("favorites", "") ?: ""
        val list = raw.split(",").toMutableList()
        while (list.size < FAVORITE_SLOTS) list.add("")
        return list.take(FAVORITE_SLOTS)
    }

    fun saveFavorites(list: List<String>) {
        sp.edit().putString("favorites", list.joinToString(",")).apply()
    }

    fun loadHidden(): Set<String> =
        sp.getStringSet("hidden", emptySet())?.toSet() ?: emptySet()

    fun saveHidden(set: Set<String>) {
        sp.edit().putStringSet("hidden", HashSet(set)).apply()
    }

    fun loadRenames(): Map<String, String> =
        sp.all
            .filterKeys { it.startsWith("rename_") }
            .mapKeys { it.key.removePrefix("rename_") }
            .mapValues { it.value.toString() }

    fun saveRename(pkg: String, name: String) {
        val editor = sp.edit()
        if (name.isBlank()) editor.remove("rename_$pkg")
        else editor.putString("rename_$pkg", name)
        editor.apply()
    }
}
