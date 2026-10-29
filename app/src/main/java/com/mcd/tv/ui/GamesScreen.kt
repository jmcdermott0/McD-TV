package com.mcd.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mcd.tv.Nav
import com.mcd.tv.Screen
import com.mcd.tv.data.Epg
import com.mcd.tv.data.GuideExtra
import com.mcd.tv.data.FavoriteTeam
import com.mcd.tv.data.MyTeams
import com.mcd.tv.data.Game
import com.mcd.tv.data.GameChannels
import com.mcd.tv.data.GameMatcher
import com.mcd.tv.data.Games
import com.mcd.tv.data.League
import com.mcd.tv.data.LiveIndex
import com.mcd.tv.data.LiveOrganizer
import com.mcd.tv.data.M3u
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.Programme
import com.mcd.tv.data.Schedule
import com.mcd.tv.data.TeamLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ============================== Games: every game this week, and where to watch it ==============================

/** The star and accent on a favorite team's game. */
val MyTeamGold = androidx.compose.ui.graphics.Color(0xFFFFC94D)

/** A league's schedule as the screens see it. */
sealed interface LeagueLoad {
    data object Loading : LeagueLoad
    class Ok(val schedule: Schedule) : LeagueLoad
    data object Failed : LeagueLoad
}

/** Shared schedule state for the Live TV rail, the Games pane and team search. Main thread. */
object GameBoard {
    val state = mutableStateMapOf<League, LeagueLoad>()

    /** Bumped whenever a schedule changes (team search re-reads the cache). */
    var version by mutableIntStateOf(0)
        private set

    /** The big leagues: shown in the rail during their season even before the schedule loads. */
    private val MAIN = setOf(League.NFL, League.NCAAF, League.NBA, League.MLB, League.NHL)

    suspend fun refresh(league: League, force: Boolean = false) {
        val cur = state[league]
        if (cur !is LeagueLoad.Ok) state[league] = LeagueLoad.Loading
        try {
            val s = Games.schedule(league, force)
            if (cur !is LeagueLoad.Ok || cur.schedule !== s) {
                state[league] = LeagueLoad.Ok(s)
                version++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (state[league] !is LeagueLoad.Ok) state[league] = LeagueLoad.Failed
        }
    }

    /** Bumped when a profile shows or hides a league, so Live Games redraws. */
    private var hiddenVersion by mutableIntStateOf(0)
    private var hiddenCache: Pair<String, Set<League>>? = null

    /** Leagues the watching profile hid from Live Games (Settings > Addons and Live TV). */
    fun hidden(): Set<League> {
        hiddenVersion // read, so a change redraws the screens that call this
        val p = Prefs.activeProfile
        hiddenCache?.let { if (it.first == p) return it.second }
        return League.hiddenFrom(Prefs.hiddenLeagueNames(p)).also { hiddenCache = p to it }
    }

    fun setShown(league: League, shown: Boolean) {
        val now = if (shown) hidden() - league else hidden() + league
        Prefs.setHiddenLeagueNames(League.entries.filter { it in now }.map { it.name })
        hiddenCache = null
        hiddenVersion++ // a league shown again loads the next time Live TV or Games opens (refreshAll)
    }

    private var teamsVersion by mutableIntStateOf(0)
    private var teamsCache: Pair<String, List<FavoriteTeam>>? = null

    /** The watching profile's favorite teams (Settings > Addons and Live TV > My teams). */
    fun myTeams(): List<FavoriteTeam> {
        teamsVersion // read, so a change redraws the screens that call this
        val p = Prefs.activeProfile
        teamsCache?.let { if (it.first == p) return it.second }
        return FavoriteTeam.listFrom(Prefs.favoriteTeamNames(p), p).also { teamsCache = p to it }
    }

    fun addTeam(t: FavoriteTeam) = saveTeams(myTeams().filter { it != t } + t)

    fun removeTeam(t: FavoriteTeam) = saveTeams(myTeams() - t)

    private fun saveTeams(list: List<FavoriteTeam>) {
        Prefs.setFavoriteTeamNames(list.map { it.stored })
        teamsCache = null
        teamsVersion++
    }

    /** Favorite-team games still to come or live in the shown leagues, live first. */
    fun myGames(): List<Game> = MyTeams.collect(visibleLeagues().mapNotNull { (state[it] as? LeagueLoad.Ok)?.schedule }, myTeams())

    /** Loads (or refreshes from cache) every shown league at once. Hidden leagues cost no ESPN requests. */
    suspend fun refreshAll() = coroutineScope {
        val skip = hidden()
        League.entries.filter { it !in skip }.forEach { l -> launch { refresh(l) } }
    }

    /** Leagues for Live Games: NFL first during its season, others while they have games this week. Hidden ones never. */
    fun visibleLeagues(): List<League> {
        val month = Calendar.getInstance().get(Calendar.MONTH) + 1
        val skip = hidden()
        return League.entries.filter { l ->
            if (l in skip) return@filter false
            when (val s = state[l]) {
                is LeagueLoad.Ok -> s.schedule.games.isNotEmpty() || (l == League.NFL && l.inSeason(month))
                else -> l in MAIN && l.inSeason(month)
            }
        }
    }

    fun gameCount(l: League): Int? = (state[l] as? LeagueLoad.Ok)?.schedule?.games?.size
}

/**
 * Games pane state that outlives a trip to the player: the game last opened (focus returns to it)
 * and the channel picker (Menu on a game).
 */
class GamesUi(lastGameState: MutableState<String?>) {
    var lastGame: String? by lastGameState
    var picker by mutableStateOf<Game?>(null)

