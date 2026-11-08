package dev.neurostenografist

import dev.neurostenografist.inference.AsrAdapterPlan
import dev.neurostenografist.inference.AsrBundleLayout
import dev.neurostenografist.inference.BundleFile
import dev.neurostenografist.inference.BundleKind
import dev.neurostenografist.inference.ModelBundle
import dev.neurostenografist.inference.ModelBundleException
import dev.neurostenografist.inference.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Unit policy for [AsrBundleLayout]: which bundle file shapes run on which sherpa
 * engine config, without loading native code. Real catalog bundles (whisper tiny,
 * legacy zipformer) and the exact member names proposed for downloaded bundles
 * (zipformer-ru-2025-04-20, whisper base, k2-fsa GigaAM-v3) must all resolve.
 */
class AsrBundleLayoutTest {
    private val dir = File("/data/bundles")

    private fun bundle(
        id: String,
        vararg names: String,
    ) = ModelBundle(
        id = id,
        kind = BundleKind.ASR,
        displayName = id,
        languages = listOf("ru"),
        assetDir = "models/$id",
        files = names.map { BundleFile(it, "sha256-x", 1L) },
    )

    private fun assertThrows(
        names: List<String>,
        expectedPart: String,
    ) {
        try {
            AsrBundleLayout.plan(bundle("test-bundle", *names.toTypedArray()), dir)
            fail("expected ModelBundleException for $names")
        } catch (e: ModelBundleException) {
            assertTrue("message '${e.message}' should mention '$expectedPart'", e.message!!.contains(expectedPart))
        }
    }

    @Test
    fun `whisper tiny from real catalog resolves whisper pair with pinned paths`() {
        val plan = AsrBundleLayout.plan(ModelCatalog.byId(ModelCatalog.WHISPER_TINY), dir)
        assertTrue(plan is AsrAdapterPlan.Whisper)
        plan as AsrAdapterPlan.Whisper
        assertEquals(File(dir, "tiny-encoder.int8.onnx"), plan.encoderFile)
        assertEquals(File(dir, "tiny-decoder.int8.onnx"), plan.decoderFile)
        assertEquals(File(dir, "tiny-tokens.txt"), plan.tokensFile)
    }

    @Test
    fun `legacy zipformer from real catalog resolves transducer triple`() {
        val plan = AsrBundleLayout.plan(ModelCatalog.byId(ModelCatalog.ZIPFORMER_RU), dir)
        assertTrue(plan is AsrAdapterPlan.Transducer)
        plan as AsrAdapterPlan.Transducer
        assertEquals(File(dir, "encoder.int8.onnx"), plan.encoderFile)
        assertEquals(File(dir, "decoder.int8.onnx"), plan.decoderFile)
        assertEquals(File(dir, "joiner.int8.onnx"), plan.joinerFile)
        assertEquals(File(dir, "tokens.txt"), plan.tokensFile)
    }

    @Test
    fun `downloaded zipformer 2025 member names resolve transducer triple`() {
        val plan =
            AsrBundleLayout.plan(
                bundle(
                    "zipformer-ru-2025-04-20",
                    "encoder.int8.onnx",
                    "decoder.int8.onnx",
                    "joiner.int8.onnx",
                    "tokens.txt",
                ),
                dir,
            )
        assertTrue(plan is AsrAdapterPlan.Transducer)
    }

    @Test
    fun `gigaam v3 member names resolve transducer triple with fp32 decoder and joiner`() {
        val plan =
            AsrBundleLayout.plan(
                bundle(
                    "gigaam-v3-rnnt-ru",
                    "encoder.int8.onnx",
                    "decoder.onnx",
                    "joiner.onnx",
                    "tokens.txt",
                ),
                dir,
            )
        plan as AsrAdapterPlan.Transducer
        assertEquals(File(dir, "encoder.int8.onnx"), plan.encoderFile)
        assertEquals(File(dir, "decoder.onnx"), plan.decoderFile)
        assertEquals(File(dir, "joiner.onnx"), plan.joinerFile)
        assertEquals(File(dir, "tokens.txt"), plan.tokensFile)
    }

    @Test
    fun `whisper base member names resolve whisper pair`() {
        val plan =
            AsrBundleLayout.plan(
                bundle(
                    "whisper-base-multilingual",
                    "base-encoder.int8.onnx",
                    "base-decoder.int8.onnx",
                    "base-tokens.txt",
                ),
                dir,
            )
        plan as AsrAdapterPlan.Whisper
        assertEquals(File(dir, "base-encoder.int8.onnx"), plan.encoderFile)
        assertEquals(File(dir, "base-decoder.int8.onnx"), plan.decoderFile)
        assertEquals(File(dir, "base-tokens.txt"), plan.tokensFile)
    }

    @Test
    fun `bundle without tokens file is a loud error`() {
        assertThrows(listOf("encoder.int8.onnx", "decoder.onnx", "joiner.onnx"), "tokens")
    }

    @Test
    fun `bundle with two encoders is a loud error`() {
        assertThrows(
            listOf("encoder.int8.onnx", "base-encoder.int8.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"),
            "encoder",
        )
    }

    @Test
    fun `bundle with no asr model files is a loud error`() {
        assertThrows(listOf("segmentation.int8.onnx", "titanet-small.onnx"), "tokens")
    }
}
