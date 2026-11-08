# Доказательства тестирования

[Методика и результаты](../../docs/TEST_RESULTS.md).

- [results.csv](results.csv) — таблица измеренных времён и статусов.
- [summary.json](summary.json) — JVM suites, Lint, функциональные проверки.
- [honor-offline.json](honor-offline.json) — фактические модели, SHA-256, стадии, offline preflight.
- [honor-native.json](honor-native.json) — Qwen/Nemotron CPU и попытки Vulkan, включая отказ.
- [honor-onnx.json](honor-onnx.json) — реальные исполнители ONNX и отсутствие NNAPI-драйвера.

Это сокращённые отчёты из настоящих запусков 8 ноября 2026 года.
Из offline/ONNX выгрузки исключены UUID запусков, тексты стенограмм и локальные пути.
Числа и статусы не заменены. Полные исходные логи сохранены локально у команды.
Неудачный комбинированный GPU-прогон и пропуск Pixel-only теста описаны в отчёте;
их нельзя считать пройденными.
