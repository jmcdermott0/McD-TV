package com.mcd.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mcd.tv.Nav
import com.mcd.tv.Screen
import com.mcd.tv.data.Addons
import com.mcd.tv.data.Channel
import com.mcd.tv.data.Epg
import com.mcd.tv.data.GuideExtra
import com.mcd.tv.data.Game
import com.mcd.tv.data.Games
import com.mcd.tv.data.League
import com.mcd.tv.data.LiveCatalog
import com.mcd.tv.data.LiveFill
import com.mcd.tv.data.LiveIndex
import com.mcd.tv.data.LiveItem
import com.mcd.tv.data.LiveOrganizer
import com.mcd.tv.data.M3u
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.Programme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live TV. With addon live catalogs installed, a source row across the top picks "My Playlist" or a catalog.
 * Without them the playlist browser shows directly: a rail of sections on the left, channel cards on the right.
 */
@Composable
fun LiveTvScreen(nav: Nav) {
    val catalogs by rememberLoad(Prefs.addonUrls) { Addons.liveCatalogs() }
    val hasPlaylist = Prefs.m3uUrl.isNotBlank()
    // null = the M3U playlist; otherwise the index into the catalog list.
    var source by remember { mutableStateOf<Int?>(null) }
    val cats: List<LiveCatalog> = catalogs.let { if (it is Load.Ok) it.value else emptyList() }
    if (!hasPlaylist && source == null && cats.isNotEmpty()) source = 0

    TabPage(nav, NavTab.Live) {
        if (cats.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasPlaylist) item { ActionButton("My Playlist", { source = null }, primary = source == null) }
                items(cats.size) { i -> ActionButton(cats[i].label, { source = i }, primary = source == i) }
            }
        }
        val idx = source
        when {
            idx != null && idx < cats.size -> CatalogList(nav, cats[idx])
            hasPlaylist -> PlaylistBrowser(nav)
            catalogs is Load.Loading -> StatusText("Loading…", Modifier.padding(start = 48.dp))
            else -> Column(Modifier.padding(horizontal = 48.dp)) {
                StatusText("No playlist yet. Add your M3U playlist (or an addon with live channels) on the Control page.")
                ActionButton("Phone & Computer Setup", { nav.push(Screen.PhoneSetup) }, primary = true)
            }
        }
    }
}

// ============================== Playlist browser ==============================

/** Rail keys besides sections ("sec:<name>"). */
private const val KEY_SEARCH = "search"
private const val KEY_FAV = "fav"
private const val KEY_RECENT = "recent"
private const val KEY_FORYOU = "foryou"
private const val KEY_ALL = "all"
/** What's On: the default view (data/LiveHome.kt). */
private const val KEY_HOME = "home"
/** All Channels A-Z: the whole playlist, one entry per network, at the bottom of the rail. */
private const val KEY_AZ = "az"
private const val SEC = "sec:"
/** Live Games: one rail entry, the leagues are chips inside it. */
private const val KEY_GAMES = "games"
/** Live Games' My Teams chip (favorite-team games across leagues), stored in place of a league name. */
private const val MY_TEAMS = "\u0001myteams"
/** Non-focusable rail headings ("CHANNELS"). */
private const val HDR = "hdr:"

private class RailEntry(val key: String, val label: String, val count: Int?)

/** The channel card that has focus, for the hint bar (only the hint bar reads it, so focus moves stay cheap). */
internal class FocusedChannel {
    var channel by mutableStateOf<Channel?>(null)
    var name by mutableStateOf("")
    /** Other feeds of the focused network (All Channels A-Z), and whether they are open under it. */
    var feeds by mutableIntStateOf(0)
    var feedsOpen by mutableStateOf(false)
    /** A What's On tile has focus. */
    var home by mutableStateOf(false)
}

private val InkOnWhite = McdColors.Ink
private val StarGold = Color(0xFFFFC94D)

internal fun countText(n: Int): String = "%,d".format(n)

@Composable
private fun PlaylistBrowser(nav: Nav) {
    // Organizing runs on Dispatchers.Default and is cached for the loaded playlist.
    val res by rememberLoad(Prefs.m3uUrl) { LiveOrganizer.indexFor(M3u.load()) }
    when (val r = res) {
        is Load.Loading -> StatusText("Loading your channels… big playlists can take up to a minute the first time.", Modifier.padding(start = 48.dp))
        is Load.Err -> StatusText(r.message, Modifier.padding(start = 48.dp))
        is Load.Ok -> if (r.value.size == 0) {
            StatusText("Your playlist has no live channels.", Modifier.padding(start = 48.dp))
        } else {
            LiveBrowser(nav, r.value)
        }
    }
}

/**
 * The Live TV browser: Games, Favorites, My Guide, Recent, sections and All. With [onPick] it is a channel
 * picker (Multiview's "choose a channel"): the same screen, but OK hands the channel back instead of playing it.
 */
