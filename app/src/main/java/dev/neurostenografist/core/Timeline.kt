package dev.neurostenografist.core

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Canonical speaker labels, original-timeline turn splitting and overlap ownership.
 *
 * Overlap policy: the timeline is partitioned into atoms of constant active-speaker set;
 * an atom with several active speakers is assigned to exactly one owner (preceding
 * single-speaker atom's owner if still active, else following single-speaker atom's owner
 * if active, else the first active canonical label). The mixture text is never duplicated.
 */
object Timeline {
    // 1 µs absorbs binary float noise before floor/ceil without moving real boundaries.
    private const val MS_EPS = 1e-3

    fun canonicalize(
        intervals: List<SpeakerInterval>,
        durationMs: Long,
    ): List<Turn> {
        require(durationMs >= 0) { "negative audio duration: $durationMs" }
        val durationSec = durationMs / 1000.0
        val clipped =
            intervals.mapNotNull { interval ->
                require(interval.startSec.isFinite() && interval.endSec.isFinite()) {
                    "non-finite speaker interval: $interval"
                }
                require(interval.startSec <= interval.endSec) { "reversed speaker interval: $interval" }
                val start = interval.startSec.coerceIn(0.0, durationSec)
                val end = interval.endSec.coerceIn(0.0, durationSec)
                if (start >= end) null else interval.copy(startSec = start, endSec = end)
            }
        if (clipped.isEmpty()) return emptyList()

        val sorted = clipped.sortedWith(compareBy({ it.startSec }, { it.endSec }, { it.speaker }))
        val ordinal = LinkedHashMap<Int, Int>()
        for (interval in sorted) {
            if (interval.speaker !in ordinal) ordinal[interval.speaker] = ordinal.size
        }

        val atoms = buildAtoms(sorted)
        val owners = resolveOwners(atoms, ordinal)

        // Merge contiguous same-owner atoms in the double domain, then round once per turn.
        val turns = mutableListOf<Turn>()
        var index = 0
        while (index < atoms.size) {
            val owner = owners[index]
            var endSec = atoms[index].endSec
            var next = index + 1
            while (next < atoms.size && owners[next] == owner && atoms[next].startSec == endSec) {
                endSec = atoms[next].endSec
                next++
            }
            // Ownership is already exclusive; outward rounding must not recreate overlap.
            val startMs = maxOf(floorMs(atoms[index].startSec, durationMs), turns.lastOrNull()?.endMs ?: 0L)
            val endMs = ceilMs(endSec, durationMs)
            if (startMs < endMs) {
                turns += Turn("speaker_${owner + 1}", startMs, endMs, "")
            }
            index = next
        }
        return turns
    }

    /** Splits a turn into consecutive chunks of at most [maxMs] on the original timeline. */
    fun split(
        turn: Turn,
        maxMs: Long,
    ): List<Turn> {
        require(maxMs > 0) { "maxMs must be positive: $maxMs" }
        require(turn.startMs < turn.endMs) { "degenerate turn: $turn" }
        val parts = mutableListOf<Turn>()
        var start = turn.startMs
        while (start < turn.endMs) {
            val end = minOf(start + maxMs, turn.endMs)
            parts += turn.copy(startMs = start, endMs = end)
            start = end
        }
        return parts
    }

    /** Total duration of spans where two or more speakers are active; reported as a warning. */
    fun overlapMs(intervals: List<SpeakerInterval>): Long {
        if (intervals.isEmpty()) return 0
        val durationMs = ((maxOf(intervals.maxOf { it.endSec }, 0.0)) * 1000.0).toLong() + 1
        val clipped =
            intervals.mapNotNull { interval ->
                if (!interval.startSec.isFinite() || !interval.endSec.isFinite()) return@mapNotNull null
                if (interval.startSec > interval.endSec) return@mapNotNull null
                if (interval.startSec < 0.0 && interval.endSec <= 0.0) return@mapNotNull null
                interval.copy(
                    startSec = interval.startSec.coerceAtLeast(0.0),
                    endSec = interval.endSec.coerceAtLeast(0.0),
                )
            }
        if (clipped.isEmpty()) return 0
        val atoms = buildAtoms(clipped)
        var total = 0L
        for (atom in atoms) {
            if (atom.active.size > 1) {
                total += ceilMs(atom.endSec, durationMs) - floorMs(atom.startSec, durationMs)
            }
        }
        return total
    }

    private data class Atom(
        val startSec: Double,
        val endSec: Double,
        val active: Set<Int>,
    )

    private fun buildAtoms(intervals: List<SpeakerInterval>): List<Atom> {
        val boundaries =
            intervals
                .flatMap { listOf(it.startSec, it.endSec) }
                .distinct()
                .sorted()
        val atoms = mutableListOf<Atom>()
        for (i in 0 until boundaries.size - 1) {
            val start = boundaries[i]
            val end = boundaries[i + 1]
            val active =
                intervals
                    .filter { it.startSec <= start && it.endSec >= end }
                    .map { it.speaker }
                    .toSet()
            if (active.isNotEmpty()) atoms += Atom(start, end, active)
        }
        return atoms
    }

    /** Resolves each atom to one canonical ordinal (0-based speaker_1, speaker_2, ...). */
    private fun resolveOwners(
        atoms: List<Atom>,
        ordinal: Map<Int, Int>,
    ): List<Int> {
        val owners = IntArray(atoms.size) { -1 }

        fun singleOwnerAt(index: Int): Int? {
            if (index !in atoms.indices) return null
            val active = atoms[index].active
            return if (active.size == 1) ordinal[active.first()] else null
        }
        for (i in atoms.indices) {
            val active = atoms[i].active
            if (active.size == 1) {
                owners[i] = ordinal.getValue(active.first())
                continue
            }
            var previous = i - 1
            var owner: Int? = null
            while (previous >= 0) {
                val candidate = singleOwnerAt(previous)
                if (candidate != null) {
                    val raw = atoms[previous].active.first()
                    if (raw in active) owner = candidate
                    break
                }
                previous--
            }
            if (owner == null) {
                var following = i + 1
                while (following < atoms.size) {
                    val candidate = singleOwnerAt(following)
                    if (candidate != null) {
                        val raw = atoms[following].active.first()
                        if (raw in active) owner = candidate
                        break
                    }
                    following++
                }
            }
            owners[i] = owner ?: active.minOf { ordinal.getValue(it) }
        }
        return owners.toList()
    }

    private fun floorMs(
        sec: Double,
        durationMs: Long,
    ): Long = floor(sec * 1000.0 + MS_EPS).toLong().coerceIn(0L, durationMs)

    private fun ceilMs(
        sec: Double,
        durationMs: Long,
    ): Long {
        if (sec >= durationMs / 1000.0) return durationMs
        return ceil(sec * 1000.0 - MS_EPS).toLong().coerceIn(0L, durationMs)
    }
}
