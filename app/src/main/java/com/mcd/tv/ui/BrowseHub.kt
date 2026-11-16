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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
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
import com.mcd.tv.data.BrowseHubs
import com.mcd.tv.data.Hub
import com.mcd.tv.data.HubKind
import com.mcd.tv.data.HubSection
import com.mcd.tv.data.Library
import com.mcd.tv.data.LibraryEvents
import com.mcd.tv.data.MOVIE_GENRES
import com.mcd.tv.data.POPULAR_COLLECTIONS
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.SERVICES
import com.mcd.tv.data.Service
import com.mcd.tv.data.Title
import com.mcd.tv.data.Tmdb
import com.mcd.tv.data.TrueCrime
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

// ============================== Browse: a visual hub ==============================
//
// Top: a spotlight (backdrop, title, year, score, overview) that follows whatever has focus, the way open-source
// TV apps do it (JetStream's immersive list, Arctic Fuse 2's spotlight, Wholphin). Then Explore: art-backed tiles
// for every way in (BrowseHubs, pure and tested). Then poster rows, genres and streaming services, each tile with
// real art from its first title. Every tile opens the same grids and screens the old list did.

/** What the spotlight shows. [backdrop] is a TMDB path. */
private data class Spot(val kicker: String, val headline: String, val meta: String, val body: String, val backdrop: String?)

private fun Title.spot(kicker: String): Spot {
    val kind = if (type == "tv") "Show" else if (type == "movie") "Movie" else ""
    val meta = listOfNotNull(year.ifBlank { null }, kind.ifBlank { null }, if (rating > 0) "TMDB %.1f".format(rating) else null).joinToString("  ·  ")
    return Spot(kicker, name, meta, overview, backdrop)
}

/** Everything the page loads at once (TMDB, in parallel). Any one list may fail and be empty. */
private class BrowseData(
    val trending: List<Title>,
    val fresh: List<Title>,
    val top: List<Title>,
    val shows: List<Title>,
    val trueCrime: List<Title>,
    /** Art per Explore tile kind (a TMDB backdrop path). */
    val art: Map<HubKind, String>,
)

private suspend fun loadBrowse(kids: Boolean, profile: String): BrowseData = coroutineScope {
    suspend fun <T> safe(block: suspend () -> T, fallback: T): T = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback
    }
    val none = emptyList<Title>()
    val tr = async { safe({ if (kids) Tmdb.kidsMovies() else Tmdb.trending() }, none) }
    val nw = async { safe({ if (kids) Tmdb.kidsMovies(genreId = 16) else Tmdb.nowPlaying() }, none) }
    val tp = async { safe({ if (kids) Tmdb.kidsMovies(genreId = 16, topRated = true) else Tmdb.topRated("movie") }, none) }
    val sh = async { safe({ if (kids) Tmdb.kidsShows() else Tmdb.popular("tv") }, none) }
    val tc = async { if (kids) none else safe({ com.mcd.tv.data.TrueCrimeHub.shelf(profile) }, none) }
    val coll = async { safe({ Tmdb.collection(POPULAR_COLLECTIONS.first()).backdrop }, null) }
    val hist = async { if (kids) null else safe({ com.mcd.tv.data.HistoryHub.shelf(HubSection.entries.first().id, profile).firstNotNullOfOrNull { it.backdrop } }, null) }
    val hol = async { if (com.mcd.tv.data.HolidayHub.showFor()) safe({ com.mcd.tv.data.HolidayHub.shelf(profile).firstNotNullOfOrNull { it.backdrop } }, null) else null }
    // Kids: these tiles take art from the kids lists below instead (no grown-up backdrops).
    val gen = async { if (kids) null else safe({ Tmdb.byGenre("movie", 28, false).firstNotNullOfOrNull { it.backdrop } }, null) }
    val yr = async { if (kids) null else safe({ Tmdb.byYear("movie", java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - 1).firstNotNullOfOrNull { it.backdrop } }, null) }
    val lang = async { if (kids) null else safe({ Tmdb.byLanguage("movie", "ko").firstNotNullOfOrNull { it.backdrop } }, null) }
    val trending = tr.await()
    val fresh = nw.await()
    val top = tp.await()
    val shows = sh.await()
    val trueCrime = tc.await()
    fun firstArt(l: List<Title>, skip: Int = 0) = l.drop(skip).firstNotNullOfOrNull { it.backdrop }
    val art = buildMap {
        firstArt(trending)?.let { put(HubKind.TRENDING, it) }
        firstArt(fresh)?.let { put(HubKind.NEW, it) }
        firstArt(top)?.let { put(HubKind.TOP, it) }
        firstArt(trueCrime)?.let { put(HubKind.TRUE_CRIME, it) }
        firstArt(shows, 1)?.let { put(HubKind.NOISE, it) }
        coll.await()?.let { put(HubKind.COLLECTIONS, it) }
        hist.await()?.let { put(HubKind.HISTORY, it) }
        hol.await()?.let { put(HubKind.HOLIDAY, it) }
        gen.await()?.let { put(HubKind.GENRES, it) }
        yr.await()?.let { put(HubKind.YEAR, it) }
        lang.await()?.let { put(HubKind.LANGUAGE, it) }
        if (kids) {
            firstArt(fresh, 1)?.let { put(HubKind.GENRES, it) }
            firstArt(top, 1)?.let { put(HubKind.YEAR, it) }
            firstArt(trending, 2)?.let { put(HubKind.LANGUAGE, it) }
        }
    }
    BrowseData(trending, fresh, top, shows, trueCrime, art)
}

