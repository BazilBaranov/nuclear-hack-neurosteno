package dev.neurostenografist.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class OnnxSessionFallbackTest {
    @Test fun constructionFailureRetriesCpuAndPreservesDiagnosticConfig() {
        val providers = mutableListOf<String>()
        val reports = mutableListOf<String>()
        val session = OnnxSessionFallback("nnapi:/config", create = {
            providers += it
            if (it.startsWith("nnapi")) throw Exception("driver rejected")
            it
        }, release = {}, onBackendReport = { reports += it })
        assertEquals(listOf("nnapi:/config", "cpu:/config"), providers)
        assertEquals("cpu:/config", session.run { it })
        assertTrue(reports.single().contains("CPU fallback"))
        session.close()
    }

    @Test fun inferenceFailureClosesNnapiBeforeCpuAndRetriesOnlyOnce() {
        val events = mutableListOf<String>()
        val session = OnnxSessionFallback("nnapi", create = { events += "create $it"; it }, release = { events += "release $it" })
        assertEquals("cpu", session.run { events += "run $it"; if (it == "nnapi") error("run failed"); it })
        session.close()
        session.close()
        assertEquals(listOf("create nnapi", "run nnapi", "release nnapi", "create cpu", "run cpu", "release cpu"), events)
    }

    @Test(expected = CancellationException::class)
    fun cancellationNeverRetries() {
        OnnxSessionFallback("nnapi", create = { it }, release = {}).use { session ->
            session.run { throw CancellationException() }
        }
    }

    @Test(expected = IllegalStateException::class)
    fun cpuFailureIsNotHidden() {
        OnnxSessionFallback("cpu", create = { error("bad model") }, release = {})
    }
}
