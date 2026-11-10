package com.mcd.tv.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mcd.tv.Nav
import com.mcd.tv.Screen
import com.mcd.tv.data.Channel
import com.mcd.tv.data.LiveIndex
import com.mcd.tv.data.LiveOrganizer
import com.mcd.tv.data.M3u
import com.mcd.tv.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ======================= Live TV multiview (1, 2 or 4 channels at once) =======================
//
// Flow: pick a layout, fill each tile from the channel chooser (Favorites, Recent, Search, sections, All), Start.
// While watching: arrows move between tiles, OK makes the focused tile the audio source, hold OK or Menu opens
// tile options (swap channel, full screen, layout). Back leaves multiview and releases every player.
// One ExoPlayer per tile; only the audio tile has volume. Muted tiles ask HLS for smaller variants so a
// Fire TV Stick has a chance of decoding several streams.

private const val PREF_LAST = "multiview_last"
private const val MAX_TILES = 4
private const val TILE_ERROR = "Couldn't play this channel. Your provider may limit how many streams play at once."

/** Last setup: layout (1, 2 or 4) and one stream URL per tile ("" = empty). */
private class MultiviewSetup(val layout: Int, val urls: List<String>)

private fun loadLastSetup(): MultiviewSetup? = runCatching {
    val raw = Prefs.json(PREF_LAST)
    if (raw.isBlank()) return@runCatching null
    val o = JSONObject(raw)
    val layout = o.optInt("layout", 4).let { if (it == 1 || it == 2) it else 4 }
    val a = o.optJSONArray("urls") ?: JSONArray()
    MultiviewSetup(layout, List(MAX_TILES) { i -> if (i < a.length()) a.optString(i, "") else "" })
}.getOrNull()

private fun saveLastSetup(layout: Int, slots: List<String?>) {
    if (slots.take(layout).all { it.isNullOrBlank() }) return
    val a = JSONArray()
    for (i in 0 until MAX_TILES) a.put(slots.getOrNull(i) ?: "")
    val o = JSONObject().put("layout", layout).put("urls", a)
    runCatching { Prefs.putJson(PREF_LAST, o.toString()) }
}

@Composable
fun MultiviewScreen(nav: Nav) {
    val hasPlaylist = Prefs.m3uUrl.isNotBlank()
    if (!hasPlaylist) {
        MultiviewMessage(
            "No playlist yet. Add your M3U playlist on the Phone & Computer Setup page, then pick channels here.",
            actionLabel = "Phone & Computer Setup",
            onAction = { nav.push(Screen.PhoneSetup) },
        )
        return
    }
    val res by rememberLoad(Prefs.m3uUrl) { LiveOrganizer.indexFor(M3u.load()) }
    when (val r = res) {
        is Load.Loading -> MultiviewMessage("Loading your channels…")
        is Load.Err -> MultiviewMessage(r.message)
        is Load.Ok -> if (r.value.size == 0) {
            MultiviewMessage("Your playlist has no live channels.")
        } else {
            Multiview(nav, r.value)
        }
    }
}

/** Header plus one line of status (no playlist, loading, errors). */
@Composable
private fun MultiviewMessage(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(actionLabel) {
        if (actionLabel != null) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
    }
    Column(Modifier.fillMaxSize().hudBackground().padding(horizontal = 48.dp, vertical = 32.dp)) {
        MultiviewHeader()
        StatusText(text)
        if (actionLabel != null && onAction != null) {
            ActionButton(actionLabel, onAction, Modifier.focusRequester(focus), primary = true)
        }
    }
}