    /** Set when the picker opens: closing it puts focus back on that game. */
    var refocusLast by mutableStateOf(false)

    fun openPicker(g: Game) {
        lastGame = g.key
        refocusLast = true
        picker = g
    }
}

@Composable
fun rememberGamesUi(): GamesUi {
    val saved = rememberSaveable { mutableStateOf<String?>(null) }
    return remember { GamesUi(saved) }
}

/** "Sunday, Oct 11" ("Today, Thursday, Oct 8" for today). */
private fun dayLabel(ms: Long): String {
    val text = SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date(ms))
    val day = Calendar.getInstance().apply { timeInMillis = ms }
    val today = Calendar.getInstance()
    val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return when {
        same(day, today) -> "Today · $text"
        same(day, tomorrow) -> "Tomorrow · $text"
        else -> text
    }
}

private fun dayKey(ms: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
}

/** Start time for a card: "1:00 PM", or "Sun 1:00 PM" when [withDay]. */
private fun startText(g: Game, withDay: Boolean): String {
    if (g.timeTbd) return if (withDay) SimpleDateFormat("EEE", Locale.getDefault()).format(Date(g.startMs)) + " TBD" else "TBD"
    if (g.startMs <= 0L) return g.detail
    val t = guideTime(g.startMs)
    return if (withDay) SimpleDateFormat("EEE", Locale.getDefault()).format(Date(g.startMs)) + " " + t else t
}

/** Header: "NFL · WEEK 6". */
fun gamesTitle(league: League, s: Schedule?): String = league.short + (s?.weekLabel?.let { " · $it" } ?: "")

/** Ranked channels for [games] (empty map without a playlist), computed off the main thread. */
@Composable
fun rememberGameChannels(games: List<Game>, index: LiveIndex?, guide: Map<String, List<Programme>>): Map<String, GameChannels>? {
    val result by produceState<Map<String, GameChannels>?>(null, games, index, guide) {
        value = if (index == null || games.isEmpty()) {
            emptyMap()
        } else {
            withContext(Dispatchers.Default) { runCatching { GameMatcher.match(games, index, guide) }.getOrDefault(emptyMap()) }
        }
    }
    return result
}

// ============================== Standalone page (Home > More > Games, QA "--es screen games") ==============================

