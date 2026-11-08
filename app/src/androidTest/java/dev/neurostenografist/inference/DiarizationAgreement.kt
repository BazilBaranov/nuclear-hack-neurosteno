package dev.neurostenografist.inference

import dev.neurostenografist.core.SpeakerInterval
import kotlin.math.abs
import kotlin.math.ceil

/** Regression comparison against CPU, not a DER benchmark against human annotation. */
internal data class DiarizationAgreement(
    val referenceSpeakers: Int,
    val candidateSpeakers: Int,
    val speakerFrameError: Double,
    val overlapSecondsDelta: Double,
)

internal fun compareDiarization(
    reference: List<SpeakerInterval>,
    candidate: List<SpeakerInterval>,
    durationSec: Double,
): DiarizationAgreement {
    require(durationSec > 0 && durationSec.isFinite())
    val refIds = reference.map { it.speaker }.distinct().sorted()
    val outIds = candidate.map { it.speaker }.distinct().sorted()
    require(refIds.size <= 8 && outIds.size <= 8)
    val frames = ceil(durationSec / 0.05).toInt().coerceIn(1, 36_000)
    val dt = durationSec / frames
    fun masks(intervals: List<SpeakerInterval>, ids: List<Int>) = IntArray(frames) { frame ->
        val time = (frame + 0.5) * dt
        intervals.fold(0) { mask, interval ->
            if (time >= interval.startSec && time < interval.endSec) mask or (1 shl ids.indexOf(interval.speaker)) else mask
        }
    }
    val a = masks(reference, refIds)
    val b = masks(candidate, outIds)
    val overlapDelta = abs(a.count { Integer.bitCount(it) > 1 } - b.count { Integer.bitCount(it) > 1 }) * dt
    val n = maxOf(refIds.size, outIds.size)
    val weights = Array(n) { IntArray(n) }
    for (frame in a.indices) for (i in refIds.indices) for (j in outIds.indices) {
        if ((a[frame] and (1 shl i)) != 0 && (b[frame] and (1 shl j)) != 0) weights[i][j]++
    }
    // At most 8! assignments. Unused slots are dummy speakers, so added voices
    // remain errors instead of disappearing in a many-to-one label mapping.
    var best = 0
    fun assign(row: Int, used: Int, score: Int) {
        if (row == n) { best = maxOf(best, score); return }
        for (column in 0 until n) if ((used and (1 shl column)) == 0) {
            assign(row + 1, used or (1 shl column), score + weights[row][column])
        }
    }
    assign(0, 0, 0)
    val refActive = a.sumOf { Integer.bitCount(it) }
    val outActive = b.sumOf { Integer.bitCount(it) }
    val error = (refActive + outActive - 2 * best).toDouble() / refActive.coerceAtLeast(1)
    return DiarizationAgreement(refIds.size, outIds.size, error, overlapDelta)
}
