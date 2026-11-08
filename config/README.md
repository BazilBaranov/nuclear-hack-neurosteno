# Конфигурация Android-проекта

Секреты и облачные ключи для расшифровки не нужны.

| Параметр | Где задан |
|---|---|
| SDK, ABI и типы сборки | [app/build.gradle.kts](../app/build.gradle.kts) |
| Память Gradle и Kotlin | [gradle.properties](../gradle.properties) |
| Зависимости native runtime | [CMakeLists.txt](../app/src/main/cpp/nemotron/CMakeLists.txt) |
| Модели, URL, размеры, SHA-256 | [ModelCatalog.kt](../app/src/main/java/dev/neurostenografist/inference/ModelCatalog.kt) |
| Android SDK локальной машины | `local.properties`, не включён в Git |

Для сборки задайте `JAVA_HOME`, `ANDROID_HOME` и `VULKAN_SDK` по
[инструкции](../docs/INSTALLATION.md#сборка-из-исходников).
Настройки языка, моделей, обработки и оформления задаются внутри приложения.
Веса загружаются отдельно; приложение не требует `.env` и серверной части.
