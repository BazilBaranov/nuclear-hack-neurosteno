package dev.neurostenografist.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPunctuationTest {
    @Test fun addsPunctuationWithoutChangingWords() {
        assertEquals("Привет, как дела?", PunctuationGuard.accept("привет как дела", "Привет, как дела?"))
    }

    @Test fun rejectsAddedDroppedReorderedAndRewrittenWords() {
        val original = "я уже отправил письмо"
        listOf("Я уже отправил письмо сегодня.", "Я отправил письмо.", "Письмо я уже отправил.", "Я уже отправила письмо.")
            .forEach { assertEquals(original, PunctuationGuard.accept(original, it)) }
    }

    @Test fun preservesNumbersAndHyphenatedWords() {
        assertEquals("цена 1.5", PunctuationGuard.accept("цена 1.5", "Цена 1,5."))
        assertEquals("температура -10", PunctuationGuard.accept("температура -10", "Температура 10."))
        assertEquals("доля 10%", PunctuationGuard.accept("доля 10%", "Доля 10."))
        assertEquals("кто-то здесь", PunctuationGuard.accept("кто-то здесь", "Кто то здесь?"))
        assertEquals("Заказ № 123 готов.", PunctuationGuard.accept("заказ № 123 готов", "Заказ № 123 готов."))
    }

    @Test fun rejectsPromptInjectionAndNonTextAdditions() {
        assertEquals("привет", PunctuationGuard.accept("привет", "<|im_start|>Привет!"))
        assertEquals("привет", PunctuationGuard.accept("привет", "Привет! 🙂"))
        assertEquals("привет", PunctuationGuard.accept("привет", ""))
        assertEquals("", PunctuationGuard.accept("", "Вы ничего не сказали."))
    }

    @Test fun splitsLongTextWithoutLosingWords() {
        val text = (1..200).joinToString(" ") { "слово$it" }
        val chunks = PunctuationGuard.chunks(text)
        assertEquals(text, chunks.joinToString(" "))
        assertTrue(chunks.all { it.length <= PunctuationGuard.MAX_CHARS })
    }

    @Test fun threadBudgetReservesRoomForUi() {
        assertEquals(1, HardwareAcceleration.cpuThreads(1))
        assertEquals(1, HardwareAcceleration.cpuThreads(2))
        assertEquals(4, HardwareAcceleration.cpuThreads(8))
    }
}
