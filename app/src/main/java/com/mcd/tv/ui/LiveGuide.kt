package com.mcd.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.mcd.tv.data.Channel
import com.mcd.tv.data.LiveFill
import com.mcd.tv.data.LiveForYou
import com.mcd.tv.data.LiveIndex
import com.mcd.tv.data.LiveSubgroup
import com.mcd.tv.data.Programme
import com.mcd.tv.data.Taste

// ============================== My Guide ==============================
//
// An old-school TV guide for the channels picked for this profile (Live TV > My Guide): one row per channel,
// half-hour columns for the next three hours, a line at the current time, and the shows that fit the
// profile lit up (a topic it asked for, like poker or stand-up, glows brightest). The panel on top
// describes the focused show. OK plays the channel; Menu (☰) adds it to Favorites.

private const val SLOT_MS = 30 * 60_000L
private val ChannelCol = 170.dp
private val RowHeight = 54.dp
private val GuideGold = Color(0xFFFFC94D)

/** One block in a channel's row. [p] null = no guide info (or a gap). */
private class GuideBlock(val p: Programme?, val start: Long, val end: Long, val match: Double, val playable: Boolean)

/** [next]: what follows [p] on that channel (shown when [p] is wrapping up). */
private class FocusedShow(val ch: Channel, val name: String, val p: Programme?, val match: Double, val next: Programme? = null)

/** A show starting soon on one of the guide's channels: [pos] is its row in the shown list. */
private class SoonShow(val pos: Int, val name: String, val p: Programme, val match: Double)

private const val SOON_MS = 30 * 60_000L

