package dev.neurostenografist

import dev.neurostenografist.core.SpeakerInterval
import dev.neurostenografist.core.Turn
import dev.neurostenografist.inference.OverlapPlan
import org.junit.Assert.*
import org.junit.Test

class OverlapPlanTest {
    @Test fun `overlaps retain both voices and first occurrence identities`() {
        val turns = OverlapPlan.turns(listOf(SpeakerInterval(9, 0.0, 5.0), SpeakerInterval(2, 3.0, 8.0)), 8000)
        assertEquals(listOf(Turn("speaker_1", 0, 5000, ""), Turn("speaker_2", 3000, 8000, "")), turns)
        assertEquals(listOf(0L to 3000L), OverlapPlan.enrollment(turns, "speaker_1"))
        assertEquals(listOf(5000L to 8000L), OverlapPlan.enrollment(turns, "speaker_2"))
    }
    @Test fun `enrollment never includes another speaker even across interruptions`() {
        val turns = listOf(Turn("a", 0, 10000, ""), Turn("b", 1000, 2000, ""), Turn("b", 3000, 9000, ""))
        assertEquals(listOf(0L to 1000L, 2000L to 3000L, 9000L to 10000L), OverlapPlan.enrollment(turns, "a"))
        assertEquals(emptyList<Pair<Long, Long>>(), OverlapPlan.enrollment(turns, "b"))
    }
    @Test fun `same speaker duplicates merge without inventing overlap`() {
        val turns = OverlapPlan.turns(listOf(SpeakerInterval(4, 1.0, 4.0), SpeakerInterval(4, 2.0, 5.0)), 4500)
        assertEquals(listOf(Turn("speaker_1", 1000, 4500, "")), turns)
    }
}