@Composable
internal fun LiveBrowser(nav: Nav, index: LiveIndex, onPick: ((Channel) -> Unit)? = null) {
    var favorites by remember { mutableStateOf(Prefs.liveFavorites) }
    val recents = remember { Prefs.liveRecents }
    val favItems = remember(index, favorites) { index.indicesOf(favorites) }
    val recentItems = remember(index, recents) { index.indicesOf(recents) }
    val favSet = remember(favorites) { favorites.toHashSet() }
    // Saveable, so Back from a channel returns to the same section, chip and card.
    var selected by rememberSaveable { mutableStateOf("") }
    var lastPlayed by rememberSaveable { mutableStateOf<String?>(null) }
    // The league open inside Live Games ("" = the first one shown).
    var gameLeague by rememberSaveable { mutableStateOf("") }

    // Live Games first: every game this week for each shown league in season (schedules load in the background).
    LaunchedEffect(Unit) { GameBoard.refreshAll() }
    val gamesUi = rememberGamesUi()
    val leagues = GameBoard.visibleLeagues()
    val leagueCounts = leagues.map { GameBoard.gameCount(it) }
    val league = leagues.firstOrNull { it.name == gameLeague } ?: leagues.firstOrNull() ?: League.NFL
    // Favorite-team games this week across the shown leagues (the My Teams chip).
    val myGames = GameBoard.myGames()
    val myTeamsOpen = gameLeague == MY_TEAMS && myGames.isNotEmpty()
    val gamesTotal = if (leagueCounts.all { it == null }) null else leagueCounts.sumOf { it ?: 0 }
    // My Guide: channels picked for this profile (habits, taste, what's on now). Rebuilt when the guide arrives.
    var forYouResult by remember(index, Prefs.activeProfile) { mutableStateOf<com.mcd.tv.data.LiveForYou.Result?>(null) }
    val forYou = forYouResult?.section
    val guideNow = LiveSession.guide
    LaunchedEffect(index, guideNow, Prefs.activeProfile) {
        forYouResult = runCatching { com.mcd.tv.data.LiveForYou.build(index, Prefs.liveFavorites.toHashSet(), guideNow) }.getOrNull()
    }
    val forYouCount = forYou?.count ?: 0
    val forYouReady = forYou != null
    // What's On and All Channels A-Z: one entry per network (built once per playlist, off the main thread).
    val profile = Prefs.activeProfile
    var groups by remember(index) { mutableStateOf<com.mcd.tv.data.LiveGroups?>(null) }
    LaunchedEffect(index) { groups = runCatching { LiveHomeData.groups(index) }.getOrNull() }
    var homeRows by remember(index, profile) { mutableStateOf(LiveHomeData.cachedRows(index, profile)) }
    val myTeams = GameBoard.myTeams()
    val entries = remember(index, favItems.size, recentItems.size, leagues.isNotEmpty(), gamesTotal, forYouCount, forYouReady) {
        buildList {
            // What's On first: rows of games and networks picked from the guide. The whole playlist waits behind
            // All Channels A-Z at the bottom (before, every section showed its full channel count here).
            add(RailEntry(KEY_HOME, "★  What's On", null))
            // Each league's week; the leagues are chips inside it, so they never crowd the rail.
            if (leagues.isNotEmpty()) add(RailEntry(KEY_GAMES, "🏆  Schedules", gamesTotal))
            add(RailEntry(HDR + "channels", "CHANNELS", null))
            add(RailEntry(KEY_SEARCH, "⌕  Search", null))
            add(RailEntry(KEY_FAV, "★  Favorites", favItems.size))
            // Always listed (count appears once the picks are ready), so it's there from the first moment.
            add(RailEntry(KEY_FORYOU, "✦  My Guide", if (forYou != null) forYouCount else null))
            if (recentItems.isNotEmpty()) add(RailEntry(KEY_RECENT, "Recent", recentItems.size))
            add(RailEntry(KEY_AZ, "All Channels A–Z", null))
        }
    }
    // A fresh open lands on What's On; the Kids profile opens All Channels A-Z on Kids.
    val sel = when {
        selected.isNotEmpty() && entries.any { it.key == selected } -> selected
        Prefs.isKidsProfile -> KEY_AZ
        else -> KEY_HOME
    }
    // A game's channel list closes when another rail entry or league is picked.
    LaunchedEffect(sel, league, myTeamsOpen) {
        val p = gamesUi.picker
        if (p != null && (sel != KEY_GAMES || myTeamsOpen || p.league != league)) gamesUi.picker = null
    }
    // Favorites as listed when the section opened: un-starring a card keeps it in place (so focus does not jump).
    val favView = remember(index, sel == KEY_FAV) { index.indicesOf(Prefs.liveFavorites) }

    val railRequesters = remember { HashMap<String, FocusRequester>() }
    val requesterFor: (String) -> FocusRequester = { k -> railRequesters.getOrPut(k) { FocusRequester() } }
    val restoreFocus = remember { FocusRequester() }
    // The open league's chip in Live Games: where a fresh open puts focus.
    val leagueFocus = remember { FocusRequester() }
    // What's On: its first tile takes focus on a fresh open; Back from the player returns to the tile played.
    val homeFocus = remember { FocusRequester() }
    var lastTile by rememberSaveable { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val hint = remember { FocusedChannel() }

    // Ticks every minute so now/next and progress bars stay current.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    // Program guide (XMLTV from the playlist or the Xtream API): loads in the background, cached 6 h.
    // A failed load is tried again twice, 90 s apart, while this screen is open.
    LaunchedEffect(index) {
        val url = M3u.guideUrl
        if (url.isNotBlank()) {
            for (attempt in 0..2) {
                if (attempt > 0) delay(91_000)
                val g = Epg.load(url, index.source)
                if (g.isNotEmpty()) { LiveSession.guide = GuideExtra.withCached(g, index.source); break }
            }
        }
    }
    // Extra guide for free streaming channels the provider's guide lacks (GuideExtra): added when it arrives.
    LaunchedEffect(index) {
        val x = GuideExtra.load(index.source)
        if (x.isNotEmpty()) LiveSession.guide = GuideExtra.merge(LiveSession.guide, x)
    }
    // Start on the card last played (Back from the player). Otherwise on What's On's first tile, or in Schedules on
    // the open league's chip, else on the selected rail entry.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        val restored = lastPlayed != null && runCatching { restoreFocus.requestFocus() }.isSuccess
        val onHome = !restored && sel == KEY_HOME && runCatching { homeFocus.requestFocus() }.isSuccess
        val onLeague = !restored && !onHome && sel == KEY_GAMES && runCatching { leagueFocus.requestFocus() }.isSuccess
        if (!restored && !onHome && !onLeague) runCatching { requesterFor(sel).requestFocus() }
    }
    // What's On builds in the background: when its rows first appear, focus moves onto them (the tile played last
    // after Back from the player, else the first tile).
    var homeFocusDone by remember { mutableStateOf(false) }
    val rowsReady = homeRows != null
    LaunchedEffect(rowsReady) {
        if (!rowsReady || homeFocusDone || sel != KEY_HOME) return@LaunchedEffect
        homeFocusDone = true
        withFrameNanos { }
        withFrameNanos { }
        val restored = lastTile != null && runCatching { restoreFocus.requestFocus() }.isSuccess
        if (!restored) runCatching { homeFocus.requestFocus() }
    }
    // [feedsOf]: the list is one network's feeds (the player then says "feed 2 of 6" and UP / DOWN switch feeds).
    val playList: (IntArray, Int, String?) -> Unit = { list, pos, feedsOf ->
        if (onPick != null) {
            onPick(index.channels[list[pos]])
        } else {
            LiveSession.channels = index.channelsOf(list)
            LiveSession.feedsOf = feedsOf
            lastPlayed = index.channels[list[pos]].url
            nav.push(Screen.LivePlay(pos))
        }
    }
    val play: (IntArray, Int) -> Unit = { list, pos -> playList(list, pos, null) }
    // Favorites changed on a phone or another TV: merge them with this TV's, then show them.
    LaunchedEffect(Unit) {
        if (com.mcd.tv.data.HouseCloud.syncLiveFavorites(Prefs.activeProfile)) favorites = Prefs.liveFavorites
    }
    val toggleFavorite: (Channel) -> Unit = { ch ->
        Prefs.toggleLiveFavorite(ch.url)
        favorites = Prefs.liveFavorites
    }
    val guide = LiveSession.guide
    // What's On rows: rebuilt when the guide, a schedule, the minute or the teams change.
    LaunchedEffect(groups, guide, now / 60_000L, GameBoard.version, myTeams) {
        val g = groups ?: return@LaunchedEffect
        homeRows = runCatching { LiveHomeData.rows(index, g, guide, profile, now) }.getOrNull() ?: homeRows
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth().padding(start = 24.dp, top = 4.dp)) {
            Column(Modifier.width(200.dp).fillMaxHeight()) {
                // Multiview: watch 1, 2 or 4 channels at once (UP from the top of the rail).
                if (onPick == null) {
                    ActionButton(
                        "Multiview",
                        { nav.push(Screen.Multiview) },
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
                LiveRail(
                    entries = entries,
                    selected = sel,
                    requesterFor = requesterFor,
                    onSelect = { selected = it },
                    onOpen = { focusManager.moveFocus(FocusDirection.Right) },
                )
            }
            Spacer(Modifier.width(16.dp))
            // Back inside the content returns to the rail (Back on the rail leaves Live TV as usual).
            Box(
                Modifier.weight(1f).fillMaxHeight().onPreviewKeyEvent { e ->
                    if (e.key == Key.Back && gamesUi.picker != null && sel == KEY_GAMES) {
                        // Back in a game's channel list returns to the games.
                        if (e.type == KeyEventType.KeyUp) gamesUi.picker = null
                        true
                    } else if (e.key == Key.Back) {
                        if (e.type == KeyEventType.KeyUp) runCatching { requesterFor(sel).requestFocus() }
                        true
                    } else {
                        false
                    }
                },
            ) {
                if (sel == KEY_HOME) {
                    WhatsOnPane(
                        rows = homeRows,
                        index = index,
                        groups = groups,
                        hasTeams = myTeams.isNotEmpty(),
                        hasGuide = guide.isNotEmpty(),
                        firstFocus = homeFocus,
                        restoreFocus = restoreFocus,
                        lastTile = lastTile,
                        onTile = { key, t ->
                            lastTile = key
                            // A game or a network: its feeds, the one to play first at the top.
                            playList(t.feeds, 0, if (t.feeds.size > 1) t.title else null)
                        },
                        onOpenAz = {
                            selected = KEY_AZ
                            runCatching { requesterFor(KEY_AZ).requestFocus() }
                        },
                        hint = hint,
                    )
                } else if (sel == KEY_AZ) {
                    val g = groups
                    if (g == null) {
                        StatusText("Sorting ${countText(index.size)} channels by network…")
                    } else {
                        AzPane(
                            groups = g,
                            index = index,
                            favSet = favSet,
                            guide = guide,
                            now = now,
                            hint = hint,
                            lastPlayed = lastPlayed,
                            restoreFocus = restoreFocus,
                            initialKind = if (Prefs.isKidsProfile) "kids" else "all",
                            onPlayGroup = { grp, i ->
                                val feeds = grp.feeds.toIntArray()
                                playList(feeds, feeds.indexOf(i).coerceAtLeast(0), if (!grp.family && feeds.size > 1) grp.label else null)
                            },
                            onMenu = toggleFavorite,
                        )
                    }
                } else if (sel == KEY_GAMES) {
                    LiveGamesPane(
                        leagues = leagues,
                        counts = leagueCounts,
                        league = league,
                        myGames = myGames,
                        myTeamsOpen = myTeamsOpen,
                        chipFocus = leagueFocus,
                        railFocus = requesterFor(KEY_GAMES),
                        onLeague = { gameLeague = it.name },
                        onMyTeams = { gameLeague = MY_TEAMS },
                        openGame = { g ->
                            gameLeague = g.league.name
                            gamesUi.openPicker(g)
                        },
                        index = index,
                        guide = guide,
                        gamesUi = gamesUi,
                        onPlay = play,
                        restoreFocus = restoreFocus,
                    )
                } else if (sel == KEY_FORYOU && forYou == null) {
                    StatusText("Building your guide… picking channels for ${Prefs.activeProfileName}.")
                } else if (sel == KEY_FORYOU && guide.isNotEmpty() && forYou != null) {
                    // Old-school TV guide of this profile's channels, its kind of shows lit up.
                    MyGuidePane(
                        index = index,
                        items = forYou.items,
                        matcher = forYouResult?.matcher,
                        guide = guide,
                        now = now,
                        favSet = favSet,
                        lastPlayed = lastPlayed,
                        restoreFocus = restoreFocus,
                        onPlay = play,
                        onMenu = toggleFavorite,
                        onFocusChannel = { ch, name ->
                            if (ch != null) {
                                hint.channel = ch
                                hint.name = name
                                hint.feeds = 0
                            } else {
                                hint.channel = null
                            }
                        },
                    )
                } else if (sel == KEY_SEARCH) {
                    SearchPane(
                        index, favSet, guide, now, hint, lastPlayed, restoreFocus, play, toggleFavorite,
                        openGames = { g ->
                            selected = KEY_GAMES
                            gameLeague = g.league.name
                            gamesUi.openPicker(g)
                        },
                    )
                } else if (sel.startsWith(SEC) && index.sections.firstOrNull { SEC + it.name == sel }?.networks?.isNotEmpty() == true) {
                    // Entertainment: one row per network, the other feeds nested under it, A to Z jumps.
                    NetworkPane(
                        section = index.sections.first { SEC + it.name == sel },
                        index = index,
                        favorites = favorites,
                        recents = recents,
                        favSet = favSet,
                        guide = guide,
                        now = now,
                        hint = hint,
                        lastPlayed = lastPlayed,
                        restoreFocus = restoreFocus,
                        onPlay = play,
                        onMenu = toggleFavorite,
                    )
                } else {
                    val section = if (sel.startsWith(SEC)) index.sections.firstOrNull { SEC + it.name == sel } else null
                    val (title, base) = when (sel) {
                        KEY_FAV -> "Favorites" to favView
                        KEY_RECENT -> "Recent" to recentItems
                        KEY_FORYOU -> "My Guide" to (forYou?.items ?: IntArray(0))
                        KEY_ALL -> "All channels" to index.all
                        else -> (section?.name ?: "Channels") to (section?.items ?: IntArray(0))
                    }
                    SectionPane(
                        paneKey = sel,
                        title = title,
                        base = base,
                        subgroups = if (sel == KEY_FORYOU) forYou?.subgroups.orEmpty() else section?.subgroups ?: emptyList(),
                        emptyText = if (sel == KEY_FAV) "No favorites yet. Focus a channel and press ☰ (Menu) to add it." else "No channels here.",
                        index = index,
                        favSet = favSet,
                        guide = guide,
                        now = now,
                        hint = hint,
                        lastPlayed = lastPlayed,
                        restoreFocus = restoreFocus,
                        onPlay = play,
                        onMenu = toggleFavorite,
                    )
                }
            }
        }
        HintBar(hint, favSet, gamesMode = sel == KEY_GAMES, picking = onPick != null, homeMode = sel == KEY_HOME)
    }
}

/** Left rail. Focus picks an entry; OK moves into the channels. Coming back always lands on the picked entry. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LiveRail(
    entries: List<RailEntry>,
    selected: String,
    requesterFor: (String) -> FocusRequester,
    onSelect: (String) -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        Modifier
            .width(200.dp)
            .fillMaxHeight()
            .focusProperties { enter = { requesterFor(selected) } }
            .focusGroup()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        entries.forEach { e ->
            // Keyed, so entries appearing above (Games load in the background) never move focus or state.
            androidx.compose.runtime.key(e.key) {
                if (e.key.startsWith(HDR)) {
                    Text(
                        e.label,
                        style = hudLabelStyle(10.sp, McdColors.Muted),
                        modifier = Modifier.padding(start = 11.dp, top = 6.dp, bottom = 2.dp),
                    )
                } else {
                    RailItem(e.label, e.count, e.key == selected, requesterFor(e.key), onFocus = { onSelect(e.key) }, onClick = onOpen)
                }
            }
        }
    }
}

@Composable
private fun RailItem(label: String, count: Int?, selected: Boolean, requester: FocusRequester, onFocus: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val lit = focused || selected
    Row(
        Modifier
            .fillMaxWidth()
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .hudBrackets(focused, McdColors.AccentBright, inset = 0.dp, arm = 6.dp, stroke = 1.5.dp)
            .clip(HudShapeSmall)
            .background(
                when {
                    focused -> McdColors.Accent.copy(alpha = 0.16f)
                    selected -> McdColors.Raised.copy(alpha = 0.7f)
                    else -> Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left glowing bar on the picked / focused entry.
        Box(
            Modifier.width(3.dp).height(16.dp)
                .then(
                    if (lit) Modifier.drawBehind {
                        val g = 3.dp.toPx()
                        drawRect(McdColors.Accent.copy(alpha = 0.3f), topLeft = Offset(-g / 2f, -g / 2f), size = Size(size.width + g, size.height + g))
                    } else Modifier,
                )
                .background(if (lit) McdColors.Accent else Color.Transparent),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = when {
                focused -> McdColors.AccentBright
                selected -> McdColors.Accent
                else -> McdColors.Muted
            },
            fontSize = 15.sp,
            fontWeight = if (lit) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Text(countText(count), color = if (lit) McdColors.Accent.copy(alpha = 0.8f) else McdColors.Muted.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1)
        }
        if (lit) {
            Spacer(Modifier.width(4.dp))
            Text("›", color = McdColors.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Live Games: a chip per shown league ("🏈 NFL Games  27"), then the open league's week. OK on a chip opens that
 * league; DOWN from a chip reaches its first live game (else the next one). Leagues a profile hid never show.
 */
@Composable
private fun LiveGamesPane(
    leagues: List<League>,
    counts: List<Int?>,
    league: League,
    myGames: List<Game>,
    myTeamsOpen: Boolean,
    chipFocus: FocusRequester,
    railFocus: FocusRequester,
    onLeague: (League) -> Unit,
    onMyTeams: () -> Unit,
    openGame: (Game) -> Unit,
    index: LiveIndex,
    guide: Map<String, List<Programme>>,
    gamesUi: GamesUi,
    onPlay: (IntArray, Int) -> Unit,
    restoreFocus: FocusRequester,
) {
    Column(Modifier.fillMaxSize()) {
        // Not lazy: every chip is composed, so the open league's chip can always take focus.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 6.dp, end = 32.dp, top = 6.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // LEFT from the first chip goes to Live Games in the rail, not to Multiview above it.
            val toRail = Modifier.focusProperties { left = railFocus }
            if (myGames.isNotEmpty()) {
                ActionButton(
                    "★ My Teams  ${countText(myGames.size)}",
                    onMyTeams,
                    toRail.then(if (myTeamsOpen) Modifier.focusRequester(chipFocus) else Modifier),
                    primary = myTeamsOpen,
                )
            }
            leagues.forEachIndexed { i, l ->
                ActionButton(
                    l.railLabel + (counts[i]?.let { "  " + countText(it) } ?: ""),
                    { onLeague(l) },
                    (if (l == league && !myTeamsOpen) Modifier.focusRequester(chipFocus) else Modifier)
                        .then(if (i == 0 && myGames.isEmpty()) toRail else Modifier),
                    primary = l == league && !myTeamsOpen,
                )
            }
        }
        if (myTeamsOpen) {
            MyTeamsPane(myGames, index, guide, gamesUi, onPlay, openGame, restoreFocus)
        } else {
            // Keyed by league: each one starts at its own top (and focus target).
            androidx.compose.runtime.key(league) { GamesPane(league, index, guide, gamesUi, onPlay, restoreFocus) }
        }
    }
}

