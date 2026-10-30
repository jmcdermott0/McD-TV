package com.mcd.tv.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.tv.material3.Text
import com.mcd.tv.Nav
import com.mcd.tv.Screen
import com.mcd.tv.data.Addons
import com.mcd.tv.data.Epg
import com.mcd.tv.data.HouseSync
import com.mcd.tv.data.FavoriteTeam
import com.mcd.tv.data.Games
import com.mcd.tv.data.League
import com.mcd.tv.data.LiveStats
import com.mcd.tv.data.LocalWeb
import com.mcd.tv.data.OriginFilter
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.QualityLog
import com.mcd.tv.data.QualityMode
import com.mcd.tv.data.RealDebrid
import com.mcd.tv.data.RecentErrors
import com.mcd.tv.data.Relay
import com.mcd.tv.data.TvIdentity
import com.mcd.tv.data.Updater
import com.mcd.tv.data.Wall
import com.mcd.tv.data.WallCycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PLAYER_TEST_URL =
    "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"

/** A category to open Settings on (QA deep link "--es screen settings:updates"). Read once. */
object SettingsLaunch {
    @Volatile var requested: SettingsCategory? = null
}

/** The dialog Settings shows over the page, if any. */
private sealed interface SettingsDialog {
    data class Edit(val title: String, val help: String, val initial: String, val max: Int, val onDone: (String) -> Unit) : SettingsDialog
    data class Choice(val title: String, val options: List<String>, val selected: Int, val onPick: (Int) -> Unit) : SettingsDialog
    data class Confirm(val title: String, val message: String, val confirm: String, val onConfirm: () -> Unit) : SettingsDialog
}

/** One focusable row: its focus modifier and its requester (focus comes back to it after a dialog). */
private class RowHandle(val modifier: Modifier, val requester: FocusRequester)

/**
 * What a category's content needs: row handles in order, dialogs and navigation.
 * Each category calls [begin] first and takes every [row] in its own body (never inside a child composable
 * that can recompose alone), so row numbers stay the same on every recomposition.
 */
private class SettingsCtx(
    val nav: Nav,
    private val category: SettingsCategory,
    private val requester: (SettingsCategory, Int) -> FocusRequester,
    private val onRowFocus: (Int) -> Unit,
    private val open: (SettingsDialog, FocusRequester) -> Unit,
) {
    var count = 0
        private set

    fun begin() { count = 0 }

    /** The next focusable row, in order. */
    fun row(): RowHandle {
        val i = count++
        val r = requester(category, i)
        return RowHandle(Modifier.focusRequester(r).onFocusChanged { if (it.isFocused) onRowFocus(i) }, r)
    }

    /** Opens [d]; focus returns to [row] when it closes. */
    fun dialog(row: RowHandle, d: SettingsDialog) = open(d, row.requester)
}

/**
 * Settings: category rail on the left, the category on the right. The remote moves through rows only; text
 * values open a dialog with the keyboard on OK. A fresh open lands on App updates; coming back from a screen
 * Settings opened restores the category and row.
 */
