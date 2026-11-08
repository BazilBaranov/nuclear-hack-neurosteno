package dev.neurostenografist.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnnxAccelerationTest {
    @Test
    fun cpuDriverDoesNotCountAsHardwareAcceleration() {
        val selection = selectOnnxBackend(true, 33, "arm64-v8a", listOf(NnapiDevice("reference-cpu", 2)))
        assertEquals("cpu", selection.provider)
        assertFalse(selection.requestedNnapi)
    }

    @Test
    fun gpuOrNpuDriversPermitNnapiButDoNotProveModelOffload() {
        for (type in listOf(3, 4)) {
            val selection = selectOnnxBackend(true, 33, "arm64-v8a", listOf(NnapiDevice("vendor-driver", type)))
            assertEquals("nnapi", selection.provider)
            assertTrue(selection.requestedNnapi)
            assertTrue(selection.description.contains("запрошен"))
        }
    }

    @Test
    fun unsupportedBuildAndExplicitCpuRemainCpu() {
        val drivers = listOf(NnapiDevice("accelerator", 4))
        assertEquals("cpu", selectOnnxBackend(true, 27, "arm64-v8a", drivers).provider)
        assertEquals("cpu", selectOnnxBackend(true, 33, "x86", drivers).provider)
        assertEquals("cpu", selectOnnxBackend(false, 33, "arm64-v8a", drivers).provider)
    }

    @Test
    fun android9CanRequestNnapiWithoutDriverEnumeration() {
        for (abi in listOf("arm64-v8a", "x86_64")) {
            assertEquals("nnapi", selectOnnxBackend(true, 28, abi, emptyList()).provider)
        }
    }

    @Test
    fun android14AndLaterNeverRequestNnapi() {
        val drivers = listOf(NnapiDevice("accelerator", 4))
        for (api in listOf(34, 35, 36)) {
            assertEquals("cpu", selectOnnxBackend(true, api, "arm64-v8a", drivers).provider)
        }
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
