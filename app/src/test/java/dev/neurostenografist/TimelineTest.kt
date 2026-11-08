package dev.neurostenografist

import dev.neurostenografist.core.SpeakerInterval
import dev.neurostenografist.core.Timeline
import dev.neurostenografist.core.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {
    @Test
    fun adjacentFractionalBoundaryDoesNotCreateMillisecondOverlap() {
        val turns = Timeline.canonicalize(listOf(
            SpeakerInterval(1, 0.030, 0.7905),
            SpeakerInterval(2, 0.7905, 5.583),
        ), durationMs = 6000)
        assertEquals(listOf(Turn("speaker_1", 30, 791, ""), Turn("speaker_2", 791, 5583, "")), turns)
    }

    @Test
    fun subMillisecondGapDoesNotBecomeOverlappingSpeech() {
        val turns = Timeline.canonicalize(listOf(
            SpeakerInterval(1, 0.030, 0.7902),
            SpeakerInterval(2, 0.7908, 5.583),
        ), durationMs = 6000)
        assertEquals(listOf(Turn("speaker_1", 30, 791, ""), Turn("speaker_2", 791, 5583, "")), turns)
        assertTrue(turns.zipWithNext().all { (previous, next) -> previous.endMs <= next.startMs })
    }

    @Test
    fun realFractionalOverlapStillHasOnlyOneCanonicalOwner() {
        val intervals = listOf(SpeakerInterval(1, 0.030, 0.7905), SpeakerInterval(2, 0.500, 5.583))
        val turns = Timeline.canonicalize(intervals, durationMs = 6000)
        assertEquals(listOf(Turn("speaker_1", 30, 791, ""), Turn("speaker_2", 791, 5583, "")), turns)
        assertEquals(291L, Timeline.overlapMs(intervals))
    }

    @Test
    fun rawSpeakersOrderByFirstAppearance() {
        val intervals =
            listOf(
                SpeakerInterval(8, 4.0, 5.0),
                SpeakerInterval(2, 1.0, 2.0),
                SpeakerInterval(2, 6.0, 7.0),
            )
        val turns = Timeline.canonicalize(intervals, durationMs = 10_000)
        assertEquals(
            listOf(
                Turn("speaker_1", 1000, 2000, ""),
                Turn("speaker_2", 4000, 5000, ""),
                Turn("speaker_1", 6000, 7000, ""),
            ),
            turns,
        )
    }

    @Test
    fun initialSilenceKeepsOriginalOffset() {
        val turns =
            Timeline.canonicalize(
                listOf(SpeakerInterval(1, 6.0, 7.0)),
                durationMs = 10_000,
            )
        assertEquals(listOf(Turn("speaker_1", 6000, 7000, "")), turns)
    }

    @Test
    fun splitLongTurnAtBoundedWindow() {
        val turn = Turn("speaker_1", 1500, 66500, "x")
        val parts = Timeline.split(turn, maxMs = 25_000)
        assertEquals(listOf(1500L, 26_500L, 51_500L), parts.map { it.startMs })
        assertEquals(listOf(26_500L, 51_500L, 66_500L), parts.map { it.endMs })
        assertTrue(parts.all { it.endMs - it.startMs <= 25_000 })
        assertTrue(parts.all { it.speakerId == "speaker_1" && it.text == "x" })
    }

    @Test
    fun rejectsNonFiniteAndReversedIntervals() {
        assertThrows(IllegalArgumentException::class.java) {
            Timeline.canonicalize(listOf(SpeakerInterval(1, Double.NaN, 2.0)), 10_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Timeline.canonicalize(listOf(SpeakerInterval(1, 1.0, Double.POSITIVE_INFINITY)), 10_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Timeline.canonicalize(listOf(SpeakerInterval(1, 5.0, 4.0)), 10_000)
        }
    }

    @Test
    fun clipsDecoderDriftToAudioBoundsWithoutManufacturingSpeech() {
        val early =
            Timeline.canonicalize(
                listOf(SpeakerInterval(1, -0.05, 1.0)),
                durationMs = 1_000,
            )
        assertEquals(listOf(Turn("speaker_1", 0, 1000, "")), early)

        val late =
            Timeline.canonicalize(
                listOf(SpeakerInterval(1, 9.95, 10.05)),
                durationMs = 10_000,
            )
        assertEquals(listOf(Turn("speaker_1", 9950, 10_000, "")), late)

        val outside =
            Timeline.canonicalize(
                listOf(SpeakerInterval(1, 11.0, 12.0)),
                durationMs = 10_000,
            )
        assertTrue(outside.isEmpty())

        val reversedByClip =
            Timeline.canonicalize(
                listOf(SpeakerInterval(1, -1.0, -0.5)),
                durationMs = 10_000,
            )
        assertTrue(reversedByClip.isEmpty())
    }

    @Test
    fun overlapAtomGetsExactlyOneOwner() {
        // A [0,2s], B [1.5s,3s]: the [1500,2000] atom is owned once, never duplicated.
        val intervals =
            listOf(
                SpeakerInterval(1, 0.0, 2.0),
                SpeakerInterval(2, 1.5, 3.0),
            )
        val turns = Timeline.canonicalize(intervals, durationMs = 3_000)
        assertEquals(
            listOf(
                Turn("speaker_1", 0, 2000, ""),
                Turn("speaker_2", 2000, 3000, ""),
            ),
            turns,
        )
        assertEquals(500L, Timeline.overlapMs(intervals))
    }

    @Test
    fun adjacentSameSpeakerIntervalsMergeAndGapsDoNot() {
        val merged =
            Timeline.canonicalize(
                listOf(
                    SpeakerInterval(3, 0.0, 1.0),
                    SpeakerInterval(3, 1.0, 2.0),
                ),
                durationMs = 10_000,
            )
        assertEquals(listOf(Turn("speaker_1", 0, 2000, "")), merged)

        val gapped =
            Timeline.canonicalize(
                listOf(
                    SpeakerInterval(3, 0.0, 1.0),
                    SpeakerInterval(3, 1.5, 2.0),
                ),
                durationMs = 10_000,
            )
        assertEquals(
            listOf(
                Turn("speaker_1", 0, 1000, ""),
                Turn("speaker_1", 1500, 2000, ""),
            ),
            gapped,
        )
    }

    @Test
    fun leadingOverlapFallsForwardToFollowingOwner() {
        // Overlap at the very start has no preceding atom; use the following single-speaker atom.
        val intervals =
            listOf(
                SpeakerInterval(1, 0.0, 2.0),
                SpeakerInterval(2, 0.0, 1.0),
            )
        // Tie-break is start, end, raw label: B(0-1s) sorts first, so raw 2 becomes
        // speaker_1 and raw 1 becomes speaker_2; the overlap atom falls forward to raw 1.
        val turns = Timeline.canonicalize(intervals, durationMs = 3_000)
        assertEquals(listOf(Turn("speaker_2", 0, 2000, "")), turns)
        assertEquals(1000L, Timeline.overlapMs(intervals))
    }

    @Test
    fun threeRecurringSpeakersGetDeterministicFirstAppearanceLabels() {
        val intervals =
            listOf(
                SpeakerInterval(11, 0.0, 1.0), // raw speaker 11 -> speaker_1
                SpeakerInterval(4, 1.0, 2.0), // raw speaker 4 -> speaker_2
                SpeakerInterval(7, 2.0, 3.0), // raw speaker 7 -> speaker_3
                SpeakerInterval(4, 3.0, 4.0), // recurring
                SpeakerInterval(11, 4.0, 5.0), // recurring
                SpeakerInterval(7, 5.0, 6.0), // recurring
            )
        val turns = Timeline.canonicalize(intervals, durationMs = 6_000)
        assertEquals(
            listOf("speaker_1", "speaker_2", "speaker_3", "speaker_2", "speaker_1", "speaker_3"),
            turns.map { it.speakerId },
        )
        for (t in turns) {
            assertTrue(t.text.isEmpty())
        }
        // global count inferred, identities recur, coverage intact
        assertEquals(setOf("speaker_1", "speaker_2", "speaker_3"), turns.map { it.speakerId }.toSet())
        assertEquals(0, turns.first().startMs)
        assertEquals(6000, turns.last().endMs)
    }
}