@Composable
fun SettingsScreen(nav: Nav) {
    var saved by rememberSaveable { mutableStateOf<String?>(null) }
    var focus by remember {
        val requested = SettingsLaunch.requested.also { SettingsLaunch.requested = null }
        mutableStateOf(SettingsFocus.open(SettingsFocus.decode(saved), requested))
    }
    SideEffect { saved = focus.encode() }

    val rail = remember { SettingsCategory.entries.associateWith { FocusRequester() } }
    val rowReqs = remember { HashMap<String, FocusRequester>() }
    val rowCounts = remember { HashMap<SettingsCategory, Int>() }
    fun rowReq(c: SettingsCategory, i: Int) = rowReqs.getOrPut("${c.id}:$i") { FocusRequester() }

    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    var returnTo by remember { mutableStateOf<FocusRequester?>(null) }
    // Bumped when a dialog saves, so the content re-reads Prefs.
    var tick by remember { mutableIntStateOf(0) }

    fun focusRail() { runCatching { rail.getValue(focus.category).requestFocus() } }
    fun focusContent() {
        val r = focus.enterRow(rowCounts[focus.category] ?: 0) ?: return
        runCatching { rowReq(focus.category, r).requestFocus() }
    }

    // Put focus where Settings opens: the saved row on a return, else the rail. A request made before the
    // window has focus can be lost on a cold start, so try again for a short while until the page holds focus.
    // It runs again when the window gains focus, which on a slow cold start can come seconds later, and when the
    // first key press ends touch mode (rows cannot take focus in touch mode, so a launch in touch mode starts
    // with nothing focused).
    var pageHasFocus by remember { mutableStateOf(false) }
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current.inputMode
    LaunchedEffect(windowFocused, inputMode) {
        repeat(20) {
            withFrameNanos { }
            if (pageHasFocus) return@LaunchedEffect
            if (focus.inContent) focusContent() else focusRail()
            delay(250)
        }
    }
    // Back to the row that opened a dialog.
    LaunchedEffect(dialog) {
        val r = returnTo
        if (dialog == null && r != null) {
            withFrameNanos { }
            // The row can be gone (a list was cleared): fall back to the rail.
            if (runCatching { r.requestFocus() }.isFailure) focusRail()
            returnTo = null
        }
    }
    // BACK from the content goes to the rail first; from the rail it leaves Settings.
    BackHandler(enabled = focus.inContent && dialog == null) { focusRail() }

    TabPage(nav, NavTab.Settings, autoFocus = false) {
        Row(Modifier.fillMaxSize().onFocusChanged { pageHasFocus = it.hasFocus }.padding(start = Space.XXL, end = Space.XXL, top = Space.S)) {
            // ---- Rail
            Column(
                Modifier.width(260.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { focusContent(); true } else false
                    },
                verticalArrangement = Arrangement.spacedBy(Space.XS),
            ) {
                Text("SETTINGS", style = hudLabelStyle(12.sp, McdColors.Muted), modifier = Modifier.padding(start = Space.M + 3.dp, bottom = Space.S))
                SettingsCategory.entries.forEach { c ->
                    RailItem(
                        label = c.title,
                        selected = c == focus.category,
                        onClick = { focus = focus.onRail(c); focusContent() },
                        modifier = Modifier.focusRequester(rail.getValue(c)).onFocusChanged { if (it.isFocused) focus = focus.onRail(c) },
                        badge = if (c == SettingsCategory.Updates && Updater.available != null) "New" else null,
                    )
                }
            }
            Spacer(Modifier.width(Space.L))
            Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = Space.S).background(McdColors.Line.copy(alpha = 0.45f)))
            Spacer(Modifier.width(Space.L))
            // ---- Content
            val cat = focus.category
            key(cat, tick) {
                val ctx = SettingsCtx(
                    nav, cat, ::rowReq,
                    onRowFocus = { i -> focus = focus.onRow(i) },
                    open = { d, r -> returnTo = r; dialog = d },
                )
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { focusRail(); true } else false
                        }
                        .padding(bottom = Space.XXL),
                    verticalArrangement = Arrangement.spacedBy(Space.XS),
                ) {
                    Text(cat.title.uppercase(), style = broadcastStyle(SettingsType.Title), modifier = Modifier.padding(start = Space.L, bottom = Space.S))
                    when (cat) {
                        SettingsCategory.Updates -> UpdatesCategory(ctx)
                        SettingsCategory.ThisTv -> ThisTvCategory(ctx) { tick++ }
                        SettingsCategory.Profiles -> ProfilesCategory(ctx) { tick++ }
                        SettingsCategory.Playback -> PlaybackCategory(ctx) { tick++ }
                        SettingsCategory.Sources -> SourcesCategory(ctx) { tick++ }
                        SettingsCategory.Wall -> WallCategory(ctx) { tick++ }
                        SettingsCategory.Network -> NetworkCategory(ctx) { tick++ }
                        SettingsCategory.About -> AboutCategory(ctx) { tick++ }
                    }
                }
                SideEffect { rowCounts[cat] = ctx.count }
            }
        }
    }

    when (val d = dialog) {
        null -> {}
        is SettingsDialog.Edit -> TextEditDialog(d.title, d.help, d.initial, d.max, onDone = { d.onDone(it); dialog = null; tick++ }, onDismiss = { dialog = null })
        is SettingsDialog.Choice -> ChoiceDialog(d.title, d.options, d.selected, onPick = { d.onPick(it); dialog = null; tick++ }, onDismiss = { dialog = null })
        is SettingsDialog.Confirm -> ConfirmDialog(d.title, d.message, d.confirm, onConfirm = { d.onConfirm(); tick++ }, onDismiss = { dialog = null })
    }
}

