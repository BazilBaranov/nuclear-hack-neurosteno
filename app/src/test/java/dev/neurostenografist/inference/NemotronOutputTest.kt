package dev.neurostenografist.inference

import org.junit.Assert.*
import org.junit.Test

class NemotronOutputTest {
    @Test fun retainsGlobalSpeakerIdsAndOriginalTimestamps() {
        val turns = decodeNemotronSegments(doubleArrayOf(7.0, 1.25, 3.0, 0.0, 2.0, 4.5), 5.0)
        assertEquals(listOf(7, 0), turns.map { it.speaker })
        assertEquals(1.25, turns[0].startSec, 0.0)
        assertEquals(4.5, turns[1].endSec, 0.0)
    }

    @Test fun silenceIsEmpty() {
        assertTrue(decodeNemotronSegments(doubleArrayOf(), 5.0).isEmpty())
    }

    @Test fun rejectsMalformedNativeResults() {
        for (raw in listOf(
            doubleArrayOf(0.0),
            doubleArrayOf(8.0, 0.0, 1.0),
            doubleArrayOf(0.5, 0.0, 1.0),
            doubleArrayOf(0.0, -1.0, 1.0),
            doubleArrayOf(0.0, 2.0, 1.0),
            doubleArrayOf(0.0, 0.0, Double.NaN),
            doubleArrayOf(0.0, 0.0, 6.0),
        )) {
            assertThrows(IllegalArgumentException::class.java) { decodeNemotronSegments(raw, 5.0) }
        }
    }
}