@Composable
fun GamesScreen(nav: Nav, start: League) {
    var leagueName by rememberSaveable { mutableStateOf(start.name) }
    val league = League.entries.firstOrNull { it.name == leagueName } ?: start
    val ui = rememberGamesUi()
    val indexLoad by rememberLoad(Prefs.m3uUrl) {
        if (Prefs.m3uUrl.isBlank()) null else LiveOrganizer.indexFor(M3u.load())
    }
    val index: LiveIndex? = (indexLoad as? Load.Ok<LiveIndex?>)?.value
    LaunchedEffect(index) {
        val url = M3u.guideUrl
        if (index != null && url.isNotBlank() && LiveSession.guide.isEmpty()) {
            val g = Epg.load(url, index.source)
            if (g.isNotEmpty()) LiveSession.guide = GuideExtra.withCached(g, index.source)
        }
    }
    LaunchedEffect(Unit) { GameBoard.refreshAll() }
    BackHandler(enabled = ui.picker != null) { ui.picker = null }
    val chipFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { chipFocus.requestFocus() }
    }
    val leagues = GameBoard.visibleLeagues().let { if (league in it) it else listOf(league) + it }

    TabPage(nav, NavTab.Live, autoFocus = false) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(count = leagues.size, key = { leagues[it].name }) { i ->
                val l = leagues[i]
                ActionButton(
                    l.railLabel,
                    { leagueName = l.name; ui.picker = null },
                    if (l == league) Modifier.focusRequester(chipFocus) else Modifier,
                    primary = l == league,
                )
            }
        }
        Box(Modifier.fillMaxSize().padding(start = 42.dp, top = 4.dp)) {
            androidx.compose.runtime.key(league) {
                GamesPane(
                    league = league,
                    index = index,
                    guide = LiveSession.guide,
                    ui = ui,
                    onPlay = { list, pos ->
                        val idx = index
                        if (idx != null && list.isNotEmpty()) {
                            LiveSession.channels = idx.channelsOf(list)
                            nav.push(Screen.LivePlay(pos))
                        }
                    },
                    restoreFocus = null,
                    autoFocus = true,
                    playlistLoading = indexLoad is Load.Loading && Prefs.m3uUrl.isNotBlank(),
                    onSetup = { nav.push(Screen.PhoneSetup) },
                )
            }
        }
    }
}

// ============================== The pane (also shown inside the Live TV browser) ==============================

