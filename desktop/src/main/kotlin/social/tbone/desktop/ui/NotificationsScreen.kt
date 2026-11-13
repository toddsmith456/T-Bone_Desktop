package social.tbone.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import social.tbone.core.nostr.Event
import social.tbone.desktop.app.AppCore
import social.tbone.desktop.app.NotifItem
import social.tbone.desktop.app.NotifKind

/**
 * Notification centre — port of upstream's `NotificationsScreen`.
 *
 * Upstream cached the last 24 h in Room and had a per-type filter sheet and a
 * "load older" button. Here the list is grouped in memory, filtered by the same
 * persisted type switches (Settings → notifications), and the header carries the
 * 24-hour summary bar the phone app showed above the list.
 */
@Composable
fun NotificationsScreen(
    app: AppCore,
    onOpenThread: (Event) -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val items by app.notifications.items.collectAsState()
    val unread by app.notifications.unread.collectAsState()

    LaunchedEffect(Unit) { app.notifications.markRead() }

    val now = System.currentTimeMillis() / 1000
    val last24 = items.filter { now - it.createdAt <= 86_400 }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("NOTIFICATIONS", style = Type.title, color = colors.text)
            Spacer(Modifier.width(12.dp))
            if (unread) Pill("new", colors.accent)
            Spacer(Modifier.weight(1f))
            Text(
                "24H: ${last24.size} · ALL: ${items.size}",
                style = Type.caption,
                color = colors.textMute,
            )
        }
        Rule()

        when {
            items.isEmpty() -> EmptyState(
                "no notifications yet",
                "Replies, reactions, reposts, quotes and mentions of your notes appear here. " +
                    "In-app only — T-Bone never sends your activity to a notification service.",
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(app.notifications.grouped(), key = { it.id }) { item ->
                    NotifRow(app, item, onOpenThread, onOpenProfile)
                    Rule()
                }
            }
        }
    }
}

@Composable
private fun NotifRow(
    app: AppCore,
    item: NotifItem,
    onOpenThread: (Event) -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val primary = item.actors.firstOrNull()
    val name = primary?.let { displayNameWithFallback(app.profiles, it) } ?: "someone"
    val others = (item.actors.size - 1).coerceAtLeast(0)

    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.bg)
            .clickable { onOpenThread(item.event) }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            if (primary != null) {
                InitialAvatar(primary, name, 34)
            } else {
                Box(Modifier.size(34.dp).clip(CircleShape).background(colors.surfaceAlt))
            }
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (item.read) colors.textMute else colors.accent),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (item.kind) {
                        NotifKind.REACTION -> "$name reacted ${item.reaction ?: "+"}"
                        NotifKind.REPLY -> "$name replied to your note"
                        NotifKind.REPOST -> "$name reposted your note"
                        NotifKind.QUOTE -> "$name quoted your note"
                        NotifKind.MENTION -> "$name mentioned you"
                        NotifKind.VOTE -> "$name voted in your poll"
                    },
                    style = Type.variant(Type.bodyDim, weight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = colors.text,
                )
                if (others > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text("+$others", style = Type.metaDim, color = colors.accent)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                item.event.content.take(160).ifBlank { "(no text)" },
                style = Type.metaDim,
                color = colors.textDim,
                maxLines = 2,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(relativeTime(item.createdAt), style = Type.caption, color = colors.textMute)
            Spacer(Modifier.height(6.dp))
            Pill(item.kind.label, colors.textDim)
        }
    }
}
