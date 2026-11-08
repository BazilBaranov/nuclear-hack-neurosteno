package dev.neurostenografist.inference

import dev.neurostenografist.core.SpeakerInterval
import dev.neurostenografist.core.Turn
import kotlin.math.ceil
import kotlin.math.floor

/** Original speaker tracks; unlike ownership fallback this preserves simultaneous voices. */
object OverlapPlan {
    fun turns(intervals: List<SpeakerInterval>, durationMs: Long): List<Turn> {
        require(durationMs >= 0)
        val sorted = intervals.onEach {
            require(it.startSec.isFinite() && it.endSec.isFinite() && it.startSec <= it.endSec)
        }.sortedWith(compareBy({ it.startSec }, { it.endSec }, { it.speaker }))
        val labels = LinkedHashMap<Int, String>()
        val bySpeaker = LinkedHashMap<String, MutableList<Turn>>()
        for (interval in sorted) {
            val start = floor(interval.startSec * 1000 + 0.001).toLong().coerceIn(0, durationMs)
            val end = ceil(interval.endSec * 1000 - 0.001).toLong().coerceIn(0, durationMs)
            if (end <= start) continue
            val label = labels.getOrPut(interval.speaker) { "speaker_${labels.size + 1}" }
            val track = bySpeaker.getOrPut(label) { mutableListOf() }
            val previous = track.lastOrNull()
            if (previous != null && start <= previous.endMs) {
                track[track.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, end))
            } else {
                track += Turn(label, start, end, "")
            }
        }
        return bySpeaker.values.flatten().sortedWith(compareBy({ it.startMs }, { it.speakerId }))
    }

    /** Up to three seconds of exclusive voice, assembled only from this recording. */
    fun enrollment(turns: List<Turn>, speaker: String): List<Pair<Long, Long>> {
        val result = mutableListOf<Pair<Long, Long>>()
        var remaining = 3000L
        for (turn in turns.filter { it.speakerId == speaker }) {
            val others = turns.filter {
                it.speakerId != speaker && it.startMs < turn.endMs && it.endMs > turn.startMs
            }.sortedBy { it.startMs }
            var cursor = turn.startMs
            for (other in others) {
                val end = minOf(other.startMs, turn.endMs, cursor + remaining)
                if (end > cursor) {
                    result += cursor to end
                    remaining -= end - cursor
                }
                if (remaining == 0L) return result
                cursor = maxOf(cursor, other.endMs)
            }
            val end = minOf(turn.endMs, cursor + remaining)
            if (end > cursor) {
                result += cursor to end
                remaining -= end - cursor
            }
            if (remaining == 0L) break
        }
        return result
    }

    fun hasOverlap(turn: Turn, turns: List<Turn>): Boolean = turns.any {
        it.speakerId != turn.speakerId && it.startMs < turn.endMs && it.endMs > turn.startMs
    }
}
