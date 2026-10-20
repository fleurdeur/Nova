package com.nova.fx.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.nova.fx.model.Project
import com.nova.fx.model.Source
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface AudioSource {
    val format: MediaFormat?
    fun write(mux: MediaMuxer, track: Int)
}

/**
 * Real mixing: decode each clip to PCM, apply volume and fades, sum overlapping clips, encode AAC.
 * Used when volume, fades, or overlapping audio clips exist.
 */
class AudioMix private constructor(
    override val format: MediaFormat?,
    private val chunks: List<Pair<ByteArray, MediaCodec.BufferInfo>>
) : AudioSource {
    override fun write(mux: MediaMuxer, track: Int) {
        for ((b, i) in chunks) mux.writeSampleData(track, ByteBuffer.wrap(b), i)
    }

    private class Pcm(val rate: Int, val ch: Int, val data: ShortArray)
    private class Clip(val pcm: Pcm, val start: Long, val frames: Int, val vol: Float, val fi: Long, val fo: Long)

    companion object {
        fun create(ctx: Context, p: Project): AudioMix? {
            val clips0 = p.layers.filter {
                it.audioUri != null && it.source is Source.Video && it.speed == 1f && !it.reversed && !it.remap.isAnimated
            }.sortedBy { it.inMs }
            var overlap = false
            var end = 0L
            for (l in clips0) {
                if (l.inMs < end) overlap = true
                end = maxOf(end, minOf(l.outMs, p.durationMs))
            }
            if (!(overlap || clips0.any { it.volume != 1f || it.fadeInMs > 0 || it.fadeOutMs > 0 })) return null

            var rate = 0
            var ch = 0
            var budget = 75_000_000L
            val clips = ArrayList<Clip>()
            for (l in clips0) {
                val len = minOf(l.outMs, p.durationMs) - l.inMs
                if (len <= 0) continue
                val pcm = decode(ctx, l.audioUri!!, l.trimMs, len) ?: continue
                if (rate == 0) {
                    rate = pcm.rate
                    ch = pcm.ch
                } else if (pcm.rate != rate || pcm.ch != ch) continue
                if (pcm.data.size > budget) continue
                budget -= pcm.data.size
                clips += Clip(pcm, l.inMs * rate / 1000, pcm.data.size / ch, l.volume, l.fadeInMs * rate / 1000, l.fadeOutMs * rate / 1000)
            }
            if (clips.isEmpty()) return null

            val total = (p.durationMs * rate / 1000).toInt()
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * ch * 2)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            val acc = FloatArray(1024 * ch)
            val chunks = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
            var outFmt: MediaFormat? = null
            var frame = 0
            var inDone = false
            try {
                while (true) {
                    if (!inDone) {
                        val ib = enc.dequeueInputBuffer(5_000)
                        if (ib >= 0) {
                            if (frame >= total) {
                                enc.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                val n = minOf(1024, total - frame)
                                java.util.Arrays.fill(acc, 0f)
                                for (c in clips) {
                                    val local0 = frame - c.start
                                    if (local0 + n <= 0 || local0 >= c.frames) continue
                                    for (k in 0 until n) {
                                        val local = local0 + k
                                        if (local < 0 || local >= c.frames) continue
                                        var g = c.vol
                                        if (c.fi > 0 && local < c.fi) g *= local.toFloat() / c.fi
                                        val rem = c.frames - local
                                        if (c.fo > 0 && rem < c.fo) g *= rem.toFloat() / c.fo
                                        for (q in 0 until ch) acc[k * ch + q] += c.pcm.data[(local * ch + q).toInt()] * g
                                    }
                                }
                                val bb = enc.getInputBuffer(ib)!!
                                bb.clear()
                                val sb = bb.order(ByteOrder.nativeOrder()).asShortBuffer()
                                for (j in 0 until n * ch) sb.put(acc[j].coerceIn(-32768f, 32767f).toInt().toShort())
                                enc.queueInputBuffer(ib, 0, n * ch * 2, frame * 1_000_000L / rate, 0)
                                frame += n
                            }
                        }
                    }
                    val info = MediaCodec.BufferInfo()
                    val ob = enc.dequeueOutputBuffer(info, 5_000)
                    if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outFmt = enc.outputFormat
                    } else if (ob >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            val b = ByteArray(info.size)
                            val src = enc.getOutputBuffer(ob)!!
                            src.position(info.offset)
                            src.get(b)
                            chunks += Pair(b, MediaCodec.BufferInfo().also { it.set(0, info.size, info.presentationTimeUs, info.flags) })
                        }
                        enc.releaseOutputBuffer(ob, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                enc.stop()
                enc.release()
            }
            return AudioMix(outFmt, chunks)
        }

        private fun decode(ctx: Context, uri: Uri, fromMs: Long, lenMs: Long): Pcm? {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(ctx, uri, null)
                val i = (0 until ex.trackCount).firstOrNull {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return null
                ex.selectTrack(i)
                val f = ex.getTrackFormat(i)
                val dec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
                dec.configure(f, null, null, 0)
                dec.start()
                ex.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val parts = ArrayList<ShortArray>()
                var total = 0
                val info = MediaCodec.BufferInfo()
                var inDone = false
                var outDone = false
                val startUs = fromMs * 1000
                val endUs = (fromMs + lenMs) * 1000
                try {
                    while (!outDone) {
                        if (!inDone) {
                            val ib = dec.dequeueInputBuffer(5_000)
                            if (ib >= 0) {
                                val n = ex.readSampleData(dec.getInputBuffer(ib)!!, 0)
                                if (n < 0 || ex.sampleTime > endUs) {
                                    dec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inDone = true
                                } else {
                                    dec.queueInputBuffer(ib, 0, n, ex.sampleTime, 0)
                                    ex.advance()
                                }
                            }
                        }
                        val ob = dec.dequeueOutputBuffer(info, 5_000)
                        if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val nf = dec.outputFormat
                            rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        } else if (ob >= 0) {
                            if (info.size > 0) {
                                val sb = dec.getOutputBuffer(ob)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                                val arr = ShortArray(sb.remaining())
                                sb.get(arr)
                                val skip = if (info.presentationTimeUs < startUs) {
                                    ((startUs - info.presentationTimeUs) * rate / 1_000_000).toInt() * ch
                                } else 0
                                if (skip < arr.size) {
                                    parts += arr.copyOfRange(skip, arr.size)
                                    total += arr.size - skip
                                }
                            }
                            dec.releaseOutputBuffer(ob, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                        }
                    }
                } finally {
                    dec.stop()
                    dec.release()
                }
                val want = minOf(total, (lenMs * rate / 1000).toInt() * ch)
                val out = ShortArray(want)
                var pos = 0
                for (a in parts) {
                    val n = minOf(a.size, want - pos)
                    if (n <= 0) break
                    System.arraycopy(a, 0, out, pos, n)
                    pos += n
                }
                return Pcm(rate, ch, out)
            } catch (_: Exception) {
                return null
            } finally {
                ex.release()
            }
        }
    }
}
