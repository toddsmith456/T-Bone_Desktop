package social.tbone.desktop.ui

import kotlinx.coroutines.launch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import social.tbone.core.store.NoteKind
import social.tbone.core.store.NoteUi
import social.tbone.desktop.app.AppCore
import social.tbone.desktop.app.copyToClipboard
import social.tbone.desktop.app.launchAction

/**
 * Encrypted notes — port of upstream's Notes tab.
 *
 * Preserved: AES-256-GCM at rest with only ciphertext + IV written to disk, the
 * "encrypted" badge shown *only after* a real encrypt→decrypt round trip passes
 * (upstream's `selfTest`), folders, pinning, and manual reordering.
 *
 * The desktop layout puts the note list in a middle column and the editor in the
 * remaining space, rather than navigating to a separate editor screen.
 */
@Composable
fun NotesScreen(app: AppCore) {
    val notes by app.notes.notes.collectAsState()
    val folders by app.notes.folders.collectAsState()
    val stats by app.notes.stats.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var newFolder by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { app.notes.load() }

    val selected = notes.firstOrNull { it.id == selectedId } ?: notes.firstOrNull()

    Row(Modifier.fillMaxSize()) {
        // ── List column ───────────────────────────────────────────────────────
        Column(
            Modifier
                .width(280.dp)
                .fillMaxHeight()
                .background(colors.surface)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("NOTES", style = Type.title, color = colors.text)
                    Spacer(Modifier.weight(1f))
                    if (app.notes.encryptionVerified) Pill("encrypted", colors.accent)
                    else Pill("crypto check failed", colors.danger)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${stats.noteCount} notes · ${stats.checklistCount} checklists · ${stats.totalCiphertextBytes} B ciphertext",
                    style = Type.caption, color = colors.textMute,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BoneButton("+ note", primary = true) {
                        app.scope.launchAction {
                            app.notes.create(NoteKind.NOTE).let { selectedId = it.id }
                            Result.success(Unit)
                        }
                    }
                    BoneButton("+ checklist") {
                        app.scope.launchAction {
                            app.notes.create(NoteKind.CHECKLIST).let { selectedId = it.id }
                            Result.success(Unit)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BoneTextField(newFolder, { newFolder = it }, Modifier.weight(1f), placeholder = "new folder")
                    Spacer(Modifier.width(6.dp))
                    BoneButton("add") {
                        if (newFolder.isNotBlank()) {
                            app.scope.launchAction { app.notes.createFolder(newFolder.trim()); Result.success(Unit) }
                            newFolder = ""
                        }
                    }
                }
            }
            Rule()
            LazyColumn(Modifier.weight(1f)) {
                if (folders.isNotEmpty()) {
                    item { SectionLabel("folders", Modifier.padding(14.dp)) }
                    items(folders, key = { "f-${it.id}" }) { f ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("▸ ${f.name}", style = Type.metaDim, color = colors.textDim, modifier = Modifier.weight(1f))
                            Text("${notes.count { it.folderId == f.id }}", style = Type.caption, color = colors.textMute)
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Outlined.Delete, "delete folder",
                                tint = colors.textMute,
                                modifier = Modifier.size(14.dp).clickable {
                                    app.scope.launchAction { app.notes.deleteFolder(f.id); Result.success(Unit) }
                                },
                            )
                        }
                    }
                    item { Rule() }
                }
                items(notes, key = { it.id }) { n ->
                    NoteRow(app, n, n.id == selected?.id) { selectedId = n.id }
                }
                if (notes.isEmpty()) {
                    item {
                        Text(
                            "No notes yet. Everything you write here is encrypted on disk with a key " +
                                "that never leaves this machine's data directory.",
                            style = Type.caption, color = colors.textMute,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }
            Rule()
            Text(
                if (app.notes.encryptionVerified)
                    "AES-256-GCM · encrypt→decrypt round trip verified this session"
                else "Encryption self-test did not pass — do not store sensitive text.",
                style = Type.caption,
                color = if (app.notes.encryptionVerified) colors.textMute else colors.danger,
                modifier = Modifier.padding(12.dp),
            )
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(colors.rule))

        // ── Editor column ─────────────────────────────────────────────────────
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (selected == null) {
                EmptyState("no note selected", "Create a note or checklist to start writing.")
            } else {
                NoteEditor(app, selected)
            }
        }
    }
}