/**
 * My Teams: this week's games of the profile's favorite teams across the shown leagues, live first. OK plays the
 * game's best channel; Menu (or a game with no channel) opens its league with the channel list.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MyTeamsPane(
    games: List<Game>,
    index: LiveIndex,
    guide: Map<String, List<Programme>>,
    gamesUi: GamesUi,
    onPlay: (IntArray, Int) -> Unit,
    openGame: (Game) -> Unit,
    restoreFocus: FocusRequester,
) {
    val matches = rememberGameChannels(games, index, guide)
    val first = remember { FocusRequester() }
    var firstShown by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 6.dp, top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text("MY TEAMS", style = broadcastStyle(18.sp))
            Spacer(Modifier.width(10.dp))
            val live = games.count { it.live }
            Text(
                if (live > 0) "$live live  ·  ${games.size} games this week" else "${games.size} games this week",
                color = if (live > 0) McdColors.LiveRed else McdColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(320.dp),
            contentPadding = PaddingValues(start = 6.dp, end = 32.dp, top = 6.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .focusProperties { enter = { if (firstShown) first else FocusRequester.Default } }
                .focusGroup(),
        ) {
            items(count = games.size, key = { games[it].key }) { i ->
                val g = games[i]
                if (i == 0) {
                    androidx.compose.runtime.DisposableEffect(Unit) {
                        firstShown = true
                        onDispose { firstShown = false }
                    }
                }
                var m: Modifier = if (i == 0) Modifier.focusRequester(first) else Modifier
                if (g.key == gamesUi.lastGame) m = m.focusRequester(restoreFocus)
                GameCard(
                    g = g,
                    channels = matches?.get(g.key),
                    hasPlaylist = true,
                    compact = false,
                    withDay = true,
                    mine = true,
                    onClick = {
                        gamesUi.lastGame = g.key
                        val c = matches?.get(g.key)
                        if (c != null && c.size > 0) onPlay(c.items, 0) else openGame(g)
                    },
                    onMenu = { openGame(g) },
                    modifier = m,
                )
            }
        }
    }
}

private const val CHIP_YOURS = "\u0001yours"

/**
 * Entertainment by network: one row per network with its best feed, the other feeds nested under it (RIGHT opens
 * them). Networks with a favorite or recent channel come first, then US networks A to Z. Letter chips jump.
 */
