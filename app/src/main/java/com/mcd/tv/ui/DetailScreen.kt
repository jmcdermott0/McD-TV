package com.mcd.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mcd.tv.Nav
import com.mcd.tv.Screen
import com.mcd.tv.data.ContinueRules
import com.mcd.tv.data.Details
import com.mcd.tv.data.Episode
import com.mcd.tv.data.Library
import com.mcd.tv.data.PlayMeta
import com.mcd.tv.data.Tmdb

/** Title page: backdrop, poster, actions, seasons/episodes (TV), cast, similar. */
@Composable
fun DetailScreen(nav: Nav, type: String, id: Int, openSources: Boolean = false) {
    var retry by remember { mutableIntStateOf(0) }
    val load by rememberLoad(type, id, retry) { Tmdb.details(type, id) }
    Box(Modifier.fillMaxSize().hudBackground()) {
        when (val l = load) {
            is Load.Loading -> StatusText("Loading…", Modifier.padding(48.dp))
            is Load.Err -> Column(Modifier.padding(48.dp)) {
                StatusText(l.message)
                ActionButton("Retry", { retry++ }, primary = true)
            }
            is Load.Ok -> {
                val d = l.value
                // From a service catalog: replace this page with the source list right away.
                LaunchedEffect(d.title.id) {
                    val imdb = d.imdbId
                    if (openSources && imdb != null) {
                        nav.replace(Screen.Sources(PlayMeta(d.title.type, d.title.id, d.title.name, d.title.poster, d.title.backdrop), imdb, autoPlay = false))
                    }
                }
                DetailBody(nav, d)
            }
        }
    }
}

