package social.tbone.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import social.tbone.core.Log
import social.tbone.core.settings.ThemeMode
import social.tbone.desktop.app.AppCore
import social.tbone.desktop.app.launchAction
import social.tbone.desktop.ui.AppShell
import social.tbone.desktop.ui.BoneButton
import social.tbone.desktop.ui.BonyTheme
import social.tbone.desktop.ui.Type
import social.tbone.desktop.ui.fontBundled
import social.tbone.desktop.ui.colors
import java.io.File

/**
 * Desktop entry point.
 *
 * Upstream's entry was an Android `FragmentActivity` + `BonyApp` Application with
 * Hilt, lifecycle-aware locking, and notification channels. The desktop
 * equivalent: one window, one [AppCore] object graph, an optional PIN lock gate
 * in front of the shell, and the same data directory conventions.
 *
 * Data directory resolution (in order):
 *   1. `-Dtbone.dataDir=...`
 *   2. `$XDG_CONFIG_HOME/t-bone-desktop` (or `~/.config/t-bone-desktop`)
 *   3. `./t-bone-data` if the home directory is not writable
 */
fun main(args: Array<String>) {
    val dataDir = resolveDataDir(args)
    Log.initFileLogging(File(dataDir, "logs"))
    Log.i("Main", "T-Bone Desktop starting; data=${dataDir.absolutePath}")
    if (!fontBundled) {
        Log.w(
            "Main",
            "bundled JetBrains Mono faces were not found on the runtime classpath; " +
                "falling back to the platform monospace family. Text will still render, " +
                "but not in the shipped face. Check that build/resources/main is on the classpath.",
        )
    }

    val app = AppCore(dataDir)
    app.start()

    Runtime.getRuntime().addShutdownHook(Thread {
        Log.i("Main", "shutting down")
        app.stop()
    })

    application {
        Window(
            onCloseRequest = { app.stop(); exitApplication() },
            title = "T-Bone Desktop",
            state = rememberWindowState(width = 1280.dp, height = 860.dp),
        ) {
            val theme by app.settings.themeMode.collectAsState()
            val accent by app.settings.accentColor.collectAsState()
            var unlocked by remember { mutableStateOf(!app.settings.hasPin()) }

            BonyTheme(mode = theme, accent = accent) {
                Box(Modifier.fillMaxSize().background(colors.bg)) {
                    if (unlocked) {
                        AppShell(app, onLock = { unlocked = false })
                    } else {
                        LockScreen(app) { unlocked = true }
                    }
                }
            }
        }
    }
}

/** PIN gate — port of upstream's PinLockScreen for the desktop window. */
@Composable
private fun LockScreen(app: AppCore, onUnlock: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var attempts by remember { mutableStateOf(0) }

    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("T-BONE", style = Type.wordmark, color = colors.text)
        Spacer(Modifier.height(6.dp))
        Text("locked", style = Type.caption, color = colors.textMute)
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier
                .width(220.dp)
                .background(colors.surface)
                .padding(12.dp)
        ) {
            BasicTextField(
                value = pin,
                onValueChange = { if (it.length <= 64) pin = it },
                textStyle = Type.title.copyTextColor(colors.text),
                cursorBrush = SolidColor(colors.accent),
                singleLine = true,
                decorationBox = { inner ->
                    Box {
                        if (pin.isEmpty()) Text("PIN", style = Type.title, color = colors.textMute)
                        inner()
                    }
                },
            )
        }
        Spacer(Modifier.height(14.dp))
        BoneButton("unlock", primary = true) {
            when {
                app.settings.verifyPin(pin) -> {
                    pin = ""
                    error = null
                    onUnlock()
                }
                app.settings.isDuressPin(pin) -> {
                    app.scope.launchAction { app.wipeAll(); Result.success(Unit) }
                    error = "all local data wiped"
                    pin = ""
                }
                else -> {
                    attempts++
                    error = "wrong PIN (attempt $attempts)"
                    pin = ""
                }
            }
        }
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = Type.metaDim, color = colors.danger)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "PINs are verified locally. If you forget it, deleting ${app.dataDir.absolutePath} " +
                "resets the app and loses the encrypted notes.",
            style = Type.caption,
            color = colors.textMute,
        )
    }
}

private fun androidx.compose.ui.text.TextStyle.copyTextColor(c: androidx.compose.ui.graphics.Color) =
    androidx.compose.ui.text.TextStyle(
        fontFamily = fontFamily,
        fontWeight = fontWeight,
        fontSize = fontSize,
        lineHeight = lineHeight,
        letterSpacing = letterSpacing,
        color = c,
    )



private fun resolveDataDir(args: Array<String>): File {
    args.firstOrNull { it.startsWith("--data-dir=") }?.let {
        return File(it.removePrefix("--data-dir=")).apply { mkdirs() }
    }
    System.getProperty("tbone.dataDir")?.let {
        return File(it).apply { mkdirs() }
    }
    val xdg = System.getenv("XDG_CONFIG_HOME")
    val home = System.getProperty("user.home") ?: "."
    val base = if (!xdg.isNullOrBlank()) File(xdg) else File(home, ".config")
    val dir = File(base, "t-bone-desktop")
    dir.mkdirs()
    if (dir.canWrite()) return dir
    return File("t-bone-data").apply { mkdirs() }
}
