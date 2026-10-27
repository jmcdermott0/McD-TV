package com.mcd.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mcd.tv.Nav
import com.mcd.tv.Screen
import com.mcd.tv.data.Channel
import com.mcd.tv.data.ContinueRules
import com.mcd.tv.data.DailyPicks
import com.mcd.tv.data.Library
import com.mcd.tv.data.LibraryEvents
import com.mcd.tv.data.WatchListRow
import com.mcd.tv.data.LiveOrganizer
import com.mcd.tv.data.M3u
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.SERVICES
import com.mcd.tv.data.Title
import com.mcd.tv.data.Tmdb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Everything the home screen shows, loaded in parallel. */
private data class HomeData(
    val trending: List<Title>,
    /** Popular movies on the first streaming service (Netflix), or plain popular movies if that fails. */
    val popular: List<Title>,
    val popularFromService: Boolean,
    val nowPlaying: List<Title>,
    val topMovies: List<Title>,
)

/**
 * Home: at most 8 rows. Continue Watching, My List (the profile's watchlist, when it has titles), Live TV favorites (when a playlist is set), Suggested for You, History (profiles that like
 * history; its See all opens Browse > History), Trending This Week, Popular on <service>, Top Rated, New Releases, then a small
 * "More" row (Ask Jarvis, Browse, Live TV, Websites, Background Noise).
 * Everything else lives under Browse. Each catalog row ends in a "See all" tile that opens its full grid.
 * Everything personal (history, lists, Live TV favorites) belongs to the active profile, so the page is
 * keyed on it: switching profiles rebuilds Home. The Kids profile gets family, animation and kids rows instead.
 */
@Composable
fun HomeScreen(nav: Nav) {
    key(Prefs.activeProfile) { HomeContent(nav, Prefs.isKidsProfile) }
}