@Composable
private fun MultiviewHeader() {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("MULTIVIEW", style = broadcastStyle(30.sp))
        Spacer(Modifier.width(16.dp))
        Text(
            "Watch up to 4 live channels at once",
            color = McdColors.Muted,
            fontSize = 15.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

@Composable
private fun Multiview(nav: Nav, index: LiveIndex) {
    var layout by remember { mutableIntStateOf(4) }
    val slots = remember { mutableStateListOf<String?>(null, null, null, null) }
    var playing by remember { mutableStateOf(false) }
    /** Tile whose channel the chooser is picking (null = chooser closed). */
    var chooserSlot by remember { mutableStateOf<Int?>(null) }
    var selected by remember { mutableIntStateOf(0) }
    var audio by remember { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf(false) }
    /** Setup tile the chooser was opened from, so focus goes back to it. */
    var lastEdited by remember { mutableStateOf<Int?>(null) }

    // Last multiview, keeping only channels still in the playlist.
    val last = remember(index) {
        loadLastSetup()?.let { s ->
            val urls = s.urls.map { u -> if (u.isNotBlank() && index.indexOf(u) >= 0) u else "" }
            if (urls.take(s.layout).any { it.isNotBlank() }) MultiviewSetup(s.layout, urls) else null
        }
    }

    val start: () -> Unit = {
        val first = (0 until layout).firstOrNull { !slots[it].isNullOrBlank() } ?: 0
        selected = first
        audio = first
        overlay = false
        playing = true
    }
    val resume: () -> Unit = {
        val s = last
        if (s != null) {
            layout = s.layout
            for (i in 0 until MAX_TILES) slots[i] = s.urls.getOrNull(i)?.ifBlank { null }
            start()
        }
    }
    val setLayout: (Int) -> Unit = { n ->
        layout = n
        if (selected >= n) selected = 0
        if (audio >= n) audio = selected
    }
    val pick: (Int, String?) -> Unit = { slot, url ->
        // The same channel in two tiles would open two connections: move it instead.
        if (url != null) for (i in 0 until MAX_TILES) if (i != slot && slots[i] == url) slots[i] = null
        slots[slot] = url
        if (playing) selected = slot
        chooserSlot = null
    }

    // Remember the setup while watching, so "Resume last multiview" brings it back next time.
    val slotKey = slots.joinToString("|") { it ?: "" }
    LaunchedEffect(playing, layout, slotKey) {
        if (playing) saveLastSetup(layout, slots.toList())
    }

    BackHandler(enabled = chooserSlot != null || overlay) {
        if (chooserSlot != null) chooserSlot = null else overlay = false
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (playing) {
            MultiviewPlayback(
                index = index,
                layout = layout,
                slots = slots,
                selected = selected,
                audio = audio,
                blocked = overlay || chooserSlot != null,
                onSelect = { selected = it },
                onOk = { i ->
                    if (slots[i].isNullOrBlank()) { chooserSlot = i } else { audio = i }
                },
                onOptions = { overlay = true },
            )
            if (overlay && chooserSlot == null) {
                val url = slots.getOrNull(selected)
                TileOptions(
                    title = url?.let { u -> index.indexOf(u).takeIf { it >= 0 }?.let { index.names[it] } } ?: "Empty tile",
                    hasChannel = url != null,
                    layout = layout,
                    onSwap = { overlay = false; chooserSlot = selected },
                    onFullScreen = {
                        // Move this channel into tile 1 (the others keep their places for going back to 2 or 4).
                        val s = selected
                        if (s != 0) {
                            val tmp = slots[0]
                            slots[0] = slots[s]
                            slots[s] = tmp
                        }
                        layout = 1
                        selected = 0
                        audio = 0
                        overlay = false
                    },
                    onLayout = { n -> setLayout(n); overlay = false },
                    onClose = { overlay = false },
                )
            }
        } else if (chooserSlot == null) {
            MultiviewSetupPage(
                index = index,
                layout = layout,
                slots = slots,
                last = last,
                onLayout = setLayout,
                focusSlot = lastEdited,
                onSlot = { lastEdited = it; chooserSlot = it },
                onClear = { slots[it] = null },
                onStart = start,
                onResume = resume,
            )
        }
        chooserSlot?.let { slot ->
            ChannelChooser(
                nav = nav,
                index = index,
                slot = slot,
                current = slots.getOrNull(slot),
                onPick = { url -> pick(slot, url) },
                onRemove = { pick(slot, null) },
                onCancel = { chooserSlot = null },
            )
        }
    }
}

// ============================== Setup ==============================

@Composable
private fun MultiviewSetupPage(
    index: LiveIndex,
    layout: Int,
    slots: List<String?>,
    last: MultiviewSetup?,
    focusSlot: Int?,
    onLayout: (Int) -> Unit,
    onSlot: (Int) -> Unit,
    onClear: (Int) -> Unit,
    onStart: () -> Unit,
    onResume: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val slotFocus = remember { List(MAX_TILES) { FocusRequester() } }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        val back = focusSlot != null && focusSlot < layout && runCatching { slotFocus[focusSlot].requestFocus() }.isSuccess
        if (!back) runCatching { firstFocus.requestFocus() }
    }
    val filled = (0 until layout).count { !slots[it].isNullOrBlank() }
    Column(Modifier.fillMaxSize().hudBackground().padding(horizontal = 48.dp, vertical = 28.dp)) {
        MultiviewHeader()
        Spacer(Modifier.height(14.dp))
        if (last != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionButton("Resume last multiview", onResume, Modifier.focusRequester(firstFocus), primary = true)
                Spacer(Modifier.width(14.dp))
                val names = last.urls.take(last.layout).filter { it.isNotBlank() }
                    .mapNotNull { u -> index.indexOf(u).takeIf { it >= 0 }?.let { index.names[it] } }
                Text(
                    names.joinToString("  •  "),
                    color = McdColors.Muted,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(14.dp))
        }
        Text("LAYOUT", style = hudLabelStyle(12.sp))
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(
                "1 channel", { onLayout(1) },
                if (last == null) Modifier.focusRequester(firstFocus) else Modifier,
                primary = layout == 1,
            )
            ActionButton("2 side by side", { onLayout(2) }, primary = layout == 2)
            ActionButton("4 in a grid", { onLayout(4) }, primary = layout == 4)
        }
        Spacer(Modifier.height(16.dp))
        Text("CHANNELS", style = hudLabelStyle(12.sp))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)) {
                SetupTiles(index, layout, slots, slotFocus, onSlot, onClear)
            }
            Spacer(Modifier.width(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (filled > 0) {
                    ActionButton("Start", onStart, primary = true)
                } else {
                    Text("Add at least one channel to start.", color = McdColors.Muted, fontSize = 15.sp)
                }
                Text(
                    "OK on a tile picks its channel. Hold OK on a tile to clear it.",
                    color = McdColors.Muted, fontSize = 13.sp,
                )
                Text(
                    "While watching: arrows move between tiles, OK plays that tile's sound, hold OK or press ☰ Menu for options.",
                    color = McdColors.Muted, fontSize = 13.sp,
                )
                Text(
                    "Many IPTV providers only allow one or two streams at the same time. If a tile can't play, try fewer channels.",
                    color = McdColors.Amber.copy(alpha = 0.85f), fontSize = 13.sp,
                )
            }
        }
    }
}

