package dev.neurostenografist

import dev.neurostenografist.core.ModelChoice
import dev.neurostenografist.core.ModelUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ModelChoiceTest {
    private val priority =
        mapOf(
            "ru" to listOf("zipformer-ru", "whisper-base", "whisper-tiny"),
            "en" to listOf("whisper-base", "whisper-tiny"),
        )

    @Test
    fun autoPicksFirstInstalledInRankedOrder() {
        val selected =
            ModelChoice.select(
                language = "ru",
                requestedId = "auto",
                installedIds = setOf("zipformer-ru", "whisper-base"),
                priority = priority,
            )
        assertEquals("zipformer-ru", selected)
    }

    @Test
    fun autoFallsBackWhenPreferredModelMissing() {
        val selected =
            ModelChoice.select(
                language = "ru",
                requestedId = "auto",
                installedIds = setOf("whisper-tiny"),
                priority = priority,
            )
        assertEquals("whisper-tiny", selected)
    }

    @Test
    fun autoNeverPersistsTheAutoPlaceholder() {
        val selected =
            ModelChoice.select(
                language = "en",
                requestedId = "auto",
                installedIds = setOf("whisper-base"),
                priority = priority,
            )
        assertNotEquals(ModelChoice.AUTO, selected)
        assertEquals("whisper-base", selected)
    }

    @Test(expected = ModelUnavailableException::class)
    fun autoWithoutAnyInstalledModelFails() {
        ModelChoice.select("ru", "auto", emptySet(), priority)
    }

    @Test
    fun manualCompatibleIdStaysUnchanged() {
        val selected =
            ModelChoice.select(
                language = "en",
                requestedId = "whisper-tiny",
                installedIds = setOf("whisper-base", "whisper-tiny"),
                priority = priority,
            )
        assertEquals("whisper-tiny", selected)
    }

    @Test(expected = ModelUnavailableException::class)
    fun manualIncompatibleIdFailsInsteadOfSubstituting() {
        ModelChoice.select("ru", "gigam-v3", setOf("gigam-v3", "zipformer-ru"), priority)
    }
}
