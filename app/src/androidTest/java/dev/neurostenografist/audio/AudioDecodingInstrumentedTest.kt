package dev.neurostenografist.audio

import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Runs the real decoders on device: WAV through the JVM parser path, AAC/M4A through
 * the actual MediaCodec pipeline. Fixtures live in androidTest assets and are copied
 * into cacheDir so they get file URIs, exactly like the import screen will pass them.
 */
@RunWith(AndroidJUnit4::class)
class AudioDecodingInstrumentedTest {
    private fun assetFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Fixtures ship in the test APK: read them from the instrumentation context,
        // then write to the target (app) cacheDir so the decoder gets a normal file URI.
        val out = File(instrumentation.targetContext.cacheDir, name)
        instrumentation.context.assets.open("audio/$name").use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    private fun decode(name: String): FloatArray {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return AudioDecoder.decodeTo16k(context, android.net.Uri.fromFile(assetFile(name)))
    }

    @Test
    fun wav48kMonoAssetDecodesToStable16kTimeline() {
        val out = decode("sine48k_mono.wav")
        // 1 s at 48 kHz box-averaged to 16 kHz: exactly 16 000 frames.
        assertEquals(16_000, out.size)
        // Sine keeps energy; nothing is loud (unity scale) and nothing is silence.
        val peak = out.max()
        assertTrue("peak=$peak", peak in 0.05f..1.0f)
    }

    @Test
    fun wavLeadingNumeratorHeaderIsConsumedOnceOnRealStream() {
        // Real-stream regression: detection consumed 12 bytes, so the parser must see
        // RIFF at byte 0 of a fresh stream, not "WAVE" shifted to index 0.
        val out = decode("sine48k_mono.wav")
        assertEquals(16_000, out.size)
    }

    @Test
    fun aacM4aAssetDecodesThroughMediaCodecToStableDuration() {
        val fixture = assetFile("sine44k_mono.m4a")
        val extractor = MediaExtractor()
        val metadata = MediaMetadataRetriever()
        try {
            extractor.setDataSource(fixture.absolutePath)
            metadata.setDataSource(fixture.absolutePath)
            Log.i("NeuroAudioCodec", "AAC track=${extractor.getTrackFormat(0)}; " +
                "presentationMs=${metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)}; " +
                "mime=${metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)}")
        } finally {
            extractor.release()
            metadata.release()
        }
        val out = decode("sine44k_mono.m4a")
        // 44.1 k -> 16 k, 1 s: 16 000 frames ±1 for container/codec rounding.
        assertTrue("size=${out.size}", out.size in 15_999..16_001)
        val peak = out.max()
        assertTrue("peak=$peak", peak in 0.05f..1.0f)
        // Zero-crossing cadence of a 440 Hz sine: first near-silence appears early,
        // proving the head of the stream is present (no lost leading buffers).
        assertTrue("first=${out[0]}", abs(out[0]) < 0.05f)
        Log.i("NeuroAudioCodec", "AAC decodedSamples=${out.size}; first=${out.first()}; last=${out.last()}")
    }

    @Test
    fun missingFileFailsExplicitly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val missing = File(context.cacheDir, "no_such_audio.wav")
        missing.delete()
        assertThrows(java.io.IOException::class.java) {
            AudioDecoder.decodeTo16k(context, android.net.Uri.fromFile(missing))
        }
    }
}
