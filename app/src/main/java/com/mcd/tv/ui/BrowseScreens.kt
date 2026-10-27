package com.mcd.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.mcd.tv.Nav
import com.mcd.tv.Screen
import com.mcd.tv.data.Addons
import com.mcd.tv.data.JarvisReplyParser
import com.mcd.tv.data.Library
import com.mcd.tv.data.PlayMeta
import com.mcd.tv.data.Resolver
import com.mcd.tv.data.SERVICES
import com.mcd.tv.data.Service
import com.mcd.tv.data.Title
import com.mcd.tv.data.Tmdb
import com.mcd.tv.player.PlayerScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Shared page frame: background + top nav + content. */
@Composable
fun TabPage(nav: Nav, tab: NavTab, autoFocus: Boolean = true, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().hudBackground()) {
        // autoFocus: start on the current tab so the remote has somewhere to go. Screens that focus
        // something themselves (Search's text field) pass false.
        TopNav(
            tab, nav.tab, autoFocus = autoFocus,
            profileName = com.mcd.tv.data.Prefs.activeProfileName,
            onProfile = { nav.push(com.mcd.tv.Screen.Profiles) },
        )
        content()
    }
}

/** Poster grid used by Search, Services and Library. */
@Composable
fun PosterGrid(items: List<Title>, state: LazyGridState = rememberLazyGridState(), onOpen: (Title) -> Unit) {
    LazyVerticalGrid(
        state = state,
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(items, key = { "${it.type}-${it.id}" }) { t -> PosterCard(t, onClick = { onOpen(t) }) }
    }
}

// ============================== Jarvis (search + Ask Jarvis in one box) ==============================

/** Descriptions ("the movie where...") go to Jarvis; short names go to title search only. */
private val DESCRIBE_WORDS = setOf(
    "movie", "movies", "film", "show", "series", "where", "about", "with", "that", "who", "plays", "played",
    "starring", "like", "guy", "girl", "man", "woman", "kid", "kids", "what", "which", "remember",
)

internal fun looksLikeDescription(q: String): Boolean {
    val words = q.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (q.trim().endsWith("?")) return true
    if (words.size >= 5) return true
    return words.size >= 3 && words.count { it in DESCRIBE_WORDS } >= 1
}