/**
 * One league's week: LIVE NOW, UPCOMING by day, then FINAL (collapsed). OK on a game plays its best channel
 * (channel up / down in the player moves through that game's channels); Menu lists them all.
 * [index] null = no playlist (games still show, with a setup hint).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GamesPane(
    league: League,
    index: LiveIndex?,
    guide: Map<String, List<Programme>>,
    ui: GamesUi,
    onPlay: (IntArray, Int) -> Unit,
    restoreFocus: FocusRequester?,
    autoFocus: Boolean = false,
    playlistLoading: Boolean = false,
    onSetup: (() -> Unit)? = null,
) {
    val load = GameBoard.state[league]
    val scope = rememberCoroutineScope()
    // Refresh while open: the cache keeps this cheap (60 s while games are live, 10 min otherwise).
    LaunchedEffect(league) {
        while (true) {
            GameBoard.refresh(league)
            delay(30_000)
        }
    }
    val schedule = (load as? LeagueLoad.Ok)?.schedule
    val games = schedule?.games ?: emptyList()
    val matches = rememberGameChannels(games, index, guide)
    // Kept here (not in the grid) so the scroll position survives opening the channel picker.
    val gridState = rememberLazyGridState()
    val leagueChannels by produceState(IntArray(0), league, index) {
        value = if (index == null) IntArray(0) else withContext(Dispatchers.Default) {
            runCatching { GameMatcher.leagueChannels(league, index) }.getOrDefault(IntArray(0))
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Header first, so it shows before anything loads.
        Row(Modifier.padding(start = 6.dp, top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(gamesTitle(league, schedule), style = broadcastStyle(18.sp))
            Spacer(Modifier.width(10.dp))
            val live = schedule?.liveCount ?: 0
            val sub = when {
                schedule == null -> ""
                live > 0 -> "$live live  ·  ${games.size} games this week"
                else -> "${games.size} games this week"
            }
            Text(sub, color = if (live > 0) McdColors.LiveRed else McdColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
        val picked = ui.picker
        when {
            picked != null && picked.league == league -> GamePicker(
                game = picked,
                index = index,
                channels = matches?.get(picked.key),
                onPlay = onPlay,
                onClose = { ui.picker = null },
            )
            schedule == null && load is LeagueLoad.Failed -> Column(Modifier.padding(start = 6.dp)) {
                StatusText("Couldn't load the schedule. Try again.")
                ActionButton("Retry", { scope.launch { GameBoard.refresh(league, force = true) } }, primary = true)
            }
            schedule == null -> StatusText("Loading the schedule…", Modifier.padding(start = 6.dp))
            else -> GamesGrid(
                league = league,
                schedule = schedule,
                gridState = gridState,
                index = index,
                matches = matches,
                leagueChannels = leagueChannels,
                ui = ui,
                onPlay = onPlay,
                restoreFocus = restoreFocus,
                autoFocus = autoFocus,
                playlistLoading = playlistLoading,
                onSetup = onSetup,
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun GamesGrid(
    league: League,
    schedule: Schedule,
    gridState: LazyGridState,
    index: LiveIndex?,
    matches: Map<String, GameChannels>?,
    leagueChannels: IntArray,
    ui: GamesUi,
    onPlay: (IntArray, Int) -> Unit,
    restoreFocus: FocusRequester?,
    autoFocus: Boolean,
    playlistLoading: Boolean,
    onSetup: (() -> Unit)?,
) {
    val games = schedule.games
    // Games with a favorite team go first, in their own block, and are not repeated below.
    val teams = GameBoard.myTeams()
    val mine = remember(games, teams) { MyTeams.collect(listOf(schedule), teams) }
    val mineKeys = remember(mine) { mine.mapTo(HashSet()) { it.key } }
    val live = remember(games, mineKeys) { games.filter { it.live && it.key !in mineKeys } }
    val upcoming = remember(games, mineKeys) { games.filter { it.state == "pre" && it.key !in mineKeys }.sortedBy { it.startMs } }
    val finals = remember(games) { games.filter { it.final }.sortedByDescending { it.startMs } }
    val days = remember(upcoming) { upcoming.groupBy { dayKey(it.startMs) }.values.toList() }
    var showFinals by rememberSaveable(league) { mutableStateOf(false) }
    val target = mine.firstOrNull() ?: live.firstOrNull() ?: upcoming.firstOrNull() ?: if (showFinals) finals.firstOrNull() else null
    val targetFocus = remember { FocusRequester() }
    var targetShown by remember { mutableStateOf(false) }
    val lastFocus = remember { FocusRequester() }
    var autoDone by remember { mutableStateOf(false) }

    // Standalone page: once the games are on screen, focus the first live game (else the next one).
    LaunchedEffect(targetShown) {
        if (autoFocus && !autoDone && targetShown && ui.lastGame == null) {
            withFrameNanos { }
            runCatching { targetFocus.requestFocus() }
            autoDone = true
        }
    }
    // Back from the channel picker (or, on the standalone page, from the player): focus that game again.
    LaunchedEffect(Unit) {
        if (ui.refocusLast || (autoFocus && ui.lastGame != null)) {
            ui.refocusLast = false
            withFrameNanos { }
            withFrameNanos { }
            runCatching { lastFocus.requestFocus() }
        }
    }

    val openGame: (Game) -> Unit = { g ->
        ui.lastGame = g.key
        val m = matches?.get(g.key)
        if (m != null && m.size > 0) onPlay(m.items, 0) else ui.openPicker(g)
    }
    val menu: (Game) -> Unit = { g -> ui.openPicker(g) }

    val gameCard: @Composable (Game, Boolean, Boolean) -> Unit = { g, compact, withDay ->
        val isTarget = target != null && g.key == target.key
        if (isTarget) {
            DisposableEffect(g.key) {
                targetShown = true
                onDispose { targetShown = false }
            }
        }
        var m: Modifier = Modifier
        if (isTarget) m = m.focusRequester(targetFocus)
        if (g.key == ui.lastGame) {
            m = m.focusRequester(lastFocus)
            if (restoreFocus != null) m = m.focusRequester(restoreFocus)
        }
        GameCard(
            g = g,
            channels = matches?.get(g.key),
            hasPlaylist = index != null,
            compact = compact,
            withDay = withDay,
            mine = g.key in mineKeys,
            onClick = { openGame(g) },
            onMenu = { menu(g) },
            modifier = m,
        )
    }
    val card: @Composable (Game, Boolean) -> Unit = { g, compact -> gameCard(g, compact, false) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(320.dp),
        state = gridState,
        contentPadding = PaddingValues(start = 6.dp, end = 32.dp, top = 6.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusProperties { enter = { if (targetShown) targetFocus else FocusRequester.Default } }
            .focusGroup(),
    ) {
        if (index == null) {
            item(key = "\u0001setup", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    StatusText(
                        if (playlistLoading) "Loading your channels…"
                        else "No playlist yet. Add your M3U playlist on the Control page to watch these games.",
                    )
                    if (!playlistLoading && onSetup != null) ActionButton("Phone & Computer Setup", onSetup)
                }
            }
        }
        if (index != null && leagueChannels.isNotEmpty()) {
            item(key = "\u0001league", span = { GridItemSpan(maxLineSpan) }) {
                LeagueChannelsRow(league, leagueChannels, index, onPlay)
            }
        }
        if (games.isEmpty()) {
            item(key = "\u0001empty", span = { GridItemSpan(maxLineSpan) }) {
                StatusText("No ${league.label} games this week.")
            }
        }
        if (mine.isNotEmpty()) {
            item(key = "\u0001mine", span = { GridItemSpan(maxLineSpan) }) { SectionLabel("★ YOUR TEAMS", MyTeamGold) }
            items(count = mine.size, key = { mine[it].key }) { i -> gameCard(mine[i], false, true) }
        }
        if (live.isNotEmpty()) {
            item(key = "\u0001live", span = { GridItemSpan(maxLineSpan) }) { SectionLabel("LIVE NOW", McdColors.LiveRed) }
            items(count = live.size, key = { live[it].key }) { i -> card(live[i], false) }
        }
        if (upcoming.isNotEmpty()) {
            item(key = "\u0001up", span = { GridItemSpan(maxLineSpan) }) { SectionLabel("UPCOMING", McdColors.Accent) }
            days.forEach { day ->
                val first = day.first()
                item(key = "\u0001day" + dayKey(first.startMs), span = { GridItemSpan(maxLineSpan) }) {
                    Text(dayLabel(first.startMs), color = McdColors.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                }
                items(count = day.size, key = { day[it].key }) { i -> card(day[i], false) }
            }
        }
        if (finals.isNotEmpty()) {
            item(key = "\u0001final", span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    SectionLabel("FINAL", McdColors.Muted)
                    Spacer(Modifier.width(14.dp))
                    ActionButton(
                        if (showFinals) "Hide final scores" else "Show ${finals.size} final scores",
                        { showFinals = !showFinals },
                    )
                }
            }
            if (showFinals) items(count = finals.size, key = { finals[it].key }) { i -> card(finals[i], true) }
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Box(Modifier.size(width = 3.dp, height = 14.dp).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, style = hudLabelStyle(12.sp, color))
    }
}

// ============================== Game card ==============================

/**
 * Score-bug style card: away over home with logos, records and scores; status (LIVE + clock, kick-off time
 * or Final), networks, and how many of your channels carry it. Menu (☰) or a long press lists the channels.
 */