/** A row that opens a dialog. Focus comes back to it when the dialog closes. */
@Composable
private fun DialogRow(
    ctx: SettingsCtx,
    h: RowHandle,
    label: String,
    value: String?,
    end: RowEnd,
    danger: Boolean = false,
    dialog: () -> SettingsDialog,
) {
    SettingsRow(label, { ctx.dialog(h, dialog()) }, h.modifier, value = value, end = end, danger = danger)
}

/** A row that flips a setting on or off. Saves at once. */
@Composable
private fun ToggleRow(h: RowHandle, label: String, value: String?, initial: Boolean, save: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(initial) }
    SettingsRow(label, { on = !on; save(on) }, h.modifier, value = value, end = RowEnd.Toggle, toggled = on)
}

// ============================== categories ==============================

@Composable
private fun UpdatesCategory(ctx: SettingsCtx) {
    ctx.begin()
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val installed = remember { Updater.installedName(c) }
    val v = updateView(installed, Updater.available?.name, Updater.checking, Updater.checkError, Updater.checkedAt)

    Row(Modifier.padding(horizontal = Space.L, vertical = Space.S), verticalAlignment = Alignment.CenterVertically) {
        val tint = when {
            v.ready -> McdColors.Amber
            v.headline.startsWith("Couldn't") -> McdColors.Coral
            else -> McdColors.Accent
        }
        Box(Modifier.size(44.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            if (v.busy) HudSpinner(diameter = 24.dp) else Text(
                when { v.ready -> "↑"; v.headline.startsWith("Couldn't") -> "!"; else -> "✓" },
                color = tint, fontSize = 22.sp,
            )
        }
        Spacer(Modifier.width(Space.M))
        Column {
            Text(v.headline, style = broadcastStyle(20.sp, if (v.ready) McdColors.Amber else McdColors.White))
            Text(v.detail, color = McdColors.Muted, fontSize = SettingsType.Value)
        }
    }
    Spacer(Modifier.height(Space.S))
    BigButton(
        v.button,
        onClick = {
            when {
                v.ready -> ctx.nav.push(Screen.Update)
                !v.busy -> scope.launch { Updater.check(c, force = true) }
            }
        },
        modifier = ctx.row().modifier.padding(start = Space.L),
        highlight = v.ready,
    )
    Spacer(Modifier.height(Space.M))
    SettingsDivider()
    InfoRow("Installed version", installed)
    InfoRow("Latest version", Updater.latestName.ifBlank { "Not checked yet" })
    InfoRow("Last checked", agoLabel(Updater.checkedAt, now))
    SettingsNote("Jarvis also checks when it starts and every 6 hours. When a new version is out, Home offers it once.")
}

@Composable
private fun ThisTvCategory(ctx: SettingsCtx, changed: () -> Unit) {
    ctx.begin()
    val platform = remember { HouseSync.platform() }
    fun saveLabel(text: String) {
        val clean = TvIdentity.cleanLabel(text)
        if (clean != Prefs.tvLabel) { Prefs.tvLabel = clean; HouseSync.onLabelChanged() }
        changed()
    }
    SettingsSection("Name", first = true)
    DialogRow(ctx, ctx.row(), "TV name", Prefs.tvLabel.ifBlank { "Not set. Phones show \"$platform\"." }, RowEnd.Edit) {
        SettingsDialog.Edit(
            "Name this TV",
            "Phones use this name to tell your TVs apart. Press Done to save. Back cancels.",
            Prefs.tvLabel, TvIdentity.LABEL_MAX, ::saveLabel,
        )
    }
    val names = TvIdentity.EXAMPLES
    DialogRow(ctx, ctx.row(), "Suggested names", names.joinToString(", "), RowEnd.Change) {
        SettingsDialog.Choice("Pick a name", names + "No name", names.indexOf(Prefs.tvLabel).let { if (it < 0 && Prefs.tvLabel.isBlank()) names.size else it }) { i ->
            saveLabel(names.getOrElse(i) { "" })
        }
    }
    SettingsSection("This device")
    InfoRow("Platform", platform)
    InfoRow("Model", android.os.Build.MODEL.orEmpty().ifBlank { "Unknown" })
    InfoRow("Maker", android.os.Build.MANUFACTURER.orEmpty().ifBlank { "Unknown" })
    InfoRow("Jarvis version", com.mcd.tv.BuildConfig.VERSION_NAME)
    SettingsSection("Start-up")
    ToggleRow(ctx.row(), "Intro on launch", "Play the Jarvis intro when the app opens.", Prefs.playIntro) { Prefs.playIntro = it }
    SettingsRow("Replay intro", { ctx.nav.push(Screen.Intro) }, ctx.row().modifier, value = "Watch the intro now.")
}

@Composable
private fun ProfilesCategory(ctx: SettingsCtx, changed: () -> Unit) {
    ctx.begin()
    SettingsSection("Who's watching", first = true)
    SettingsRow("Watching now", { ctx.nav.push(Screen.Profiles) }, ctx.row().modifier, value = "${Prefs.activeProfileName}. Press OK to switch.")
    SettingsSection("Names")
    Prefs.PROFILE_IDS.forEachIndexed { i, id ->
        val slot = if (id == Prefs.KIDS_PROFILE) "Kids profile" else "Profile ${i + 1}"
        val name = Prefs.profileName(id)
        DialogRow(ctx, ctx.row(), name, slot, RowEnd.Edit) {
            SettingsDialog.Edit("Rename $name", "$slot. Up to 20 letters. Press Done to save. Back cancels.", name, 20) { t ->
                if (t.isNotBlank()) Prefs.setProfileName(id, t.trim())
                changed()
            }
        }
    }
    SettingsSection("Taste")
    Prefs.PROFILE_IDS.forEach { id ->
        SettingsRow(possessive(Prefs.profileName(id)) + " taste", { ctx.nav.push(Screen.Taste(id)) }, ctx.row().modifier, value = "Genres and topics to like or skip.")
    }
    SettingsNote(
        "Each profile has its own history, Continue Watching, My List, favorites and Live TV favorites. " +
            "Settings, keys, addons and the playlist are shared by the whole TV. Jarvis also learns from what each person watches.",
    )
}

@Composable
private fun PlaybackCategory(ctx: SettingsCtx, changed: () -> Unit) {
    ctx.begin()
    val rd by rememberLoad(Prefs.rdAccessToken) { if (RealDebrid.connected) RealDebrid.accountSummary() else "Not connected" }
    SettingsSection("Real-Debrid", first = true)
    InfoRow("Status", when (val r = rd) { is Load.Ok -> r.value; is Load.Err -> "Error: ${r.message}"; else -> "Checking…" })
    SettingsRow(
        if (RealDebrid.connected) "Reconnect Real-Debrid" else "Connect Real-Debrid",
        { ctx.nav.push(Screen.RdConnect) }, ctx.row().modifier, value = "Sign in with a code on your phone.",
    )

    SettingsSection("What Jarvis shows")
    val origins = OriginFilter.entries
    DialogRow(ctx, ctx.row(), "Titles", Prefs.origin.label, RowEnd.Change) {
        SettingsDialog.Choice("Titles", origins.map { it.label }, origins.indexOf(Prefs.origin)) { i -> Prefs.origin = origins[i] }
    }
    ToggleRow(ctx.row(), "US release dates", "Lists use US releases and US streaming services.", Prefs.usOnly) { Prefs.usOnly = it }

    SettingsSection("Video")
    // Pref quality_mode, this TV only (see QualityFallback).
    val qualities = QualityMode.ALL
    DialogRow(ctx, ctx.row(), "Quality", QualityMode.label(Prefs.qualityMode), RowEnd.Change) {
        SettingsDialog.Choice("Quality", qualities.map(QualityMode::label), qualities.indexOf(Prefs.qualityMode).coerceAtLeast(0)) { i -> Prefs.qualityMode = qualities[i] }
    }
    SettingsNote(
        when (Prefs.qualityMode) {
            QualityMode.PREFER_4K -> "Plays 4K when the stream has it. Never steps down."
            QualityMode.MAX_1080 -> "Never plays above 1080p. Live TV opens the 1080p version of a 4K channel."
            else -> "Plays 4K. Steps down to 1080p if 4K keeps freezing or the connection is too slow."
        },
    )

    SettingsSection("Source list")
    DialogRow(ctx, ctx.row(), "Movie size limit", sizeLimitLabel(Prefs.maxMovieGb), RowEnd.Change) {
        SettingsDialog.Choice("Movie size limit", MOVIE_LIMITS.map(::sizeLimitLabel), MOVIE_LIMITS.indexOf(Prefs.maxMovieGb)) { i -> Prefs.maxMovieGb = MOVIE_LIMITS[i] }
    }
    DialogRow(ctx, ctx.row(), "Episode size limit", sizeLimitLabel(Prefs.maxEpisodeGb), RowEnd.Change) {
        SettingsDialog.Choice("Episode size limit", EPISODE_LIMITS.map(::sizeLimitLabel), EPISODE_LIMITS.indexOf(Prefs.maxEpisodeGb)) { i -> Prefs.maxEpisodeGb = EPISODE_LIMITS[i] }
    }
    SettingsNote("Bigger files stay hidden. Show hidden in the source list shows them.")

    SettingsSection("Player test")
    SettingsRow("Play test stream", { ctx.nav.push(Screen.Player(PLAYER_TEST_URL, "Player test")) }, ctx.row().modifier, value = "A short sample video. Checks that the player works.")
    if (Prefs.customUrl.isNotBlank()) {
        SettingsRow("Play My Stream", { ctx.nav.push(Screen.Player(Prefs.customUrl, "My Stream")) }, ctx.row().modifier, value = "The stream link saved on the Control page.")
    }

    if (RealDebrid.connected) {
        SettingsSection("Sign out")
        DialogRow(ctx, ctx.row(), "Disconnect Real-Debrid", "Signs this TV out of Real-Debrid.", RowEnd.None, danger = true) {
            SettingsDialog.Confirm(
                "Disconnect Real-Debrid?",
                "Cached sources stop working on this TV until you connect again. Linked TVs share this sign-in.",
                "Disconnect",
            ) { Prefs.clearRealDebrid(); changed() }
        }
    }
}

@Composable
private fun SourcesCategory(ctx: SettingsCtx, changed: () -> Unit) {
    ctx.begin()
    val c = LocalContext.current
    val addons by rememberLoad { Prefs.addonUrls.map { u -> runCatching { Addons.manifest(u).name }.getOrDefault(u.take(40)) } }
    val link = remember { Relay.controlUrl() }

    Row(Modifier.fillMaxWidth().padding(horizontal = Space.L, vertical = Space.S), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.S)) {
            Text("Set up from your phone", style = broadcastStyle(18.sp))
            Text(
                "Scan the code with your phone's camera. Add addons, playlists, websites and keys there, then tap Save.",
                color = McdColors.Muted, fontSize = SettingsType.Value,
            )
            Text(Relay.status, color = McdColors.White, fontSize = SettingsType.Caption)
        }
        Spacer(Modifier.width(Space.L))
        QrCode(link, 140.dp)
    }
    SettingsRow("Phone & Computer Setup", { ctx.nav.push(Screen.PhoneSetup) }, ctx.row().modifier, value = "The full setup page, with the home Wi-Fi option.")

    SettingsSection("What's set up")
    InfoRow("TMDB", if (Prefs.tmdbKey.isNotBlank()) "Connected" else "Add a key on the Control page")
    InfoRow("Addons", when (val a = addons) { is Load.Ok -> a.value.joinToString(", ").ifBlank { "None" }; else -> "…" })
    InfoRow("Live TV playlist", if (Prefs.m3uUrl.isNotBlank()) "Set" else "None")
    InfoRow("Websites", Prefs.websites.joinToString(", ") { it.first }.ifBlank { "None" })
    SettingsNote("Long text such as links and keys stays on the Control page, so you never type it with the remote.")

    // Per profile, this TV. Hiding a league only hides it in Live Games; the playlist stays as it is.
    SettingsSection("Live Games")
    SettingsNote("Leagues ${Prefs.activeProfileName} sees in Live TV > Live Games. Hidden leagues stay in your playlist.")
    val hiddenLeagues = remember(Prefs.activeProfile) { GameBoard.hidden() }
    League.entries.forEach { l ->
        ToggleRow(ctx.row(), "${l.emoji}  ${l.label}", null, l !in hiddenLeagues) { GameBoard.setShown(l, it) }
    }

    // Per profile, this TV. A game with one of these teams goes first in its league, with a star, and in My Teams.
    SettingsSection("My teams")
    SettingsNote("Games with ${Prefs.activeProfileName}'s teams go first in Live Games, with a star, and fill the My Teams chip.")
    val myTeams = GameBoard.myTeams()
    myTeams.forEach { t ->
        DialogRow(ctx, ctx.row(), "${t.league.emoji}  ${t.name}", "${t.league.label}. OK removes it.", RowEnd.None) {
            SettingsDialog.Confirm("Remove ${t.name}?", "Its games stop going first in Live Games.", "Remove") { GameBoard.removeTeam(t) }
        }
    }
    // Every team of each shown league, from ESPN (once per app run). Until it loads, the teams playing this week.
    val shownLeagues = League.entries.filter { it !in hiddenLeagues }
    val teamLists by rememberLoad(shownLeagues) {
        shownLeagues.associateWith { l -> runCatching { Games.teams(l) }.getOrDefault(emptyList()) }
    }
    shownLeagues.forEach { l ->
        DialogRow(ctx, ctx.row(), "Add a ${l.label} team", null, RowEnd.Change) {
            val loaded = when (val tl = teamLists) { is Load.Ok -> tl.value[l].orEmpty(); else -> emptyList() }
            val all = loaded.ifEmpty {
                Games.cached(l)?.games.orEmpty().flatMap { listOf(it.home, it.away) }.distinctBy { it.id }.sortedBy { it.short }
            }
            val picks = all.filter { t -> myTeams.none { it.league == l && it.matches(t) } }
            SettingsDialog.Choice("Add a ${l.label} team", picks.map { it.name }.ifEmpty { listOf("No teams loaded yet. Try again in a moment.") }, -1) { i ->
                picks.getOrNull(i)?.let { GameBoard.addTeam(FavoriteTeam(l, it.short)) }
            }
        }
    }

    SettingsSection("Live TV health")
    InfoRow("This TV", remember { LiveStats.device(c) })
    // Guide coverage of the last guide read: shows on the TV whether a guide change helped or broke matching.
    remember { Epg.lastStats }?.let { g ->
        InfoRow("Guide", "%,d of %,d channels have listings. %,d have a guide id.".format(g.listed, g.channels, g.withId))
    }
    val sessions = remember { LiveStats.recent() }
    if (sessions.isEmpty()) {
        SettingsNote("Watch a few live channels, then come back. Jarvis notes how each one played.")
    } else {
        sessions.take(8).forEach { s ->
            SettingsRow(s.channel, {}, ctx.row().modifier, value = s.summary() + (if (s.decoder.isNotBlank()) ". Decoder: ${s.decoder}" else ""), end = RowEnd.None)
        }
        DialogRow(ctx, ctx.row(), "Clear Live TV health", "Forget how these channels played.", RowEnd.None, danger = true) {
            SettingsDialog.Confirm("Clear Live TV health?", "Jarvis forgets the channel notes on this TV.", "Clear") { LiveStats.clear(); changed() }
        }
    }

    // Every quality switch on this TV (pref quality_log, see QualityLog), newest first.
    SettingsSection("Quality changes")
    val changes = remember { QualityLog.recent().take(8) }
    if (changes.isEmpty()) {
        SettingsNote("No quality changes yet. Jarvis notes each time a stream moves between 4K and 1080p.")
    } else {
        val fmt = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
        changes.forEach { e ->
            val line = buildString {
                if (e.from.isNotBlank() || e.to.isNotBlank()) append("${e.from.ifBlank { "?" }} to ${e.to.ifBlank { "?" }}. ")
                append(QualityLog.words(e.code))
                if (e.est > 0) append(" Network ${QualityLog.mbps(e.est)}.")
            }
            SettingsRow("${fmt.format(java.util.Date(e.at))}  ${e.title}", {}, ctx.row().modifier, value = line, end = RowEnd.None)
        }
    }
}