@Composable
private fun NetworkPane(
    section: com.mcd.tv.data.LiveSection,
    index: LiveIndex,
    favorites: List<String>,
    recents: List<String>,
    favSet: Set<String>,
    guide: Map<String, List<Programme>>,
    now: Long,
    hint: FocusedChannel,
    lastPlayed: String?,
    restoreFocus: FocusRequester,
    onPlay: (IntArray, Int) -> Unit,
    onMenu: (Channel) -> Unit,
) {
    // Ordered when the pane opens: starring a channel here does not move rows under the remote.
    val personal = remember(section, index) { LiveOrganizer.personalize(section.networks, index.channels, favorites, recents) }
    val ordered = personal.first
    val pinned = personal.second
    val letters = remember(ordered) { ordered.groupBy { LiveOrganizer.letterOf(index.names[it.primary]) }.toSortedMap() }
    // null = all networks, CHIP_YOURS = pinned ones, else a letter.
    var chip by rememberSaveable(section.name) { mutableStateOf<String?>(null) }
    // The network whose other feeds are open under it (its key).
    var openKey by rememberSaveable(section.name) { mutableStateOf<String?>(null) }
    val shown = when (val c = chip) {
        null -> ordered
        CHIP_YOURS -> ordered.take(pinned)
        else -> letters[c] ?: ordered
    }
    val byPrimary = remember(shown) { HashMap<Int, com.mcd.tv.data.LiveNetwork>().also { m -> shown.forEach { m[it.primary] = it } } }
    val (list, nested) = remember(shown, openKey) {
        val out = ArrayList<Int>(shown.size + 8)
        val alt = HashSet<Int>()
        for (net in shown) {
            out.add(net.primary)
            if (net.key == openKey) for (k in 1 until net.feeds.size) { out.add(net.feeds[k]); alt.add(net.feeds[k]) }
        }
        out.toIntArray() to alt
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(section.name.uppercase(), style = broadcastStyle(18.sp))
            Spacer(Modifier.width(10.dp))
            Text(
                "${countText(ordered.size)} networks  ·  ${countText(section.count)} channels",
                color = McdColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(start = 2.dp, end = 32.dp, top = 4.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "\u0001all") { ActionButton("All  ${countText(ordered.size)}", { chip = null }, primary = chip == null) }
            if (pinned > 0) item(key = CHIP_YOURS) { ActionButton("★ Yours  $pinned", { chip = CHIP_YOURS }, primary = chip == CHIP_YOURS) }
            items(letters.keys.toList(), key = { it }) { l -> ActionButton(l, { chip = l }, primary = chip == l) }
        }
        // Keyed by chip: each list starts at the top. Opening a network's feeds keeps the list and its focus.
        androidx.compose.runtime.key(section.name, chip) {
            ChannelGrid(
                list, index, favSet, guide, now, hint, lastPlayed, restoreFocus, onPlay, onMenu,
                feeds = { i -> byPrimary[i]?.alternates ?: 0 },
                feedsOpen = { i -> byPrimary[i]?.key == openKey },
                nested = { i -> i in nested },
                onFeeds = { i ->
                    val k = byPrimary[i]?.key
                    if (k != null) {
                        openKey = if (openKey == k) null else k
                        hint.feedsOpen = openKey == k
                    }
                },
            )
        }
    }
}

