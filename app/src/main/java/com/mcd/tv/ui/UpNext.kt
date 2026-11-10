package com.mcd.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mcd.tv.data.Chapters
import com.mcd.tv.data.CreditsChapter
import com.mcd.tv.data.CreditsLearn
import com.mcd.tv.data.NextEpisode
import com.mcd.tv.data.PlayMeta
import com.mcd.tv.data.PlayNextPoint
import com.mcd.tv.data.PlayNextRules
import com.mcd.tv.data.PlayNextSource
import com.mcd.tv.data.PlayNextStore
import com.mcd.tv.data.PlayNextTrigger
import com.mcd.tv.data.SeekChain
import com.mcd.tv.data.StillWatching
import com.mcd.tv.data.Tmdb
import com.mcd.tv.player.PlayerHook
import com.mcd.tv.player.PlayerScreen
import kotlinx.coroutines.delay

private const val UP_NEXT_SECONDS = 10

/**
 * Player for a TV episode that rolls into the next one.
 *
 * Play Next at credits (Nov 2026): a "Play next" card shows while the video plays, at the point
 * [PlayNextRules.decide] picks (a credits chapter in the file, a learned offset for the series, or Settings >
 * Playback > Play Next before the end). It counts down [UP_NEXT_SECONDS] (paused while the video is paused);
 * OK on "Play next" goes now, "Watch credits" or BACK hides it for this episode. No early card on the last
 * episode there is, or when the next episode is not known yet.
 *
 * At the end of the file the player is closed (freeing the decoder) and the "Up next" card counts down 10 s
 * before [onNext] runs. "Play now" (focused) skips the wait; "Cancel" or BACK runs [onCancel].
 * After 3 episodes in a row that started by themselves (no key pressed), the card stops and asks
 * "Still watching?" instead: "Keep watching" (focused) or "Stop".
 *
 * Movies use the same player with [next] = [NextEpisode.None]: with the movie card on (off by default), a credits
 * chapter shows "Credits" with Done (leave) and Keep watching. No countdown.
 */