/** Preview of the layout: one card per tile ("+ Add channel" when empty). */
@Composable
private fun SetupTiles(
    index: LiveIndex,
    layout: Int,
    slots: List<String?>,
    slotFocus: List<FocusRequester>,
    onSlot: (Int) -> Unit,
    onClear: (Int) -> Unit,
) {
    val tile: @Composable (Int, Modifier) -> Unit = { i, m ->
        SetupTile(index, i, slots.getOrNull(i), { onSlot(i) }, { onClear(i) }, m.focusRequester(slotFocus[i]))
    }
    val gap = 10.dp
    when (layout) {
        1 -> tile(0, Modifier.fillMaxSize())
        2 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            tile(0, Modifier.weight(1f).aspectRatio(16f / 9f))
            tile(1, Modifier.weight(1f).aspectRatio(16f / 9f))
        }
        else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                tile(0, Modifier.weight(1f).fillMaxHeight())
                tile(1, Modifier.weight(1f).fillMaxHeight())
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                tile(2, Modifier.weight(1f).fillMaxHeight())
                tile(3, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun SetupTile(index: LiveIndex, slot: Int, url: String?, onClick: () -> Unit, onClear: () -> Unit, modifier: Modifier) {
    val i = url?.let { index.indexOf(it) } ?: -1
    val ch = if (i >= 0) index.channels[i] else null
    HudCard(onClick = onClick, onLongClick = onClear, modifier = modifier, focusedScale = 1.03f) { focused ->
        Box(Modifier.fillMaxSize()) {
            Text(
                "${slot + 1}",
                style = hudLabelStyle(12.sp),
                modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 10.dp, vertical = 6.dp),
            )
            if (ch == null) {
                Text(
                    "+ Add channel",
                    color = if (focused) McdColors.AccentBright else McdColors.Accent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Column(Modifier.align(Alignment.Center).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (ch.logo != null) {
                        AsyncImage(
                            model = ch.logo,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(width = 120.dp, height = 56.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        index.names[i],
                        style = broadcastStyle(16.sp),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ============================== Playback ==============================

/** Where tile [i] goes for [layout] inside a [w] x [h] area: x, y, width, height. */
private fun tileRect(i: Int, layout: Int, w: Dp, h: Dp, gap: Dp): List<Dp> = when (layout) {
    1 -> listOf(0.dp, 0.dp, w, h)
    2 -> {
        val tw = (w - gap) / 2
        val th = (tw * 9f / 16f).coerceAtMost(h)
        listOf(if (i == 0) 0.dp else tw + gap, (h - th) / 2, tw, th)
    }
    else -> {
        val tw = (w - gap) / 2
        val th = (h - gap) / 2
        listOf(if (i % 2 == 0) 0.dp else tw + gap, if (i / 2 == 0) 0.dp else th + gap, tw, th)
    }
}

/** Next selected tile after an arrow press. */
private fun moveTile(sel: Int, layout: Int, k: Key): Int = when (layout) {
    2 -> when (k) {
        Key.DirectionLeft -> 0
        Key.DirectionRight -> 1
        else -> sel
    }
    4 -> {
        val r = sel / 2
        val c = sel % 2
        when (k) {
            Key.DirectionLeft -> r * 2
            Key.DirectionRight -> r * 2 + 1
            Key.DirectionUp -> c
            Key.DirectionDown -> 2 + c
            else -> sel
        }
    }
    else -> 0
}

@Composable
private fun MultiviewPlayback(
    index: LiveIndex,
    layout: Int,
    slots: List<String?>,
    selected: Int,
    audio: Int,
    blocked: Boolean,
    onSelect: (Int) -> Unit,
    onOk: (Int) -> Unit,
    onOptions: () -> Unit,
) {
    val gridFocus = remember { FocusRequester() }
    // A held OK (repeating key-down) opens the options when the key comes back up.
    val longPress = remember { booleanArrayOf(false) }
    var hintTick by remember { mutableIntStateOf(0) }
    var hintVisible by remember { mutableStateOf(true) }

    LaunchedEffect(blocked) {
        if (!blocked) {
            withFrameNanos { }
            runCatching { gridFocus.requestFocus() }
        }
    }
    LaunchedEffect(hintTick, layout) {
        hintVisible = true
        delay(5_000)
        hintVisible = false
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                when (e.key) {
                    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> {
                        if (e.type == KeyEventType.KeyDown) {
                            val n = moveTile(selected, layout, e.key)
                            if (n != selected) onSelect(n)
                        }
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (e.type == KeyEventType.KeyDown) {
                            if (e.nativeKeyEvent.repeatCount > 0) longPress[0] = true
                        } else if (e.type == KeyEventType.KeyUp) {
                            if (longPress[0]) {
                                longPress[0] = false
                                onOptions()
                            } else {
                                onOk(selected)
                                hintTick++
                            }
                        }
                        true
                    }
                    Key.Menu -> {
                        if (e.type == KeyEventType.KeyUp) onOptions()
                        true
                    }
                    else -> false
                }
            }
            .focusProperties { canFocus = !blocked }
            .focusRequester(gridFocus)
            .focusable(),
    ) {
        val w = maxWidth
        val h = maxHeight
        val gap = if (layout == 1) 0.dp else 6.dp
        for (i in 0 until layout) {
            val url = slots.getOrNull(i)
            val r = tileRect(i, layout, w, h, gap)
            val tileModifier = Modifier.offset(x = r[0], y = r[1]).size(width = r[2], height = r[3])
            // Keyed by URL: a channel that moves to another tile (full screen, layout change) keeps its player.
            key(url ?: "empty-$i") {
                val ci = url?.let { index.indexOf(it) } ?: -1
                val showFocus = layout > 1 && i == selected
                if (ci >= 0) {
                    MultiTile(
                        ch = index.channels[ci],
                        name = index.names[ci],
                        layout = layout,
                        isAudio = i == audio,
                        isSelected = showFocus,
                        showAudioBadge = layout > 1 && i == audio,
                        modifier = tileModifier,
                    )
                } else {
                    Box(
                        tileModifier
                            .background(McdColors.Card)
                            .border(if (showFocus) 3.dp else 1.dp, if (showFocus) McdColors.Accent else McdColors.Line, RectangleShape)
                            .hudBrackets(showFocus, McdColors.AccentBright, inset = 8.dp, arm = 16.dp, stroke = 2.dp),
                    ) {
                        Text(
                            "+ Add channel",
                            color = if (showFocus) McdColors.AccentBright else McdColors.Accent,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
        }
        if (hintVisible && !blocked) {
            Text(
                if (layout > 1) "◀ ▶ ▲ ▼ move    OK: sound from this tile    Hold OK or ☰ Menu: options    Back: exit"
                else "Hold OK or ☰ Menu: options and layout    Back: exit",
                color = McdColors.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .clip(HudShapeSmall)
                    .background(McdColors.Card.copy(alpha = 0.92f))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

/** Builds a MediaItem, hinting HLS / DASH when the URL makes it obvious (same rule as the main player). */
private fun multiviewMediaItem(url: String): MediaItem {
    val lower = url.lowercase()
    val mime = when {
        ".m3u8" in lower -> MimeTypes.APPLICATION_M3U8
        ".mpd" in lower -> MimeTypes.APPLICATION_MPD
        else -> null
    }
    return MediaItem.Builder().setUri(url).apply { if (mime != null) setMimeType(mime) }.build()
}

/**
 * Video size limits per tile. HLS / DASH streams with several variants then pick a smaller one, which keeps
 * several decoders within what a Fire TV Stick can handle. Single-variant streams play as they are.
 */
private fun tileQuality(base: TrackSelectionParameters, layout: Int, isAudio: Boolean): TrackSelectionParameters {
    val b = base.buildUpon()
    when {
        layout <= 1 -> b.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE).setForceLowestBitrate(false)
        layout == 2 -> {
            if (isAudio) b.setMaxVideoSize(1280, 720) else b.setMaxVideoSize(960, 540)
            b.setForceLowestBitrate(false)
        }
        else -> {
            b.setMaxVideoSize(960, 540)
            b.setForceLowestBitrate(!isAudio)
        }
    }
    return b.build()
}

@OptIn(UnstableApi::class)
@Composable
private fun MultiTile(
    ch: Channel,
    name: String,
    layout: Int,
    isAudio: Boolean,
    isSelected: Boolean,
    showAudioBadge: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION") // androidx.compose.ui.platform.LocalLifecycleOwner: always on the classpath with this BOM
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var failed by remember { mutableStateOf(false) }

    val player = remember {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(M3u.userAgent)
        val dataSource = DefaultDataSource.Factory(context, http)
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            // Lighter buffer per tile: four players on a 1 GB stick (see LiveTuning).
            .setLoadControl(com.mcd.tv.player.LiveTuning.loadControl(tile = true))
            .build()
            .apply {
                volume = 0f // the effect below turns the audio tile up
                trackSelectionParameters = tileQuality(trackSelectionParameters, layout, isAudio)
                setMediaItem(multiviewMediaItem(ch.url))
                prepare()
                playWhenReady = true
            }
    }

    // Audio source and quality follow the tile's role.
    LaunchedEffect(player, isAudio, layout) {
        player.volume = if (isAudio) 1f else 0f
        player.trackSelectionParameters = tileQuality(player.trackSelectionParameters, layout, isAudio)
    }

    DisposableEffect(player) {
        var retried = false
        var retryJob: Job? = null
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                // Tile phase (STATE-MACHINES.md): Playing -> Retrying (one, 2.5 s) -> Playing | Failed. Playing again
                // earns a fresh retry, so a hiccup an hour later is retried too instead of failing at once.
                if (state == Player.STATE_READY) {
                    failed = false
                    retried = false
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                // Fell behind the live window: jump back to the live edge.
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                // One retry (a provider slot may free up a moment later), then show the message in this tile only.
                if (!retried) {
                    retried = true
                    retryJob?.cancel()
                    retryJob = scope.launch {
                        delay(2_500)
                        player.seekToDefaultPosition()
                        player.prepare()
                    }
                    return
                }
                failed = true
            }
        }
        player.addListener(listener)
        onDispose {
            retryJob?.cancel()
            player.removeListener(listener)
            player.release() // frees the hardware decoder and the provider connection
        }
    }

    // Leaving the app: pause every tile. Coming back: jump to the live edge and play again.
    DisposableEffect(lifecycleOwner, player) {
        var pausedByStop = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                pausedByStop = true
                player.pause()
            } else if (event == Lifecycle.Event.ON_START && pausedByStop) {
                pausedByStop = false
                player.seekToDefaultPosition()
                // A tile that failed while away (or before) is reopened: play() alone does nothing on an errored player.
                if (player.playerError != null || player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier
            .background(Color.Black)
            .border(if (isSelected) 3.dp else 1.dp, if (isSelected) McdColors.Accent else McdColors.Line.copy(alpha = 0.6f), RectangleShape),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(if (isSelected) 3.dp else 1.dp),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    keepScreenOn = true
                    // The Compose tile grid owns the remote keys.
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    this.player = player
                }
            },
        )
        if (isSelected) {
            Box(Modifier.fillMaxSize().hudBrackets(true, McdColors.AccentBright, inset = 8.dp, arm = 16.dp, stroke = 2.dp))
        }
        if (showAudioBadge) {
            Text(
                "🔊 AUDIO",
                style = hudLabelStyle(11.sp, McdColors.Ink),
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .background(McdColors.Accent, HudShapeTiny)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        if (layout > 1) {
            Text(
                name,
                color = if (isSelected) McdColors.White else McdColors.White.copy(alpha = 0.75f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.6f), HudShapeTiny)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        if (failed) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(16.dp)
                    .clip(HudShape)
                    .background(McdColors.Card.copy(alpha = 0.95f))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("CAN'T PLAY", style = broadcastStyle(18.sp, McdColors.Amber))
                Spacer(Modifier.height(6.dp))
                Text(TILE_ERROR, color = McdColors.White, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Options for the selected tile: swap channel, full screen, layout. */
@Composable
private fun TileOptions(
    title: String,
    hasChannel: Boolean,
    layout: Int,
    onSwap: () -> Unit,
    onFullScreen: () -> Unit,
    onLayout: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
        HudPanel(Modifier.width(520.dp).focusGroup(), padding = 22.dp) {
            Text("TILE OPTIONS", style = hudLabelStyle(12.sp))
            Text(title, style = broadcastStyle(22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(if (hasChannel) "Swap channel" else "Add channel", onSwap, Modifier.focusRequester(first), primary = true)
                if (hasChannel && layout > 1) ActionButton("Full screen", onFullScreen)
            }
            Text("LAYOUT", style = hudLabelStyle(11.sp), modifier = Modifier.padding(top = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("1", { onLayout(1) }, primary = layout == 1)
                ActionButton("2", { onLayout(2) }, primary = layout == 2)
                ActionButton("4", { onLayout(4) }, primary = layout == 4)
            }
            ActionButton("Close", onClose, Modifier.padding(top = 6.dp))
        }
    }
}

// ============================== Channel chooser ==============================

/** Same layout as Live TV (Games by league, Favorites, My Guide, Recent, sections), picking instead of playing. */
@Composable
private fun ChannelChooser(
    nav: Nav,
    index: LiveIndex,
    slot: Int,
    current: String?,
    onPick: (String) -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(McdColors.Navy).hudBackground().padding(top = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("CHOOSE A CHANNEL FOR TILE ${slot + 1}", style = broadcastStyle(22.sp), modifier = Modifier.weight(1f))
            if (current != null) {
                ActionButton("Remove channel", onRemove)
                Spacer(Modifier.width(10.dp))
            }
            ActionButton("Cancel", onCancel)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LiveBrowser(nav, index, onPick = { ch -> onPick(ch.url) })
        }
    }
}
