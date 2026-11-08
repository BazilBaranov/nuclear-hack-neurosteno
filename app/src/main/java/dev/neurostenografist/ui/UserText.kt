package dev.neurostenografist.ui

internal fun stageLabel(stage: String?): String = if (stage?.startsWith("retry_") == true) {
    "Повтор: ${stageLabel(stage.removePrefix("retry_"))}"
} else when (stage) {
    "created" -> "В очереди"
    "running" -> "Обработка"
    "completed" -> "Готово"
    "failed" -> "Ошибка"
    "cancelled" -> "Отменено"
    "interrupted" -> "Прервано"
    "decoding audio", "decode" -> "Подготовка аудио"
    "checking models" -> "Проверка моделей"
    "diarizing", "diarization" -> "Определение участников"
    "detecting language", "language" -> "Определение языка"
    "transcribing", "asr" -> "Распознавание речи"
    "punctuation", "punctuating", "llm" -> "Восстановление пунктуации"
    "denoise", "preprocessing", "enhancing", "enhancement" -> "Очистка звука"
    "separating", "separation" -> "Разделение голосов"
    "retry" -> "Повторное распознавание фрагмента"
    "saving recording" -> "Сохранение записи"
    "recording" -> "Запись"
    "downloading" -> "Загрузка"
    "installing" -> "Установка"
    null -> "Подготовка"
    else -> if (stage.any { it in 'А'..'я' || it == 'ё' }) stage else "Обработка"
}

/** Technical details remain in persisted diagnostics, while user-facing errors are Russian. */
internal fun userMessage(message: String?): String {
    if (message.isNullOrBlank()) return "Неизвестная ошибка"
    if (message.firstOrNull { it.isLetter() }?.let { it in 'А'..'я' || it == 'ё' || it == 'Ё' } == true) return message
    return when {
        message.contains("permission", true) || message.contains("access", true) || message.contains("cannot open", true) -> "Нет доступа к файлу. Выберите его заново"
        message.contains("space", true) -> "Недостаточно свободного места на устройстве"
        message.contains("checksum", true) || message.contains("size mismatch", true) -> "Файл модели повреждён. Загрузите модель заново"
        message.contains("download", true) || message.contains("HTTP", true) -> "Не удалось загрузить модель. Проверьте соединение и повторите"
        message.contains("language", true) -> "Не удалось надёжно определить поддерживаемый язык записи (русский или английский)"
        message.contains("short", true) -> "Аудиофрагмент слишком короткий для выбранной модели"
        message.contains("unsupported", true) || message.contains("encoding", true) -> "Этот формат аудио или модель не поддерживается"
        message.contains("cancel", true) -> "Обработка отменена"
        message.contains("restart", true) || message.contains("interrupted", true) -> "Обработка прервана после перезапуска приложения"
        message.contains("model", true) || message.contains("bundle", true) -> "Модель недоступна. Проверьте её загрузку в разделе «Модели»"
        else -> "Не удалось выполнить операцию. Проверьте исходный файл и загруженные модели"
    }
}

internal fun warningLabel(message: String): String = when {
    message.contains("clustering", true) -> "Число участников определено автоматически; ошибки разделения голосов возможны"
    message.contains("overlap", true) && message.contains("недостаточно чистой речи") -> "Одновременная речь: ${message.substringAfter(": ")}"
    message.contains("overlap", true) -> "Обнаружена одновременная речь. Проверьте разделение голосов и принадлежность реплик"
    message.contains("Nemotron", true) && message.contains("experimental", true) -> "Nemotron: качество, память и скорость на этом устройстве ещё не измерены"
    message.contains("empty ASR", true) -> "Некоторые фрагменты не дали распознанного текста"
    else -> if (message.firstOrNull { it.isLetter() }?.let { it in 'А'..'я' || it == 'ё' || it == 'Ё' } == true) message else "Диагностика: $message"
}

internal fun languageLabel(code: String): String = when (code) {
    "ru" -> "русский"
    "en" -> "английский"
    "*", "multilingual" -> "несколько языков"
    else -> code
}
