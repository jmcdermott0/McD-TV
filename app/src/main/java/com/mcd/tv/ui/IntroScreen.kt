package com.mcd.tv.ui

import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.mcd.tv.R
import kotlinx.coroutines.delay

/*
 * Jarvis intro: a pre-rendered 5.6 s HUD boot sequence (res/raw/intro_video.mp4) with an
 * original synth sting and the "All systems online" voiceover and the full JARVIS name. Rendered offline so it can use
 * bloom and glow effects that would be too heavy to draw live on a Fire TV Stick.
 * Any remote button skips straight to the home screen. If the video cannot play, the
 * intro ends at once instead of showing a black screen.
 *
 * Holiday mode (Holiday.kt, Nov 1 to Jan 6) keeps the same video and flow and lays a seasonal skin on top:
 * a sleigh-bell jingle (res/raw/holiday_jingle.ogg, built by tools/holiday/make_jingle.py to the video's
 * timing) that starts with the video's first frame, light snowfall, and "Happy Holidays" at the second hit.
 * Holiday mode off: nothing here changes, the intro is exactly the original.
 */
private const val INTRO_MAX_MS = 8_000L // safety net: never hold the user here longer than this

@OptIn(UnstableApi::class)
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val done by rememberUpdatedState(onDone)
    var finished by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val finish: () -> Unit = {
        if (!finished) {
            finished = true
            done()
        }
    }

    val holiday = remember { com.mcd.tv.data.HolidayHub.active() }
    // Holiday mode: the jingle waits, prepared, and starts when the video starts, so the two stay in step.
    val jingle = remember {
        if (!holiday) null else runCatching {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(R.raw.holiday_jingle)))
                volume = HOLIDAY_JINGLE_VOLUME
                playWhenReady = false
                prepare()
            }
        }.getOrNull()
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(R.raw.intro_video)))
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) finish()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && jingle != null && !jingle.playWhenReady) jingle.play()
            }

            override fun onPlayerError(error: PlaybackException) {
                finish()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            jingle?.release()
        }
    }

    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        delay(INTRO_MAX_MS)
        finish()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyUp) finish()
                true
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    isFocusable = false
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (holiday) HolidaySkin(videoMs = { player.currentPosition })
    }
}

private const val HOLIDAY_JINGLE_VOLUME = 1.0f
private const val HOLIDAY_GREETING_AT_MS = 4_400L // the video's second hit
private const val SNOWFLAKES = 70

/** One snowflake: where it starts (0..1 of the screen), its size, speed and sway. */
private class Flake(val x: Float, val y: Float, val r: Float, val speed: Float, val sway: Float, val phase: Float)

/**
 * Holiday mode's skin over the intro video: light snow (a few dozen dots on one Canvas, cheap enough for a
 * Fire TV Stick) and "HAPPY HOLIDAYS" fading in under the logo at the video's second hit. Draws only; takes no keys.
 */
@Composable
private fun HolidaySkin(videoMs: () -> Long) {
    val flakes = remember {
        val rnd = java.util.Random(1225)
        List(SNOWFLAKES) {
            Flake(rnd.nextFloat(), rnd.nextFloat(), 1.5f + rnd.nextFloat() * 3f, 0.06f + rnd.nextFloat() * 0.10f, 0.01f + rnd.nextFloat() * 0.02f, rnd.nextFloat() * 6.28f)
        }
    }
    var seconds by remember { mutableFloatStateOf(0f) }
    var greeting by remember { mutableStateOf(false) }
    // Snow falls on the frame clock; the greeting follows the video's own position, so it lands on the hit.
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            seconds = (now - start) / 1_000_000_000f
            if (!greeting && videoMs() >= HOLIDAY_GREETING_AT_MS) greeting = true
        }
    }
    val greetAlpha by animateFloatAsState(if (greeting) 1f else 0f, tween(600), label = "greeting")
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val t = seconds
            for (f in flakes) {
                val y = ((f.y + f.speed * t) % 1f) * size.height
                val x = (f.x + f.sway * kotlin.math.sin(f.phase + t * 1.6f)) * size.width
                drawCircle(Color.White.copy(alpha = 0.55f + f.r / 10f), radius = f.r * density / 1.5f, center = Offset(x, y))
            }
        }
        Text(
            "HAPPY HOLIDAYS",
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).graphicsLayer { alpha = greetAlpha },
            style = broadcastStyle(26.sp, HolidayRed),
        )
    }
}

private val HolidayRed = Color(0xFFE53935)
