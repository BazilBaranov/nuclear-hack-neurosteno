package dev.neurostenografist.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dev.neurostenografist.audio.decoder.Pcm
import dev.neurostenografist.audio.decoder.StreamingPcm16k
import dev.neurostenografist.audio.decoder.pcmPresentationSlice
import dev.neurostenografist.audio.decoder.readMp4MovieDurationUs
import java.io.IOException

/**
 * Decodes compressed audio from a content/file URI through MediaCodec into mono
 * 16 kHz floats using [AudioDecoder.resampleTo16k] for the rate conversion that
 * matches the WAV path exactly.
 *
 * Codec output is downmixed/resampled as it arrives; only bounded 16 kHz output
 * blocks are retained. Only codec priming/padding outside the presentation timeline
 * is excluded; silence inside the recording is preserved.
 */
object MediaDecoder {
    fun decodeTo16k(
        context: Context,
        uri: Uri,
    ): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                throw UnsupportedAudioException("no audio track in $uri")
            }
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = presentationDurationUs(context, uri, format, extractor.trackCount == 1)
            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            // The configured format carries rate/channel counts until an output format
            // change supersedes them; PCM encoding defaults to 16-bit on every platform.
            return drain(
                codec,
                extractor,
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                durationUs,
            )
        } finally {
            // Release Partially created codec/extractor quietly so cleanup cannot mask the
            // original failure bubbling out of this block.
            codec?.let { c ->
                runCatching { c.stop() }
                runCatching { c.release() }
            }
            runCatching { extractor.release() }
        }
    }

    private fun drain(
        codec: MediaCodec,
        extractor: MediaExtractor,
        configuredRate: Int,
        configuredChannels: Int,
        durationUs: Long?,
    ): FloatArray {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var stallsAfterInputEos = 0
        var sampleRate = configuredRate
        var channels = configuredChannels
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        var output: StreamingPcm16k? = null

        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(inBuf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            // One dequeue per iteration: the EOS marker is a property of the buffer we
            // receive, never a separate probe dequeue that would drop a real index.
            when (val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val out = codec.outputFormat
                    val nextRate = if (out.containsKey(MediaFormat.KEY_SAMPLE_RATE)) out.getInteger(MediaFormat.KEY_SAMPLE_RATE) else sampleRate
                    val nextChannels = if (out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) out.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else channels
                    if (output != null && (nextRate != sampleRate || nextChannels != channels)) {
                        throw IOException("Формат звука меняется внутри записи; преобразуйте файл в WAV PCM16")
                    }
                    if (out.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    val encoding =
                        if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT // platform default when the key is absent
                        }
                    Pcm.requireSupported(encoding)
                    pcmEncoding = encoding
                }

                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputDone) {
                        // No output yet while the decoder flushes post-EOS data is normal;
                        // keep waiting, fail only when the decoder is genuinely stalled.
                        stallsAfterInputEos++
                        if (stallsAfterInputEos >= MAX_EOS_STALL_POLLS) {
                            throw RuntimeException(
                                "MediaCodec decoder still silent after $MAX_EOS_STALL_POLLS post-EOS polls for mime " +
                                    codec.name,
                            )
                        }
                    }
                }

                else -> {
                    if (outIdx >= 0) {
                        stallsAfterInputEos = 0
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val sink = output ?: StreamingPcm16k(sampleRate, channels).also { output = it }
                                val pcm = convertOutput(codec.getOutputBuffer(outIdx)!!, info, pcmEncoding)
                                val slice = pcmPresentationSlice(pcm.size, channels, sampleRate, info.presentationTimeUs, durationUs)
                                sink.append(pcm, slice.offset, slice.length)
                            }
                        } finally {
                            codec.releaseOutputBuffer(outIdx, false)
                        }
                        // EOS can ride on the final PCM buffer, so convert before returning.
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        }

        return output?.finish() ?: FloatArray(0)
    }

    private fun presentationDurationUs(context: Context, uri: Uri, format: MediaFormat, singleAudioTrack: Boolean): Long? {
        val trackDuration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 }
        } else null
        if (!singleAudioTrack) return trackDuration
        // Older Android MP4 extractors and MetadataRetriever both report mdhd
        // duration including priming. mvhd gives the presentation duration.
        // Single-track audio only: a video's end must not define its audio's end.
        val presentationDuration = try {
            context.contentResolver.openInputStream(uri)?.use(::readMp4MovieDurationUs)
        } catch (_: Exception) {
            null // Missing optional metadata must not prevent normal codec decoding.
        }
        return listOfNotNull(trackDuration, presentationDuration).minOrNull()
    }

    /** Copies this output buffer's payload and converts it per the declared PCM encoding. */
    private fun convertOutput(
        buffer: java.nio.ByteBuffer,
        info: MediaCodec.BufferInfo,
        pcmEncoding: Int,
    ): FloatArray {
        val bytes = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.get(bytes)
        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> Pcm.pcmFloatLe(bytes, 0, bytes.size)
            else -> Pcm.pcm16Le(bytes, 0, bytes.size)
        }
    }

    /** 10 ms polls; at most 2 s of silence after input EOS before declaring the decoder stalled. */
    private const val TIMEOUT_US = 10_000L
    private const val MAX_EOS_STALL_POLLS = 200
}
