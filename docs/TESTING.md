# Проверки проекта

Актуальные результаты и границы проверки: [VALIDATION.md](VALIDATION.md).
Сначала выполните подготовку NNAPI/Vulkan из [README](../README.md).

## Сборка и JVM

```bash
./scripts/build.sh :app:testOptimizedUnitTest :app:lintOptimized
./scripts/build.sh :app:assembleOptimized
./scripts/verify-delivery.sh app/build/outputs/apk/optimized/app-optimized.apk arm64-v8a
python3 tests/evaluation/test_evaluate.py
bash scripts/source-fingerprint.sh --self-check
```

`verify-delivery.sh` проверяет ABI, наличие native runtime, отсутствие весов в APK,
контрольные суммы fixtures и записывает SHA-256 APK и fingerprint исходников в `artifacts/`.
Сам fingerprint не доказывает происхождение APK: сначала соберите текущие исходники.
Для `-PappAbi=universal` передайте проверке два аргумента `arm64-v8a x86_64` вместо `arm64-v8a`.

## Физическое Android-устройство

```bash
./scripts/build.sh :app:assembleDebug :app:assembleDebugAndroidTest
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w -r -e class dev.neurostenografist.ui.NavigationTest dev.neurostenografist.ultra.test/androidx.test.runner.AndroidJUnitRunner
```

Для сборки только ARM64 можно добавить `-Pandroid.injected.build.abi=arm64-v8a`.
В этом случае APK находятся в `app/build/intermediates/apk/`, а установка debug APK
требует `adb install -r -t`. Обычная optimized-сборка не требует `-t`.

UI-проверки: `NavigationTest`, `ExportIoTest`, `PermissionUiTest`, `SettingsUiTest`.
Декодирование WAV/AAC на Android: `audio.AudioDecodingInstrumentedTest`.
`ResultPerformanceProbeTest` создаёт и удаляет синтетический результат на 2000 реплик;
он проверяет навигацию и пишет времена кадров в logcat, но не измеряет качество ASR.

## Настоящие модели

Русский ASR — GigaAM-v3; английский — Whisper base; Whisper tiny используется для LID.
Перед модельными тестами скачайте и проверьте нужные модели через приложение.
Положите контрольные записи `files/validation.wav` (русская речь) и
`files/validation-ami.wav` (AMI, английская речь) в приватную папку debug-приложения.
Без подготовленных записей полный acceptance не пройдёт.

- `inference.NativeBackendProbeTest`: Qwen CPU/GPU, Nemotron CPU/GPU и сравнение
  диаризации с CPU. Наличие устройства Vulkan не доказывает исполнение модели.
  Проверяйте `actualGpu`, результат и совпадение с CPU. При зависании драйвера
  завершите только тестовый процесс; не запускайте такой путь по умолчанию.
  На Honor явные GPU-пробы запускайте отдельно по `Class#method`: последовательность
  Qwen GPU → Nemotron GPU в одном процессе зависала. Регрессию CPU-default запускайте
  отдельно как `NativeBackendProbeTest#f_honorDefaultPunctuationAvoidsFailedVulkanDriver`.
- `acceptance.OnnxBackendProbeTest`: профиль исполнителей LID/ASR/диаризации.
  Pixel-специфичная проверка GigaAM пропускается на других устройствах.
- `acceptance.UltraAcceptanceTest` с `-e qualityModels true`: реальные RU/EN,
  повтор отдельной реплики и тишина. Требует отключения всех физических сетей.
  Сохраните настройки связи перед тестом и восстановите после него.

JSON-отчёты находятся в `files/native-backend-probe.json`, `files/onnx-backend-probe.json`
и `files/ultra-acceptance.json`; доступны через `adb exec-out run-as dev.neurostenografist.ultra cat …`
только в debug-сборке. Сохраняйте их локально в `artifacts/`, не в репозитории.

## Метрики и ограничения

`tests/fixtures/` содержит восемь синтетических CC0 WAV для проверки схемы и метрик.
Они не являются речевым корпусом: WER на них не подтверждает точность распознавания.
`tests/evaluation/evaluate.py` считает WER, DER с collar 150 мс и RTF;
для оценки качества нужны настоящая речь и независимая ручная разметка.
Скорость, число тестов и аппаратное ускорение всегда относятся к конкретному запуску.