@Composable
fun SearchScreen(nav: Nav) {
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    /** Jarvis asked for this query even though it looked like a plain title. */
    var forceAsk by rememberSaveable { mutableStateOf(false) }
    val hasKey = remember { com.mcd.tv.data.Jarvis.configured }
    val field = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    fun submit(text: String = query) {
        val q = text.trim()
        if (q.isEmpty()) return
        keyboard?.hide()
        query = q
        forceAsk = false
        if (q == submitted) attempt++ else submitted = q
        runCatching { field.requestFocus() }
    }

    // Focus the field (no keyboard yet: OK opens it), so typing is one press away.
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { field.requestFocus() } }
    TabPage(nav, NavTab.Search, autoFocus = false) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 48.dp, end = 48.dp, top = 4.dp, bottom = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HudRing(40.dp)
                Spacer(Modifier.width(14.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it.take(600) },
                    singleLine = true,
                    textStyle = TextStyle(color = McdColors.White, fontSize = 22.sp, fontFamily = HudText),
                    cursorBrush = SolidColor(McdColors.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = false),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) {
                                Text("Search or ask Jarvis…", color = McdColors.Muted.copy(alpha = 0.7f), fontSize = 22.sp, maxLines = 1)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f).focusRequester(field)
                        .background(McdColors.Card, HudShape).border(1.5.dp, McdColors.Accent, HudShape)
                        .padding(16.dp),
                )
                Spacer(Modifier.width(14.dp))
                ActionButton("Go", { submit() }, primary = true)
            }
            Text(
                "Search movies and shows by name, or describe one and Jarvis works it out. Press OK to type or use the mic.",
                color = McdColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
            )

            if (submitted.isBlank()) {
                RailHeader("Try")
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    (listOf("Interstellar", "The Office") + ASK_EXAMPLES.take(2)).forEach { ex -> AskChip(ex) { submit(ex) } }
                }
            } else {
            val describe = looksLikeDescription(submitted)
            val hits by rememberLoad(submitted) { Tmdb.searchAll(submitted) }
            val titles = when (val h = hits) {
                is Load.Ok -> Load.Ok(h.value.titles)
                is Load.Err -> h
                is Load.Loading -> Load.Loading
            }
            // A name ("Daniel day Lewis"): that person's best-known titles straight from TMDB, no Jarvis needed.
            // The search reply is cached, so this adds only the person's own page.
            val known by rememberLoad(submitted) {
                if (!JarvisReplyParser.looksLikeName(submitted)) null
                else Tmdb.personByName(submitted, Tmdb.searchAll(submitted).people)?.let { hit ->
                    val p = Tmdb.person(hit.id)
                    (p to Tmdb.knownFor(p)).takeIf { it.second.isNotEmpty() }
                }
            }
            val person = (known as? Load.Ok)?.value
            val noTitles = (hits as? Load.Ok)?.value?.let { it.titles.isEmpty() && it.people.isEmpty() } == true &&
                known !is Load.Loading && person == null
            // Ask Jarvis when it reads like a description, when asked to, or when nothing matches the name.
            val ask = hasKey && (describe || forceAsk || noTitles)

            @Composable
            fun PersonPart() {
                val p = person ?: return
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RailHeader(p.first.name)
                    Spacer(Modifier.width(14.dp))
                    ActionButton("All titles", { nav.push(Screen.Person(p.first.id)) })
                }
                LazyRow(
                    contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(p.second, key = { "${it.type}-${it.id}" }) { t ->
                        PosterCard(t, onClick = { nav.push(Screen.Detail(t.type, t.id)) }, width = 150.dp)
                    }
                }
            }

            @Composable
            fun PeoplePart() {
                val people = (hits as? Load.Ok)?.value?.people.orEmpty().filter { it.id != person?.first?.id }.take(12)
                if (people.isEmpty()) return
                RailHeader("People", Modifier.padding(top = 8.dp))
                LazyRow(
                    contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(people, key = { it.id }) { h -> CastBubble(h.name, h.department, h.photo) { nav.push(Screen.Person(h.id)) } }
                }
            }

            @Composable
            fun JarvisPart() {
                val res by rememberLoad(submitted, attempt) { com.mcd.tv.data.Jarvis.ask(submitted) }
                when (val r = res) {
                    is Load.Loading -> JarvisThinking()
                    is Load.Err -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        JarvisLine(r.message)
                        Row { ActionButton("Try again", { attempt++ }) }
                    }
                    is Load.Ok -> JarvisResults(r.value) { m -> nav.push(Screen.Detail(m.title.type, m.title.id)) }
                }
            }

            @Composable
            fun TitlesPart() {
                when (val r = titles) {
                    is Load.Loading -> StatusText("Searching…")
                    is Load.Err -> StatusText(r.message)
                    is Load.Ok -> if (r.value.isEmpty()) {
                        if (!hasKey && noTitles) Text("No titles match \"$submitted\".", color = McdColors.Muted, fontSize = 16.sp)
                    } else {
                        RailHeader("Titles", Modifier.padding(top = 8.dp))
                        LazyRow(
                            contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(r.value.distinctBy { "${it.type}-${it.id}" }.take(30), key = { "${it.type}-${it.id}" }) { t ->
                                PosterCard(t, onClick = { nav.push(Screen.Detail(t.type, t.id)) }, width = 150.dp)
                            }
                        }
                    }
                }
            }

            if (ask && (describe || forceAsk)) {
                JarvisPart()
                PersonPart()
                TitlesPart()
                PeoplePart()
            } else {
                PersonPart()
                TitlesPart()
                PeoplePart()
                if (ask) {
                    JarvisPart()
                } else if (hasKey && hits is Load.Ok) {
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Not what you meant?", color = McdColors.Muted, fontSize = 15.sp)
                        Spacer(Modifier.width(14.dp))
                        ActionButton("✦ Ask Jarvis", { forceAsk = true })
                    }
                }
            }
            if (!hasKey && describe) {
                JarvisLine(com.mcd.tv.data.Jarvis.NO_KEY)
            }
            }
        }
    }
}