@Composable
private fun WallCategory(ctx: SettingsCtx, changed: () -> Unit) {
    ctx.begin()
    SettingsNote(
        if (Wall.configured) "Photo frame and family calendar. Turn on Open in Wall on start on the wall TV."
        else "Photo frame and family calendar. Add the wall address and key on the Control page first.",
    )
    // A TV that turns itself off after hours with no remote use (attentive_timeout) beats keep-screen-on.
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    com.mcd.tv.data.WallWatch.sleepWarning(com.mcd.tv.data.WallRecorder.attentiveMs(appCtx))?.let { SettingsNote(it) }
    Spacer(Modifier.height(Space.S))
    SettingsRow("Open Wall", { ctx.nav.push(Screen.Wall) }, ctx.row().modifier, value = "OK switches views. Back closes it.")
    ToggleRow(ctx.row(), "Open in Wall on start", "For a TV on the wall. Back goes to Home.", Prefs.wallStart) { Prefs.wallStart = it }
    val modes = listOf("" to "Photos and calendar", "photos" to "Photos only")
    DialogRow(ctx, ctx.row(), "Wall shows", modes.firstOrNull { it.first == Prefs.wallMode }?.second ?: modes[0].second, RowEnd.Change) {
        SettingsDialog.Choice("Wall shows", modes.map { it.second }, modes.indexOfFirst { it.first == Prefs.wallMode }.coerceAtLeast(0)) { i ->
            Wall.setMode(modes[i].first)
        }
    }
    // How often the month calendar replaces the photos (pref wall_cycle, see WallCycle). Photos lead by default.
    if (Prefs.wallMode != "photos") {
        val cycle = WallCycle.parse(Prefs.wallCycle)
        fun everyLabel(e: Int) = when (e) { 0 -> "Never (OK on the remote still shows it)"; 60 -> "Every hour"; else -> "Every $e minutes" }
        DialogRow(ctx, ctx.row(), "Show the full calendar", everyLabel(cycle.every), RowEnd.Change) {
            SettingsDialog.Choice("Show the full calendar", WallCycle.EVERY.map(::everyLabel), WallCycle.EVERY.indexOf(cycle.every)) { i ->
                Wall.setCycle("${WallCycle.EVERY[i]}/${cycle.stays.coerceAtLeast(1)}")
            }
        }
        if (cycle.every > 0) {
            val stays = WallCycle.STAYS.filter { it <= cycle.every / 2 }
            DialogRow(ctx, ctx.row(), "The calendar stays for", "${cycle.stays} min", RowEnd.Change) {
                SettingsDialog.Choice("The calendar stays for", stays.map { if (it == 1) "1 minute" else "$it minutes" }, stays.indexOf(cycle.stays)) { i ->
                    Wall.setCycle("${cycle.every}/${stays[i]}")
                }
            }
        }
        SettingsNote(WallCycle.label(cycle) + ". Photos show the rest of the time.")
    }
}

