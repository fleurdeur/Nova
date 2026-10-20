package com.nova.fx.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.nova.fx.model.Project
import com.nova.fx.model.Source
import java.nio.ByteBuffer

/**
 * Lossless AAC passthrough: copies the compressed audio of normal-speed, forward, non-overlapping video clips into the MP4.
 */
class AudioCopy(private val ctx: Context, project: Project) : AudioSource {
    private class Seg(val uri: Uri, val trimMs: Long, val inMs: Long, val lenMs: Long)
    private val segs = ArrayList<Seg>()
    override var format: MediaFormat? = null; private set

    init {
        var end = 0L
        for (l in project.layers.sortedBy { it.inMs }) {
            val u = l.audioUri ?: continue
            if (l.source !is Source.Video || l.speed != 1f || l.reversed || l.remap.isAnimated || l.inMs < end) continue
            val ex = MediaExtractor()
            try {
                ex.setDataSource(ctx, u, null)
                val i = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC } ?: continue
                val f = ex.getTrackFormat(i)
                val first = format
                if (first == null) format = f
                else if (first.getInteger(MediaFormat.KEY_SAMPLE_RATE) != f.getInteger(MediaFormat.KEY_SAMPLE_RATE) ||
                    first.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) continue
                val len = minOf(l.outMs, project.durationMs) - l.inMs
                if (len > 0) {
                    segs += Seg(u, l.trimMs, l.inMs, len)
                    end = l.inMs + len
                }
            } catch (_: Exception) {} finally { ex.release() }
        }
    }

    override fun write(mux: MediaMuxer, track: Int) {
        val buf = ByteBuffer.allocate(512 * 1024)
        val info = MediaCodec.BufferInfo()
        for (s in segs) {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(ctx, s.uri, null)
                val i = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC }
                ex.selectTrack(i)
                ex.seekTo(s.trimMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (true) {
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    val tUs = ex.sampleTime
                    if (tUs >= (s.trimMs + s.lenMs) * 1000) break
                    if (tUs >= s.trimMs * 1000) {
                        info.set(0, n, tUs - s.trimMs * 1000 + s.inMs * 1000, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        mux.writeSampleData(track, buf, info)
                    }
                    ex.advance()
                }
            } finally { ex.release() }
        }
    }
}
