package social.tbone.desktop.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import social.tbone.core.nostr.relay.RelayStatus
import social.tbone.core.settings.ThemeMode
import social.tbone.desktop.app.AppCore
import social.tbone.desktop.app.FeedTab
import social.tbone.desktop.app.launchAction
import kotlinx.coroutines.launch

/**
 * Desktop application shell.
 *
 * The Android app was a single-activity Compose navigation stack with bottom
 * tabs. A desktop window has room for a persistent sidebar, so navigation moved
 * there, but every destination the phone app had is present: home/global feed,
 * notifications, encrypted notes, profile, search, settings — plus a thread view
 * and a composer that the phone reached from the feed.
 *
 * The window is laid out as: sidebar (accounts + destinations + transport status)
 * | content. Keyboard: Ctrl+N focuses the composer, Ctrl+F opens search, Esc backs
 * out of a thread.
 */
sealed interface Route {
    data object Home : Route
    data object Notifications : Route
    data object Notes : Route
    data object Search : Route
    data object Settings : Route
    data class Thread(val eventId: String) : Route
    data class Profile(val pubkey: String) : Route
}

@Composable
fun AppShell(app: AppCore, onLock: () -> Unit) {
    val themeMode by app.settings.themeMode.collectAsState()
    val accent by app.settings.accentColor.collectAsState()
    val account by app.accounts.activeAccount.collectAsState()
    val accounts by app.accounts.accounts.collectAsState()
    val relayStatuses by app.relayStatuses.collectAsState()
    val transport by app.transportStatus.collectAsState()
    val signerProblem by app.signerProblem.collectAsState()
    val unread by app.notifications.unread.collectAsState()

    var route by remember { mutableStateOf<Route>(Route.Home) }
    var feedTab by remember { mutableStateOf(FeedTab.HOME) }

    BonyTheme(mode = themeMode, accent = accent) {
        Row(Modifier.fillMaxSize().background(colors.bg)) {
            Sidebar(
                app = app,
                route = route,
                accountPubkey = account?.pubkey,
                accounts = accounts.map { it.pubkey to displayNameWithFallback(app.profiles, it.pubkey) },
                unread = unread,
                relayStatuses = relayStatuses,
                transport = transport,
                onNavigate = { route = it },
                onSwitchAccount = { pk -> app.scope.launch { app.switchAccount(pk) } },
                onLock = onLock,
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.rule))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (signerProblem != null) {
                    SignerProblemBanner(signerProblem!!)
                }
                Box(Modifier.weight(1f)) {
                    when (val r = route) {
                        Route.Home -> FeedScreen(
                            app = app,
                            tab = feedTab,
                            onTabChange = { feedTab = it; app.feed.switchTab(it) },
                            onOpenThread = { route = Route.Thread(it.id) },
                            onOpenProfile = { route = Route.Profile(it) },
                            onOpenSearch = { route = Route.Search },
                        )
                        Route.Notifications -> NotificationsScreen(
                            app = app,
                            onOpenThread = { route = Route.Thread(it.id) },
                            onOpenProfile = { route = Route.Profile(it) },
                        )
                        Route.Notes -> NotesScreen(app)
                        Route.Search -> SearchScreen(
                            app = app,
                            onOpenThread = { route = Route.Thread(it.id) },
                            onOpenProfile = { route = Route.Profile(it) },
                        )
                        Route.Settings -> SettingsScreen(app, onLock = onLock)
                        is Route.Thread -> ThreadScreen(
                            app = app,
                            eventId = r.eventId,
                            onBack = { route = Route.Home },
                            onOpenProfile = { route = Route.Profile(it) },
                            onOpenThread = { route = Route.Thread(it.id) },
                        )
                        is Route.Profile -> ProfileScreen(
                            app = app,
                            pubkey = r.pubkey,
                            onBack = { route = Route.Home },
                            onOpenThread = { route = Route.Thread(it.id) },
                        )
                    }
                }
            }
        }
        // First run: the onboarding layer sits over the shell until an identity
        // exists. It is not dismissable — posting without a key would fail later
        // with a worse message than "choose how to sign".
        if (account == null) {
            OnboardingScreen(app)
        }
    }
}

@Composable
private fun SignerProblemBanner(message: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceAlt)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill("read only", colors.warn)
        Spacer(Modifier.width(10.dp))
        Text(message, style = Type.metaDim, color = colors.textDim)
    }
}