@Composable
private fun DetailBody(nav: Nav, d: Details) {
    val t = d.title
    var fav by remember { mutableStateOf(Library.isFavorite(t)) }
    var listed by remember { mutableStateOf(Library.inWatchlist(t)) }
    var noise by remember { mutableStateOf(Library.inNoise(t)) }
    var liked by remember { mutableStateOf(Library.isLiked(t)) }
    var note by remember { mutableStateOf("") }
    // More Like This: ranked for this profile (story, genres, people, quality, taste). TMDB's list until ready.
    val similarLoad by rememberLoad(t.type, t.id, com.mcd.tv.data.Prefs.activeProfile) {
        com.mcd.tv.data.Recommender.moreLikeThis(t.type, t.id, d.similar)
    }
    val playFocus = remember { FocusRequester() }
    val context = LocalContext.current

    // TV: resume the last episode watched, else S1E1.
    // Where this profile left off on any device (resume sync), before Play / Resume is pressed.
    LaunchedEffect(Unit) { com.mcd.tv.data.ResumeCloud.pull() }
    val cloud = com.mcd.tv.data.ResumeCloud.version
    val last = remember(cloud) { Library.history().firstOrNull { it.meta.historyKey == "tv:${t.id}" } }
    var season by remember { mutableIntStateOf(last?.meta?.season?.takeIf { it > 0 } ?: d.seasons.firstOrNull()?.number ?: 1) }

    fun meta(s: Int = 0, e: Int = 0) = PlayMeta(t.type, t.id, t.name, t.poster, t.backdrop, s, e)

    fun play(s: Int, e: Int, auto: Boolean) {
        val imdb = d.imdbId
        if (imdb == null) { note = "No IMDb id for this title, so addons cannot find sources."; return }
        nav.push(Screen.Sources(meta(s, e), imdb, auto))
    }

    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { playFocus.requestFocus() } }

    // "⋯ More" opens a second row of actions; focus jumps to its first button.
    var more by remember { mutableStateOf(false) }
    val moreFocus = remember { FocusRequester() }
    LaunchedEffect(more) {
        if (more) { withFrameNanos { }; runCatching { moreFocus.requestFocus() } }
    }
    val movieProgress = remember(cloud) { if (t.type == "movie") Library.history().firstOrNull { it.meta.historyKey == "movie:${t.id}" } else null }
    // The last episode was finished: Play starts the next one (Sources rolls over to the next season).
    val upNext = last?.takeIf { it.meta.season > 0 }?.let { ContinueRules.playEpisode(it.meta.season, it.meta.episode, it.progress) }
        ?: (season to 1)
    val playLabel = when {
        t.type == "tv" && last != null && last.finished && last.meta.season > 0 -> "▶  Play S${upNext.first}E${upNext.second}"
        t.type == "tv" && last != null -> "▶  Resume S${last.meta.season}E${last.meta.episode}"
        movieProgress != null && !movieProgress.finished && movieProgress.positionMs > 60_000 -> "▶  Resume"
        else -> "▶  Play"
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
        item {
            Box(Modifier.fillMaxWidth().heightIn(min = 400.dp)) {
                AsyncImage(
                    Tmdb.img(t.backdrop, "w1280"), t.name, contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter, colorFilter = HudDuotone, modifier = Modifier.matchParentSize(),
                )
                // Duotone wash and scanlines.
                Box(Modifier.matchParentSize().background(McdColors.Ink.copy(alpha = 0.25f)).background(McdColors.Accent.copy(alpha = 0.15f)).hudScanlines())
                // Full-bleed backdrop melting into the page on the left and at the bottom (Max-style).
                Box(
                    Modifier.matchParentSize().background(
                        Brush.horizontalGradient(
                            0f to McdColors.Navy,
                            0.35f to McdColors.Navy.copy(alpha = 0.88f),
                            0.65f to McdColors.Navy.copy(alpha = 0.35f),
                            1f to Color.Transparent,
                        ),
                    ),
                )
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(
                            0f to McdColors.Navy.copy(alpha = 0.45f),
                            0.20f to Color.Transparent,
                            0.65f to Color.Transparent,
                            0.88f to McdColors.Navy.copy(alpha = 0.85f),
                            1f to McdColors.Navy,
                        ),
                    ),
                )
                Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
                    AsyncImage(
                        Tmdb.img(t.poster, "w342"), t.name, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(160.dp).height(240.dp)
                            .hudBrackets(!McdLook.calm, McdColors.Accent, inset = (-5).dp, arm = 12.dp, stroke = 1.5.dp)
                            .clip(HudShape).background(McdColors.Card).border(1.dp, McdColors.Line, HudShape),
                    )
                    Spacer(Modifier.width(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.name.uppercase(), style = broadcastStyle(32.sp).copy(lineHeight = 32.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (d.tagline.isNotBlank()) Text(d.tagline, color = McdColors.Accent, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                t.year.ifBlank { null },
                                if (d.runtimeMin > 0) "${d.runtimeMin / 60}h ${d.runtimeMin % 60}m" else null,
                                d.genres.take(3).joinToString(" / ").ifBlank { null },
                            ).joinToString("  ▪  ").uppercase(),
                            color = McdColors.Muted, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, letterSpacing = 1.sp,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                        RatingsRow(t.rating, d.imdbId)
                        Text(t.overview, color = McdColors.White.copy(alpha = 0.9f), fontSize = 15.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(10.dp))
                        // Main row: Play / Resume, Trailer, Watchlist, and "More" for everything else.
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionButton(playLabel, {
                                if (t.type == "movie") play(0, 0, true)
                                else play(upNext.first, upNext.second, true)
                            }, Modifier.focusRequester(playFocus), primary = true)
                            d.trailerKey?.let { key ->
                                ActionButton("▶  Trailer", { if (!openYouTube(context, key)) note = "No YouTube app on this TV" })
                            }
                            ActionButton(if (listed) "✓ Watchlist" else "+ Watchlist", { listed = Library.toggleWatchlist(t) })
                            ActionButton(if (more) "⋯ Less" else "⋯ More", { more = !more })
                        }
                        if (more) {
                            Spacer(Modifier.height(8.dp))
                            // LazyRow: long labels scroll instead of running off the screen.
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (t.type == "movie") item { ActionButton("Choose Source", { play(0, 0, false) }, Modifier.focusRequester(moreFocus)) }
                                item {
                                    ActionButton(
                                        if (fav) "♥ Favorite" else "♡ Favorite", { fav = Library.toggleFavorite(t); com.mcd.tv.data.Recommender.invalidate() },
                                        if (t.type == "movie") Modifier else Modifier.focusRequester(moreFocus),
                                    )
                                }
                                item {
                                    ActionButton(if (liked) "✓ I like this" else "👍 I like this", {
                                        liked = Library.toggleLike(t)
                                        com.mcd.tv.data.Recommender.invalidate()
                                        note = if (liked) "Got it. You'll see more like this." else ""
                                    })
                                }
                                if (t.type == "movie") item { ActionButton("Mark as Watched", { Library.markWatched(meta()); note = "Marked as watched" }) }
                                if (t.type == "tv") item {
                                    ActionButton(if (noise) "✓ In Background Noise" else "+ Background Noise", { noise = Library.toggleNoise(t) })
                                }
                                item { ActionButton("Not for me", { Library.hide(t); com.mcd.tv.data.Recommender.invalidate(); note = "Hidden from home rows"; nav.back() }) }
                            }
                        }
                        if (note.isNotBlank()) Text(note, color = McdColors.Amber, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }

        if (t.type == "tv" && d.seasons.isNotEmpty()) {
            item {
                Column(Modifier.padding(top = 12.dp)) {
                    RailHeader("Episodes", Modifier.padding(start = 48.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(d.seasons, key = { it.number }) { s -> ActionButton(s.name, { season = s.number }, primary = s.number == season) }
                    }
                }
            }
            item { EpisodeRow(t.id, season, onPlay = { e -> play(e.season, e.number, false) }) }
        }

        if (d.cast.isNotEmpty()) item {
            Column(Modifier.padding(top = 18.dp)) {
                RailHeader("Cast", Modifier.padding(start = 48.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(d.cast) { c -> CastBubble(c.name, c.character, c.photo) { if (c.id > 0) nav.push(Screen.Person(c.id)) } }
                }
            }
        }
        item {
            val list = (similarLoad as? Load.Ok)?.value ?: d.similar
            TitleRow(if (t.type == "tv") "More Shows Like This" else "More Like This", list) { s -> nav.push(Screen.Detail(s.type, s.id)) }
        }
        val collectionId = d.collectionId
        if (t.type == "movie" && collectionId != null) item { CollectionRow(nav, collectionId, d.collectionName) }
    }
}

@Composable
private fun EpisodeRow(tvId: Int, season: Int, onPlay: (Episode) -> Unit) {
    val eps by rememberLoad(tvId, season) { Tmdb.season(tvId, season) }
    val watched = remember(tvId, season) { Library.seasonProgress(tvId, season) }
    when (val l = eps) {
        is Load.Loading -> StatusText("Loading episodes…", Modifier.padding(start = 48.dp))
        is Load.Err -> StatusText(l.message, Modifier.padding(start = 48.dp))
        is Load.Ok -> LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(l.value, key = { it.number }) { e ->
                val p = watched[e.number]
                val done = p != null && p >= 0.9f
                WideCard(
                    title = "${e.number}. ${e.name}",
                    subtitle = if (done) "✓ Watched   ${e.airDate}" else e.airDate,
                    image = Tmdb.img(e.still, "w500"),
                    onClick = { onPlay(e) },
                    progress = if (p != null && !done && p > 0.01f) p else null,
                )
            }
        }
    }
}

/** Movie franchise row (TMDB collection), every entry, oldest first. */
@Composable
private fun CollectionRow(nav: Nav, collectionId: Int, fallbackName: String?) {
    val c by rememberLoad(collectionId) { Tmdb.collection(collectionId) }
    val col = (c as? Load.Ok<com.mcd.tv.data.TitleCollection>)?.value ?: return
    if (col.parts.size < 2) return
    TitleRow(col.name.ifBlank { fallbackName ?: "Collection" }, col.parts) { s -> nav.push(Screen.Detail(s.type, s.id)) }
}

/**
 * Plays a YouTube video: the TV YouTube app first (Fire TV, then Android TV), then any app
 * that opens the link. Returns false when nothing on this TV can open it.
 */
fun openYouTube(context: android.content.Context, key: String): Boolean {
    val web = android.net.Uri.parse("https://www.youtube.com/watch?v=$key")
    val app = android.net.Uri.parse("vnd.youtube:$key")
    fun tryStart(uri: android.net.Uri, pkg: String?): Boolean {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) intent.setPackage(pkg)
        return try {
            context.startActivity(intent)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
    for (pkg in listOf("com.amazon.firetv.youtube", "com.google.android.youtube.tv")) {
        if (tryStart(web, pkg) || tryStart(app, pkg)) return true
    }
    return tryStart(web, null)
}

/** Scores as small HUD arc gauges: TMDB, plus IMDb, Rotten Tomatoes critics / audience and Metacritic when MDBList is set up. */
@Composable
private fun RatingsRow(tmdb: Double, imdbId: String?) {
    val r by rememberLoad(imdbId) { if (imdbId != null) com.mcd.tv.data.RatingsSource.forImdb(imdbId) else com.mcd.tv.data.Ratings() }
    val ratings = (r as? Load.Ok<com.mcd.tv.data.Ratings>)?.value ?: com.mcd.tv.data.Ratings()
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        if (tmdb > 0) HudGauge((tmdb / 10.0).toFloat(), "%.1f".format(tmdb), "TMDB")
        ratings.imdb?.let { HudGauge((it / 10.0).toFloat(), "%.1f".format(it), "IMDb", color = Color(0xFFF5C518)) }
        ratings.rtCritics?.let { HudGauge(it / 100f, "$it%", "RT", color = McdColors.Coral) }
        ratings.rtAudience?.let { HudGauge(it / 100f, "$it%", "AUDIENCE", color = McdColors.Amber) }
        ratings.metacritic?.let { HudGauge(it / 100f, "$it", "META", color = McdColors.AccentBright) }
    }
}