private fun hubTarget(h: Hub): Screen = when (h.kind) {
    HubKind.TRENDING -> Screen.BrowseGrid("trending")
    HubKind.TRUE_CRIME -> Screen.BrowseGrid(TrueCrime.ID, "", TrueCrime.LABEL)
    HubKind.COLLECTIONS -> Screen.BrowseGrid("collections")
    HubKind.NEW -> Screen.BrowseGrid("new")
    HubKind.TOP -> Screen.BrowseGrid("top", "movie")
    HubKind.HISTORY -> Screen.History
    HubKind.HOLIDAY -> Screen.BrowseGrid(com.mcd.tv.data.Holiday.ID, "", com.mcd.tv.data.Holiday.LABEL)
    HubKind.GENRES -> Screen.Genres
    HubKind.YEAR -> Screen.BrowseGrid("year")
    HubKind.LANGUAGE -> Screen.BrowseGrid("lang", "en")
    HubKind.NOISE -> Screen.Noise
    HubKind.WEBSITES -> Screen.Sports
}

@Composable
fun BrowseScreen(nav: Nav) {
    key(Prefs.activeProfile) { BrowseHubContent(nav, Prefs.activeProfile, Prefs.isKidsProfile) }
}

@Composable
private fun BrowseHubContent(nav: Nav, profile: String, kids: Boolean) {
    val libVersion by LibraryEvents.version.collectAsState()
    val hidden = remember(libVersion) { Library.hidden().map { "${it.type}-${it.id}" }.toSet() }
    fun List<Title>.visible() = filterNot { "${it.type}-${it.id}" in hidden }
    val hubs = remember(kids) {
        BrowseHubs.hubs(kids, com.mcd.tv.data.HolidayHub.showFor(), com.mcd.tv.data.TrueCrimeHub.showFor(profile))
    }
    var retry by rememberSaveable { mutableStateOf(0) }
    val load by rememberLoad(profile, retry) { loadBrowse(kids, profile) }
    val data = (load as? Load.Ok<BrowseData>)?.value

    // The spotlight follows focus after a short pause, so a fast run along a row does not load every backdrop.
    var wanted by remember { mutableStateOf<Spot?>(null) }
    var spot by remember { mutableStateOf<Spot?>(null) }
    LaunchedEffect(data) {
        if (spot == null) data?.trending?.visible()?.firstOrNull { it.backdrop != null }?.let { spot = it.spot(if (kids) "FAMILY PICK" else "TRENDING NOW") }
    }
    LaunchedEffect(wanted) {
        val w = wanted ?: return@LaunchedEffect
        delay(220)
        spot = w
    }

    val focus = rememberSaveable(saver = FocusMemory.Saver) { FocusMemory() }
    val listState = rememberLazyListState()
    focus.indexOfRow = { k -> listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == k }?.index }
    val open: (Title) -> Unit = { t -> nav.push(Screen.Detail(t.type, t.id)) }

    TabPage(nav, NavTab.Browse) {
        Box(Modifier.fillMaxSize()) {
            SpotlightBackdrop(spot?.backdrop)
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 48.dp)) {
                item(key = "spot") { SpotlightText(spot) }
                item(key = "hubs") {
                    HubGrid(hubs, data?.art ?: emptyMap(), focus, onFocus = { h, art ->
                        wanted = Spot("EXPLORE", h.label, h.subtitle, "", art)
                    }) { nav.push(hubTarget(it)) }
                }
                when (val l = load) {
                    is Load.Loading -> item(key = "loading") { StatusText("Loading…", Modifier.padding(start = 48.dp, top = 16.dp)) }
                    is Load.Err -> item(key = "err") {
                        Column(Modifier.padding(start = 48.dp, top = 16.dp)) {
                            StatusText(l.message)
                            ActionButton("Retry", { retry++ }, primary = true)
                        }
                    }
                    is Load.Ok -> {
                        val d = l.value
                        val rows = buildList {
                            add(Triple("trending", if (kids) "Family Movies" else "Trending This Week", d.trending))
                            if (!kids) add(Triple("truecrime", TrueCrime.LABEL, d.trueCrime))
                            add(Triple("new", if (kids) "Animated" else "New Releases", d.fresh))
                            add(Triple("top", if (kids) "Best Animated" else "Top Rated Movies", d.top))
                            add(Triple("shows", if (kids) "Kids Shows" else "Popular Shows", d.shows))
                        }
                        rows.forEach { (k, label, list) ->
                            val items = list.visible()
                            if (items.isNotEmpty()) item(key = k) {
                                SpotRail(label, items.take(20), focus, k, onSpot = { wanted = it.spot(label.uppercase()) }, onSeeAll = seeAllFor(k, kids)?.let { s -> { nav.push(s) } }, onOpen = open)
                            }
                        }
                    }
                }
                item(key = "genres") { GenreRow(kids, focus, onSpot = { wanted = it }) { g -> nav.push(Screen.BrowseGrid("genre", g.id.toString(), g.name)) } }
                if (!kids) item(key = "services") { ServiceRow(focus, onSpot = { wanted = it }) { s -> nav.push(Screen.ServiceGrid(s)) } }
            }
        }
    }
}

