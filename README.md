# Nuclear Hack: НейроСтенограф

Android-приложение для локальной расшифровки разговоров на русском и английском.
Русский — GigaAM-v3, английский — Whisper; диаризация, временные метки,
редактирование реплик и экспорт JSON. По выбору доступны GTCRN, VoxLock
и локальная Qwen 3.5 0.8B для пунктуации. После загрузки моделей сеть не нужна.

## Сборка

Нужны Linux x86_64, JDK 17, Android SDK 35, build-tools 35.0.0,
NDK 28.2.13676358, CMake 3.22+, Ninja, Python 3, curl, unzip, patch и dpkg-deb.
Задайте `JAVA_HOME` и `ANDROID_HOME`. `local.properties` хранится только локально.

```bash
export VULKAN_SDK="$PWD/artifacts/build/vulkan-tools/usr"

./scripts/prepare-nnapi.sh &&
  ./scripts/prepare-vulkan-tools.sh &&
  ./scripts/build.sh :app:assembleOptimized
```

APK: `app/build/outputs/apk/optimized/app-optimized.apk` (arm64-v8a).
Для x86_64 задайте `-PappAbi=x86_64`, для обеих архитектур — `-PappAbi=universal`.
APK подписан локальным debug-ключом, но не содержит debug-инструментации.
Обновление существующей установки требует того же сертификата.

```bash
adb install -r app/build/outputs/apk/optimized/app-optimized.apk
```

В приложении загрузите нужные модели, импортируйте аудио или создайте запись,
выберите язык и обработку, затем запустите расшифровку. Основные модели для русского:
GigaAM-v3 и выбранный диаризатор. Для автоматического языка нужен Whisper tiny.
Дополнительные модели нужны только при включении соответствующих функций.

## Проверки

```bash
./scripts/build.sh :app:testOptimizedUnitTest :app:lintOptimized
./scripts/build.sh :app:assembleDebug :app:assembleDebugAndroidTest
./scripts/verify-delivery.sh app/build/outputs/apk/optimized/app-optimized.apk arm64-v8a
```

Инструментационные проверки настоящих моделей требуют подготовленного телефона;
offline-тесты дополнительно требуют отсутствия сети. См. [тестирование](docs/TESTING.md) и [результаты проверки переноса](docs/VALIDATION.md).

## Ускорение и ограничения

CPU — переносимый путь. Vulkan и NNAPI выбираются по доступности и настройкам;
при поддерживаемых ошибках выполняется повтор на CPU. Наличие GPU/NPU в телефоне
не гарантирует совместимость графа или выигрыш скорости. GigaAM NNAPI на Pixel
исполняет часть энкодера аппаратно, но известный холодный запуск медленнее CPU.
Исправленный Nemotron Vulkan включён по умолчанию только на проверенном Pixel 9 Pro.
На Honor 200 Qwen и Nemotron по умолчанию работают на CPU: проверенный Adreno-драйвер
не выполнил эти модели через Vulkan, а аппаратный NNAPI-драйвер не обнаружен.

- [Архитектура](docs/ARCHITECTURE.md)
- [Модели](docs/MODELS.md), [лицензии моделей](docs/speech-model-licenses.md)
- [NNAPI](docs/onnx-acceleration.md), [Nemotron](docs/nemotron-android.md), [локальная LLM](docs/local-llm-runtime.md)
- [Ограничения](docs/LIMITATIONS.md), [исходные требования](docs/requirements.md)

Исходный код перенесён из ветки `plan` проекта
`nuclear-it-hack-2026/neurostenografist` (GitLab), коммит `e6987c4`.
История разработки, старые APK, измерительные дампы, веса и дублирующие проекты
не входят в этот репозиторий. Веса скачиваются по закреплённым URL и SHA-256.
Лицензии зависимостей сохранены в исходниках и ресурсах приложения.