@Composable
private fun NoteRow(app: AppCore, note: NoteUi, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (selected) colors.accentBg else colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (note.pinned) {
                Icon(Icons.Outlined.PushPin, null, tint = colors.accent, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                note.displayTitle(),
                style = Type.variant(Type.metaDim, weight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                color = colors.text, maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(relativeTime(note.updatedAt), style = Type.caption, color = colors.textMute)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            (if (note.kind == NoteKind.CHECKLIST) "checklist · " else "") +
                (note.folderId?.let { id -> app.notes.folders.value.firstOrNull { it.id == id }?.name + " · " } ?: "") +
                note.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
            style = Type.caption, color = colors.textMute, maxLines = 1,
        )
    }
}

@Composable
private fun NoteEditor(app: AppCore, note: NoteUi) {
    var title by remember(note.id) { mutableStateOf(note.title) }
    var body by remember(note.id) { mutableStateOf(note.text) }
    var dirty by remember(note.id) { mutableStateOf(false) }
    val folders by app.notes.folders.collectAsState()

    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(if (note.kind == NoteKind.CHECKLIST) "checklist" else "note", colors.textDim)
            Spacer(Modifier.width(8.dp))
            Text(relativeTime(note.createdAt), style = Type.caption, color = colors.textMute)
            Spacer(Modifier.weight(1f))
            BoneButton(if (note.pinned) "unpin" else "pin") {
                app.scope.launchAction { app.notes.setPinned(note.id, !note.pinned); Result.success(Unit) }
            }
            Spacer(Modifier.width(6.dp))
            BoneButton("move up") {
                app.scope.launchAction { app.notes.move(note.id, up = true); Result.success(Unit) }
            }
            Spacer(Modifier.width(6.dp))
            BoneButton("move down") {
                app.scope.launchAction { app.notes.move(note.id, up = false); Result.success(Unit) }
            }
            Spacer(Modifier.width(6.dp))
            BoneButton("copy") { copyToClipboard("${title}\n\n${body}".trim()) }
            Spacer(Modifier.width(6.dp))
            BoneButton("delete") {
                app.scope.launchAction { app.notes.delete(note.id); Result.success(Unit) }
            }
        }
        Spacer(Modifier.height(12.dp))
        BoneTextField(title, { title = it; dirty = true }, Modifier.fillMaxWidth(), placeholder = "title")
        Spacer(Modifier.height(8.dp))
        BoneTextField(
            body, { body = it; dirty = true },
            Modifier.fillMaxWidth().weight(1f),
            placeholder = if (note.kind == NoteKind.CHECKLIST) "one item per line" else "write here…",
            singleLine = false, minLines = 12,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BoneButton("save", primary = true, enabled = dirty) {
                app.scope.launchAction {
                    app.notes.save(note.copy(title = title, text = body)).also { dirty = false }
                    Result.success(Unit)
                }
            }
            Spacer(Modifier.width(10.dp))
            if (folders.isNotEmpty()) {
                SectionLabel("folder")
                Spacer(Modifier.width(8.dp))
                BoneButton("none") {
                    app.scope.launchAction { app.notes.setFolder(note.id, null); Result.success(Unit) }
                }
                folders.forEach { f ->
                    Spacer(Modifier.width(6.dp))
                    BoneButton(f.name) {
                        app.scope.launchAction { app.notes.setFolder(note.id, f.id); Result.success(Unit) }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (dirty) "unsaved changes" else "saved · stored encrypted",
                style = Type.caption,
                color = if (dirty) colors.warn else colors.textMute,
            )
        }
    }
}
