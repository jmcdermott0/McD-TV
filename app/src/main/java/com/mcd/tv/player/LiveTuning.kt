package com.mcd.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/**
 * Player settings for live IPTV on Fire TV sticks, where the defaults freeze and stutter:
 *  - Play a little further behind the live edge (12 s) and let the player speed up or slow down by up to 3%
 *    to hold that distance, instead of stalling every time the next segment is late.
 *  - A steady buffer (20 to 40 s, capped in bytes for 1 GB sticks) that starts playing after 2.5 s and,
 *    after a stall, waits for 5 s so it doesn't stall again right away.
 *  - Retry a late or failed segment several times before giving up.
 *  - MPEG-TS (most IPTV): start on any keyframe and detect frame boundaries, so a channel that starts mid-GOP
 *    shows a picture at once instead of freezing on audio only.
 */
@OptIn(UnstableApi::class)
object LiveTuning {
    /** How far behind the live edge to play. */
    const val TARGET_OFFSET_MS = 12_000L

    /** A live stream stuck buffering this long is reconnected at the live edge. */
    const val STALL_MS = 12_000L

    /** Reconnects in a row (no picture in between) before the player shows an error. */
    const val MAX_RECOVERIES = 4

    /** The live player whose stream is open now (one at a time: many IPTV accounts allow one connection). */
    private var current: Player? = null

    /**
     * A new live channel is about to open in [next]: stop the stream of the one still open (it is released when its
     * screen goes, a moment later). Main thread only.
     */
    fun takeOver(next: Player) {
        val old = current
        if (old != null && old !== next) runCatching { old.stop() }
        current = next
    }

    /** [player] is being released. Main thread only. */
    fun left(player: Player) {
        if (current === player) current = null
    }

    private const val TS_FLAGS =
        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS

    /** Buffer for one full-screen live channel ([tile]: a small Multiview tile, with a lighter buffer). */
    fun loadControl(tile: Boolean = false): LoadControl =
        if (tile) {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(8_000, 16_000, 1_500, 3_000)
                .setTargetBufferBytes(12 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(20_000, 40_000, 2_500, 5_000)
                .setTargetBufferBytes(40 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
        }

    /** The channel as a MediaItem with the live-edge settings above. */
    fun mediaItem(url: String): MediaItem {
        val hls = ".m3u8" in url.lowercase()
        return MediaItem.Builder()
            .setUri(url)
            .apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(TARGET_OFFSET_MS)
                    .setMinPlaybackSpeed(0.97f)
                    .setMaxPlaybackSpeed(1.03f)
                    .build(),
            )
            .build()
    }

    /** HLS or plain TS source for [url], with the TS settings and extra segment retries. */
    fun mediaSource(dataSource: DataSource.Factory, url: String): MediaSource {
        val item = mediaItem(url)
        val retries = DefaultLoadErrorHandlingPolicy(6)
        return if (".m3u8" in url.lowercase()) {
            HlsMediaSource.Factory(dataSource)
                .setExtractorFactory(DefaultHlsExtractorFactory(TS_FLAGS, true))
                .setLoadErrorHandlingPolicy(retries)
                .createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(dataSource, DefaultExtractorsFactory().setTsExtractorFlags(TS_FLAGS))
                .setLoadErrorHandlingPolicy(retries)
                .createMediaSource(item)
        }
    }
}
