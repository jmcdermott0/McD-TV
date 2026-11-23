package com.mcd.tv.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.mcd.tv.data.VoiceMachine.Action
import com.mcd.tv.data.VoiceMachine.Event
import com.mcd.tv.data.VoiceMachine.Failure
import com.mcd.tv.data.VoiceMachine.Line
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/**
 * Jarvis voice: the speaker. Every line Jarvis speaks also shows on screen; with no key, voice off or any
 * failure, Jarvis stays silent and works exactly as before.
 *
 * Rules: [VoicePolicy] (may this line speak now), [VoiceMachine] (the one state value and its transitions),
 * [VoiceCache] (each line is paid for once, saved on this TV), [FishTts] (the speech service). This object only
 * carries out the machine's actions: it runs on the main thread, network and disk on IO, and catches everything,
 * so a voice problem can never crash Jarvis.
 */
object Voice {
    private const val TAG = "JarvisVoice"
    /** The speech player is released after this long with nothing to say. */
    private const val IDLE_RELEASE_MS = 30_000L

    private lateinit var dir: File
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, t -> Log.w(TAG, "voice: ${t.javaClass.simpleName}") })

    private var state = VoiceMachine.State()
    private var player: ExoPlayer? = null
    private var releaseJob: Job? = null
    private var keySig = ""
    private var lastWallAt = 0L
    private val recentAsk = LinkedHashMap<String, Long>()

    /** The line being spoken now (null: silent). Screens without their own text for it can show it. */
    private val _speaking = MutableStateFlow<String?>(null)
    val speaking: StateFlow<String?> = _speaking

    /** True while a line plays: the video player lowers its volume. */
    private val _ducking = MutableStateFlow(false)
    val ducking: StateFlow<Boolean> = _ducking

    /** Plain status for TV Settings. */
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    /** Video players open now (a channel change opens the next before the last one closes). */
    private val openVideos = java.util.concurrent.atomic.AtomicInteger(0)
    val videoOpen: Boolean get() = openVideos.get() > 0
    fun videoOpened() { openVideos.incrementAndGet() }
    fun videoClosed() { if (openVideos.decrementAndGet() < 0) openVideos.set(0) }

    fun init(context: Context) {
        appContext = context.applicationContext
        dir = File(context.applicationContext.filesDir, "voice")
        scope.launch { withContext(Dispatchers.IO) { runCatching { VoiceCache.trim(dir, VoiceCache.CAP_BYTES) } } }
        refreshStatus()
    }

    private val ready: Boolean get() = appContext != null

    val keySet: Boolean get() = Prefs.voiceKey.isNotBlank()

    /**
     * Speak [text] if the policy allows it now. The caller always shows the same text on screen.
     * Safe to call from any thread and with no key: it then does nothing.
     */
    fun say(kind: VoiceKind, text: String, deadline: Long = System.currentTimeMillis() + VoicePolicy.LATE_MS) {
        if (!ready) return
        scope.launch { sayNow(kind, VoiceLines.clean(text), deadline) }
    }

    /** Fetch [text] into the cache now, without speaking it (Wall lines a few minutes ahead). */
    fun prefetch(text: String) {
        if (!ready || !keySet || !Prefs.voiceOn) return
        scope.launch {
            checkKey()
            val now = System.currentTimeMillis()
            val clean = VoiceLines.clean(text)
            if (clean.isEmpty() || !VoiceMachine.canFetch(state, now)) return@launch
            if (withContext(Dispatchers.IO) { cachedFile(clean) != null }) return@launch
            val (r, _) = fetchAudio(clean)
            if (r is FishTts.Result.Fail) dispatch(Event.PrefetchFailed(r.failure, System.currentTimeMillis()))
        }
    }

    /** Plays a short test line now (TV Settings > Jarvis voice). Needs only a key. */
    fun test() = say(VoiceKind.TEST, VoiceLines.test(hourNow()))

    /** Stop speaking and drop the queue. */
    fun stop() { if (ready) scope.launch { dispatch(Event.Stop) } }

    private fun hourNow() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    /** The key or the voice changed (Control page, Settings, another TV): clear any block or rest. */
    private fun checkKey() {
        val sig = "${Prefs.voiceKey.hashCode()}|${Prefs.voiceId}|${Prefs.voiceIdBackup}|${Prefs.voiceModel}"
        if (sig != keySig) {
            val first = keySig.isEmpty()
            keySig = sig
            if (!first) { usingBackup = false; dispatch(Event.KeyChanged) }
        }
    }

    private suspend fun sayNow(kind: VoiceKind, text: String, deadline: Long) {
        if (text.isEmpty()) return
        checkKey()
        val now = System.currentTimeMillis()
        val ctx = VoicePolicy.Ctx(
            now = now, hourOfDay = hourNow(), keySet = keySet, voiceOn = Prefs.voiceOn, usesOff = Prefs.voiceUsesOff,
            videoOpen = videoOpen, kids = Prefs.isKidsProfile, lastWallAt = lastWallAt,
        )
        val skip = VoicePolicy.skip(kind, deadline, ctx)
        if (skip != null) { Log.i(TAG, "text only (${skip.name.lowercase()}): ${kind.key}"); return }
        if (kind == VoiceKind.ASK) {
            recentAsk.entries.removeAll { now - it.value > VoicePolicy.REPEAT_MS }
            if (recentAsk.containsKey(text)) return
            recentAsk[text] = now
        }
        if (kind.wall) lastWallAt = now
        val cached = withContext(Dispatchers.IO) { cachedFile(text) != null }
        dispatch(Event.Add(Line(kind, text, deadline, cached), now))
    }

    private fun dispatch(e: Event) {
        val (next, actions) = VoiceMachine.reduce(state, e)
        state = next
        actions.forEach(::run)
        refreshStatus()
    }

    private fun run(a: Action) {
        when (a) {
            is Action.Fetch -> scope.launch {
                val f = withContext(Dispatchers.IO) { cachedFile(a.line.text) }
                if (f != null) { pending[a.gen] = f; dispatch(Event.Ready(a.gen, System.currentTimeMillis())); return@launch }
                val (r, file) = fetchAudio(a.line.text)
                val now = System.currentTimeMillis()
                when {
                    r is FishTts.Result.Fail -> { Log.i(TAG, "fetch failed: ${r.failure} ${r.code}"); dispatch(Event.Failed(a.gen, r.failure, now)) }
                    file != null -> { pending[a.gen] = file; dispatch(Event.Ready(a.gen, now)) }
                    else -> dispatch(Event.Failed(a.gen, Failure.OTHER, now))
                }
            }
            is Action.Play -> play(a.gen, a.line)
            Action.StopAudio -> { runCatching { player?.stop() }; pending.clear(); speakingDone() }
            is Action.Blocked -> noteBlocked(a.failure)
        }
    }

    /** Audio files ready for a generation (filled by Fetch, read by Play). */
    private val pending = HashMap<Int, File>()

    /** The saved file for [text] with the current voice, or null. */
    private fun cachedFile(text: String): File? = VoiceCache.get(dir, VoiceCache.key(Prefs.voiceModel, Prefs.voiceId, FishTts.SPEED, text))

    /** Set after the service stopped finding the main voice and the backup answered instead (this app run). */
    private var usingBackup = false

    /** Fetches [text] and saves it in the cache (the file comes back too). Tries the backup voice when the main voice is not found. */
    private suspend fun fetchAudio(text: String): Pair<FishTts.Result, File?> {
        val key = Prefs.voiceKey
        if (key.isBlank()) return FishTts.Result.Fail(Failure.KEY, 0) to null
        val model = Prefs.voiceModel
        val main = Prefs.voiceId
        val backup = Prefs.voiceIdBackup
        val first = if (usingBackup && backup.isNotBlank()) backup else main
        var r = FishTts.fetch(key, model, first, text)
        if (r is FishTts.Result.Fail && r.failure == Failure.VOICE && first == main && backup.isNotBlank() && backup != main) {
            r = FishTts.fetch(key, model, backup, text)
            if (r is FishTts.Result.Ok) {
                usingBackup = true
                RecentErrors.note("Jarvis voice could not find its voice, so it is using the backup voice.")
            }
        }
        if (r !is FishTts.Result.Ok) return r to null
        val bytes = r.mp3
        // Saved under the main voice's name, so the line is found again even while the backup voice speaks.
        val file = withContext(Dispatchers.IO) { runCatching { VoiceCache.put(dir, VoiceCache.key(model, main, FishTts.SPEED, text), bytes) }.getOrNull() }
        return r to file
    }

    private fun volume(): Float = when (Prefs.voiceVolume) { "soft" -> 0.55f; "loud" -> 1f; else -> 0.8f }

    private fun play(gen: Int, line: Line) {
        val file = pending.remove(gen)
        val ctx = appContext
        if (file == null || ctx == null) { dispatch(Event.Done(gen, System.currentTimeMillis())); return }
        try {
            releaseJob?.cancel()
            val p = player ?: ExoPlayer.Builder(ctx).build().also { np ->
                np.setAudioAttributes(
                    AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).setUsage(C.USAGE_MEDIA).build(),
                    /* handleAudioFocus = */ false,
                )
                np.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) finished()
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        Log.i(TAG, "play error ${error.errorCodeName}")
                        finished()
                    }
                })
                player = np
            }
            p.volume = volume()
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            p.prepare()
            p.playWhenReady = true
            _speaking.value = line.text
            _ducking.value = true
        } catch (t: Throwable) {
            Log.w(TAG, "play failed: ${t.javaClass.simpleName}")
            finished()
        }
    }

    private fun finished() {
        val p = state.phase
        speakingDone()
        if (p is VoiceMachine.Phase.Playing) dispatch(Event.Done(p.gen, System.currentTimeMillis()))
    }

    private fun speakingDone() {
        _speaking.value = null
        _ducking.value = false
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            if (state.phase == VoiceMachine.Phase.Idle) { runCatching { player?.release() }; player = null }
        }
    }

    private fun noteBlocked(f: Failure) {
        val text = when (f) {
            Failure.KEY -> "Jarvis voice key rejected. Replace it on the Control page."
            Failure.CREDIT -> "Jarvis voice credit is empty."
            Failure.VOICE -> "Jarvis voice could not find its voice. Check the voice ID on the Control page."
            else -> return
        }
        RecentErrors.note(text)
    }

    private fun refreshStatus() {
        _status.value = if (!Prefs.voiceOn && keySet) "Off on this TV." else VoiceMachine.status(state, keySet, System.currentTimeMillis())
    }

    /** Current status text (also refreshes it). */
    fun statusNow(): String { if (ready) { checkKey(); refreshStatus() }; return _status.value }
}
