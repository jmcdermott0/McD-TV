package com.mcd.tv.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mcd.tv.Nav
import com.mcd.tv.data.Channel
import com.mcd.tv.data.Epg
import com.mcd.tv.data.GuideExtra
import com.mcd.tv.data.LiveFill
import com.mcd.tv.data.LiveOrganizer
import com.mcd.tv.data.LiveStats
import com.mcd.tv.data.M3u
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.Programme
import com.mcd.tv.data.QualityFallback
import com.mcd.tv.data.QualityLog
import com.mcd.tv.data.QualityMemory
import com.mcd.tv.data.QualityMode
import com.mcd.tv.player.PlayerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * The channel list the Live TV page last opened a channel from, plus the program guide.
 * Kept in memory so Screen.LivePlay only carries an index.
 */
object LiveSession {
    /** Setting a new list clears [feedsOf]; set that after it. */
    var channels: List<Channel> = emptyList()
        set(v) { field = v; feedsOf = null }
    var guide by mutableStateOf<Map<String, List<Programme>>>(emptyMap())

    /** When [channels] are the feeds of one network or game (What's On, All Channels A-Z): its name, else null. */
    var feedsOf: String? = null
}

/**
 * The 1080p version of [ch] (same guide key, see [QualityFallback.pickSibling]) in the whole playlist, else in the
 * list the channel was opened from. Runs off the main thread; never downloads the playlist.
 */
private suspend fun sibling1080(ch: Channel): Channel? = withContext(Dispatchers.Default) {
    val session = LiveSession.channels
    val all = M3u.cached
    (if (all.isNotEmpty()) QualityFallback.pickSibling(ch, all) else null)
        ?: if (all !== session) QualityFallback.pickSibling(ch, session) else null
}

/**
 * Quality Auto, before a 4K channel opens: 4K or its 1080p sibling (see [QualityFallback.startTier]), from the
 * last throughput this TV measured, the channel's measured bitrate and its last step-down. A Live TV health
 * session of this channel that froze in 4K counts as a step-down too. Off the main thread.
 */
private suspend fun upFrontPick(key: String): QualityFallback.StartPick = withContext(Dispatchers.Default) {
    val now = System.currentTimeMillis()
    val (est, estAt, mem) = QualityMemory.read(key)
    var down = mem.down
    for (s in LiveStats.recent()) {
        if (s.at <= mem.clr || s.at <= down) continue
        if (QualityFallback.frozeIn4k(now, s.at, s.video, s.stalls, s.stallMs) && LiveFill.guideKey(s.channel) == key) down = s.at
    }
    QualityFallback.startTier(now, est, estAt, mem.br, down)
}

private fun logLive(title: String, from: String, to: String, code: String) {
    QualityLog.add(QualityLog.Entry(System.currentTimeMillis(), LiveOrganizer.cleanName(title), from, to, code, QualityMemory.estimate()))
}

private val guideTimeFormat: DateFormat by lazy { DateFormat.getTimeInstance(DateFormat.SHORT) }

/** "8:00 PM" (device locale). Main thread only. */
internal fun guideTime(ms: Long): String = guideTimeFormat.format(Date(ms))

/** 0..1 through a programme, for the progress bar. */
internal fun programmeProgress(p: Programme, now: Long): Float {
    val len = (p.end - p.start).coerceAtLeast(1L)
    return ((now - p.start).toFloat() / len).coerceIn(0f, 1f)
}

/**
 * Plays channel [index] of [LiveSession.channels]. Channel up / down:
 *  - CHANNEL_UP / PAGE_UP, or D-pad UP while the control bar is hidden: previous channel.
 *  - CHANNEL_DOWN / PAGE_DOWN, or D-pad DOWN while the control bar is hidden: next channel. Wraps around.
 *  - MENU: add / remove the channel shown in the overlay from Favorites.
 * Quick presses only move the overlay; the stream switches once you stop for a moment.
 */