/** A section (or Favorites / Recent / All): optional sub-group chips above the channel grid. */
@Composable
private fun SectionPane(
    paneKey: String,
    title: String,
    base: IntArray,
    subgroups: List<com.mcd.tv.data.LiveSubgroup>,
    emptyText: String,
    index: LiveIndex,
    favSet: Set<String>,
    guide: Map<String, List<Programme>>,
    now: Long,
    hint: FocusedChannel,
    lastPlayed: String?,
    restoreFocus: FocusRequester,
    onPlay: (IntArray, Int) -> Unit,
    onMenu: (Channel) -> Unit,
) {
    // null = All. Resets when the section changes.
    var chip by rememberSaveable(paneKey) { mutableStateOf<String?>(null) }
    val current = subgroups.firstOrNull { it.label == chip }
    val shown = current?.items ?: base
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(title.uppercase(), style = broadcastStyle(18.sp))
            Spacer(Modifier.width(10.dp))
            Text("${countText(shown.size)} channels", color = McdColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
        if (subgroups.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(start = 2.dp, end = 32.dp, top = 4.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "\u0001all") { ActionButton("All  ${countText(base.size)}", { chip = null }, primary = current == null) }
                items(subgroups.size) { k ->
                    val g = subgroups[k]
                    ActionButton("${g.label}  ${countText(g.items.size)}", { chip = g.label }, primary = current === g)
                }
            }
        }
        if (shown.isEmpty()) {
            StatusText(emptyText)
        } else {
            // Keyed by section + chip: each list starts at the top, and Back restores its scroll position.
            androidx.compose.runtime.key(paneKey, current?.label) {
                ChannelGrid(shown, index, favSet, guide, now, hint, lastPlayed, restoreFocus, onPlay, onMenu)
            }
        }
    }
}

