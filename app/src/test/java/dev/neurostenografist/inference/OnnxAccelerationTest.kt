package dev.neurostenografist.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnnxAccelerationTest {
    @Test
    fun cpuDriverDoesNotCountAsHardwareAcceleration() {
        val selection = selectOnnxBackend(true, 35, "arm64-v8a", listOf(NnapiDevice("reference-cpu", 2)))
        assertEquals("cpu", selection.provider)
        assertFalse(selection.requestedNnapi)
    }

    @Test
    fun gpuOrNpuDriversPermitNnapiButDoNotProveModelOffload() {
        for (type in listOf(3, 4)) {
            val selection = selectOnnxBackend(true, 35, "arm64-v8a", listOf(NnapiDevice("vendor-driver", type)))
            assertEquals("nnapi", selection.provider)
            assertTrue(selection.requestedNnapi)
            assertTrue(selection.description.contains("запрошен"))
        }
    }

    @Test
    fun unsupportedBuildAndExplicitCpuRemainCpu() {
        val drivers = listOf(NnapiDevice("accelerator", 4))
        assertEquals("cpu", selectOnnxBackend(true, 28, "arm64-v8a", drivers).provider)
        assertEquals("cpu", selectOnnxBackend(true, 35, "x86_64", drivers).provider)
        assertEquals("cpu", selectOnnxBackend(false, 35, "arm64-v8a", drivers).provider)
    }

    @Test
    fun profileEvidenceCountsRealNodeProvidersOnly() {
        val profile = """[
          {"cat":"Session","args":{"provider":"NnapiExecutionProvider"}},
          {"cat":"Node","args":{"provider":"NnapiExecutionProvider"}},
          {"cat":"Node","args":{"provider":"CPUExecutionProvider"}},
          {"cat":"Node","args":{}}
        ]"""
        assertEquals(OnnxProfileEvidence(1, 1), parseOnnxProfile(profile))
    }
}