@Composable
private fun NetworkCategory(ctx: SettingsCtx, changed: () -> Unit) {
    ctx.begin()
    SettingsSection("Phone link", first = true)
    InfoRow("Control page link", Relay.status)
    SettingsRow("Connect a phone", { ctx.nav.push(Screen.ConnectPhone) }, ctx.row().modifier, value = "Link a phone with a code. Works away from home too.")
    SettingsSection("Other TVs")
    SettingsRow("Join my other TVs", { ctx.nav.push(Screen.JoinHouse) }, ctx.row().modifier, value = "Share profiles, settings and Real-Debrid with another TV.")
    SettingsSection("Connection")
    ToggleRow(ctx.row(), "Slow connection", "Prefer smaller files and 720p when Jarvis picks a source.", Prefs.slowConnection) { Prefs.slowConnection = it }
    InfoRow("Home Wi-Fi address", remember { LocalWeb.addresses().firstOrNull() ?: "Not on Wi-Fi" })
    SettingsNote("The home Wi-Fi setup page runs only while Phone & Computer Setup is open.")
}

@Composable
private fun AboutCategory(ctx: SettingsCtx, changed: () -> Unit) {
    ctx.begin()
    val now = remember { System.currentTimeMillis() }
    SettingsSection("Jarvis", first = true)
    InfoRow("Version", com.mcd.tv.BuildConfig.VERSION_NAME)
    InfoRow("Build", com.mcd.tv.BuildConfig.VERSION_CODE.toString())
    InfoRow("Device", remember { HouseSync.platform() + "  ·  " + android.os.Build.MODEL.orEmpty() })
    SettingsSection("Help")
    SettingsNote("To install Jarvis on another TV, open Downloader and enter jfortress0.github.io/McD-TV/get.")
    SettingsNote("To add addons, playlists or keys, open Addons and Live TV and scan the code with your phone.")
    SettingsSection("Recent errors")
    val errors = remember { RecentErrors.recent() }
    if (errors.isEmpty()) {
        SettingsNote("No problems noted on this TV.")
    } else {
        errors.forEach { e ->
            SettingsRow(
                e.text, {}, ctx.row().modifier,
                value = agoLabel(e.at, now) + if (e.count > 1) ". ${e.count} times." else "",
                end = RowEnd.None,
            )
        }
        DialogRow(ctx, ctx.row(), "Clear recent errors", "Start a fresh list.", RowEnd.None, danger = true) {
            SettingsDialog.Confirm("Clear recent errors?", "The list starts empty again.", "Clear") { RecentErrors.clear(); changed() }
        }
    }
}

