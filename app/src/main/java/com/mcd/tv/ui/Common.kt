package com.mcd.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mcd.tv.data.Library
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.ProfilePhotos
import com.mcd.tv.data.Ratings
import com.mcd.tv.data.RatingsSource
import com.mcd.tv.data.Title
import com.mcd.tv.data.Tmdb
import kotlinx.coroutines.CancellationException

// ---------------- Async loading ----------------

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

/** Runs [block] off the main thread when the screen opens (or a key changes). */
@Composable
fun <T> rememberLoad(vararg keys: Any?, block: suspend () -> T): State<Load<T>> =
    produceState<Load<T>>(Load.Loading, *keys) {
        value = Load.Loading // a key change (e.g. Retry) shows Loading again instead of the old result
        value = try {
            Load.Ok(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Err(e.message ?: e.toString())
        }
    }

/** Status line. Text that ends in "…" (Loading…, Searching…) is a busy state and gets the HUD spinner. */
@Composable
fun StatusText(text: String, modifier: Modifier = Modifier) {
    val busy = text.endsWith("…")
    Row(modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (busy) {
            HudSpinner(diameter = 22.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = if (busy) text.uppercase() else text,
            color = if (busy) McdColors.Accent else McdColors.Muted,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = if (busy) 1.5.sp else 0.sp,
        )
    }
}

// ---------------- Cards ----------------

private val ImdbYellow = Color(0xFFF5C518)

/** True if [date] ("yyyy-MM-dd") is between [days] days ago and today. */
private fun releasedWithinDays(date: String, days: Int): Boolean {
    if (date.length < 10) return false
    val d = runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(date.take(10)) }.getOrNull() ?: return false
    val age = System.currentTimeMillis() - d.time
    return age >= 0 && age <= days * 86_400_000L
}

/**
 * Portrait poster on a chamfered HUD card. Scores sit in a small dark tag along the bottom (TMDB always;
 * IMDb and Rotten Tomatoes when an MDBList key is set, loaded lazily per visible card). "IN CINEMA" on
 * movies released in the last 45 days, a check mark on watched movies. The title shows only while focused.
 * [badges] = false hides all of that (e.g. collection tiles).
 */
@Composable
fun PosterCard(t: Title, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 140.dp, badges: Boolean = true) {
    val ratings by produceState<Ratings?>(if (badges) RatingsSource.cachedTmdb(t.type, t.id) else null, t.type, t.id, badges) {
        if (badges && value == null && RatingsSource.configured) value = RatingsSource.forTmdb(t.type, t.id)
    }
    val watched = remember(t.type, t.id, badges) { badges && Library.isWatched(t.type, t.id) }
    val inCinema = remember(t.type, t.releaseDate, badges) { badges && t.type == "movie" && releasedWithinDays(t.releaseDate, 45) }
    HudCard(onClick = onClick, modifier = modifier.width(width).height(width * 1.5f)) { focused ->
        AsyncImage(
            model = Tmdb.img(t.poster),
            contentDescription = t.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (t.poster == null) {
            Text(t.name, style = broadcastStyle(14.sp), modifier = Modifier.align(Alignment.Center).padding(8.dp))
        }
        if (inCinema) {
            Text(
                "IN CINEMA",
                style = hudLabelStyle(7.sp, McdColors.Ink),
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                    .background(McdColors.Accent, HudShapeTiny)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        if (watched) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape)
                    .background(McdColors.Ink.copy(alpha = 0.75f)).border(1.5.dp, McdColors.Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = McdColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        val r = ratings
        val imdb = if (badges) r?.imdb else null
        val rt = if (badges) r?.rtCritics else null
        val scores = buildAnnotatedString {
            var first = true
            fun sep() {
                if (!first) withStyle(SpanStyle(color = McdColors.Line)) { append("  ▪  ") }
                first = false
            }
            if (imdb != null) {
                sep()
                withStyle(SpanStyle(color = ImdbYellow, fontWeight = FontWeight.Bold)) { append("IMDb") }
                append(" %.1f".format(imdb))
            }
            if (rt != null) {
                sep()
                withStyle(SpanStyle(color = McdColors.Coral, fontWeight = FontWeight.Bold)) { append("RT") }
                append(" $rt%")
            }
            if (badges && imdb == null && t.rating > 0) {
                sep()
                withStyle(SpanStyle(color = McdColors.Accent, fontWeight = FontWeight.Bold)) { append("TMDB") }
                append(" %.1f".format(t.rating))
            }
        }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .then(
                    if (focused) Modifier.background(Brush.verticalGradient(listOf(Color.Transparent, McdColors.Ink.copy(alpha = 0.9f))))
                    else Modifier,
                )
                .padding(start = 6.dp, end = 6.dp, bottom = 6.dp, top = if (focused) 18.dp else 0.dp),
        ) {
            if (focused) {
                Text(
                    t.name.uppercase(), color = McdColors.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 13.sp,
                    letterSpacing = 0.6.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            if (scores.isNotEmpty()) {
                Text(
                    scores,
                    color = McdColors.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier
                        .background(McdColors.Ink.copy(alpha = 0.78f), HudShapeTiny)
                        .border(1.dp, McdColors.Line, HudShapeTiny)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** Poster-sized "See all" tile at the end of a row: opens the full grid for that row. */
@Composable
fun SeeAllCard(onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 210.dp) {
    HudCard(onClick = onClick, modifier = modifier.width(100.dp).height(height)) { focused ->
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SEE ALL", style = hudLabelStyle(10.sp, if (focused) McdColors.AccentBright else McdColors.White))
            Text("›", color = McdColors.Accent, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Landscape card with optional progress bar (Continue Watching). Title and subtitle sit under the image. */
@Composable
fun WideCard(
    title: String,
    subtitle: String,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(280.dp)) {
        HudCard(
            onClick = onClick,
            modifier = modifier.onFocusChanged { focused = it.isFocused }.width(280.dp).height(158.dp),
        ) { _ ->
            AsyncImage(model = image, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (progress != null) {
                Box(
                    Modifier.fillMaxWidth().height(40.dp).align(Alignment.BottomStart)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, McdColors.Ink.copy(alpha = 0.75f)))),
                )
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
                        .height(3.dp).background(McdColors.Line.copy(alpha = 0.6f)),
                ) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(McdColors.Accent))
                }
            }
        }
        // Title and subtitle only while focused (the space stays reserved so the row never jumps).
        Box(Modifier.padding(top = 8.dp).height(36.dp)) {
            if (focused) {
                Column {
                    Text(title, color = McdColors.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, color = McdColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** A titled horizontal row of posters. */
@Composable
fun TitleRow(label: String, items: List<Title>, onOpen: (Title) -> Unit) = TitleRail(label, items, null, onOpen)

/**
 * A titled horizontal row of posters; [onSeeAll] adds a "See all" tile at the end that opens the full grid.
 * [focus] (optional): the page's focus memory. The row reports its focused tile under [focusKey] and,
 * when the page is returned to, scrolls to that tile and focuses it again.
 */
@Composable
fun TitleRail(
    label: String,
    items: List<Title>,
    onSeeAll: (() -> Unit)?,
    onOpen: (Title) -> Unit,
    focus: FocusMemory? = null,
    focusKey: String = label,
) {
    if (items.isEmpty()) return
    val unique = remember(items) { items.distinctBy { "${it.type}-${it.id}" } }
    val rowState = rememberLazyListState()
    val tileCount = unique.size + (if (onSeeAll != null) 1 else 0)
    val restoreAt = focus.restoreTarget(focusKey, tileCount, rowState)
    Column(Modifier.padding(top = 20.dp)) {
        RailHeader(label, Modifier.padding(start = 48.dp))
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(unique, key = { _, t -> "${t.type}-${t.id}" }) { i, t ->
                PosterCard(t, onClick = { onOpen(t) }, modifier = focus.tileModifier(focusKey, i, restoreAt))
            }
            if (onSeeAll != null) item(key = "see-all") {
                SeeAllCard(onSeeAll, modifier = focus.tileModifier(focusKey, unique.size, restoreAt))
            }
        }
    }
}

/**
 * Which row and tile of a page had focus, so Back can put focus there again (Home). Saved with the
 * page's back-stack entry; after a restore, [pending] stays true until a tile takes focus again.
 * Rows report focus with [tileModifier] and restore with [restoreTarget].
 */
@androidx.compose.runtime.Stable
class FocusMemory {
    /** Key of the row that last had focus ("" = none yet). */
    var row by mutableStateOf("")
    /** Tile index in that row (-1 = the row as a whole). */
    var tile by mutableIntStateOf(-1)
    /** Position of that row in the page's list, to scroll back to it when it is not on screen. */
    var rowIndex by mutableIntStateOf(-1)
    /** True while focus still has to go back to [row] / [tile]. */
    var pending by mutableStateOf(false)
    /** Set by the page: where a row sits in its list, from the row's key (null = not on screen). */
    var indexOfRow: (String) -> Int? = { null }
    /** One requester per row key, used by [restoreTarget] and [tileModifier]. */
    private val requesters = HashMap<String, FocusRequester>()

    fun requesterFor(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    fun focused(key: String, tileIndex: Int) {
        row = key
        tile = tileIndex
        indexOfRow(key)?.let { rowIndex = it }
        pending = false
    }

    companion object {
        val Saver: androidx.compose.runtime.saveable.Saver<FocusMemory, Any> = androidx.compose.runtime.saveable.listSaver<FocusMemory, Any>(
            save = { listOf(it.row, it.tile, it.rowIndex) },
            restore = { v ->
                FocusMemory().apply {
                    row = v.getOrNull(0) as? String ?: ""
                    tile = v.getOrNull(1) as? Int ?: -1
                    rowIndex = v.getOrNull(2) as? Int ?: -1
                    pending = row.isNotEmpty()
                }
            },
        )
    }
}

/**
 * For a row of [count] tiles: the tile to focus again after Back (clamped to the row), or -1.
 * Scrolls the row to it and requests focus once the tile is laid out.
 */
@Composable
fun FocusMemory?.restoreTarget(key: String, count: Int, rowState: androidx.compose.foundation.lazy.LazyListState): Int {
    val m = this ?: return -1
    if (!m.pending || m.row != key || m.tile < 0 || count <= 0) return -1
    val target = m.tile.coerceIn(0, count - 1)
    val requester = m.requesterFor(key)
    LaunchedEffect(key, target) {
        rowState.scrollToItem(target)
        // A few frames' grace for the tile to attach; stops as soon as a tile has focus (pending clears).
        repeat(3) {
            withFrameNanos { }
            if (!m.pending) return@LaunchedEffect
            runCatching { requester.requestFocus() }
        }
    }
    return target
}

/** Reports focus on tile [index] of row [key]; tile [restoreAt] also gets the restore requester. */
fun FocusMemory?.tileModifier(key: String, index: Int, restoreAt: Int): Modifier {
    val m = this ?: return Modifier
    val base = Modifier.onFocusChanged { if (it.hasFocus) m.focused(key, index) }
    return if (index == restoreAt) base.focusRequester(m.requesterFor(key)) else base
}

/**
 * Chamfered HUD button (about 40dp tall), uppercase Rajdhani SemiBold.
 * Primary: cyan fill with near-black text; focused turns bright cyan with a glow and corner brackets.
 * Secondary: transparent with a cyan hairline and cyan text; focused fills cyan with dark text.
 * Focus also scales it up slightly.
 */
@Composable
fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val shape = HudShapeSmall
    val solid = primary || focused
    val fill = when {
        primary && focused -> McdColors.AccentBright
        solid -> McdColors.Accent
        else -> Color.Transparent
    }
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { val s = if (focused) 1.05f else 1f; scaleX = s; scaleY = s }
            .hudGlow(focused, shape, McdColors.Accent)
            .hudBrackets(focused, McdColors.AccentBright, inset = (-4).dp, arm = 7.dp, stroke = 1.5.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, if (solid) Color.Transparent else McdColors.Accent.copy(alpha = 0.75f), shape)
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            color = if (solid) McdColors.Ink else McdColors.Accent,
            fontFamily = HudText,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            maxLines = 1,
        )
    }
}

/** Round cast photo in a thin cyan ring. Focusable and clickable when [onClick] is given (opens the person's page). */
@Composable
fun CastBubble(name: String, role: String, photo: String?, onClick: (() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(104.dp)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { val sc = if (focused) 1.06f else 1f; scaleX = sc; scaleY = sc }
            .then(if (onClick != null) Modifier.clip(HudShape).clickable(onClick = onClick) else Modifier)
            .padding(4.dp),
    ) {
        Box(
            Modifier.size(90.dp).hudGlow(focused, CircleShape, McdColors.Accent, layers = 3),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = Tmdb.img(photo, "w185"),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(84.dp).clip(CircleShape).background(McdColors.Card)
                    .border(if (focused) 2.dp else 1.dp, if (focused) McdColors.Accent else McdColors.Line, CircleShape),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            name, fontSize = 13.sp, color = if (focused) McdColors.AccentBright else McdColors.White.copy(alpha = 0.9f),
            fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(role, fontSize = 12.sp, color = McdColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Top navigation: wordmark, uppercase Orbitron tabs (current one cyan with an underline and dot), clock, profile ring. */
enum class NavTab(val label: String) { Home("Home"), Search("Jarvis"), Browse("Browse"), Library("My List"), Genres("Genres"), Sports("Websites"), Live("Live TV"), Services("Services"), Noise("Background Noise"), Settings("Settings") }

/** Tabs shown in the bar, in order. Genres, Websites (NavTab.Sports), Services and Background Noise live under Browse. */
private val BarTabs = listOf(NavTab.Home, NavTab.Search, NavTab.Browse, NavTab.Live, NavTab.Library)

/** The bar tab to highlight for a page: pages reached from Browse highlight Browse. */
private fun NavTab.barTab(): NavTab = when (this) {
    NavTab.Genres, NavTab.Sports, NavTab.Services, NavTab.Noise -> NavTab.Browse
    else -> this
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .hudBrackets(focused, McdColors.AccentBright, inset = 0.dp, arm = 6.dp, stroke = 1.5.dp)
            .clip(HudShapeSmall)
            .background(if (focused) McdColors.Accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label.uppercase(),
            style = hudLabelStyle(
                11.sp,
                when {
                    focused -> McdColors.AccentBright
                    selected -> McdColors.Accent
                    else -> McdColors.Muted
                },
            ),
            fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
        Row(Modifier.padding(top = 4.dp).height(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Box(Modifier.width(14.dp).height(1.5.dp).background(McdColors.Accent))
                Spacer(Modifier.width(3.dp))
                Box(
                    Modifier.size(4.dp)
                        .drawBehind { drawCircle(McdColors.Accent.copy(alpha = 0.35f), radius = size.minDimension * 1.1f) }
                        .clip(CircleShape).background(McdColors.AccentBright),
                )
                Spacer(Modifier.width(3.dp))
                Box(Modifier.width(14.dp).height(1.5.dp).background(McdColors.Accent))
            }
        }
    }
}

/** Profile / settings button: a thin cyan ring with a person glyph. [badge]: an amber dot (an update is ready). */
@Composable
private fun ProfileCircle(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: Boolean = false,
    photo: ImageBitmap? = null,
    photoLabel: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = modifier
                .onFocusChanged { focused = it.isFocused }
                .graphicsLayer { val sc = if (focused) 1.08f else 1f; scaleX = sc; scaleY = sc }
                .size(36.dp)
                .hudGlow(focused || selected, CircleShape, McdColors.Accent, layers = 3)
                .clip(CircleShape)
                .background(if (focused) McdColors.Accent.copy(alpha = 0.18f) else McdColors.Ink.copy(alpha = 0.4f))
                .clickable(onClick = onClick),
        ) {
            // The active profile's photo (set from the phone) fills the circle; the ring draws over it.
            if (photo != null) {
                Image(photo, contentDescription = photoLabel, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
            }
            Canvas(Modifier.fillMaxSize()) {
                val ring = if (focused) McdColors.AccentBright else McdColors.Accent
                val sw = (if (focused) 2.dp else 1.5.dp).toPx()
                drawCircle(ring, radius = size.minDimension / 2f - sw / 2f, style = Stroke(sw))
                if (photo == null) drawPersonIcon(ring.copy(alpha = 0.95f))
            }
        }
        if (badge) {
            Box(
                Modifier.align(Alignment.TopEnd).size(11.dp).clip(CircleShape)
                    .background(McdColors.Navy).padding(1.5.dp).clip(CircleShape).background(McdColors.Amber),
            )
        }
    }
}

/** The person icon in the top bar's circle, for a profile without a photo. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPersonIcon(c: Color) {
    drawCircle(c, radius = size.minDimension * 0.13f, center = Offset(size.width / 2, size.height * 0.39f))
    drawArc(
        c, startAngle = 180f, sweepAngle = 180f, useCenter = true,
        topLeft = Offset(size.width * 0.27f, size.height * 0.58f),
        size = Size(size.width * 0.46f, size.height * 0.34f),
    )
}

/**
 * Transparent top bar (it floats over hero backdrops): wordmark, text tabs, clock, who is watching, profile ring.
 * [profileName] + [onProfile]: shows the active profile; selecting it opens "Who's watching?" to switch.
 */
@Composable
fun TopNav(
    current: NavTab,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    profileName: String? = null,
    onProfile: (() -> Unit)? = null,
) {
    val currentFocus = remember { FocusRequester() }
    if (autoFocus) {
        // Give the remote somewhere to start: focus the current tab once the page is laid out.
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { currentFocus.requestFocus() } }
    }
    val shown = current.barTab()
    // The circle shows the active profile's photo from the same store as Who's watching; it redraws when a photo
    // arrives (from the phone or another TV) and follows the profile (Home is rebuilt when the profile changes).
    val photoRev by ProfilePhotos.revision.collectAsState()
    val active = Prefs.activeProfile
    val photo = remember(photoRev, active) { ProfilePhotos.bitmap(active) }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        McdLogo(scale = 0.7f)
        Spacer(Modifier.width(22.dp))
        BarTabs.forEach { tab ->
            NavItem(tab.label, tab == shown, { onSelect(tab) }, if (tab == shown) Modifier.focusRequester(currentFocus) else Modifier)
        }
        Spacer(Modifier.weight(1f))
        HudClock()
        Spacer(Modifier.width(14.dp))
        if (profileName != null && onProfile != null) {
            NavItem("◉ $profileName", false, onProfile)
            Spacer(Modifier.width(6.dp))
        }
        ProfileCircle(
            current == NavTab.Settings, { onSelect(NavTab.Settings) },
            if (current == NavTab.Settings) Modifier.focusRequester(currentFocus) else Modifier,
            badge = com.mcd.tv.data.Updater.available != null,
            photo = photo,
            photoLabel = "${Prefs.profileName(active)} profile photo",
        )
    }
}
