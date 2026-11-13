package social.tbone.desktop.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import social.tbone.core.Log
import social.tbone.desktop.ui.BoneButton
import social.tbone.desktop.ui.Type
import social.tbone.desktop.ui.colors
import java.awt.Desktop
import java.io.File

/**
 * Desktop-only interactions that have no Android equivalent.
 *
 * Android used intents for "open in browser" and the MediaStore for saving files.
 * On the JVM the equivalents are `java.awt.Desktop` and an AWT file dialog, both
 * wrapped here so the UI layer stays free of AWT specifics.
 */
object DesktopUx {

    fun openExternal(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(java.net.URI(url))
            } else {
                // No registered browser (headless/server): say so instead of failing silently.
                Log.w("DesktopUx", "no browser available to open $url")
            }
        }.onFailure { Log.w("DesktopUx", "could not open $url: ${it.message}") }
    }

    /** Native "save as" dialog; returns the chosen file or null if cancelled. */
    fun chooseSaveFile(parentTitle: String, suggestedName: String): File? {
        return runCatching {
            val dialog = java.awt.FileDialog(
                null as java.awt.Frame?,
                "Save image",
                java.awt.FileDialog.SAVE,
            )
            dialog.file = suggestedName
            dialog.isVisible = true
            val dir = dialog.directory
            val name = dialog.file
            if (dir == null || name == null) null else File(dir, name)
        }.getOrNull()
    }

    fun openInFileManager(file: File) {
        runCatching {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file.parentFile ?: file)
        }
    }
}

/** Shared error surface: nothing fails silently in the UI. */
@Composable
fun ErrorDialog(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SOMETHING WENT WRONG", style = Type.button, color = colors.danger) },
        text = { Text(message, style = Type.bodyDim, color = colors.text) },
        confirmButton = { BoneButton("dismiss", onClick = onDismiss) },
        containerColor = colors.surface,
    )
}