@Composable
private fun Sidebar(
    app: AppCore,
    route: Route,
    accountPubkey: String?,
    accounts: List<Pair<String, String>>,
    unread: Boolean,
    relayStatuses: Map<String, RelayStatus>,
    transport: String,
    onNavigate: (Route) -> Unit,
    onSwitchAccount: (String) -> Unit,
    onLock: () -> Unit,
) {
    var showAccounts by remember { mutableStateOf(false) }
    Column(
        Modifier
            .width(232.dp)
            .fillMaxHeight()
            .background(colors.surface)
            .padding(vertical = 16.dp)
    ) {
        // Wordmark
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text("T-BONE", style = Type.wordmark, color = colors.text)
            Text("DESKTOP 0.3.36", style = Type.caption, color = colors.textMute)
        }
        Spacer(Modifier.height(18.dp))
        Rule()

        // Active account
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { showAccounts = !showAccounts }
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (accountPubkey != null) {
                    InitialAvatar(accountPubkey, displayNameWithFallback(app.profiles, accountPubkey), 30)
                } else {
                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(4.dp)).background(colors.surfaceAlt))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        accountPubkey?.let { displayNameWithFallback(app.profiles, it) } ?: "NO ACCOUNT",
                        style = Type.variant(Type.body, weight = FontWeight.SemiBold),
                        color = colors.text,
                        maxLines = 1,
                    )
                    Text(
                        app.signerKindLabel.collectAsState().value ?: "—",
                        style = Type.caption,
                        color = colors.textMute,
                    )
                }
            }
            if (showAccounts && accounts.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                accounts.forEach { (pk, name) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(3.dp))
                            .clickable { onSwitchAccount(pk); showAccounts = false }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (pk == accountPubkey) "●" else "○",
                            style = Type.metaDim,
                            color = if (pk == accountPubkey) colors.accent else colors.textMute,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(name, style = Type.metaDim, color = colors.textDim, maxLines = 1)
                    }
                }
            }
        }
        Rule()

        Spacer(Modifier.height(10.dp))
        NavItem("Feed", Icons.Outlined.Home, route is Route.Home) { onNavigate(Route.Home) }
        NavItem("Notifications", Icons.Outlined.Notifications, route is Route.Notifications, badge = unread) {
            onNavigate(Route.Notifications)
        }
        NavItem("Notes", Icons.Outlined.Description, route is Route.Notes) { onNavigate(Route.Notes) }
        NavItem("Search", Icons.Outlined.Search, route is Route.Search) { onNavigate(Route.Search) }
        NavItem(
            "Profile",
            Icons.Outlined.Person,
            route is Route.Profile || route is Route.Thread,
            enabled = accountPubkey != null,
        ) { accountPubkey?.let { onNavigate(Route.Profile(it)) } }
        NavItem("Settings", Icons.Outlined.Settings, route is Route.Settings) { onNavigate(Route.Settings) }

        Spacer(Modifier.weight(1f))

        // Connection state: relay dots + transport, straight from the pool.
        Column(Modifier.padding(horizontal = 18.dp)) {
            SectionLabel("relays")
            Spacer(Modifier.height(6.dp))
            relayStatuses.entries.take(6).forEach { (url, status) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                when (status) {
                                    RelayStatus.CONNECTED -> colors.accent
                                    RelayStatus.CONNECTING -> colors.warn
                                    RelayStatus.DISCONNECTED -> colors.danger
                                }
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        url.removePrefix("wss://").removePrefix("ws://"),
                        style = Type.caption,
                        color = colors.textMute,
                        maxLines = 1,
                    )
                }
            }
            if (relayStatuses.isEmpty()) {
                Text("no relays connected", style = Type.caption, color = colors.textMute)
            }
            Spacer(Modifier.height(10.dp))
            Pill(
                text = if (transport.isBlank()) "transport" else transport,
                color = when {
                    transport.startsWith("CLEARNET") -> colors.warn
                    transport.startsWith("TOR") && !transport.contains("NO PROXY") -> colors.accent
                    else -> colors.textMute
                },
            )
            Spacer(Modifier.height(12.dp))
            if (app.settings.hasPin()) {
                BoneButton("lock", Modifier.fillMaxWidth(), onClick = onLock)
            }
        }
    }
}

@Composable
private fun NavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean = true,
    badge: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) colors.accentBg else androidx.compose.ui.graphics.Color.Transparent)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                !enabled -> colors.textMute
                selected -> colors.accent
                else -> colors.textDim
            },
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = Type.variant(Type.bodyDim, weight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = when {
                !enabled -> colors.textMute
                selected -> colors.text
                else -> colors.textDim
            },
            modifier = Modifier.weight(1f),
        )
        if (badge) Box(Modifier.size(6.dp).clip(CircleShape).background(colors.accent))
    }
}