/** Setup from a phone or computer: QR code for the internet Control page, plus the home-network page. */
@Composable
fun PhoneSetupScreen(nav: Nav) {
    // The home-network setup page only runs while this screen is open.
    var webRunning by remember { mutableStateOf(LocalWeb.running) }
    DisposableEffect(Unit) {
        com.mcd.tv.data.LocalWeb.start()
        webRunning = LocalWeb.running
        onDispose { com.mcd.tv.data.LocalWeb.stop() }
    }
    com.mcd.tv.data.Relay.start()
    val addrs = remember { LocalWeb.addresses() }
    var link by remember { mutableStateOf(com.mcd.tv.data.Relay.controlUrl()) }
    // Keep the TV awake (no screensaver) while this screen is open.
    androidx.compose.runtime.DisposableEffect(Unit) {
        val hold = com.mcd.tv.data.ScreenAwake.holds.acquire(com.mcd.tv.data.ScreenAwake.PHONE_SETUP)
        onDispose { hold.release() }
    }
    Row(
        Modifier.fillMaxSize().hudBackground().padding(48.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            McdLogo()
            Text("JARVIS CONTROL", style = broadcastStyle(36.sp))
            Text("1.  Point your phone's camera at the code and tap the link.", fontSize = 20.sp, color = McdColors.White)
            Text("2.  Add addons, websites and keys, then tap Save.", fontSize = 20.sp, color = McdColors.White)
            Text(
                "Works over the internet from any network, at home or away. To use a computer, tap Copy link on your phone's Control page and open it there.",
                fontSize = 16.sp, color = McdColors.Muted,
            )
            Text(com.mcd.tv.data.Relay.status, fontSize = 16.sp, color = McdColors.White)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("New link (disconnect old devices)", {
                    com.mcd.tv.data.Relay.newLink()
                    link = com.mcd.tv.data.Relay.controlUrl()
                })
            }
            Text(
                "Home Wi-Fi page: ${addrs.first()}   •   ${if (webRunning) "running" else "stopped"}   •   browser requests: ${LocalWeb.requests}",
                fontSize = 13.sp, color = McdColors.Muted,
            )
            Text(LocalWeb.error?.let { "Home page error: $it" } ?: LocalWeb.lastMessage, fontSize = 13.sp, color = McdColors.Muted)
        }
        HudPanel(padding = 16.dp) {
            QrCode(link, 280.dp)
            Text(
                "SCAN TO OPEN JARVIS CONTROL", style = hudLabelStyle(9.sp, McdColors.Accent),
                modifier = Modifier.padding(top = 2.dp).align(androidx.compose.ui.Alignment.CenterHorizontally),
            )
        }
    }
}