@Composable
fun LivePlayerScreen(nav: Nav, index: Int) {
    val channels = remember { LiveSession.channels }
    val feedsOf = remember { LiveSession.feedsOf }
    if (channels.isEmpty()) {
        // Channel list is gone (should not happen): go back to the Live TV page.
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val count = channels.size
    // target = the channel the overlay shows; playing = the stream that is open.
    var target by remember { mutableIntStateOf(index.coerceIn(0, count - 1)) }
    var playing by remember { mutableIntStateOf(target) }
    var overlayTick by remember { mutableIntStateOf(0) }
    var overlayVisible by remember { mutableStateOf(true) }
    var favorites by remember { mutableStateOf(Prefs.liveFavorites.toSet()) }

    // Guide: normally loaded by the channel list already (shared, cached); load it here too if not.
    LaunchedEffect(Unit) {
        if (LiveSession.guide.isEmpty()) {
            val url = M3u.guideUrl
            if (url.isNotBlank()) {
                val all = runCatching { M3u.load() }.getOrDefault(emptyList())
                val g = Epg.load(url, all)
                if (g.isNotEmpty()) LiveSession.guide = GuideExtra.withCached(g, all)
                val x = GuideExtra.load(all)
                if (x.isNotEmpty()) LiveSession.guide = GuideExtra.merge(LiveSession.guide, x)
            }
        }
    }

    // Channel overlay: shown for 4 s after opening, each channel change and each favorite toggle.
    LaunchedEffect(overlayTick) {
        overlayVisible = true
        delay(4_000)
        overlayVisible = false
    }

    // Switch the stream once channel presses settle (fast zapping does not open every stream on the way).
    LaunchedEffect(target) {
        if (target != playing) {
            delay(450)
            playing = target
        }
    }

    val playingChannel = channels[playing.coerceIn(0, count - 1)]
    // Quality (Settings > Playback): the stream actually opened can be the channel's 1080p sibling.
    // swap = (channel url, sibling); swapNote = what to tell the viewer about the switch (null = nothing).
    val qualityMode = remember { Prefs.qualityMode }
    val scope = rememberCoroutineScope()
    var swap by remember { mutableStateOf<Pair<String, Channel>?>(null) }
    var swapNote by remember { mutableStateOf<String?>(null) }
    // A 4K channel's guide key: where QualityMemory keeps what this TV learned about it.
    val is4kChannel = QualityFallback.is4kName(playingChannel.name)
    val qualityKey = remember(playingChannel.url) { if (is4kChannel) LiveFill.guideKey(playingChannel.name) else "" }
    // Looked up before the channel opens. Max 1080p: always the 1080p sibling when the playlist has one.
    // Auto: the up-front pick (upFrontPick) can open the sibling straight away.
    val preSwap = is4kChannel && (qualityMode == QualityMode.MAX_1080 || (qualityMode == QualityMode.AUTO && qualityKey.isNotBlank()))
    var preSwapDone by remember(playingChannel.url) { mutableStateOf(!preSwap) }
    LaunchedEffect(playingChannel.url) {
        if (preSwap && swap?.first != playingChannel.url) {
            val ch = playingChannel
            if (qualityMode == QualityMode.MAX_1080) {
                val sib = sibling1080(ch)
                if (sib != null) {
                    swap = ch.url to sib
                    swapNote = null
                    logLive(ch.name, "4K channel", "1080p channel", "max_1080")
                }
            } else {
                val pick = upFrontPick(qualityKey)
                if (pick.clear) {
                    QualityMemory.noteChannel(qualityKey, clearedAt = System.currentTimeMillis())
                    QualityMemory.save()
                    logLive(ch.name, "1080p channel", "4K channel", pick.why)
                }
                val sib = if (pick.tier == "1080") sibling1080(ch) else null
                if (sib != null) {
                    swap = ch.url to sib
                    swapNote = if (pick.why == "slow_network") QualityFallback.NOTICE_SLOW else QualityFallback.NOTICE_FROZE
                    logLive(ch.name, "4K channel", "1080p channel", pick.why)
                }
            }
        }
        preSwapDone = true
    }
    val streamChannel = swap?.takeIf { it.first == playingChannel.url }?.second ?: playingChannel
    // The notice shows once, in the player that opened the sibling (it keeps its own copy).
    LaunchedEffect(streamChannel.url) { swapNote = null }
    LaunchedEffect(playingChannel.url) { Prefs.addLiveRecent(playingChannel.url) }
    // Minutes watched per channel feed Live TV > For You (counted while this screen is open).
    LaunchedEffect(playingChannel.url) {
        val url = playingChannel.url
        while (true) {
            delay(60_000)
            Prefs.addLiveMinutes(url, 1.0)
        }
    }

    val onKey: (KeyEvent, Boolean) -> Boolean = remember(count) {
        { e: KeyEvent, controlsShowing: Boolean ->
            val step = when (e.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> -1
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> 1
                KeyEvent.KEYCODE_DPAD_UP -> if (controlsShowing) 0 else -1
                KeyEvent.KEYCODE_DPAD_DOWN -> if (controlsShowing) 0 else 1
                else -> 0
            }
            when {
                step != 0 -> {
                    // Consume both down and up so the player does not also pop its control bar.
                    if (e.action == KeyEvent.ACTION_DOWN) {
                        target = (target + step).mod(count)
                        overlayTick++
                    }
                    true
                }
                e.keyCode == KeyEvent.KEYCODE_MENU -> {
                    if (e.action == KeyEvent.ACTION_UP) {
                        Prefs.toggleLiveFavorite(channels[target].url)
                        favorites = Prefs.liveFavorites.toSet()
                        overlayTick++
                    }
                    true
                }
                else -> false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (preSwapDone) key(streamChannel.url) {
            val forChannel = playingChannel
            val swapped = streamChannel.url != forChannel.url
            // Quality Auto: 4K keeps stalling and the stream has no lower track, so open the 1080p sibling.
            // Only from the channel itself, so a sibling never switches again.
            val findSibling: (Long) -> Unit = { _ ->
                scope.launch {
                    val sib = sibling1080(forChannel)
                    if (sib == null) {
                        logLive(forChannel.name, "4K channel", "4K channel", "no_lower")
                    } else if (channels.getOrNull(playing) == forChannel) { // still on that channel
                        swap = forChannel.url to sib
                        swapNote = QualityFallback.NOTICE
                    }
                }
            }
            PlayerScreen(
                url = streamChannel.url,
                title = LiveOrganizer.cleanName(streamChannel.name),
                headers = mapOf("User-Agent" to M3u.userAgent),
                onKeyEvent = onKey,
                autoShowControls = false,
                sleepTimerEnabled = false,
                live = true,
                qualityLabel = streamChannel.name,
                onStepDown = if (swapped) null else findSibling,
                startNotice = if (swapped) swapNote else null,
                qualityKey = qualityKey,
            )
        }
        if (overlayVisible) {
            val ch = channels[target.coerceIn(0, count - 1)]
            ChannelOverlay(
                number = target + 1,
                total = count,
                ch = ch,
                favorite = ch.url in favorites,
                guide = LiveSession.guide,
                feedsOf = feedsOf,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 40.dp),
            )
        }
    }
}

@Composable
private fun ChannelOverlay(
    number: Int,
    total: Int,
    ch: Channel,
    favorite: Boolean,
    guide: Map<String, List<Programme>>,
    modifier: Modifier = Modifier,
    feedsOf: String? = null,
) {
    val now = System.currentTimeMillis()
    val (cur, next) = remember(guide, ch.url, now / 60_000L) { LiveFill.nowNext(ch, guide, now) }
    // One network's feeds: the feed's own name ("FOX 29 WTXF Philadelphia HD") tells them apart.
    val name = remember(ch.name, feedsOf) { if (feedsOf != null) LiveOrganizer.feedLabel(ch.name) else LiveOrganizer.cleanName(ch.name) }
    val group = remember(ch.group) { LiveOrganizer.cleanGroup(ch.group) }
    Column(
        modifier
            .width(540.dp)
            .clip(HudShape)
            .background(McdColors.Card.copy(alpha = 0.93f))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$number", style = broadcastStyle(30.sp, McdColors.Red))
            Spacer(Modifier.width(14.dp))
            AsyncImage(ch.logo, name, contentScale = ContentScale.Fit, modifier = Modifier.size(width = 84.dp, height = 52.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = broadcastStyle(22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (feedsOf != null) "$feedsOf  •  feed $number of $total" else "$group  •  $number of $total",
                    color = McdColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (cur != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                if (cur.untimed) "Now  Live event  ${cur.title}" else "Now  ${guideTime(cur.start)} – ${guideTime(cur.end)}  ${cur.title}",
                color = McdColors.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(McdColors.White.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth(programmeProgress(cur, now)).height(3.dp).background(McdColors.Red))
            }
            if (cur.desc.isNotBlank()) {
                Text(cur.desc, color = McdColors.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (next != null) {
            Text(
                "Next  ${guideTime(next.start)}  ${next.title}",
                color = McdColors.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            (if (favorite) "★ Favorite" else "☆ Favorite") + "  (Menu ☰ to change)    ▲ ▼ " + if (feedsOf != null) "other feeds" else "change channel",
            color = if (favorite) McdColors.RedBright else McdColors.Muted,
            fontSize = 12.sp,
        )
    }
}