// ============================== Library ==============================

@Composable
fun LibraryScreen(nav: Nav) {
    val openTitle: (Title) -> Unit = { nav.push(Screen.Detail(it.type, it.id)) }
    val history = remember { Library.history() }
    val watchlist = remember { Library.watchlist() }
    val favorites = remember { Library.favorites() }
    val noiseShows = remember { Library.noiseShows() }
    TabPage(nav, NavTab.Library) {
        LazyColumn(contentPadding = PaddingValues(bottom = 48.dp)) {
            item { Row(Modifier.padding(horizontal = 48.dp)) { ActionButton("☁  Real-Debrid Cloud", { nav.push(Screen.RdCloud) }, primary = true) } }
            item { TitleRow("Watchlist", watchlist, openTitle) }
            item { TitleRow("Favorites", favorites, openTitle) }
            item { TitleRow("Background Noise Shows", noiseShows, openTitle) }
            if (history.isNotEmpty()) item {
                Column(Modifier.padding(top = 18.dp)) {
                    RailHeader("Watch History", Modifier.padding(start = 48.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(history, key = { it.meta.historyKey }) { h ->
                            WideCard(
                                title = h.meta.name,
                                subtitle = if (h.finished) "Watched" else "${(h.progress * 100).toInt()}%",
                                image = Tmdb.img(h.meta.backdrop, "w780"),
                                progress = h.progress,
                                onClick = { nav.push(Screen.Detail(h.meta.type, h.meta.tmdbId)) },
                            )
                        }
                    }
                }
            }
            if (history.isEmpty() && watchlist.isEmpty() && favorites.isEmpty()) item {
                StatusText("Nothing here yet. Use Favorite and Watchlist on any title page.", Modifier.padding(start = 48.dp))
            }
        }
    }
}

// ============================== Services ==============================

@Composable
fun ServicesScreen(nav: Nav) {
    TabPage(nav, NavTab.Services) {
        Text("Browse the catalog of any major streaming service.", color = McdColors.Muted, modifier = Modifier.padding(horizontal = 48.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(220.dp),
            contentPadding = PaddingValues(48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(SERVICES) { s -> TileCard(title = s.name, subtitle = "Movies and shows", onClick = { nav.push(Screen.ServiceGrid(s)) }) }
        }
    }
}

@Composable
fun ServiceGridScreen(nav: Nav, service: Service) {
    var type by rememberSaveable { mutableStateOf("movie") }
    var pages by rememberSaveable(type) { mutableIntStateOf(2) }
    var retry by remember { mutableIntStateOf(0) }
    val newest by rememberLoad(service.id, type) { Tmdb.byService(type, service.id, 1, newest = true) }
    // "Load more" only fetches the new pages and appends them; pages already shown stay put.
    val all = remember(type) { mutableStateListOf<Title>() }
    var loaded by remember(type) { mutableIntStateOf(0) }
    var allErr by remember(type) { mutableStateOf<String?>(null) }
    LaunchedEffect(service.id, type, pages, retry) {
        allErr = null
        while (loaded < pages) {
            val next = loaded + 1
            val got = try {
                Tmdb.byService(type, service.id, next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                allErr = e.message ?: e.toString()
                return@LaunchedEffect
            }
            val seen = all.map { it.id }.toHashSet()
            all.addAll(got.distinctBy { it.id }.filter { it.id !in seen })
            loaded = next
        }
    }
    val listState = rememberLazyListState()
    // Movies go straight to the source list; shows open their page to pick an episode.
    val pick: (Title) -> Unit = { t -> nav.push(Screen.Detail(t.type, t.id, openSources = t.type == "movie")) }
    TabPage(nav, NavTab.Services) {
        Row(Modifier.padding(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(service.name.uppercase(), style = broadcastStyle(30.sp))
            ActionButton("Movies", { type = "movie" }, primary = type == "movie")
            ActionButton("Shows", { type = "tv" }, primary = type == "tv")
        }
        Text(
            "Current US catalog, refreshed daily from TMDB / JustWatch. Selecting a movie opens its sources.",
            color = McdColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp),
        )
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 48.dp)) {
            item {
                val n = newest
                if (n is Load.Ok) TitleRow("New on ${service.name}", n.value, pick)
            }
            item { RailHeader("Everything on ${service.name}", Modifier.padding(start = 48.dp, top = 18.dp)) }
            val err = allErr
            if (all.isEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = 48.dp)) {
                        StatusText(err ?: "Loading…")
                        if (err != null) ActionButton("Retry", { retry++ }, primary = true)
                    }
                }
            } else {
                // Rows of 5 posters (5 x 140dp + gaps fits the 864dp content width), so the whole catalog scrolls in one list.
                items(all.chunked(5)) { rowItems ->
                    Row(Modifier.padding(horizontal = 48.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        rowItems.forEach { t -> PosterCard(t, onClick = { pick(t) }) }
                    }
                }
                item(key = "loadMore") {
                    Row(Modifier.padding(horizontal = 48.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ActionButton(
                            if (err != null) "Retry (${all.size} shown)" else "Load more (${all.size} shown)",
                            { if (err != null) retry++ else pages += 2 },
                            primary = true,
                        )
                        if (err != null) StatusText(err) else if (loaded < pages) StatusText("Loading…")
                    }
                }
            }
        }
    }
}

// ============================== Background Noise ==============================

@Composable
fun NoiseScreen(nav: Nav) {
    var shows by remember { mutableStateOf(Library.noiseShows()) }
    TabPage(nav, NavTab.Noise) {
        Column(Modifier.padding(horizontal = 48.dp)) {
            Text("BACKGROUND NOISE", style = broadcastStyle(32.sp))
            Text(
                "Pick your shows once. Press play and random episodes keep coming, like leaving a TV on. " +
                    "Add shows with \"+ Background Noise\" on any show page.",
                color = McdColors.Muted, fontSize = 15.sp,
            )
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (shows.isNotEmpty()) ActionButton("▶  Play Background Noise", { nav.push(Screen.NoiseRun) }, primary = true)
                Text("Your shows: ${shows.size} of 50", color = McdColors.Muted)
            }
            if (shows.isEmpty()) StatusText("No shows yet. Open any show and press \"+ Background Noise\" to add it here.")
        }
        // After Remove, focus moves to the neighbouring show's Remove button instead of getting lost.
        var focusAfterRemove by remember { mutableStateOf<Int?>(null) }
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(shows, key = { _, t -> t.id }) { i, t ->
                val removeFocus = remember { FocusRequester() }
                LaunchedEffect(focusAfterRemove) {
                    if (focusAfterRemove == t.id) {
                        withFrameNanos { }
                        runCatching { removeFocus.requestFocus() }
                        focusAfterRemove = null
                    }
                }
                Column {
                    PosterCard(t, onClick = { nav.push(Screen.Detail("tv", t.id)) })
                    ActionButton("Remove", {
                        Library.toggleNoise(t)
                        val left = Library.noiseShows()
                        shows = left
                        focusAfterRemove = (left.getOrNull(i) ?: left.lastOrNull())?.id
                    }, Modifier.focusRequester(removeFocus))
                }
            }
        }
    }
}

