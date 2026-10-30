package com.mcd.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.mcd.tv.Nav
import com.mcd.tv.Screen
import com.mcd.tv.data.BROWSE_LANGUAGES
import com.mcd.tv.data.POPULAR_COLLECTIONS
import com.mcd.tv.data.SERVICES
import com.mcd.tv.data.Title
import com.mcd.tv.data.Tmdb
import com.mcd.tv.data.TrueCrime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// ============================== Browse menu ==============================

/** One row of the Browse menu. [section] starts a new group with that heading. */
private class MenuEntry(val title: String, val subtitle: String, val target: Screen, val section: String? = null)

private fun browseMenu(): List<MenuEntry> = buildList {
    add(MenuEntry("Trending", "This week's most watched", Screen.BrowseGrid("trending"), section = "Catalog"))
    add(MenuEntry("Popular", "Movies and shows everyone is watching", Screen.BrowseGrid("popular", "movie")))
    add(MenuEntry("Top Rated", "Best reviewed of all time", Screen.BrowseGrid("top", "movie")))
    add(MenuEntry("New Releases", "In theaters now", Screen.BrowseGrid("new")))
    add(MenuEntry("By Year", "Pick any year", Screen.BrowseGrid("year")))
    add(MenuEntry("By Language", "English, Spanish, Korean, Japanese, Hindi…", Screen.BrowseGrid("lang", "en")))
    add(MenuEntry("Genres", "Action, Comedy, Drama, Horror…", Screen.Genres))
    add(MenuEntry("Collections", "Movie franchises", Screen.BrowseGrid("collections")))
    if (!com.mcd.tv.data.Prefs.isKidsProfile) add(MenuEntry("History", "Ken Burns, historical dramas, every era", Screen.History))
    // True Crime: the same shelf as the Home row (TrueCrimeHub.shelf), one category id on both surfaces.
    if (com.mcd.tv.data.TrueCrimeHub.showFor()) add(MenuEntry(TrueCrime.LABEL, TrueCrime.SUBTITLE, Screen.BrowseGrid(TrueCrime.ID, "", TrueCrime.LABEL)))
    SERVICES.forEachIndexed { i, s ->
        add(MenuEntry(s.name, "Current catalog", Screen.ServiceGrid(s), section = if (i == 0) "Streaming services" else null))
    }
    add(MenuEntry("Websites", "Sites you added on the Control page", Screen.Sports, section = "More"))
    add(MenuEntry("Background Noise", "Random episodes of your shows", Screen.Noise))
}

/** Catalog sheet: a plain vertical list (dark rows, hairline separators, focused row bright). */
@Composable
fun BrowseScreen(nav: Nav) {
    val entries = remember { browseMenu() }
    val listState = rememberLazyListState()
    TabPage(nav, NavTab.Browse) {
        LazyColumn(state = listState, contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 4.dp, bottom = 48.dp)) {
            entries.forEachIndexed { i, e ->
                val section = e.section
                if (section != null) item(key = "section-$i") {
                    Text(
                        section.uppercase(),
                        color = McdColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp,
                        modifier = Modifier.padding(start = 14.dp, top = if (i == 0) 4.dp else 22.dp, bottom = 6.dp),
                    )
                }
                item(key = "row-$i") { MenuRow(e.title, e.subtitle) { nav.push(e.target) } }
            }
        }
    }
}

@Composable
private fun MenuRow(title: String, subtitle: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .hudBrackets(focused, McdColors.AccentBright, inset = 0.dp, arm = 7.dp, stroke = 1.5.dp)
                .clip(HudShapeSmall)
                .background(if (focused) McdColors.Accent.copy(alpha = 0.12f) else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).height(18.dp).background(if (focused) McdColors.Red else Color.Transparent))
            Spacer(Modifier.width(12.dp))
            Text(
                title,
                color = if (focused) McdColors.AccentBright else McdColors.White.copy(alpha = 0.82f),
                fontSize = 17.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(subtitle, color = McdColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(14.dp))
            Text("›", color = if (focused) McdColors.Accent else McdColors.Muted, fontSize = 20.sp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(McdColors.Line))
    }
}

// ============================== Browse grid ==============================

private fun currentYear() = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

/** Movies / Shows (and All for Trending) toggle options per grid kind; empty = no toggle. */
private fun typeOptions(kind: String): List<Pair<String, String>> = when (kind) {
    "trending" -> listOf("all" to "All", "movie" to "Movies", "tv" to "Shows")
    "popular", "top", "year", "lang" -> listOf("movie" to "Movies", "tv" to "Shows")
    else -> emptyList()
}

/** Chip row (years or languages) per grid kind; empty = no chips. */
private fun chipOptions(kind: String): List<Pair<String, String>> = when (kind) {
    "year" -> (currentYear() downTo 1950).map { "$it" to "$it" }
    "lang" -> BROWSE_LANGUAGES
    else -> emptyList()
}

private fun defaultHeading(kind: String) = when (kind) {
    "trending" -> "Trending"
    "popular" -> "Popular"
    "top" -> "Top Rated"
    "new" -> "New Releases"
    "year" -> "By Year"
    "lang" -> "By Language"
    "collections" -> "Collections"
    "collection" -> "Collection"
    "history" -> "History"
    TrueCrime.ID -> TrueCrime.LABEL
    else -> "Browse"
}