/** Search across every channel's clean and original name (debounced, at most 200 results). */
@Composable
private fun SearchPane(
    index: LiveIndex,
    favSet: Set<String>,
    guide: Map<String, List<Programme>>,
    now: Long,
    hint: FocusedChannel,
    lastPlayed: String?,
    restoreFocus: FocusRequester,
    onPlay: (IntArray, Int) -> Unit,
    onMenu: (Channel) -> Unit,
    openGames: (Game) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // A team name ("packers", "green bay", "chiefs") also lists that team's games this week, above the channels.
    val teamGames = remember(query.trim(), GameBoard.version) { Games.forTeamQuery(query) }
    val teamChannels = rememberGameChannels(teamGames, index, guide)
    val firstGame = remember { FocusRequester() }
    // First result list is computed right away so Back from a channel can focus it again.
    var results by remember(index) { mutableStateOf(index.search(query, 200)) }
    LaunchedEffect(index, query) {
        delay(300)
        results = withContext(Dispatchers.Default) { index.search(query, 200) }
    }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val firstResult = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    /** Done typing (keyboard's Search/Done/Next, or DOWN): close the keyboard and move to the first channel. */
    val toResults: () -> Unit = {
        keyboard?.hide()
        results = index.search(query, 200)
        val target = if (teamGames.isNotEmpty()) firstGame else firstResult
        scope.launch {
            withFrameNanos { }
            withFrameNanos { }
            runCatching { target.requestFocus() }
        }
    }
    Column(Modifier.fillMaxSize()) {
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = McdColors.White, fontSize = 18.sp),
            cursorBrush = SolidColor(McdColors.Red),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = false),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSearch = { toResults() }, onDone = { toResults() }, onNext = { toResults() }, onGo = { toResults() },
            ),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text("Channel name… (press OK to type)", color = McdColors.Muted, fontSize = 18.sp)
                    inner()
                }
            },
            modifier = Modifier.padding(top = 4.dp, end = 32.dp, bottom = 8.dp).fillMaxWidth()
                .onPreviewKeyEvent { e ->
                    if (e.key == Key.DirectionDown && (results.isNotEmpty() || teamGames.isNotEmpty()) && query.isNotBlank()) {
                        if (e.type == KeyEventType.KeyDown) toResults()
                        true
                    } else {
                        false
                    }
                }
                .background(McdColors.Card, HudShape).border(1.5.dp, McdColors.Accent, HudShape)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
        val q = query.trim()
        if (q.isNotEmpty() && teamGames.isNotEmpty()) {
            TeamGamesRow(
                games = teamGames,
                channels = teamChannels,
                hasPlaylist = true,
                firstFocus = firstGame,
                onOpen = { g ->
                    val m = teamChannels?.get(g.key)
                    if (m != null && m.size > 0) onPlay(m.items, 0) else openGames(g)
                },
                onMenu = openGames,
            )
        }
        when {
            q.isEmpty() -> StatusText("Search all ${countText(index.size)} channels by name, or a team for its games.")
            results.isEmpty() -> StatusText("No channels match \"$q\".")
            else -> ChannelGrid(results, index, favSet, guide, now, hint, lastPlayed, restoreFocus, onPlay, onMenu, firstFocus = firstResult)
        }
    }
}

/**
 * Channels as a guide list: one row per channel with its logo and name, what's on now (with its times
 * and a progress bar) and what's on next. Lazy, keyed by stream URL.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ChannelGrid(
    list: IntArray,
    index: LiveIndex,
    favSet: Set<String>,
    guide: Map<String, List<Programme>>,
    now: Long,
    hint: FocusedChannel,
    lastPlayed: String?,
    restoreFocus: FocusRequester,
    onPlay: (IntArray, Int) -> Unit,
    onMenu: (Channel) -> Unit,
    firstFocus: FocusRequester? = null,
    // Entertainment's networks: other feeds of a row's network, whether they are open, rows that are such a feed.
    feeds: (Int) -> Int = { 0 },
    feedsOpen: (Int) -> Boolean = { false },
    nested: (Int) -> Boolean = { false },
    onFeeds: ((Int) -> Unit)? = null,
    // A row's own label (All Channels A-Z: the network's name), else its clean channel name.
    nameOf: (Int) -> String? = { null },
) {
    val gridState = rememberLazyGridState()
    val ownFirst = remember { FocusRequester() }
    val first = firstFocus ?: ownFirst
    // True while the first card is composed (a FocusRequester must be attached before focus is sent to it).
    var firstShown by remember { mutableStateOf(false) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(1),
        state = gridState,
        contentPadding = PaddingValues(start = 6.dp, end = 32.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        // Coming in from the rail (now taller with the Games block) lands on the first card while the grid is at
        // the top, not on whichever row happens to line up with the rail entry.
        modifier = Modifier
            .fillMaxSize()
            .focusProperties { enter = { if (firstShown && gridState.firstVisibleItemIndex == 0) first else FocusRequester.Default } }
            .focusGroup(),
    ) {
        items(count = list.size, key = { pos -> index.channels[list[pos]].url }) { pos ->
            val i = list[pos]
            val ch = index.channels[i]
            val name = nameOf(i) ?: index.names[i]
            if (pos == 0) {
                androidx.compose.runtime.DisposableEffect(Unit) {
                    firstShown = true
                    onDispose { firstShown = false }
                }
            }
            val inner = nested(i)
            val more = feeds(i)
            LiveChannelListRow(
                ch = ch,
                name = if (inner) LiveOrganizer.feedLabel(ch.name) else name,
                favorite = ch.url in favSet,
                guide = guide,
                now = now,
                onClick = { onPlay(list, pos) },
                onMenu = { onMenu(ch) },
                feeds = more,
                feedsOpen = more > 0 && feedsOpen(i),
                nested = inner,
                onFeeds = if (more > 0 && onFeeds != null) ({ onFeeds(i) }) else null,
                onFocused = { focused ->
                    if (focused) {
                        hint.channel = ch
                        hint.name = name
                        hint.feeds = more
                        hint.feedsOpen = more > 0 && feedsOpen(i)
                    } else if (hint.channel === ch) {
                        hint.channel = null
                    }
                },
                modifier = (if (ch.url == lastPlayed) Modifier.focusRequester(restoreFocus) else Modifier)
                    .then(if (pos == 0) Modifier.focusRequester(first) else Modifier),
            )
        }
    }
}

private val LiveCardShape = HudShape

/**
 * One channel in a section list: logo, name (gold star on favorites), then NOW: title, "8:00 PM to 9:00 PM"
 * and a progress bar, then NEXT: time and title. Without guide data it says so. Menu (☰) toggles the favorite.
 */
