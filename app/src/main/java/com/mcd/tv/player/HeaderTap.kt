package com.mcd.tv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * The first [cap] bytes of the file, copied as the player itself reads them (chapters live there; see
 * data/Chapters.kt). Only reads that continue from byte 0 are kept, so seeks never mix in other parts of the file.
 */
class HeaderBytes(private val cap: Int = 512 * 1024) {
    private var buf = ByteArray(0)
    private var filled = 0

    @Synchronized
    fun offer(at: Long, b: ByteArray, off: Int, n: Int) {
        if (at != filled.toLong() || filled >= cap) return
        val take = minOf(n, cap - filled)
        if (filled + take > buf.size) buf = buf.copyOf(minOf(cap, maxOf(filled + take, buf.size * 2, 64 * 1024)))
        System.arraycopy(b, off, buf, filled, take)
        filled += take
    }

    @Synchronized
    fun snapshot(): ByteArray = buf.copyOf(filled)
}

/**
 * A data source that hands what it reads to [sink] as well (no extra request or connection). With [only] set, only
 * reads of that link are kept (an addon subtitle file loads through the same factory and must not fill the header).
 */
@OptIn(UnstableApi::class)
class HeaderTap(private val upstream: DataSource, private val sink: HeaderBytes, private val only: String? = null) : DataSource {
    private var pos = -1L

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val n = upstream.open(dataSpec)
        pos = if (only == null || dataSpec.uri.toString() == only) dataSpec.position else -1L
        return n
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val r = upstream.read(buffer, offset, length)
        if (r > 0 && pos >= 0) {
            runCatching { sink.offer(pos, buffer, offset, r) }
            pos += r
        }
        return r
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        pos = -1L
        upstream.close()
    }

    companion object {
        fun factory(upstream: DataSource.Factory, sink: HeaderBytes, only: String? = null): DataSource.Factory =
            DataSource.Factory { HeaderTap(upstream.createDataSource(), sink, only) }
    }
}

/**
 * What the episode player (ui/UpNext.kt) hears from [PlayerScreen]. Non-live video only. Callbacks run on the
 * main thread.
 */
class PlayerHook {
    val header = HeaderBytes()
    /** The player reached Failed (its error panel is up): no card, no keys for the card. */
    var failed = false
    /** The sleep timer is set to "End of this video": the episode must stop at its end, never roll on early. */
    var stopAtEnd = false
    /** First time the player is ready with a known length. */
    var onReady: ((durationMs: Long) -> Unit)? = null
    /** Twice a second while the player screen is up. */
    var onTick: ((posMs: Long, durationMs: Long, playing: Boolean) -> Unit)? = null
    /** A seek (remote LEFT / RIGHT, the control bar). */
    var onSeek: ((fromMs: Long, toMs: Long, durationMs: Long) -> Unit)? = null
    /** The player is going away. [ended]: it reached the end of the file. */
    var onLeave: ((posMs: Long, durationMs: Long, ended: Boolean) -> Unit)? = null
}