/** Picks a random show + episode, finds the best source, plays, repeats when it ends. */
@Composable
fun NoiseRunScreen(nav: Nav) {
    var round by remember { mutableIntStateOf(0) }
    var url by remember { mutableStateOf<String?>(null) }
    var meta by remember { mutableStateOf<PlayMeta?>(null) }
    var headers by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var status by remember { mutableStateOf("Shuffling…") }

    LaunchedEffect(round) {
        url = null
        val shows = Library.noiseShows()
        if (shows.isEmpty()) { status = "Add shows first."; return@LaunchedEffect }
        // Real-Debrid takes one new torrent per 25 s: each round uses torrents already in the account, plus at most 2 adds.
        val adds = Resolver.AddAllowance(2)
        repeat(6) { attempt ->
            val pick = runCatching {
                val show = shows.random()
                status = "Shuffling… ${show.name}"
                val d = Tmdb.details("tv", show.id)
                val imdb = d.imdbId ?: error("No IMDb id")
                val s = d.seasons.filter { it.episodeCount > 0 }.random()
                val e = (1..s.episodeCount).random()
                val m = PlayMeta("tv", show.id, show.name, show.poster, show.backdrop, s.number, e)
                val list = Addons.streams("tv", "$imdb:${s.number}:$e", show.id)
                val (src, u) = Resolver.best(list, allowance = adds, onStatus = { status = "${show.name}: $it" })
                Triple(m, u, src.headers)
            }.getOrNull()
            if (pick != null) { meta = pick.first; headers = pick.third; url = pick.second; return@LaunchedEffect }
            status = "That one didn't work, trying another… (${attempt + 1})"
            delay(500)
        }
        status = "Could not find playable episodes. Check your addons and Real-Debrid."
    }

    val u = url
    val m = meta
    if (u != null && m != null) {
        key(u) { PlayerScreen(url = u, title = "Background Noise  •  ${m.label}", meta = m, onEnded = { round++ }, headers = headers) }
    } else {
        Box(Modifier.fillMaxSize().hudBackground().padding(48.dp)) {
            Column {
                McdLogo()
                Text(status, color = McdColors.White, fontSize = 20.sp, modifier = Modifier.padding(top = 24.dp))
            }
        }
    }
}