@Composable
private fun HomeContent(nav: Nav, kids: Boolean) {
    // Goes up on every My List / "Not for me" change, here or on another TV: those rows re-read without a restart.
    val libVersion by LibraryEvents.version.collectAsState()
    val hidden = remember(libVersion) { Library.hidden().map { "${it.type}-${it.id}" }.toSet() }
    fun List<Title>.visible() = filterNot { "${it.type}-${it.id}" in hidden }
    val service = SERVICES.first()

    var retry by remember { mutableIntStateOf(0) }
    val data by rememberLoad(retry) {
        if (kids) coroutineScope {
            val fam = async { Tmdb.kidsMovies() }
            val anim = async { Tmdb.kidsMovies(genreId = 16) }
            val shows = async { Tmdb.kidsShows() }
            val best = async { Tmdb.kidsMovies(genreId = 16, topRated = true) }
            HomeData(fam.await(), anim.await(), false, shows.await(), best.await())
        } else coroutineScope {
            val tr = async { Tmdb.trending() }
            val ps = async { runCatching { Tmdb.byService("movie", service.id) }.getOrNull()?.takeIf { it.isNotEmpty() } }
            val np = async { Tmdb.nowPlaying() }
            val tm = async { Tmdb.topRated("movie") }
            val svc = ps.await()
            HomeData(tr.await(), svc ?: Tmdb.popular("movie"), svc != null, np.await(), tm.await())
        }
    }
    // Positions saved on the phones or the other TVs (resume sync) are merged in, then the rows re-read.
    LaunchedEffect(Unit) { com.mcd.tv.data.ResumeCloud.pull() }
    val cloud = com.mcd.tv.data.ResumeCloud.version
    val history = remember(cloud) { Library.history().sortedByDescending { it.updatedAt } }
    val continueWatching = remember(cloud) { Library.continueWatching().sortedByDescending { it.updatedAt } }
    val firstFocus = remember { FocusRequester() }
    val heroFocus = remember { FocusRequester() }
    // Which row and tile had focus. Saved with this back-stack entry, so Back from a title puts focus
    // back on the tile it was opened from instead of the top of the page.
    val focus = rememberSaveable(saver = FocusMemory.Saver) { FocusMemory() }
    val restoring = remember { focus.pending }
    val favorites = remember { Library.favorites() }
    // My List: the active profile's own watchlist (synced between TVs by ProfileSync). No titles, no row.
    val myList = remember(libVersion, hidden) { WatchListRow.items(Library.watchlist(), { "${it.type}-${it.id}" }, hidden) }
    val listed = remember(libVersion) { Library.watchlist().map { "${it.type}-${it.id}" }.toSet() }
    // Picked for <name>: learned from this profile's history, favorites, likes, "Not for me" and Settings > Taste.
    // A new row each day (DailyPicks): a Home left open past midnight loads the new day's row.
    val day by produceState(DailyPicks.dayOf(System.currentTimeMillis())) {
        while (true) {
            kotlinx.coroutines.delay(DailyPicks.msUntilNextDay(System.currentTimeMillis()) + 1_000)
            value = DailyPicks.dayOf(System.currentTimeMillis())
        }
    }
    val suggestions by rememberLoad(Prefs.activeProfile, day) { com.mcd.tv.data.Recommender.forYou() }
    // Because You Watched: More Like This for the latest title this profile finished.
    val because by rememberLoad(Prefs.activeProfile) { com.mcd.tv.data.Recommender.becauseYouWatched() }
    // History: Ken Burns, for profiles whose Taste likes history (Dad). Never built from what was watched.
    val historyRow by rememberLoad(Prefs.activeProfile) {
        if (kids || !com.mcd.tv.data.HistoryHub.homeRowFor()) null else com.mcd.tv.data.HistoryHub.homeRow()
    }
    LaunchedEffect(Unit) {
        if (continueWatching.isNotEmpty() && !restoring) { withFrameNanos { }; runCatching { firstFocus.requestFocus() } }
    }
    // Live TV row: favorite channels (or recent ones) from the playlist. Loads in the background (playlist
    // cached 6 h) and only appears once ready, so Home never waits on a big playlist.
    val liveRow by produceState<Pair<String, List<Channel>>?>(null) {
        if (Prefs.m3uUrl.isBlank()) return@produceState
        val favs = Prefs.liveFavorites
        val recents = Prefs.liveRecents
        if (favs.isEmpty() && recents.isEmpty()) return@produceState
        val all = try {
            M3u.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@produceState
        }
        val index = LiveOrganizer.indexFor(all)
        val fav = index.indicesOf(favs)
        val recent = if (fav.isEmpty()) index.indicesOf(recents) else IntArray(0)
        value = when {
            fav.isNotEmpty() -> "Live TV · Favorites" to index.channelsOf(fav)
            recent.isNotEmpty() -> "Recent channels" to index.channelsOf(recent)
            else -> null
        }
    }
    // Home stays at 8 rows or fewer: when Continue Watching and the live row both show, or Because You Watched or My List shows, Top Rated gives up its place.
    val dropTopRated = (liveRow != null && continueWatching.isNotEmpty()) || (because as? Load.Ok)?.value != null || myList.isNotEmpty()
    val openTitle: (Title) -> Unit = { nav.push(Screen.Detail(it.type, it.id)) }
    val heroes = (data as? Load.Ok<HomeData>)?.value?.trending?.visible()?.filter { it.backdrop != null }?.take(6) ?: emptyList()
    // Nothing to resume: the hero goes full-bleed at the very top of the page, behind the transparent
    // TopNav (Max-style), and takes focus. With Continue Watching, that row comes first and has focus.
    val heroOnTop = continueWatching.isEmpty()

    // The TV scrolls a focused item about a third of the way down the screen. For the top of Home that hides
    // the menu and the top of the hero, so focus there always shows the page from its very top.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    focus.indexOfRow = { k -> listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == k }?.index }
    /** Records focus on a row that does not report its tiles (top bar, hero, Live TV row). */
    fun rowFocus(key: String) = Modifier.onFocusChanged { if (it.hasFocus && focus.row != key) focus.focused(key, -1) }

    // Back to Home: once the catalog rows are back, bring the remembered row on screen. The row then
    // focuses its tile itself. If it can't (row gone, or a row without tiles), use the normal first focus.
    val dataReady = data !is Load.Loading
    LaunchedEffect(dataReady) {
        if (!dataReady || !focus.pending) return@LaunchedEffect
        withFrameNanos { }
        val key = focus.row
        val onScreen = listState.layoutInfo.visibleItemsInfo.any { it.key == key }
        if (!onScreen && focus.rowIndex >= 0) {
            val last = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
            listState.scrollToItem(focus.rowIndex.coerceAtMost(last))
        }
        if (focus.tile >= 0) kotlinx.coroutines.delay(1_500) // the row restores its tile in this time
        if (!focus.pending) return@LaunchedEffect
        focus.pending = false
        if (key == "hero" && !heroOnTop && heroes.isNotEmpty()) {
            withFrameNanos { }
            if (runCatching { heroFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
        listState.scrollToItem(0)
        withFrameNanos { }
        when {
            continueWatching.isNotEmpty() -> runCatching { firstFocus.requestFocus() }
            heroOnTop && heroes.isNotEmpty() -> runCatching { heroFocus.requestFocus() }
            else -> Unit
        }
    }
    val pinTop = Modifier.onFocusChanged { f ->
        if (f.hasFocus) {
            scope.launch {
                withFrameNanos { }
                listState.animateScrollToItem(0)
            }
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().hudBackground(),
        contentPadding = PaddingValues(bottom = 48.dp),
    ) {
        item(key = "top") {
            Box(Modifier.fillMaxWidth().then(pinTop).then(rowFocus("top"))) {
                if (heroOnTop && heroes.isNotEmpty()) Hero(heroes, nav, heroFocus, takeFocus = !restoring, underNav = true)
                TopNav(NavTab.Home, nav.tab, profileName = Prefs.activeProfileName, onProfile = { nav.push(Screen.Profiles) })
            }
        }

        if (continueWatching.isNotEmpty()) item(key = "continue") {
            Box(pinTop) { HistoryRow("Continue Watching", continueWatching, nav, firstFocus, focus) }
        }
        if (myList.isNotEmpty()) item(key = "mylist") {
            TitleRail(WatchListRow.LABEL, myList, { nav.tab(NavTab.Library) }, openTitle, focus, "mylist")
        }
        liveRow?.let { (label, channels) -> item(key = "live") { Box(rowFocus("live")) { LiveChannelsRow(label, channels, nav) } } }
        if (!heroOnTop && heroes.isNotEmpty()) item(key = "hero") { Box(rowFocus("hero")) { Hero(heroes, nav, heroFocus, takeFocus = false) } }
        item(key = "suggested") {
            val sg = suggestions
            if (sg is Load.Ok && sg.value.isNotEmpty()) TitleRail("Picked for ${Prefs.activeProfileName}", sg.value.visible().filterNot { "${it.type}-${it.id}" in listed }, null, openTitle, focus, "suggested")
        }
        item(key = "because") {
            val b = (because as? Load.Ok)?.value
            if (b != null) TitleRail("Because You Watched ${b.first}", b.second.visible(), null, openTitle, focus, "because")
        }
        item(key = "history") {
            val h = (historyRow as? Load.Ok)?.value
            if (h != null) TitleRail(h.first, h.second.visible(), { nav.push(Screen.History) }, openTitle, focus, "history")
        }

        when (val d = data) {
            is Load.Loading -> item { StatusText("Loading…", Modifier.padding(start = 48.dp)) }
            is Load.Err -> item {
                Column(Modifier.padding(horizontal = 48.dp)) {
                    StatusText(d.message)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionButton("Retry", { retry++ }, primary = Prefs.tmdbKey.isNotBlank())
                        if (Prefs.tmdbKey.isBlank()) ActionButton("Phone & Computer Setup", { nav.push(Screen.PhoneSetup) }, primary = true)
                    }
                }
            }
            is Load.Ok -> {
                val v = d.value
                if (kids) {
                    item(key = "kids_family") { TitleRail("Family Movies", v.trending.visible(), null, openTitle, focus, "kids_family") }
                    item(key = "kids_animation") { TitleRail("Animated Movies", v.popular.visible(), null, openTitle, focus, "kids_animation") }
                    item(key = "kids_shows") { TitleRail("Kids Shows", v.nowPlaying.visible(), null, openTitle, focus, "kids_shows") }
                    if (!dropTopRated) item(key = "kids_best") { TitleRail("Best Rated Animation", v.topMovies.visible(), null, openTitle, focus, "kids_best") }
                } else {
                item(key = "trending") {
                    TitleRail("Trending This Week", v.trending.visible(), { nav.push(Screen.BrowseGrid("trending")) }, openTitle, focus, "trending")
                }
                item(key = "popular") {
                    if (v.popularFromService) {
                        TitleRail("Popular on ${service.name} · Movies", v.popular.visible(), { nav.push(Screen.ServiceGrid(service)) }, openTitle, focus, "popular")
                    } else {
                        TitleRail("Popular · Movies", v.popular.visible(), { nav.push(Screen.BrowseGrid("popular", "movie")) }, openTitle, focus, "popular")
                    }
                }
                if (!dropTopRated) item(key = "toprated") {
                    TitleRail("Top Rated · Movies", v.topMovies.visible(), { nav.push(Screen.BrowseGrid("top", "movie")) }, openTitle, focus, "toprated")
                }
                item(key = "new") {
                    TitleRail("New Releases · In Theaters", v.nowPlaying.visible(), { nav.push(Screen.BrowseGrid("new")) }, openTitle, focus, "new")
                }
                }
            }
        }
        item(key = "more") { MoreRow(nav, focus) }
    }
}

/** Small last row: shortcuts to everything that moved off Home. */
@Composable
private fun MoreRow(nav: Nav, focus: FocusMemory) {
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    val restoreAt = focus.restoreTarget("more", 5, rowState)
    fun tile(i: Int) = focus.tileModifier("more", i, restoreAt)
    Column(Modifier.padding(top = 24.dp)) {
        RailHeader("More", Modifier.padding(start = 48.dp))
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { CompactTile("Browse", "Genres, years, languages, services", { nav.tab(NavTab.Browse) }, tile(0)) }
            item { CompactTile("Live TV", "Your channels and favorites", { nav.tab(NavTab.Live) }, tile(1)) }
            item { CompactTile("Games", "Every NFL game this week", { nav.push(Screen.Games(com.mcd.tv.data.League.NFL)) }, tile(2)) }
            item { CompactTile("Websites", "Sites you added on the Control page", { nav.push(Screen.Sports) }, tile(3)) }
            item { CompactTile("Background Noise", "Random episodes of your shows", { nav.push(Screen.Noise) }, tile(4)) }
        }
    }
}

/** Hero height: about 65% of the 540dp TV canvas. */
private val HeroHeight = 350.dp

/**
 * Full-bleed backdrop banner that rotates through trending titles (Max-style): the image fades into
 * the page on the left and at the bottom, with title, meta line, overview and Play / More Info on top.
 * [underNav]: the hero is the first thing on the page and the transparent TopNav floats over its top.
 */
@Composable
private fun Hero(items: List<Title>, nav: Nav, playFocus: FocusRequester, takeFocus: Boolean = true, underNav: Boolean = false) {
    var index by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    // Holds still while Play or More Info has focus, so the title does not change under the viewer.
    var focused by remember { mutableStateOf(false) }
    val t = items[index % items.size]

    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { playFocus.requestFocus() }
    }
    LaunchedEffect(paused, focused) {
        while (!paused && !focused) {
            kotlinx.coroutines.delay(8000)
            index = (index + 1) % items.size
        }
    }

    Box(Modifier.fillMaxWidth().height(HeroHeight).onFocusChanged { focused = it.hasFocus }) {
        AsyncImage(
            model = Tmdb.img(t.backdrop, "w1280"),
            contentDescription = t.name,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            colorFilter = HudDuotone,
            modifier = Modifier.fillMaxSize(),
        )
        // Duotone: darken, then a faint cyan wash and scanlines over the backdrop.
        Box(Modifier.fillMaxSize().background(McdColors.Ink.copy(alpha = 0.25f)).background(McdColors.Accent.copy(alpha = 0.15f)).hudScanlines())
        // Left: solid page color fading to clear, so the text always reads.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to McdColors.Navy,
                    0.30f to McdColors.Navy.copy(alpha = 0.85f),
                    0.60f to McdColors.Navy.copy(alpha = 0.25f),
                    1f to Color.Transparent,
                ),
            ),
        )
        // Top (only when the nav floats over it, or the hero sits mid-page) and bottom: melt into the page.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to McdColors.Navy.copy(alpha = if (underNav) 0.6f else 0.9f),
                    0.25f to Color.Transparent,
                    0.60f to Color.Transparent,
                    0.85f to McdColors.Navy.copy(alpha = 0.8f),
                    1f to McdColors.Navy,
                ),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 22.dp).width(580.dp)) {
            Text(
                if (t.type == "tv") "▸ FEATURED SERIES" else "▸ FEATURED MOVIE",
                style = hudLabelStyle(10.sp, McdColors.Accent),
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.name.uppercase(),
                    style = broadcastStyle(34.sp).copy(lineHeight = 34.sp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (t.rating > 0) {
                    Spacer(Modifier.width(14.dp))
                    HudGauge((t.rating / 10.0).toFloat(), "%.1f".format(t.rating), "TMDB")
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                listOfNotNull(t.year.ifBlank { null }, if (t.type == "tv") "Series" else "Movie").joinToString("  ▪  ").uppercase(),
                color = McdColors.Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(t.overview, color = McdColors.White.copy(alpha = 0.9f), fontSize = 15.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("▶  Play", { paused = true; nav.push(Screen.Detail(t.type, t.id)) }, Modifier.focusRequester(playFocus), primary = true)
                ActionButton("More Info", { paused = true; nav.push(Screen.Detail(t.type, t.id)) })
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 30.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items.indices.forEach { i ->
                Box(
                    Modifier.width(if (i == index) 22.dp else 8.dp).height(3.dp)
                        .background(if (i == index) McdColors.Accent else McdColors.Line),
                )
            }
        }
    }
}

/** A row of landscape cards from watch history (resume points or recently watched). */
@Composable
private fun HistoryRow(
    label: String,
    list: List<com.mcd.tv.data.HistoryEntry>,
    nav: Nav,
    focus: FocusRequester?,
    memory: FocusMemory? = null,
    memoryKey: String = "continue",
) {
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    val restoreAt = memory.restoreTarget(memoryKey, list.size, rowState)
    Column(Modifier.padding(top = 12.dp)) {
        RailHeader(label, Modifier.padding(start = 48.dp))
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(list, key = { _, h -> h.meta.historyKey }) { i, h ->
                WideCard(
                    title = h.meta.name,
                    subtitle = when {
                        h.finished && h.meta.type == "tv" && h.meta.season > 0 ->
                            ContinueRules.playEpisode(h.meta.season, h.meta.episode, h.progress).let { (s, e) -> "Up next: S$s E$e" }
                        h.finished -> "Watched"
                        h.meta.type == "tv" -> "S${h.meta.season} E${h.meta.episode}  •  ${(h.progress * 100).toInt()}%"
                        else -> "${(h.progress * 100).toInt()}% watched"
                    },
                    image = Tmdb.img(h.meta.backdrop, "w780"),
                    progress = if (h.finished) null else h.progress,
                    onClick = { nav.push(Screen.Detail(h.meta.type, h.meta.tmdbId)) },
                    modifier = (if (i == 0 && focus != null) Modifier.focusRequester(focus) else Modifier)
                        .then(memory.tileModifier(memoryKey, i, restoreAt)),
                )
            }
        }
    }
}