private fun seeAllFor(key: String, kids: Boolean): Screen? = if (kids) null else when (key) {
    "trending" -> Screen.BrowseGrid("trending")
    "truecrime" -> Screen.BrowseGrid(TrueCrime.ID, "", TrueCrime.LABEL)
    "new" -> Screen.BrowseGrid("new")
    "top" -> Screen.BrowseGrid("top", "movie")
    "shows" -> Screen.BrowseGrid("popular", "tv")
    else -> null
}

/** The spotlight's backdrop: top right, fading into the page on the left and at the bottom. */
@Composable
private fun SpotlightBackdrop(path: String?) {
    Box(Modifier.fillMaxWidth().height(330.dp)) {
        if (path != null) {
            AsyncImage(
                model = Tmdb.img(path, "w1280"),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopEnd,
                modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.72f).height(330.dp),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to McdColors.Navy, 0.32f to McdColors.Navy.copy(alpha = 0.92f), 0.62f to McdColors.Navy.copy(alpha = 0.25f), 1f to Color.Transparent),
            ),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to McdColors.Navy)))
    }
}

@Composable
private fun SpotlightText(s: Spot?) {
    Column(Modifier.padding(start = 48.dp, top = 8.dp, end = 48.dp).height(150.dp).widthIn(max = 470.dp)) {
        if (s == null) return@Column
        Text(s.kicker, style = hudLabelStyle(10.sp, McdColors.Accent), maxLines = 1)
        Text(s.headline, style = broadcastStyle(34.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        if (s.meta.isNotBlank()) Text(s.meta, color = McdColors.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        if (s.body.isNotBlank()) Text(s.body, color = McdColors.Muted, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Explore: art-backed tiles, [BrowseHubs.PER_ROW] per row. */
@Composable
private fun HubGrid(hubs: List<Hub>, art: Map<HubKind, String>, focus: FocusMemory, onFocus: (Hub, String?) -> Unit, onOpen: (Hub) -> Unit) {
    val restoreAt = if (focus.pending && focus.row == "hubs") focus.tile.coerceIn(0, hubs.size - 1) else -1
    if (restoreAt >= 0) {
        LaunchedEffect(Unit) {
            repeat(3) {
                withFrameNanos { }
                if (!focus.pending) return@LaunchedEffect
                runCatching { focus.requesterFor("hubs").requestFocus() }
            }
        }
    }
    Column(Modifier.padding(horizontal = 48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RailHeader("Explore")
        hubs.chunked(BrowseHubs.PER_ROW).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEachIndexed { c, h ->
                    val i = r * BrowseHubs.PER_ROW + c
                    val a = art[h.kind]
                    ArtTile(
                        label = h.label,
                        sub = h.subtitle,
                        art = a,
                        width = 204.dp,
                        onClick = { onOpen(h) },
                        modifier = focus.tileModifier("hubs", i, restoreAt).onFocusChanged { if (it.isFocused) onFocus(h, a) },
                    )
                }
            }
        }
    }
}

/** A 16:9 tile with art (dimmed), its label in display type and a short line under it. */
@Composable
private fun ArtTile(label: String, sub: String, art: String?, width: androidx.compose.ui.unit.Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    HudCard(onClick = onClick, modifier = modifier.width(width).height(width * 0.5625f), focusedScale = 1.06f) { focused ->
        if (art != null) {
            AsyncImage(model = Tmdb.img(art, "w780"), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to McdColors.Navy.copy(alpha = if (focused) 0.05f else 0.35f),
                    1f to McdColors.Navy.copy(alpha = 0.92f),
                ),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 10.dp, bottom = 9.dp)) {
            Text(label, style = broadcastStyle(19.sp, if (focused) McdColors.AccentBright else McdColors.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, color = McdColors.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A poster row whose focused title drives the spotlight. Ends in See all when it has a full grid. */
@Composable
private fun SpotRail(
    label: String,
    items: List<Title>,
    focus: FocusMemory,
    key: String,
    onSpot: (Title) -> Unit,
    onSeeAll: (() -> Unit)?,
    onOpen: (Title) -> Unit,
) {
    val unique = remember(items) { items.distinctBy { "${it.type}-${it.id}" } }
    val rowState = rememberLazyListState()
    val count = unique.size + (if (onSeeAll != null) 1 else 0)
    val restoreAt = focus.restoreTarget(key, count, rowState)
    Column(Modifier.padding(top = 22.dp)) {
        RailHeader(label, Modifier.padding(start = 48.dp))
        LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(unique, key = { _, t -> "${t.type}-${t.id}" }) { i, t ->
                PosterCard(
                    t,
                    onClick = { onOpen(t) },
                    modifier = focus.tileModifier(key, i, restoreAt).onFocusChanged { if (it.isFocused) onSpot(t) },
                )
            }
            if (onSeeAll != null) item(key = "see-all") { SeeAllCard(onSeeAll, modifier = focus.tileModifier(key, unique.size, restoreAt)) }
        }
    }
}

/** Movie genres as art tiles; each tile's art is the genre's most popular title (one request per tile on screen). */
@Composable
private fun GenreRow(kids: Boolean, focus: FocusMemory, onSpot: (Spot) -> Unit, onOpen: (com.mcd.tv.data.Genre) -> Unit) {
    val genres = remember(kids) {
        if (kids) MOVIE_GENRES.filter { it.id in setOf(16, 10751, 12, 35, 14) } else MOVIE_GENRES
    }
    val rowState = rememberLazyListState()
    val restoreAt = focus.restoreTarget("genres", genres.size, rowState)
    Column(Modifier.padding(top = 22.dp)) {
        RailHeader("Genres", Modifier.padding(start = 48.dp))
        LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(genres, key = { _, g -> g.id }) { i, g ->
                val art by produceState<Title?>(null, g.id, kids) {
                    value = runCatching {
                        if (kids) Tmdb.kidsMovies(genreId = g.id).firstOrNull { it.backdrop != null }
                        else Tmdb.byGenre("movie", g.id, false).firstOrNull { it.backdrop != null }
                    }.getOrNull()
                }
                ArtTile(
                    label = g.name,
                    sub = "",
                    art = art?.backdrop,
                    width = 176.dp,
                    onClick = { onOpen(g) },
                    modifier = focus.tileModifier("genres", i, restoreAt).onFocusChanged {
                        if (it.isFocused) onSpot(Spot("GENRE", g.name, art?.let { t -> "Top pick: ${t.name}" } ?: "", "", art?.backdrop))
                    },
                )
            }
        }
    }
}

/** Streaming services as art tiles; each tile's art is the newest popular title on that service. */
@Composable
private fun ServiceRow(focus: FocusMemory, onSpot: (Spot) -> Unit, onOpen: (Service) -> Unit) {
    val rowState = rememberLazyListState()
    val restoreAt = focus.restoreTarget("services", SERVICES.size, rowState)
    Column(Modifier.padding(top = 22.dp)) {
        RailHeader("Streaming services", Modifier.padding(start = 48.dp))
        LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(SERVICES, key = { _, s -> s.id }) { i, s ->
                val top by produceState<Title?>(null, s.id) {
                    value = runCatching { Tmdb.byService("tv", s.id, 1).firstOrNull { it.backdrop != null } }.getOrNull()
                }
                ArtTile(
                    label = s.name,
                    sub = top?.name ?: "",
                    art = top?.backdrop,
                    width = 204.dp,
                    onClick = { onOpen(s) },
                    modifier = focus.tileModifier("services", i, restoreAt).onFocusChanged {
                        if (it.isFocused) onSpot(Spot("STREAMING", s.name, top?.let { t -> "Popular now: ${t.name}" } ?: "Current catalog", "", top?.backdrop))
                    },
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}
