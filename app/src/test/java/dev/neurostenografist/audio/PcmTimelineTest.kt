package dev.neurostenografist.audio

import dev.neurostenografist.audio.decoder.PcmSlice
import dev.neurostenografist.audio.decoder.StreamingPcm16k
import dev.neurostenografist.audio.decoder.pcmPresentationSlice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmTimelineTest {
    @Test fun aacPrimingAndLastPartialPacketPreserveExactlyThePresentedSecond() {
        // Fixture: MediaCodec has already removed the 1024-sample priming.
        // 44 payload packets remain; the last has only 68 presented samples
        // although the AAC decoder returns all 1024 (old output: 16347 at 16k).
        val expected = FloatArray(44_100) { 0.1f + it / 1_000_000f }
        val sink = StreamingPcm16k(44_100, 1)
        for (packet in 0..43) {
            val start = packet * 1024
            val pcm = FloatArray(1024) { index -> expected.getOrElse(start + index) { -0.8f } }
            val pts = (start * 1_000_000L / 44_100)
            val slice = pcmPresentationSlice(pcm.size, 1, 44_100, pts, 1_000_000)
            sink.append(pcm, slice.offset, slice.length)
        }
        val actual = sink.finish()
        assertEquals(16_000, actual.size)
        assertArrayEquals(AudioDecoder.resampleTo16k(expected, 44_100), actual, 1e-7f)
    }

    @Test fun alreadyTrimmedPrimingDoesNotRemoveSpeechTwice() {
        assertEquals(PcmSlice(0, 1024), pcmPresentationSlice(1024, 1, 44_100, 0, 1_000_000))
        // PTS truncation to integer microseconds must not lose an extra sample.
        assertEquals(PcmSlice(0, 68), pcmPresentationSlice(1024, 1, 44_100, 998_458, 1_000_000))
    }

    @Test fun partialLeadingAndTrailingStereoFramesKeepChannelPairs() {
        assertEquals(PcmSlice(4, 6), pcmPresentationSlice(12, 2, 16_000, -125, 188))
    }

    @Test fun absentDurationKeepsAllPresentedSamplesAndSilence() {
        assertEquals(PcmSlice(0, 4096), pcmPresentationSlice(4096, 2, 44_100, 3_000_000, null))
        assertEquals(PcmSlice(1024, 1024), pcmPresentationSlice(2048, 1, 44_100, -23_219, null))
    }

    @Test fun buffersEntirelyOutsidePresentationWindowAreEmpty() {
        assertEquals(0, pcmPresentationSlice(1024, 1, 44_100, -100_000, 1_000_000).length)
        assertEquals(0, pcmPresentationSlice(1024, 1, 44_100, 1_000_000, 1_000_000).length)
    }
}