@Composable
fun UpNextPlayer(
    url: String,
    title: String,
    meta: PlayMeta,
    headers: Map<String, String>,
    onNext: () -> Unit,
    onCancel: () -> Unit,
    onSleep: (() -> Unit)? = null,
    /** "Try next source" when the stream fails (null: just the error). */
    nextSource: com.mcd.tv.player.NextSource? = null,
    /** Quality fallback, passed to the player (see [PlayerScreen]). */
    qualityLabel: String = "",
    onStepDown: ((Long) -> Unit)? = null,
    startPositionMs: Long = -1L,
    startNotice: String? = null,
    /** com.mcd.tv.data.SourceHealth.key of the playing file, so a cut-off file is flagged for the house. */
    sourceKey: String? = null,
    /** The episode after this one (TMDB). [NextEpisode.Unknown] keeps the old end card with no early card. */
    next: NextEpisode.Result = NextEpisode.Unknown,
) {
    var ended by remember { mutableStateOf(false) }
    val currentNext by rememberUpdatedState(next)
    val cancel by rememberUpdatedState(onCancel)
    val goNext by rememberUpdatedState(onNext)
    val episode = meta.type == "tv"
    val hook = remember { PlayerHook() }
    val session = remember { CreditsSession(meta, title, hook) }
    var card by remember { mutableStateOf(false) }
    var choice by remember { mutableIntStateOf(0) } // 0 = Play next (Done), 1 = Watch credits (Keep watching)
    var remaining by remember { mutableIntStateOf(UP_NEXT_SECONDS) }
    var playing by remember { mutableStateOf(true) }
    var gone by remember { mutableStateOf(false) }

    fun dismiss() {
        card = false
        session.dismissed()
    }

    fun playNext(auto: Boolean) {
        if (gone) return
        gone = true
        card = false
        session.leftByCard = true
        if (auto) StillWatching.counter.autoAdvanced() else StillWatching.counter.keyPressed()
        goNext()
    }

    fun act() {
        if (episode) {
            if (choice == 0) playNext(auto = false) else dismiss()
        } else {
            if (choice == 0) {
                gone = true
                card = false
                cancel()
            } else {
                dismiss()
            }
        }
    }

    remember {
        hook.onReady = { d -> session.ready(d) }
        hook.onTick = { pos, d, isPlaying ->
            playing = isPlaying
            when (session.tick(pos, d)) {
                PlayNextTrigger.Event.SHOW -> {
                    if (session.mayShow(currentNext, hook)) { choice = 0; remaining = UP_NEXT_SECONDS; card = true } else session.hold()
                }
                PlayNextTrigger.Event.HIDE -> { card = false }
                null -> {}
            }
        }
        hook.onSeek = { from, to, d ->
            when (session.seek(from, to, d)) {
                PlayNextTrigger.Event.SHOW -> {
                    if (session.mayShow(currentNext, hook)) { choice = 0; remaining = UP_NEXT_SECONDS; card = true } else session.hold()
                }
                PlayNextTrigger.Event.HIDE -> { card = false }
                null -> {}
            }
        }
        hook.onLeave = { pos, d, atEnd -> session.leave(pos, d, atEnd) }
        true
    }

    // The early card's countdown: only for an episode, only while the video plays.
    LaunchedEffect(card) {
        if (!card || !episode) return@LaunchedEffect
        while (card && remaining > 0) {
            delay(1_000)
            if (playing && card) remaining--
        }
        // Re-checked at zero: the sleep timer may have moved to "End of this video", or the player failed.
        if (card && remaining == 0) {
            if (hook.stopAtEnd || hook.failed) { card = false; session.hold() } else playNext(auto = true)
        }
    }

    val keys: (android.view.KeyEvent, Boolean) -> Boolean = keys@{ e, _ ->
        if (!card || hook.failed) return@keys false // no keys for a card that is not on screen
        val up = e.action == android.view.KeyEvent.ACTION_UP
        when (e.keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!up) choice = 1 - choice
                true
            }
            android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN -> true
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (up) act()
                true
            }
            android.view.KeyEvent.KEYCODE_BACK -> {
                if (up) dismiss()
                true
            }
            else -> false
        }
    }

    if (!ended) {
        PlayerScreen(
            url = url, title = title, meta = meta, onEnded = if (episode) ({ card = false; ended = true }) else null, headers = headers, onSleep = onSleep,
            nextSource = nextSource, onBack = onCancel, sourceKey = sourceKey,
            qualityLabel = qualityLabel, onStepDown = onStepDown, startPositionMs = startPositionMs, startNotice = startNotice,
            onKeyEvent = keys, hook = hook,
            overlay = { if (card) PlayNextCard(meta, episode, nextLabel(currentNext, meta), remaining, choice, Modifier.align(Alignment.BottomEnd)) },
        )
    } else if (!episode || next is NextEpisode.None) {
        // A movie, or the last episode there is: no "Up next" for something that does not exist.
        LaunchedEffect(Unit) {
            if (!gone) {
                gone = true
                cancel()
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black))
    } else {
        UpNextCard(meta, nextLabel(next, meta), onNext, onCancel)
    }
}

/** "S1E2" for the next episode; the old guess (same season, next number) when TMDB has not answered. */
private fun nextLabel(next: NextEpisode.Result, meta: PlayMeta): String = when (next) {
    is NextEpisode.Known -> "S${next.season}E${next.episode}"
    else -> "S${meta.season}E${meta.episode + 1}"
}