@Composable
internal fun MyGuidePane(
    index: LiveIndex,
    items: IntArray,
    matcher: Taste.ProgrammeMatcher?,
    guide: Map<String, List<Programme>>,
    now: Long,
    favSet: Set<String>,
    lastPlayed: String?,
    restoreFocus: FocusRequester,
    onPlay: (IntArray, Int) -> Unit,
    onMenu: (Channel) -> Unit,
    onFocusChannel: (Channel?, String) -> Unit,
    groups: List<LiveSubgroup> = emptyList(),
) {
    // Window: from the current half hour, three hours ahead (the guide keeps 8 hours).
    val start = now / SLOT_MS * SLOT_MS
    val end = start + LiveForYou.WINDOW_MS
    var onlyMine by rememberSaveable { mutableStateOf(false) }
    // One group of the lineup (Huntsville Locals, Sports, Crime, Movies, More to Watch), or "" for all of them.
    var group by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf<FocusedShow?>(null) }

    // Listings per channel: the real guide, else the event title or the network's game (schedules already
    // loaded, no network). Refreshed every 5 minutes so a game going live shows up.
    val tick = now / 300_000L
    val listings = remember(items, guide, tick) {
        val games = LiveFill.loadedGames()
        items.associateWith { i -> LiveFill.programmes(index.channels[i], guide, now) { games } }
    }
    // Blocks per channel, scored once per guide / half hour.
    val rows = remember(listings, start, matcher) {
        items.map { i -> i to blocksFor(listings[i].orEmpty(), start, end, matcher) }
    }
    val inGroup = remember(groups, group) { groups.firstOrNull { it.label == group }?.items?.toHashSet() }
    val shown = remember(rows, onlyMine, inGroup) {
        val base = if (inGroup == null) rows else rows.filter { (i, _) -> i in inGroup }
        if (!onlyMine) base else base.filter { (_, b) -> b.any { it.match >= 0.5 } }
    }
    val shownItems = remember(shown) { shown.map { it.first }.toIntArray() }
    // Starting in the next 30 minutes on these channels: shows that fit your taste first, then by start time.
    val soon = remember(shown, listings, now / 60_000) {
        shown.mapIndexedNotNull { pos, (i, _) ->
            val p = listings[i].orEmpty().firstOrNull { it.start > now && it.start - now <= SOON_MS }
                ?: return@mapIndexedNotNull null
            SoonShow(pos, index.names[i], p, matcher?.score(p.title + " " + p.desc) ?: 0.0)
        }.sortedWith(compareByDescending<SoonShow> { if (it.match >= 0.5) 1 else 0 }.thenBy { it.p.start }).take(16)
    }

    Column(Modifier.fillMaxSize().padding(end = 24.dp)) {
        GuidePreview(focused, now)
        val mine = rows.count { (_, b) -> b.any { it.match >= 0.5 } }
        LazyRow(Modifier.padding(top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            item { ActionButton("All my channels  ${rows.size}", { onlyMine = false; group = "" }, primary = !onlyMine && group.isEmpty()) }
            items(count = groups.size) { k ->
                val g = groups[k]
                ActionButton("${g.label}  ${g.items.size}", { onlyMine = false; group = if (group == g.label) "" else g.label }, primary = group == g.label && !onlyMine)
            }
            item { ActionButton("★ Shows for me  $mine", { onlyMine = true; group = "" }, primary = onlyMine) }
        }
        if (soon.isNotEmpty()) {
            Text("STARTING SOON", style = hudLabelStyle(11.sp, McdColors.Accent), modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                items(count = soon.size) { k ->
                    val x = soon[k]
                    val mins = ((x.p.start - now + 59_999) / 60_000).coerceAtLeast(1)
                    ActionButton(
                        "${if (x.match >= 0.5) "★ " else ""}${guideTime(x.p.start)}  ${x.p.title}  ·  ${x.name}  (in $mins min)",
                        { onPlay(shownItems, x.pos) },
                    )
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val gridW = (maxWidth - ChannelCol).coerceAtLeast(200.dp)
            val perMin = gridW / ((end - start) / 60_000f)
            Column(Modifier.fillMaxSize()) {
                TimeHeader(start, end, perMin)
                if (shown.isEmpty()) {
                    StatusText("Nothing for you in the next three hours. Pick \"All my channels\".")
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(count = shown.size, key = { pos -> index.channels[shown[pos].first].url }) { pos ->
                            val (i, blocks) = shown[pos]
                            val ch = index.channels[i]
                            val name = index.names[i]
                            GuideRow(
                                ch = ch,
                                name = name,
                                favorite = ch.url in favSet,
                                blocks = blocks,
                                start = start,
                                now = now,
                                perMin = perMin,
                                restoreFocus = if (ch.url == lastPlayed) restoreFocus else null,
                                onPlay = { onPlay(shownItems, pos) },
                                onMenu = { onMenu(ch) },
                                onFocus = { b ->
                                    if (b != null) {
                                        val next = b.p?.let { cur -> listings[i].orEmpty().firstOrNull { it.start >= cur.end - 60_000L && it.start > cur.start } }
                                        focused = FocusedShow(ch, name, b.p, b.match, next)
                                        onFocusChannel(ch, name)
                                    } else {
                                        onFocusChannel(null, "")
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun blocksFor(programmes: List<Programme>, start: Long, end: Long, matcher: Taste.ProgrammeMatcher?): List<GuideBlock> {
    val list = programmes.filter { it.end > start && it.start < end }
    if (list.isEmpty()) return listOf(GuideBlock(null, start, end, 0.0, playable = true))
    val out = ArrayList<GuideBlock>()
    var t = start
    for (p in list) {
        val s = maxOf(p.start, start)
        val e = minOf(p.end, end)
        if (e <= t) continue
        if (s > t) out.add(GuideBlock(null, t, s, 0.0, playable = false))
        val m = matcher?.score(p.title + " " + p.desc) ?: 0.0
        out.add(GuideBlock(p, maxOf(s, t), e, m, playable = true))
        t = e
    }
    if (t < end) out.add(GuideBlock(null, t, end, 0.0, playable = false))
    return out
}

/** "8:00 PM   8:30 PM   9:00 PM ..." over the grid. */
@Composable
private fun TimeHeader(start: Long, end: Long, perMin: Dp) {
    Row(Modifier.fillMaxWidth().height(22.dp)) {
        Spacer(Modifier.width(ChannelCol))
        var t = start
        while (t < end) {
            Text(
                guideTime(t),
                color = McdColors.Muted, fontSize = 12.sp, maxLines = 1,
                modifier = Modifier.width(perMin * 30f).padding(start = 4.dp),
            )
            t += SLOT_MS
        }
    }
}

@Composable
private fun GuideRow(
    ch: Channel,
    name: String,
    favorite: Boolean,
    blocks: List<GuideBlock>,
    start: Long,
    now: Long,
    perMin: Dp,
    restoreFocus: FocusRequester?,
    onPlay: () -> Unit,
    onMenu: () -> Unit,
    onFocus: (GuideBlock?) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(RowHeight), verticalAlignment = Alignment.CenterVertically) {
        // Channel cell.
        Row(
            Modifier.width(ChannelCol).fillMaxHeight().padding(end = 4.dp).clip(HudShapeSmall)
                .background(McdColors.Card).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name, color = McdColors.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (favorite) Text("★", color = GuideGold, fontSize = 13.sp)
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxSize()) {
                var restoreUsed = false
                blocks.forEach { b ->
                    val w = perMin * ((b.end - b.start) / 60_000f)
                    if (!b.playable) {
                        Spacer(Modifier.width(w))
                    } else {
                        val takeRestore = restoreFocus != null && !restoreUsed && (b.p == null || (b.start <= now && b.end > now))
                        if (takeRestore) restoreUsed = true
                        GuideCell(b, w, now, if (takeRestore) restoreFocus else null, onPlay, onMenu, onFocus)
                    }
                }
            }
            // The current time.
            val x = perMin * ((now - start) / 60_000f)
            Box(Modifier.offset(x = x).width(2.dp).fillMaxHeight().background(McdColors.AccentBright.copy(alpha = 0.8f)))
        }
    }
}

@Composable
private fun GuideCell(
    b: GuideBlock,
    width: Dp,
    now: Long,
    restoreFocus: FocusRequester?,
    onPlay: () -> Unit,
    onMenu: () -> Unit,
    onFocus: (GuideBlock?) -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val mine = b.match >= 0.5
    val topic = b.match >= 0.95
    val skip = b.match < 0
    val airing = b.p != null && b.start <= now && b.end > now
    val bg = when {
        isFocused -> McdColors.Accent.copy(alpha = 0.30f)
        topic -> GuideGold.copy(alpha = 0.22f)
        mine -> McdColors.Accent.copy(alpha = 0.14f)
        airing -> McdColors.Raised.copy(alpha = 0.85f)
        else -> McdColors.Card
    }
    val edge = when {
        isFocused -> McdColors.AccentBright
        topic -> GuideGold
        mine -> McdColors.Accent.copy(alpha = 0.7f)
        else -> McdColors.Line.copy(alpha = 0.5f)
    }
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .padding(end = 3.dp)
            .then(if (restoreFocus != null) Modifier.focusRequester(restoreFocus) else Modifier)
            .onFocusChanged {
                val was = isFocused
                isFocused = it.isFocused
                if (it.isFocused) onFocus(b) else if (was) onFocus(null)
            }
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Menu) {
                    if (e.type == KeyEventType.KeyUp) onMenu()
                    true
                } else {
                    false
                }
            }
            .clip(HudShapeTiny)
            .background(bg)
            .border(if (isFocused) 2.dp else 1.dp, edge, HudShapeTiny)
            .clickable(onClick = onPlay)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (width >= 36.dp) {
            Column {
                Text(
                    (if (mine) "★ " else "") + (b.p?.title ?: "No guide info"),
                    color = when {
                        skip -> McdColors.Muted.copy(alpha = 0.6f)
                        topic -> GuideGold
                        else -> McdColors.White
                    },
                    fontSize = 13.sp,
                    fontWeight = if (mine || airing) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (b.p != null && width >= 90.dp) {
                    Text(
                        "${guideTime(b.p.start)} to ${guideTime(b.p.end)}",
                        color = McdColors.Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Top panel: the focused show, like the preview box on a cable guide. */
@Composable
private fun GuidePreview(f: FocusedShow?, now: Long) {
    Column(
        Modifier.fillMaxWidth().height(78.dp).clip(HudShapeSmall).background(McdColors.Card)
            .border(1.dp, McdColors.Line.copy(alpha = 0.6f), HudShapeSmall).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        if (f == null) {
            Text("MY GUIDE", style = broadcastStyle(16.sp))
            Text(
                "Your channels and what's on for the next three hours. ★ marks shows that fit your taste; gold is a topic you asked for.",
                color = McdColors.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        } else {
            val p = f.p
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p?.title ?: "No guide info",
                    color = if (f.match >= 0.95) GuideGold else McdColors.White,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(10.dp))
                val whenText = when {
                    p == null -> f.name
                    p.start <= now -> "${f.name}  ·  on now, until ${guideTime(p.end)}"
                    else -> "${f.name}  ·  ${guideTime(p.start)} to ${guideTime(p.end)}"
                }
                Text(whenText, color = McdColors.Accent, fontSize = 12.sp, maxLines = 1)
                if (f.match >= 0.5) {
                    Spacer(Modifier.width(10.dp))
                    Text(if (f.match >= 0.95) "★ YOUR TOPIC" else "★ FOR YOU", color = GuideGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            val nx = f.next
            if (p != null && nx != null && p.start <= now && p.end - now <= SOON_MS) {
                // Wrapping up: what comes on next.
                val left = ((p.end - now + 59_999) / 60_000).coerceAtLeast(1)
                Text(
                    "Ends in $left min.  UP NEXT at ${guideTime(nx.start)}: ${nx.title}",
                    color = McdColors.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(nx.desc.ifBlank { "OK plays this channel." }, color = McdColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(
                    p?.desc?.ifBlank { null } ?: "OK plays this channel.",
                    color = McdColors.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
