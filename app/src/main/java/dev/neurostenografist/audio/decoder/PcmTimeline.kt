package dev.neurostenografist.audio.decoder

import kotlin.math.round

internal data class PcmSlice(val offset: Int, val length: Int)

/** Clip before resampling, in whole interleaved PCM frames, on the source timeline. */
internal fun pcmPresentationSlice(
    sampleCount: Int,
    channels: Int,
    sampleRate: Int,
    presentationTimeUs: Long,
    durationUs: Long?,
): PcmSlice {
    require(sampleCount >= 0 && channels > 0 && sampleCount % channels == 0)
    require(sampleRate > 0 && (durationUs == null || durationUs >= 0))
    val frames = sampleCount / channels
    // PTS has only microsecond precision. Nearest-frame rounding avoids dropping
    // one extra 44.1k sample at every truncated timestamp (e.g. 998458 us -> 44032).
    val start = round(presentationTimeUs.toDouble() * sampleRate / 1_000_000.0)
    val end = durationUs?.let { round(it.toDouble() * sampleRate / 1_000_000.0) }
        ?: Double.POSITIVE_INFINITY
    val first = (-start).coerceIn(0.0, frames.toDouble()).toInt()
    val last = (end - start).coerceIn(first.toDouble(), frames.toDouble()).toInt()
    return PcmSlice(first * channels, (last - first) * channels)
}
