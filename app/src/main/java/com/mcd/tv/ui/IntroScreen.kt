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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
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
import com.mcd.tv.data.HolidayIntro
import com.mcd.tv.data.IntroSound
import com.mcd.tv.data.SnowField
import kotlinx.coroutines.delay

/*
 * Jarvis intro: a pre-rendered 5.6 s HUD boot sequence (res/raw/intro_video.mp4) with an
 * original synth sting and the "All systems online" voiceover and the full JARVIS name. Rendered offline so it can use
 * bloom and glow effects that would be too heavy to draw live on a Fire TV Stick.
 * Any remote button skips straight to the home screen. If the video cannot play, the
 * intro ends at once instead of showing a black screen.
 *
 * Holiday mode (Holiday.kt, Nov 1 to Jan 6) keeps the same video and flow, and REPLACES its sound: the video
 * plays muted and the holiday track (res/raw/holiday_intro.ogg, built by tools/holiday/make_holiday_intro.py to the
 * video's beats) is the only sound. Over the picture: two layers of soft snow and "Happy Holidays" at the second
 * flash. If the track cannot play, the video's own sound comes back, so the intro is never silent.
 * Holiday mode off: nothing here changes, the intro is exactly the original (its sound, no snow).
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
    // Holiday mode: the holiday track waits, prepared, and starts when the video starts, so the two stay in step.
    val track = remember {
        if (!holiday) null else runCatching {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(R.raw.holiday_intro)))
                playWhenReady = false
                prepare()
            }
        }.getOrNull()
    }
    // The one sound the intro plays (HolidayIntro): the original, or the holiday track with the video muted.
    var sound by remember { mutableStateOf(HolidayIntro.start(holiday, track != null)) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(R.raw.intro_video)))
            volume = HolidayIntro.videoVolume(sound)
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
                if (isPlaying && track != null && sound == IntroSound.HOLIDAY_TRACK && !track.playWhenReady) {
                    val at = player.currentPosition
                    if (at > TRACK_SYNC_SLACK_MS) track.seekTo(at)
                    track.play()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                finish()
            }
        }
        val trackListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                sound = HolidayIntro.afterTrackError()
                player.volume = HolidayIntro.videoVolume(sound)
                android.util.Log.w(INTRO_LOG_TAG, "holiday track failed (${error.errorCodeName}): sound=$sound")
            }
        }
        player.addListener(listener)
        track?.addListener(trackListener)
        android.util.Log.i(INTRO_LOG_TAG, "holiday=$holiday sound=$sound")
        onDispose {
            player.removeListener(listener)
            track?.removeListener(trackListener)
            player.release()
            track?.release()
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

/** The holiday track may start this far behind the video before it is moved to the video's position. */
private const val TRACK_SYNC_SLACK_MS = 40L
private const val SNOW_LOG_TAG = "HolidaySnow"
private const val INTRO_LOG_TAG = "HolidayIntro"

/**
 * Holiday mode's skin over the intro video: two layers of soft snow (SnowField) and "HAPPY HOLIDAYS" fading in
 * under the logo at the video's second flash. Draws only; takes no keys.
 *
 * The draw is cheap enough for a Fire TV Stick: about a hundred flakes, each one small pre-drawn sprite
 * (a white disc with a radial falloff, made once) stamped with a translate and a scale. No shaders or paths
 * per frame, no allocations per flake. The frame times are logged when the intro closes (tag HolidaySnow).
 */
@Composable
private fun HolidaySkin(videoMs: () -> Long) {
    val snow = remember { SnowField() }
    val softSprite = remember { flakeSprite(core = 0.0f) }
    val crispSprite = remember { flakeSprite(core = 0.45f) }
    var seconds by remember { mutableFloatStateOf(0f) }
    var fade by remember { mutableFloatStateOf(0f) }
    var greeting by remember { mutableStateOf(false) }
    val frameMs = remember { FrameTimes() }
    // Snow falls on the frame clock; the fades and the greeting follow the video's own position.
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        var last = start
        while (true) {
            val now = withFrameNanos { it }
            frameMs.add((now - last) / 1_000_000f)
            last = now
            seconds = (now - start) / 1_000_000_000f
            val at = videoMs()
            fade = HolidayIntro.snowAlpha(at)
            if (!greeting && at >= HolidayIntro.HIT2_MS) greeting = true
        }
    }
    DisposableEffect(Unit) { onDispose { frameMs.log(snow.size) } }
    val greetAlpha by animateFloatAsState(if (greeting) 1f else 0f, tween(600), label = "greeting")
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val t = seconds
            val all = fade
            if (all <= 0f) return@Canvas
            val w = size.width
            val h = size.height
            // Far layer first, near layer on top.
            for (pass in 0..1) {
                for (i in 0 until snow.size) {
                    if (snow.layer[i] != pass) continue
                    val sprite = if (snow.layers[pass].soft) softSprite else crispSprite
                    val r = snow.radius[i] * density
                    val s = 2f * r / FLAKE_SPRITE_PX
                    val x = snow.x(i, t) * w - r
                    val y = snow.y(i, t) * h - r
                    withTransform({
                        translate(x, y)
                        scale(s, s, pivot = Offset.Zero)
                    }) {
                        drawImage(sprite, alpha = snow.alpha[i] * all)
                    }
                }
            }
        }
        Text(
            "HAPPY HOLIDAYS",
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).graphicsLayer { alpha = greetAlpha },
            style = broadcastStyle(26.sp, HolidayRed),
        )
    }
}

private const val FLAKE_SPRITE_PX = 32

/**
 * One snowflake sprite: a white disc that is solid out to [core] of its radius and then falls off smoothly to
 * nothing at the edge (0 = all falloff: a soft, out-of-focus far flake). Drawn once, then only stamped.
 */
private fun flakeSprite(core: Float): ImageBitmap {
    val px = FLAKE_SPRITE_PX
    val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val half = px / 2f
    val stops = if (core > 0f) floatArrayOf(0f, core, 0.75f, 1f) else floatArrayOf(0f, 0.35f, 0.7f, 1f)
    val colors = if (core > 0f) {
        intArrayOf(0xFFFFFFFF.toInt(), 0xF2FFFFFF.toInt(), 0x55FFFFFF, 0x00FFFFFF)
    } else {
        intArrayOf(0xE6FFFFFF.toInt(), 0x99FFFFFF.toInt(), 0x33FFFFFF, 0x00FFFFFF)
    }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.RadialGradient(half, half, half, colors, stops, android.graphics.Shader.TileMode.CLAMP)
    }
    c.drawCircle(half, half, half, paint)
    return bmp.asImageBitmap()
}

/** The snow's frame times during one intro, logged once when it closes (for the performance budget). */
private class FrameTimes {
    private val ms = FloatArray(600)
    private var n = 0

    fun add(v: Float) {
        if (v > 0f && n < ms.size) ms[n++] = v
    }

    fun log(flakes: Int) {
        if (n < 2) return
        val sorted = ms.copyOf(n).also { it.sort() }
        val avg = sorted.average()
        val p95 = sorted[((n - 1) * 0.95).toInt()]
        android.util.Log.i(
            SNOW_LOG_TAG,
            "frames=$n avg=${"%.1f".format(avg)}ms p95=${"%.1f".format(p95)}ms worst=${"%.1f".format(sorted[n - 1])}ms flakes=$flakes",
        )
    }
}

private val HolidayRed = Color(0xFFE53935)