// ============================== Genres ==============================

@Composable
fun GenresScreen(nav: Nav) {
    var type by rememberSaveable { mutableStateOf("movie") }
    val genres = if (type == "movie") com.mcd.tv.data.MOVIE_GENRES else com.mcd.tv.data.TV_GENRES
    // Saved by id (Genre itself is not saveable) so Back from a title restores the selection.
    var genreId by rememberSaveable(type) { mutableIntStateOf(genres.first().id) }
    val genre = genres.firstOrNull { it.id == genreId } ?: genres.first()
    var topRated by rememberSaveable { mutableStateOf(false) }
    var year by rememberSaveable { mutableStateOf<Int?>(null) }
    val gridState = rememberLazyGridState()
    val years = remember { listOf<Int?>(null) + (java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) downTo 1960).toList() }
    val res by rememberLoad(type, genre.id, topRated, year) {
        (1..3).flatMap { Tmdb.byGenre(type, genre.id, topRated, it, year) }.distinctBy { it.id }
    }
    TabPage(nav, NavTab.Genres) {
        Row(Modifier.padding(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("Movies", { type = "movie" }, primary = type == "movie")
            ActionButton("Shows", { type = "tv" }, primary = type == "tv")
            ActionButton(if (topRated) "Sort: Top Rated" else "Sort: Popular", { topRated = !topRated })
            Text(
                com.mcd.tv.data.Prefs.origin.label, color = McdColors.Muted, fontSize = 13.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(genres, key = { it.id }) { g -> ActionButton(g.name, { genreId = g.id }, primary = g.id == genre.id) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(years) { y -> ActionButton(y?.toString() ?: "All years", { year = y }, primary = y == year) }
        }
        when (val r = res) {
            is Load.Loading -> StatusText("Loading ${genre.name}${year?.let { " from $it" } ?: ""}…", Modifier.padding(start = 48.dp))
            is Load.Err -> StatusText(r.message, Modifier.padding(start = 48.dp))
            is Load.Ok -> PosterGrid(r.value, gridState) { nav.push(Screen.Detail(it.type, it.id)) }
        }
    }
}
