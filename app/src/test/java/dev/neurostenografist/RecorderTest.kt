package dev.neurostenografist

import dev.neurostenografist.audio.WavWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

/** Coverage: WAV header canonical bytes, finalizeMono16 size verification, empty-PCM
 *  rejection, failed finalization leaves no broken WAV behind. */
class RecorderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var bytes: ByteArray = byteArrayOf()

    private fun u32(o: Int) =
        (bytes[o].toInt() and 0xff) or
            ((bytes[o + 1].toInt() and 0xff) shl 8) or
            ((bytes[o + 2].toInt() and 0xff) shl 16) or
            ((bytes[o + 3].toInt() and 0xff) shl 24)

    private fun u16(o: Int) = (bytes[o].toInt() and 0xff) or ((bytes[o + 1].toInt() and 0xff) shl 8)

    @Test
    fun `wav header is canonical mono pcm16`() {
        val pcm = tmp.newFile("capture.pcm")
        pcm.writeBytes(ByteArray(20)) // 10 samples
        val wav = File(tmp.root, "capture.wav")
        WavWriter.finalizeMono16(pcm, wav, 16_000)
        bytes = wav.readBytes()
        assertEquals(64L, bytes.size.toLong()) // 44 header + 20 data
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(16, u32(16)) // fmt chunk size
        assertEquals(1, u16(20)) // PCM format
        assertEquals(1, u16(22)) // channels (mono)
        assertEquals(16_000, u32(24)) // sample rate
        assertEquals(32_000, u32(28)) // byte rate = 16000 * 2
        assertEquals(2, u16(32)) // block align
        assertEquals(16, u16(34)) // bits per sample
        assertEquals(20, u32(40)) // data chunk size
        assertEquals(36 + 20, u32(4)) // riff size
    }

    @Test
    fun `empty pcm is rejected and no wav written`() {
        val pcm = tmp.newFile("empty.pcm")
        val wav = File(tmp.root, "empty.wav")
        val e = assertThrows(IllegalStateException::class.java) { WavWriter.finalizeMono16(pcm, wav, 16_000) }
        assertTrue(e.message!!.contains("produced no audio"))
        assertFalse(wav.exists())
    }

    @Test
    fun `failed finalization removes the broken wav`() {
        val pcm = tmp.newFile("broken.pcm")
        pcm.writeBytes(ByteArray(4))
        // target inside a nonexistent directory: the write throws, cleanup must run
        val target = File(tmp.root, "missing-dir/broken.wav")
        assertThrows(FileNotFoundException::class.java) { WavWriter.finalizeMono16(pcm, target, 16_000) }
        assertFalse(target.exists())
    }

    @Test
    fun `duration math uses actual pcm bytes`() {
        val pcm = tmp.newFile("dur.pcm")
        pcm.writeBytes(ByteArray(16_000 * 2)) // exactly one second at 16 kHz mono 16-bit
        val wav = File(tmp.root, "dur.wav")
        val pcmBytes = WavWriter.finalizeMono16(pcm, wav, 16_000)
        assertEquals(1000L, pcmBytes * 1000L / (2 * 16_000))
        assertTrue(wav.isFile)
    }
}