/** The early card, bottom right over the playing video. Two choices; LEFT / RIGHT move, OK picks. */
@Composable
private fun PlayNextCard(meta: PlayMeta, episode: Boolean, label: String, remaining: Int, choice: Int, modifier: Modifier) {
    Column(
        modifier
            .padding(end = 48.dp, bottom = 56.dp)
            .background(McdColors.Card.copy(alpha = 0.94f), HudShape).border(1.dp, McdColors.Line, HudShape)
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        if (episode) {
            Text("Up next", color = McdColors.Muted, fontSize = 15.sp)
            Text("${meta.name}  $label", style = broadcastStyle(24.sp))
            Text("Plays in $remaining s", color = McdColors.White, fontSize = 15.sp)
        } else {
            Text("Credits", style = broadcastStyle(24.sp))
            Text(meta.name, color = McdColors.Muted, fontSize = 15.sp)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CardChoice(if (episode) "▶  Play next" else "Done", choice == 0)
            CardChoice(if (episode) "Watch credits" else "Keep watching", choice == 1)
        }
    }
}

@Composable
private fun CardChoice(text: String, selected: Boolean) {
    Text(
        text,
        color = if (selected) Color.Black else McdColors.White,
        fontSize = 17.sp,
        modifier = Modifier
            .background(if (selected) McdColors.Cyan else Color.Transparent, HudShape)
            .border(1.dp, if (selected) McdColors.Cyan else McdColors.Line, HudShape)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

@Composable
private fun UpNextCard(meta: PlayMeta, label: String, onNext: () -> Unit, onCancel: () -> Unit) {
    val playFocus = remember { FocusRequester() }
    var remaining by remember { mutableIntStateOf(UP_NEXT_SECONDS) }
    var done by remember { mutableStateOf(false) }
    val next by rememberUpdatedState(onNext)
    val cancel by rememberUpdatedState(onCancel)
    // Read once: the answer stays put while the card is up.
    val ask = remember { StillWatching.counter.shouldAsk() }

    fun go(action: () -> Unit) {
        if (done) return
        done = true
        action()
    }

    BackHandler { go(cancel) }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { playFocus.requestFocus() }
    }
    LaunchedEffect(Unit) {
        if (ask) return@LaunchedEffect // wait for "Keep watching"
        while (remaining > 0) {
            delay(1_000)
            remaining--
        }
        if (!done) StillWatching.counter.autoAdvanced() // started with no key press
        go(next)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AsyncImage(
            Tmdb.img(meta.backdrop, "w1280"), meta.name,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
        Column(
            Modifier
                .align(Alignment.Center)
                .background(McdColors.Card.copy(alpha = 0.92f), HudShape).border(1.dp, McdColors.Line, HudShape)
                .padding(horizontal = 40.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(meta.name, color = McdColors.Muted, fontSize = 16.sp)
            if (ask) {
                Text("Still watching?", style = broadcastStyle(28.sp))
                Text(
                    "Paused after a few episodes in a row. Next: $label.",
                    color = McdColors.White, fontSize = 16.sp,
                )
            } else {
                Text(
                    "Up next: $label in $remaining…",
                    style = broadcastStyle(28.sp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (ask) {
                    ActionButton("Keep watching", { StillWatching.counter.keyPressed(); go(next) }, Modifier.focusRequester(playFocus), primary = true)
                    ActionButton("Stop", { go(cancel) })
                } else {
                    ActionButton("▶  Play now", { go(next) }, Modifier.focusRequester(playFocus), primary = true)
                    ActionButton("Cancel", { go(cancel) })
                }
            }
        }
    }
}

/**
 * One playback's Play Next state: reads the chapters, picks the card's time, learns from J's own skips.
 * Main thread only.
 */
private class CreditsSession(private val meta: PlayMeta, private val title: String, private val hook: PlayerHook) {
    private val episode = meta.type == "tv"
    private val series = PlayNextStore.seriesKey(meta)
    private val chain = SeekChain()
    private var trigger: PlayNextTrigger? = null
    var point = PlayNextPoint.NONE
        private set
    /** The card started the next episode: that exit is not a lesson. */
    var leftByCard = false

    init {
        // The episode before this one was left in its credits a moment ago: that was a skip to this one.
        val p = PlayNextStore.pending
        if (episode && p != null) {
            if (CreditsLearn.commits(p, series, meta.season, meta.episode, System.currentTimeMillis())) {
                PlayNextStore.addSample(series, p.remainingMs)
                android.util.Log.i(TAG, "$series learned ${p.remainingMs} ms before the end")
            }
            PlayNextStore.pending = null
        }
    }

    fun ready(durationMs: Long) {
        val scan = Chapters.scan(hook.header.snapshot())
        val creditsAt = CreditsChapter.start(scan.chapters, durationMs)
        val learned = if (episode) CreditsLearn.learned(PlayNextStore.samples(series)) else null
        point = PlayNextRules.decide(
            durationMs, episode, creditsAt, learned,
            PlayNextStore.creditsOn, PlayNextStore.endSeconds, PlayNextStore.movieCard,
        )
        trigger = PlayNextTrigger(point.atMs, durationMs)
        val credits = creditsAt?.let { at -> scan.chapters.firstOrNull { it.startMs == at } }
        val note = scan.note.ifBlank { if (scan.laterAt >= 0 && scan.chapters.isEmpty()) "chapters later in the file" else "" }
        PlayNextStore.note(
            PlayNextStore.LogEntry(
                System.currentTimeMillis(), meta.label.ifBlank { title }, scan.container, scan.chapters.size,
                credits?.title.orEmpty(), creditsAt ?: -1L, point.source.word, point.atMs, durationMs, note,
            ),
        )
        android.util.Log.i(
            TAG,
            "${meta.label}: ${scan.container} chapters=${scan.chapters.size} [${scan.chapters.joinToString { "${it.startMs}:${it.title.take(24)}" }}] " +
                "credits=${creditsAt ?: -1} learned=${learned ?: -1} card=${point.atMs} source=${point.source.word} duration=$durationMs $note",
        )
    }

    fun tick(posMs: Long, durationMs: Long): PlayNextTrigger.Event? = trigger?.tick(posMs)

    fun seek(fromMs: Long, toMs: Long, durationMs: Long): PlayNextTrigger.Event? {
        val start = chain.from(fromMs, android.os.SystemClock.elapsedRealtime())
        // A jump from the credits to the very end is a skip to the next episode: one sample for this series.
        if (episode && durationMs > 0 && toMs >= durationMs - PlayNextTrigger.END_SLACK_MS &&
            start >= durationMs * CreditsChapter.MIN_FRACTION && CreditsLearn.sampleOk(durationMs - start, durationMs)
        ) {
            PlayNextStore.addSample(series, durationMs - start)
            android.util.Log.i(TAG, "$series learned ${durationMs - start} ms before the end (seek)")
        }
        return trigger?.seek(fromMs, toMs)
    }

    /** No card for a next episode that does not exist, or that TMDB has not confirmed yet; none before "Still watching?". */
    fun mayShow(next: NextEpisode.Result, hook: PlayerHook): Boolean {
        if (hook.failed) return false
        if (!episode) return true
        if (hook.stopAtEnd) return false // sleep timer "End of this video": stop at the end, never roll on early
        return next is NextEpisode.Known && !StillWatching.counter.shouldAsk()
    }

    fun hold() = trigger?.hold()

    fun dismissed() {
        trigger?.dismiss()
        // A learned time J turned down was wrong: learn again from the next skips.
        if (point.source == PlayNextSource.LEARNED) PlayNextStore.forget(series)
    }

    fun leave(posMs: Long, durationMs: Long, ended: Boolean) {
        if (!episode || ended || leftByCard || durationMs <= 0) return
        val remain = durationMs - posMs
        if (posMs >= durationMs * CreditsChapter.MIN_FRACTION && CreditsLearn.sampleOk(remain, durationMs)) {
            PlayNextStore.pending = CreditsLearn.Pending(series, meta.season, meta.episode, remain, System.currentTimeMillis())
        }
    }

    companion object { const val TAG = "PlayNext" }
}