@Composable
fun GameCard(
    g: Game,
    channels: GameChannels?,
    hasPlaylist: Boolean,
    compact: Boolean,
    withDay: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    mine: Boolean = false,
) {
    val awayScore = g.away.score.toIntOrNull()
    val homeScore = g.home.score.toIntOrNull()
    val showScore = g.state != "pre" && (awayScore != null || homeScore != null)
    val awayLeads = (awayScore ?: 0) > (homeScore ?: 0)
    val homeLeads = (homeScore ?: 0) > (awayScore ?: 0)
    HudCard(
        onClick = onClick,
        onLongClick = onMenu,
        focusedScale = 1.03f,
        modifier = modifier
            .fillMaxWidth()
            .height(if (compact) 84.dp else 112.dp)
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Menu) {
                    if (e.type == KeyEventType.KeyUp) onMenu()
                    true
                } else {
                    false
                }
            },
    ) { _ ->
        if (g.live || mine) {
            Box(Modifier.align(Alignment.CenterStart).width(3.dp).fillMaxHeight().background(if (g.live) McdColors.LiveRed else MyTeamGold))
        }
        Row(
            Modifier.fillMaxSize().padding(start = 14.dp, end = 12.dp, top = if (compact) 8.dp else 10.dp, bottom = if (compact) 8.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
                val teams = if (mine) GameBoard.myTeams() else emptyList()
                TeamRow(g.away, showScore, dim = g.final && homeLeads, compact = compact, fav = teams.any { it.league == g.league && it.matches(g.away) })
                TeamRow(g.home, showScore, dim = g.final && awayLeads, compact = compact, fav = teams.any { it.league == g.league && it.matches(g.home) })
            }
            Spacer(Modifier.width(12.dp))
            Column(
                Modifier.width(120.dp).fillMaxHeight(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                when {
                    g.live -> Row(verticalAlignment = Alignment.CenterVertically) {
                        LiveBadge()
                        Spacer(Modifier.width(6.dp))
                        Text(g.detail, color = McdColors.LiveRed, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    g.final -> Text(g.detail.ifBlank { "Final" }, color = McdColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    else -> Text(startText(g, withDay), color = McdColors.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                if (g.broadcasts.isNotEmpty()) {
                    Text(
                        g.broadcasts.take(3).joinToString(" · ").uppercase(),
                        style = hudLabelStyle(9.sp, McdColors.Accent),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!compact || !g.final) {
                    val n = channels?.size ?: 0
                    Text(
                        when {
                            !hasPlaylist -> "Add a playlist to watch"
                            channels == null -> "Finding channels…"
                            n == 1 -> "1 channel"
                            n > 1 -> "$n channels"
                            else -> "No channel found"
                        },
                        color = if (hasPlaylist && n > 0) McdColors.AccentBright else McdColors.Muted,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun TeamRow(t: TeamLine, showScore: Boolean, dim: Boolean, compact: Boolean, fav: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        AsyncImage(t.logo, t.name, contentScale = ContentScale.Fit, modifier = Modifier.size(if (compact) 20.dp else 28.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            (if (t.rank > 0) "#${t.rank} " else "") + t.short,
            color = if (dim) McdColors.Muted else McdColors.White,
            fontSize = if (compact) 13.sp else 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (t.record.isNotBlank()) {
            Spacer(Modifier.width(6.dp))
            Text(t.record, color = McdColors.Muted, fontSize = 11.sp, maxLines = 1)
        }
        if (fav) {
            Spacer(Modifier.width(6.dp))
            Text("★ MY TEAM", style = hudLabelStyle(9.sp, MyTeamGold), maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        if (showScore) Text(t.score, style = broadcastStyle(if (compact) 15.sp else 19.sp, if (dim) McdColors.Muted else McdColors.White))
    }
}

// ============================== League channels row ==============================

@Composable
private fun LeagueChannelsRow(league: League, items: IntArray, index: LiveIndex, onPlay: (IntArray, Int) -> Unit) {
    Column {
        RailHeader("${league.short} channels")
        LazyRow(
            contentPadding = PaddingValues(start = 2.dp, end = 32.dp, top = 2.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(count = items.size, key = { index.channels[items[it]].url }) { pos ->
                val ch = index.channels[items[pos]]
                val name = index.names[items[pos]]
                HudCard(onClick = { onPlay(items, pos) }, modifier = Modifier.width(190.dp).height(56.dp)) { _ ->
                    Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (ch.logo != null) {
                            AsyncImage(ch.logo, name, contentScale = ContentScale.Fit, modifier = Modifier.size(width = 44.dp, height = 30.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(name, color = McdColors.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ============================== Channel picker (Menu on a game) ==============================

@Composable
private fun GamePicker(
    game: Game,
    index: LiveIndex?,
    channels: GameChannels?,
    onPlay: (IntArray, Int) -> Unit,
    onClose: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val hits = channels?.hits ?: emptyList()
    LaunchedEffect(game.key) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Column(Modifier.fillMaxSize().padding(start = 6.dp, end = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            AsyncImage(game.away.logo, game.away.name, contentScale = ContentScale.Fit, modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(10.dp))
            Text("${game.away.short} @ ${game.home.short}".uppercase(), style = broadcastStyle(20.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(10.dp))
            AsyncImage(game.home.logo, game.home.name, contentScale = ContentScale.Fit, modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(14.dp))
            val status = when {
                game.live -> "LIVE  ${game.detail}"
                game.final -> game.detail
                else -> dayLabel(game.startMs) + "  " + startText(game, false)
            }
            Text(status, color = if (game.live) McdColors.LiveRed else McdColors.Muted, fontSize = 13.sp, maxLines = 1)
        }
        if (game.broadcasts.isNotEmpty()) {
            Text("On " + game.broadcasts.joinToString(", "), color = McdColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            ActionButton("Back to games", onClose, if (hits.isEmpty()) Modifier.focusRequester(first) else Modifier)
            Spacer(Modifier.width(14.dp))
            Text(
                when {
                    index == null -> "Add your playlist on the Control page to watch from here."
                    channels == null -> "Finding channels…"
                    hits.isEmpty() -> "No channel in your playlist matched this game. Try Search for the network."
                    else -> "OK plays a channel. In the player, channel up / down moves through this list."
                },
                color = McdColors.Muted,
                fontSize = 12.sp,
                maxLines = 2,
            )
        }
        if (index != null && hits.isNotEmpty()) {
            val items = channels!!.items
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(count = hits.size, key = { index.channels[hits[it].idx].url }) { pos ->
                    val hit = hits[pos]
                    val ch = index.channels[hit.idx]
                    val name = index.names[hit.idx]
                    Surface(
                        onClick = { onPlay(items, pos) },
                        modifier = Modifier.fillMaxWidth().then(if (pos == 0) Modifier.focusRequester(first) else Modifier),
                        colors = ClickableSurfaceDefaults.colors(containerColor = McdColors.Card, focusedContainerColor = McdColors.NavyLight),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                        shape = ClickableSurfaceDefaults.shape(HudShape),
                        border = hudSurfaceBorder(),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(ch.logo, name, contentScale = ContentScale.Fit, modifier = Modifier.size(width = 56.dp, height = 34.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, style = broadcastStyle(16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(LiveOrganizer.cleanGroup(ch.group), color = McdColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(hit.why, style = hudLabelStyle(9.sp, if (pos == 0) McdColors.AccentBright else McdColors.Accent), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(170.dp))
                        }
                    }
                }
            }
        }
    }
}

// ============================== Search: a team's games above the channel results ==============================

/** "Games this week" row for a team search. [firstFocus] goes on the first card (DOWN from the search box). */
@Composable
fun TeamGamesRow(
    games: List<Game>,
    channels: Map<String, GameChannels>?,
    hasPlaylist: Boolean,
    firstFocus: FocusRequester,
    onOpen: (Game) -> Unit,
    onMenu: (Game) -> Unit,
) {
    Column(Modifier.padding(bottom = 4.dp)) {
        RailHeader("Games this week")
        LazyRow(
            contentPadding = PaddingValues(start = 6.dp, end = 32.dp, top = 2.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(count = games.size, key = { games[it].key }) { i ->
                val g = games[i]
                GameCard(
                    g = g,
                    channels = channels?.get(g.key),
                    hasPlaylist = hasPlaylist,
                    compact = false,
                    withDay = true,
                    onClick = { onOpen(g) },
                    onMenu = { onMenu(g) },
                    modifier = Modifier.width(330.dp).then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier),
                )
            }
        }
    }
}