@Composable
private fun LiveChannelListRow(
    ch: Channel,
    name: String,
    favorite: Boolean,
    guide: Map<String, List<Programme>>,
    now: Long,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    onFocused: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    feeds: Int = 0,
    feedsOpen: Boolean = false,
    nested: Boolean = false,
    onFeeds: (() -> Unit)? = null,
) {
    val (cur, next) = remember(guide, ch.url, now / 60_000L) { LiveFill.nowNext(ch, guide, now) }
    HudCard(
        onClick = onClick,
        onLongClick = onMenu,
        focusedScale = 1.02f,
        modifier = modifier
            .then(if (nested) Modifier.padding(start = 36.dp) else Modifier)
            .fillMaxWidth()
            .height(if (nested) 64.dp else 76.dp)
            .onFocusChanged { onFocused(it.isFocused) }
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Menu) {
                    if (e.type == KeyEventType.KeyUp) onMenu()
                    true
                } else if (e.key == Key.DirectionRight && onFeeds != null) {
                    // RIGHT opens (or closes) this network's other feeds under it. On key down: the RIGHT that moved
                    // focus here from the rail ends its key up on this row, and must not open them.
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) onFeeds()
                    true
                } else {
                    false
                }
            },
    ) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Logo (or the name on a tile when there is none).
            Box(
                Modifier.width(96.dp).fillMaxHeight().clip(HudShapeSmall).background(McdColors.Ink.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                if (ch.logo != null) {
                    AsyncImage(
                        model = ch.logo, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                } else {
                    Text(name, style = broadcastStyle(11.sp), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(4.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            // Channel name.
            Column(Modifier.width(170.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name, color = if (focused) McdColors.AccentBright else McdColors.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (favorite) Text("  ★", color = StarGold, fontSize = 13.sp)
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (cur != null) LiveBadge()
                    if (feeds > 0) {
                        if (cur != null) Spacer(Modifier.width(8.dp))
                        Text(
                            (if (feedsOpen) "▾ " else "▸ ") + if (feeds == 1) "1 more feed" else "$feeds more feeds",
                            color = if (focused) McdColors.Accent else McdColors.Muted, fontSize = 11.sp, maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            // On now: title, start to end, progress.
            Column(Modifier.weight(1f)) {
                if (cur != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("NOW", color = McdColors.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Text(cur.title, color = McdColors.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(if (cur.untimed) "Live event" else "${guideTime(cur.start)} to ${guideTime(cur.end)}", color = McdColors.Muted, fontSize = 12.sp, maxLines = 1)
                    if (!cur.untimed) {
                        Box(Modifier.padding(top = 4.dp).fillMaxWidth(0.9f).height(3.dp).background(McdColors.Line.copy(alpha = 0.5f))) {
                            Box(Modifier.fillMaxWidth(programmeProgress(cur, now)).height(3.dp).background(McdColors.Accent))
                        }
                    }
                } else {
                    val msg = when {
                        next != null -> "Starts ${guideTime(next.start)}"
                        guide.isEmpty() -> "Guide loading…"
                        else -> "No guide info for this channel"
                    }
                    Text(msg, color = McdColors.Muted, fontSize = 13.sp, maxLines = 1)
                }
            }
            // Up next.
            if (next != null) {
                Spacer(Modifier.width(12.dp))
                Column(Modifier.width(190.dp)) {
                    Text("NEXT  ${guideTime(next.start)}", color = McdColors.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(next.title, color = McdColors.Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Channel card: logo centered on a dark tile, clean name and what's on now underneath, a thin progress bar
 * and a gold star on favorites. Menu (☰) toggles the favorite; so does a long press where the remote supports it.
 */
@Composable
private fun LiveChannelCard(
    ch: Channel,
    name: String,
    favorite: Boolean,
    guide: Map<String, List<Programme>>,
    now: Long,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    onFocused: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cur = remember(guide, ch.url, now / 60_000L) { LiveFill.nowNext(ch, guide, now).first }
    Column(modifier) {
        HudCard(
            onClick = onClick,
            onLongClick = onMenu,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onFocusChanged { onFocused(it.isFocused) }
                .onPreviewKeyEvent { e ->
                    if (e.key == Key.Menu) {
                        if (e.type == KeyEventType.KeyUp) onMenu()
                        true
                    } else {
                        false
                    }
                },
        ) { _ ->
            Box(Modifier.fillMaxSize()) {
                if (ch.logo != null) {
                    AsyncImage(
                        model = ch.logo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
                    )
                } else {
                    Text(
                        name,
                        style = broadcastStyle(15.sp),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.Center).padding(8.dp),
                    )
                }
                if (favorite) {
                    Text("★", color = StarGold, fontSize = 14.sp, modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 8.dp, vertical = 3.dp))
                }
                if (cur != null) LiveBadge(Modifier.align(Alignment.TopStart).padding(7.dp))
                if (cur != null) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(McdColors.Line.copy(alpha = 0.5f))) {
                        Box(Modifier.fillMaxWidth(programmeProgress(cur, now)).height(3.dp).background(McdColors.Accent))
                    }
                }
            }
        }
        Text(
            name,
            color = McdColors.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        // Always present (even blank) so every grid row has the same height.
        Text(
            if (cur != null) cur.title else "",
            color = McdColors.Muted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Bottom line: what OK and Menu do on the focused card. */
@Composable
private fun HintBar(hint: FocusedChannel, favSet: Set<String>, gamesMode: Boolean = false, picking: Boolean = false, homeMode: Boolean = false) {
    val ch = hint.channel
    Row(
        Modifier.fillMaxWidth().height(30.dp).background(McdColors.Ink.copy(alpha = 0.6f)).drawBehind { drawLine(McdColors.Line, Offset(0f, 0f), Offset(size.width, 0f), 1f) }.padding(start = 32.dp, end = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (homeMode && ch == null) {
            Text(hint.name.takeIf { hint.home } ?: "What's on for you", color = McdColors.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            KeyHint("OK", if (picking) "Put in this tile" else "Play")
            Spacer(Modifier.width(18.dp))
            KeyHint("▲▼", "In the player: the network's other feeds")
            Spacer(Modifier.width(18.dp))
            KeyHint("↓", "All Channels A–Z at the bottom")
        } else if (ch != null) {
            val fav = ch.url in favSet
            Text(hint.name, color = McdColors.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            KeyHint("OK", if (picking) "Put in this tile" else "Play")
            if (hint.feeds > 0) {
                Spacer(Modifier.width(18.dp))
                KeyHint("►", if (hint.feedsOpen) "Hide the other feeds" else "${hint.feeds} other feeds")
            }
            Spacer(Modifier.width(18.dp))
            KeyHint("☰", if (fav) "★ Favorite (press to remove)" else "☆ Add to favorites")
        } else if (gamesMode) {
            KeyHint("OK", if (picking) "Put the game's best channel in this tile" else "Watch the game on its best channel")
            Spacer(Modifier.width(18.dp))
            KeyHint("☰", "Every channel showing it")
        } else {
            Text(
                "Pick a section on the left, then press ► to browse its channels.  ☰ on a channel adds it to Favorites.",
                color = McdColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun KeyHint(keyLabel: String, action: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            keyLabel,
            color = InkOnWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(HudShapeTiny).background(McdColors.Accent).padding(horizontal = 6.dp, vertical = 1.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(action, color = McdColors.Muted, fontSize = 12.sp, maxLines = 1)
    }
}

// ============================== Home row ==============================

/**
 * Home row of playlist channels (favorites, or recent channels): wide logo cards with the clean name and
 * what's on now. OK plays the channel straight away; channel up / down moves through this row's channels.
 */
@Composable
fun LiveChannelsRow(label: String, channels: List<Channel>, nav: Nav) {
    if (channels.isEmpty()) return
    val guide = LiveSession.guide
    Column(Modifier.padding(top = 20.dp)) {
        RailHeader(label, Modifier.padding(start = 48.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(channels, key = { _, ch -> ch.url }) { i, ch ->
                LiveWideCard(ch, guide) {
                    LiveSession.channels = channels
                    nav.push(Screen.LivePlay(i))
                }
            }
        }
    }
}

@Composable
private fun LiveWideCard(ch: Channel, guide: Map<String, List<Programme>>, onClick: () -> Unit) {
    val name = remember(ch.name) { LiveOrganizer.cleanName(ch.name) }
    val now = System.currentTimeMillis()
    val cur = remember(guide, ch.url, now / 60_000L) { LiveFill.nowNext(ch, guide, now).first }
    Column(Modifier.width(220.dp)) {
        HudCard(
            onClick = onClick,
            modifier = Modifier.width(220.dp).height(124.dp),
        ) { _ ->
            Box(Modifier.fillMaxSize()) {
                if (ch.logo != null) {
                    AsyncImage(
                        model = ch.logo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                } else {
                    Text(
                        name, style = broadcastStyle(17.sp), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.Center).padding(10.dp),
                    )
                }
                LiveBadge(Modifier.align(Alignment.TopStart).padding(7.dp))
                if (cur != null) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(McdColors.Line.copy(alpha = 0.5f))) {
                        Box(Modifier.fillMaxWidth(programmeProgress(cur, now)).height(3.dp).background(McdColors.Accent))
                    }
                }
            }
        }
        Text(name, color = McdColors.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(if (cur != null) "Now: ${cur.title}" else "", color = McdColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ============================== Addon catalogs ==============================

@Composable
private fun CatalogList(nav: Nav, cat: LiveCatalog) {
    val list = remember(cat) { mutableStateListOf<LiveItem>() }
    var page by remember(cat) { mutableIntStateOf(0) }
    var status by remember(cat) { mutableStateOf("Loading…") }
    var more by remember(cat) { mutableStateOf(true) }
    LaunchedEffect(cat, page) {
        runCatching { Addons.catalog(cat, skip = list.size) }
            .onSuccess { got ->
                // Addons can repeat an item within one page or send blank ids; both would crash the keyed list.
                val fresh = got.distinctBy { it.id }.filter { g -> g.id.isNotBlank() && list.none { it.id == g.id } }
                list.addAll(fresh)
                more = fresh.isNotEmpty()
                status = if (list.isEmpty()) "Nothing listed right now." else ""
            }
            .onFailure { status = "Could not load this list: ${it.message}" }
    }
    if (status.isNotBlank()) StatusText(status, Modifier.padding(start = 48.dp))
    LazyColumn(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list, key = { it.id }) { item -> LiveItemRow(item) { nav.push(Screen.LiveChannel(item)) } }
        if (list.isNotEmpty() && more) item(key = "loadMore") { ActionButton("Load more", { page++ }) }
    }
}

@Composable
private fun LiveItemRow(item: LiveItem, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(containerColor = McdColors.Card, focusedContainerColor = McdColors.NavyLight),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        shape = ClickableSurfaceDefaults.shape(HudShape),
        border = hudSurfaceBorder(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(item.poster, item.name, contentScale = ContentScale.Fit, modifier = Modifier.size(width = 64.dp, height = 40.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = broadcastStyle(18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.info.isNotBlank()) Text(item.info, color = McdColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The streams one addon offers for a live channel or event. */
@Composable
fun LiveChannelScreen(nav: Nav, item: LiveItem) {
    val res by rememberLoad(item) { Addons.liveStreams(item) }
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
        Text(item.name.uppercase(), style = broadcastStyle(30.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (item.info.isNotBlank()) Text(item.info, color = McdColors.Muted, fontSize = 14.sp)
        Spacer(Modifier.size(12.dp))
        when (val r = res) {
            is Load.Loading -> StatusText("Finding streams…")
            is Load.Err -> StatusText("Could not load streams: ${r.message}")
            is Load.Ok -> if (r.value.isEmpty()) StatusText("No playable streams right now. Live events usually list streams shortly before they start.")
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(r.value) { s ->
                    Surface(
                        onClick = { nav.push(Screen.Player(s.url!!, item.name, headers = s.headers)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ClickableSurfaceDefaults.colors(containerColor = McdColors.Card, focusedContainerColor = McdColors.NavyLight),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                        shape = ClickableSurfaceDefaults.shape(HudShape),
        border = hudSurfaceBorder(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(s.name, style = broadcastStyle(16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (s.title.isNotBlank()) Text(s.title.replace("\n", "  "), color = McdColors.Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