/** The curated franchises as poster tiles (Title.type = "collection", id = TMDB collection id). */
private suspend fun popularCollections(): List<Title> = coroutineScope {
    val results = POPULAR_COLLECTIONS.map { id -> async { runCatching { Tmdb.collection(id) } } }.awaitAll()
    val ok = results.mapNotNull { it.getOrNull() }
    if (ok.isEmpty()) results.firstOrNull()?.exceptionOrNull()?.let { throw it }
    ok.map { c ->
        Title(id = c.id, type = "collection", name = c.name, overview = "", poster = c.poster ?: c.parts.firstOrNull()?.poster,
            backdrop = c.backdrop, rating = 0.0, year = "")
    }
}

/** One page of a grid. Kinds without paging return everything on page 1 and nothing after. */
private suspend fun fetchGrid(kind: String, param: String, type: String, chip: String, page: Int): List<Title> = when (kind) {
    "trending" -> Tmdb.trending(type, page)
    "popular" -> Tmdb.popular(type, page)
    "top" -> Tmdb.topRated(type, page)
    "new" -> Tmdb.nowPlaying(page)
    "year" -> Tmdb.byYear(type, chip.toIntOrNull() ?: currentYear(), page)
    "lang" -> Tmdb.byLanguage(type, chip.ifBlank { "en" }, page)
    "collection" -> if (page == 1) param.toIntOrNull()?.let { Tmdb.collection(it).parts } ?: emptyList() else emptyList()
    "collections" -> if (page == 1) popularCollections() else emptyList()
    "history" -> if (page == 1) com.mcd.tv.data.HistoryHub.shelf(param) else emptyList()
    TrueCrime.ID -> if (page == 1) com.mcd.tv.data.TrueCrimeHub.shelf() else emptyList()
    else -> emptyList()
}

/**
 * Full poster grid for a Home row's "See all" or a Browse entry: rows of 5 posters, "See more" loads
 * two more pages. Optional Movies / Shows toggle and a year or language chip row.
 */
@Composable
fun BrowseGridScreen(nav: Nav, kind: String, param: String, title: String) {
    val types = remember(kind) { typeOptions(kind) }
    val chips = remember(kind) { chipOptions(kind) }
    var type by rememberSaveable {
        mutableStateOf(types.firstOrNull { it.first == param }?.first ?: types.firstOrNull()?.first ?: "movie")
    }
    var chip by rememberSaveable {
        mutableStateOf(chips.firstOrNull { it.first == param }?.first ?: chips.firstOrNull()?.first ?: "")
    }
    var pages by rememberSaveable(type, chip) { mutableIntStateOf(2) }
    var retry by remember { mutableIntStateOf(0) }
    val all = remember(type, chip) { mutableStateListOf<Title>() }
    var loaded by remember(type, chip) { mutableIntStateOf(0) }
    var ended by remember(type, chip) { mutableStateOf(false) }
    var err by remember(type, chip) { mutableStateOf<String?>(null) }
    LaunchedEffect(kind, param, type, chip, pages, retry) {
        err = null
        while (loaded < pages && !ended) {
            val next = loaded + 1
            val got = try {
                fetchGrid(kind, param, type, chip, next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                err = e.message ?: e.toString()
                return@LaunchedEffect
            }
            val seen = all.map { "${it.type}-${it.id}" }.toHashSet()
            all.addAll(got.distinctBy { "${it.type}-${it.id}" }.filter { "${it.type}-${it.id}" !in seen })
            loaded = next
            if (got.isEmpty()) ended = true
        }
    }
    val heading = title.ifBlank { defaultHeading(kind) }
    val openItem: (Title) -> Unit = { t ->
        if (t.type == "collection") nav.push(Screen.BrowseGrid("collection", t.id.toString(), t.name))
        else nav.push(Screen.Detail(t.type, t.id))
    }
    val listState = rememberLazyListState()
    TabPage(nav, NavTab.Browse) {
        Row(
            Modifier.padding(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(heading.uppercase(), style = broadcastStyle(26.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            types.forEach { (key, label) -> ActionButton(label, { type = key }, primary = key == type) }
        }
        if (chips.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chips, key = { it.first }) { (key, label) -> ActionButton(label, { chip = key }, primary = key == chip) }
            }
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp)) {
            val e = err
            if (all.isEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = 48.dp)) {
                        StatusText(e ?: if (ended) "Nothing here." else "Loading…")
                        if (e != null) ActionButton("Retry", { retry++ }, primary = true)
                    }
                }
            } else {
                // Rows of 5 posters (5 x 140dp + gaps fits the 864dp content width).
                items(all.chunked(5)) { rowItems ->
                    Row(Modifier.padding(horizontal = 48.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        rowItems.forEach { t -> PosterCard(t, onClick = { openItem(t) }, badges = t.type != "collection") }
                    }
                }
                if (!ended || e != null) item(key = "seeMore") {
                    Row(
                        Modifier.padding(horizontal = 48.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ActionButton(
                            if (e != null) "Retry (${all.size} shown)" else "See more (${all.size} shown)",
                            { if (e != null) retry++ else pages += 2 },
                            primary = true,
                        )
                        if (e != null) StatusText(e) else if (loaded < pages) StatusText("Loading…")
                    }
                }
            }
        }
    }
}
