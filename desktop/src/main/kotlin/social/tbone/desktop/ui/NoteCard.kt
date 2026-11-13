package social.tbone.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import social.tbone.core.nostr.Event
import social.tbone.core.nostr.EventKind
import social.tbone.core.nostr.Nip19
import social.tbone.core.nostr.quotedEventId
import social.tbone.core.settings.ContentFilter
import social.tbone.desktop.app.AppCore
import social.tbone.desktop.app.MediaItem
import social.tbone.desktop.app.ParsedNote
import social.tbone.desktop.app.copyToClipboard
import social.tbone.desktop.app.launchAction
import androidx.compose.runtime.rememberCoroutineScope

/**
 * Note rendering — port of upstream's `NoteCard` + `NoteContent` work.
 *
 * Kept from the phone app: monospaced body text, relative timestamps, engagement
 * counts with an outline→filled like, quoted-note cards rendered as a bordered
 * sub-card instead of a bare `nostr:` URI, inline images honouring the media
 * setting, and NIP-36 content-warning gating (a hidden note shows a warning strip
 * and a "show" affordance rather than silently disappearing).
 *
 * Adapted for desktop: hover highlights instead of ripple, a right-click-free
 * "•••" row of actions, and click targets sized for a mouse (32 px icons).
 */
@Composable
fun NoteCard(
    app: AppCore,
    event: Event,
    modifier: Modifier = Modifier,
    onOpenThread: ((Event) -> Unit)? = null,
    onOpenProfile: ((String) -> Unit)? = null,
    compact: Boolean = false,
) {
    val profiles = app.profiles
    val name = displayNameWithFallback(profiles, event.pubkey)
    val showName = if (compact) true else true
    val reactionSet = app.feed.reactions.value[event.id].orEmpty()
    val myPubkey = app.accounts.activeAccount.value?.pubkey
    val liked = myPubkey != null && myPubkey in reactionSet
    val replyCount = app.feed.replyCounts.value[event.id] ?: 0

    // Polls and reposts delegate to their own renderers, as upstream did.
    when (event.kind) {
        EventKind.REPOST -> RepostCard(app, event, modifier, onOpenThread, onOpenProfile)
        EventKind.POLL -> PollCard(app, event, modifier, onOpenThread)
        else -> Unit
    }
    if (event.kind == EventKind.REPOST || event.kind == EventKind.POLL) return

    var warningAccepted by remember(event.id) { mutableStateOf(false) }
    val warning = ContentFilter.contentWarningReason(event)

    Column(
        modifier
            .fillMaxWidth()
            .background(colors.bg)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        if (warning != null && !warningAccepted) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(colors.warn.red, colors.warn.green, colors.warn.blue, 0.10f))
                    .border(1.dp, Color(colors.warn.red, colors.warn.green, colors.warn.blue, 0.45f), RoundedCornerShape(4.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("CONTENT WARNING", style = Type.caption, color = colors.warn)
                    Spacer(Modifier.height(4.dp))
                    Text(warning.ifBlank { "The author marked this note sensitive." }, style = Type.bodyDim, color = colors.textDim)
                }
                BoneButton("show", onClick = { warningAccepted = true })
            }
            return@Column
        }

        Row(verticalAlignment = Alignment.Top) {
            InitialAvatar(event.pubkey, name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showName) {
                        Text(
                            name,
                            style = Type.variant(Type.body, weight = FontWeight.SemiBold),
                            color = colors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = onOpenProfile != null) {
                                onOpenProfile?.invoke(event.pubkey)
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(relativeTime(event.createdAt), style = Type.metaDim, color = colors.textMute)
                    Spacer(Modifier.weight(1f))
                    if (myPubkey == event.pubkey) {
                        Text("you", style = Type.caption, color = colors.accent)
                    }
                }
                Spacer(Modifier.height(6.dp))

                NoteBody(app, event, onOpenThread)

                // Quoted note (NIP-18 q tag or inline nostr: reference).
                val quotedId = event.parsedTags.quotedEventId
                if (quotedId != null) {
                    app.feed.quoted.value[quotedId]?.let { q ->
                        Spacer(Modifier.height(10.dp))
                        QuotedCard(app, q, onOpenThread)
                    } ?: run {
                        Spacer(Modifier.height(10.dp))
                        PendingQuote()
                    }
                }

                if (!compact) {
                    Spacer(Modifier.height(12.dp))
                    Engagement(
                        app = app,
                        event = event,
                        liked = liked,
                        reactionCount = reactionSet.size,
                        replyCount = replyCount,
                        onOpenThread = onOpenThread,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteBody(app: AppCore, event: Event, onOpenThread: ((Event) -> Unit)?) {
    val bleep = app.settings.bleepWords.value
    val parsed: ParsedNote = remember(event.id, bleep) {
        social.tbone.desktop.app.parseNote(event.content, bleep)
    }
    Column {
        if (parsed.text.isNotBlank()) {
            RichText(parsed.text, app, onOpenThread = onOpenThread)
        }
        if (parsed.media.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            parsed.media.forEach { item ->
                when (item) {
                    is MediaItem.Image -> InlineImage(app, item.url)
                    is MediaItem.Video -> VideoPlaceholder(item.url)
                }
            }
        }
    }
}

@Composable
private fun Engagement(
    app: AppCore,
    event: Event,
    liked: Boolean,
    reactionCount: Int,
    replyCount: Int,
    onOpenThread: ((Event) -> Unit)?,
) {
    val myPubkey = app.accounts.activeAccount.value?.pubkey
    Row(verticalAlignment = Alignment.CenterVertically) {
        ActionIcon(
            icon = if (liked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
            count = reactionCount,
            active = liked,
            activeColor = Color(0xFFD96A5A),
            onClick = { app.scope.launchAction { app.react(event) } },
        )
        Spacer(Modifier.width(18.dp))
        ActionIcon(
            icon = Icons.Outlined.ChatBubbleOutline,
            count = replyCount,
            active = false,
            activeColor = colors.accent,
            onClick = { onOpenThread?.invoke(event) },
        )
        Spacer(Modifier.width(18.dp))
        ActionIcon(
            icon = Icons.Outlined.Repeat,
            count = 0,
            active = false,
            activeColor = colors.accent,
            onClick = { app.scope.launchAction { app.repost(event) } },
        )
        Spacer(Modifier.width(18.dp))
        ActionIcon(
            icon = Icons.Outlined.FormatQuote,
            count = 0,
            active = false,
            activeColor = colors.accent,
            onClick = { onOpenThread?.invoke(event) },
        )
        Spacer(Modifier.weight(1f))
        ActionIcon(Icons.Filled.ContentCopy, 0, false, colors.textMute) {
            copyToClipboard(Nip19.hexToNote(event.id))
        }
        if (myPubkey == event.pubkey) {
            Spacer(Modifier.width(18.dp))
            ActionIcon(Icons.Outlined.Delete, 0, false, colors.textMute) {
                app.scope.launchAction { app.deleteEvent(event) }
            }
        }
    }
}

@Composable
private fun ActionIcon(
    icon: ImageVector,
    count: Int,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (active) activeColor else colors.textMute,
            modifier = Modifier.size(18.dp),
        )
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Text("$count", style = Type.metaDim, color = if (active) activeColor else colors.textMute)
        }
    }
}

@Composable
fun QuotedCard(app: AppCore, event: Event, onOpenThread: ((Event) -> Unit)?) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(colors.surface)
            .border(1.dp, colors.rule, RoundedCornerShape(4.dp))
            .clickable(enabled = onOpenThread != null) { onOpenThread?.invoke(event) }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InitialAvatar(event.pubkey, displayNameWithFallback(app.profiles, event.pubkey), size = 22)
            Spacer(Modifier.width(8.dp))
            Text(
                displayNameWithFallback(app.profiles, event.pubkey),
                style = Type.variant(Type.metaDim, weight = FontWeight.SemiBold),
                color = colors.textDim,
            )
            Spacer(Modifier.width(8.dp))
            Text(relativeTime(event.createdAt), style = Type.caption, color = colors.textMute)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            event.content.take(400),
            style = Type.bodyDim,
            color = colors.textDim,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PendingQuote() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(colors.surface)
            .border(1.dp, colors.rule, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text("loading quoted note…", style = Type.caption, color = colors.textMute)
    }
}

@Composable
private fun InlineImage(app: AppCore, url: String) {
    val mode = app.settings.imageLoadMode.value
    val bitmap = app.media.cached(url)
    LaunchedEffect(url, mode) { app.media.request(url) }

    Column {
        when {
            mode == social.tbone.core.settings.ImageLoadMode.OFF -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, colors.rule, RoundedCornerShape(4.dp))
                        .clickable { social.tbone.desktop.app.DesktopUx.openExternal(url) }
                        .padding(10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Column {
                        Text("IMAGE (not loaded)", style = Type.caption, color = colors.textMute)
                        Text(url, style = Type.metaDim, color = colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            bitmap != null -> {
                Image(
                    bitmap = bitmap,
                    contentDescription = url,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surfaceAlt)
                        .clickable { app.media.saveImageInteractive(url) },
                    contentScale = ContentScale.FillWidth,
                )
            }
            else -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surfaceAlt)
                        .border(1.dp, colors.rule, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("loading image…", style = Type.caption, color = colors.textMute)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("click image to save · ${hostOf(url)}", style = Type.caption, color = colors.textMute)
    }
}

@Composable
private fun VideoPlaceholder(url: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, colors.rule, RoundedCornerShape(4.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("VIDEO", style = Type.caption, color = colors.textMute)
        Text(url, style = Type.metaDim, color = colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RepostCard(
    app: AppCore,
    event: Event,
    modifier: Modifier,
    onOpenThread: ((Event) -> Unit)?,
    onOpenProfile: ((String) -> Unit)?,
) {
    val name = displayNameWithFallback(app.profiles, event.pubkey)
    // A kind-6 usually embeds the reposted event in `content`; upstream used the
    // embedded copy when it verified and fell back to an id lookup otherwise.
    val embedded = remember(event.id) {
        if (event.content.length > 64 * 1024) null
        else runCatching { Event.fromJson(event.content) }.getOrNull()?.takeIf { it.verify() }
    }
    Column(modifier.fillMaxWidth().background(colors.bg)) {
        Row(Modifier.padding(start = 18.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Repeat, null, tint = colors.textMute, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("$name reposted", style = Type.caption, color = colors.textMute)
        }
        if (embedded != null) {
            NoteCard(app, embedded, Modifier.fillMaxWidth(), onOpenThread, onOpenProfile)
        } else {
            Box(
                Modifier.fillMaxWidth().padding(18.dp).height(48.dp),
                contentAlignment = Alignment.Center,
            ) { Text("reposted note not loaded", style = Type.caption, color = colors.textMute) }
        }
    }
}

@Composable
fun PollCard(app: AppCore, event: Event, modifier: Modifier, onOpenThread: ((Event) -> Unit)?) {
    val poll = remember(event.id) { social.tbone.desktop.app.parsePoll(event) }
    Column(modifier.fillMaxWidth().background(colors.bg).padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InitialAvatar(event.pubkey, displayNameWithFallback(app.profiles, event.pubkey))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(displayNameWithFallback(app.profiles, event.pubkey), style = Type.variant(Type.body, weight = FontWeight.SemiBold), color = colors.text)
                Text(relativeTime(event.createdAt), style = Type.metaDim, color = colors.textMute)
            }
            Spacer(Modifier.weight(1f))
            Pill("poll", colors.accent)
        }
        Spacer(Modifier.height(8.dp))
        Text(poll.question, style = Type.body, color = colors.text)
        Spacer(Modifier.height(10.dp))
        if (poll.options.isEmpty()) {
            Text("This poll could not be parsed.", style = Type.metaDim, color = colors.textMute)
        }
        poll.options.forEach { opt ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, colors.ruleStrong, RoundedCornerShape(4.dp))
                    .clickable { app.scope.launchAction { app.votePoll(event, listOf(opt.id)) } }
                    .padding(10.dp),
            ) {
                Text(opt.label, style = Type.bodyDim, color = colors.text)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("opens the thread to see counts", style = Type.caption, color = colors.textMute)
    }
}

fun hostOf(url: String): String = runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