/** Real-Debrid device sign-in: show a code, user approves at real-debrid.com/device. */
@Composable
fun RdConnectScreen(nav: Nav) {
    var code by remember { mutableStateOf<RealDebrid.DeviceCode?>(null) }
    var status by remember { mutableStateOf("Getting a code…") }
    LaunchedEffect(Unit) {
        try {
            val c = RealDebrid.startDeviceLogin()
            code = c
            status = "Waiting for approval…"
            val until = System.currentTimeMillis() + c.expiresSec * 1000L
            while (System.currentTimeMillis() < until) {
                delay(c.intervalSec * 1000L)
                if (runCatching { RealDebrid.pollDeviceLogin(c) }.getOrDefault(false)) {
                    status = "Connected: " + runCatching { RealDebrid.accountSummary() }.getOrDefault("OK")
                    delay(2500)
                    nav.back()
                    return@LaunchedEffect
                }
            }
            status = "Code expired. Press BACK and try again."
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            status = "Error: ${e.message}"
        }
    }
    Column(Modifier.fillMaxSize().hudBackground().padding(48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        McdLogo()
        Text("CONNECT REAL-DEBRID", style = broadcastStyle(36.sp))
        Text("1.  On your phone or computer, open  real-debrid.com/device", fontSize = 20.sp, color = McdColors.White)
        Text("2.  Sign in to Real-Debrid and enter this code:", fontSize = 20.sp, color = McdColors.White)
        Text(
            code?.userCode ?: "……",
            style = hudDisplayStyle(44.sp, McdColors.Ink, 6.sp),
            modifier = Modifier.hudGlow(true, HudShape).background(McdColors.Accent, HudShape).padding(horizontal = 28.dp, vertical = 8.dp),
        )
        Text(status, fontSize = 18.sp, color = McdColors.Muted)
    }
}
