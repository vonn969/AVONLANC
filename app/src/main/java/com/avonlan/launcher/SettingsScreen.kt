package com.avonlan.launcher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun <T> List<T>.cycle(current: T): T =
    this[(indexOf(current) + 1) % size]

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
