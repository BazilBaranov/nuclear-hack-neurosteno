package dev.neurostenografist.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UserTextTest {
    @Test
    fun cyrillicFileNameDoesNotMakeAnEnglishErrorLocalized() {
        assertEquals("Нет доступа к файлу. Выберите его заново", userMessage("cannot open запись.wav"))
    }

    @Test
    fun localizedMessageIsPreserved() {
        assertEquals("Подготовьте модель Qwen", userMessage("Подготовьте модель Qwen"))
    }

    @Test
    fun stageNamesAreRussianAndUnknownStageDoesNotLeakInternalIdentifier() {
        assertEquals("Определение участников", stageLabel("diarizing"))
        assertEquals("Готово", stageLabel("completed"))
        assertEquals("Обработка", stageLabel("custom_native_backend_stage"))
    }

    @Test
    fun warningsExplainLimitsInRussian() {
        assertEquals("Число участников определено автоматически; ошибки разделения голосов возможны", warningLabel("speaker clustering is automatic and not calibrated"))
    }
    @Test
    fun backendDiagnosticsAreNotReportedAsFailures() {
        assertEquals("Диагностика: ASR: CPU", warningLabel("ASR: CPU"))
    }

    @Test
    fun overlapFallbackKeepsItsActionableLimitation() {
        assertEquals("Одновременная речь: недостаточно чистой речи; проверьте аудио", warningLabel("speaker overlap 2.0s: недостаточно чистой речи; проверьте аудио"))
    }

    @Test
    fun languagesAndEnhancementStageAreLocalized() {
        assertEquals("русский", languageLabel("ru"))
        assertEquals("английский", languageLabel("en"))
        assertEquals("Очистка звука", stageLabel("enhancing"))
        assertEquals("Повтор: Очистка звука", stageLabel("retry_enhancement"))
    }

}
