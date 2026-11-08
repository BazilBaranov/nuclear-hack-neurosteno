# Лицензии GTCRN, VoxLock и их рантайма

Проверено 7 ноября 2026 года по официальным репозиториям и страницам выпуска.
Оригинальные тексты включены в `app/src/main/assets/licenses`; URL, ревизии
снимков и SHA-256 текстов — в `speech-model-license-sources.json` в том же каталоге.

| Компонент | Что подтверждено источником | Что сохранено |
|---|---|---|
| GTCRN | [Официальный LICENSE](https://github.com/Xiaobin-Rong/gtcrn/blob/502ebfab64da7c4a9af78dcb9c6ceef1ebb01c73/LICENSE): MIT, © 2024 Rong Xiaobin. ONNX-файл находится в том же репозитории; отдельного разрешения именно для весов в просмотренных источниках не найдено. | `GTCRN-LICENSE.txt` |
| VoxLock | [Официальный выпуск ONNX](https://github.com/arbazsiddiqui/voxlock/releases/tag/sherpa-onnx-models) явно указывает Apache-2.0 для весов. [NOTICE](https://github.com/arbazsiddiqui/voxlock/blob/48b9a71e9b6ce853670c9a4dbc64ddb532638d53/NOTICE) описывает происхождение от REAL-TSE/WeSep и подтверждение автора исходных параметров. | `VoxLock-LICENSE.txt`, неизменённый `VoxLock-NOTICE.txt` |
| sherpa-onnx 2.0.1 | [Apache-2.0](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/LICENSE). | `sherpa-onnx-LICENSE.txt` |
| ONNX Runtime 1.28.2 | [MIT, © Microsoft](https://github.com/microsoft/onnxruntime/blob/v1.28.2/LICENSE), отдельные [third-party notices](https://github.com/microsoft/onnxruntime/blob/v1.28.2/ThirdPartyNotices.txt). | `onnxruntime-LICENSE.txt`, `onnxruntime-ThirdPartyNotices.txt` |

Для GTCRN происхождение release-файла подтверждают официальные sherpa-скрипты
[загрузки](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/scripts/gtcrn/run.sh)
и [добавления метаданных](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/scripts/gtcrn/add_meta_data.py).
Это основание для указания MIT-лицензии исходного репозитория, а не отдельное
документированное обещание автора о лицензии весов. Apache-лицензия рантайма
сама по себе не перелицензирует модель.

Приложение не меняет скачанные модельные байты: только локальные имена файлов.
Используемые файлы из `ModelCatalog.kt`:

| Локальное имя | Размер | SHA-256 |
|---|---:|---|
| `gtcrn.onnx` | 535638 | `e77603ac0c23dac3227dd2d7135b3a585cbee2679048aecfa886657d3ae1b534` |
| `enroll.onnx` | 12476014 | `5d06048c156bb8cd422d29848e6d38fb26a7b39f6f7fd92eccf2a6ed1ce003c6` |
| `chunk_int8.onnx` | 66608910 | `df61a9d0062b0adbcf1182302b1eb8b815f15ac47ea4923e8e164c1aff64121d` |

Сохранены авторские notices и полные условия. Для изменённой arm64-сборки sherpa
перечень изменений приведён в `SPEECH-MODELS-NOTICE.txt`; воспроизводимые патчи
находятся в `scripts/prepare-nnapi.sh` и `scripts/patch-sherpa-jni.py`.

Лицензии статических зависимостей фактически собранного arm64-рантайма скопированы
из исходных архивов в `assets/licenses/sherpa-dependencies`. Они не подменены
общей Apache-лицензией. Неизменённые исходники доступны по версиям ниже; точные
URL и контрольные суммы загрузок также заданы CMake-файлами sherpa 2.0.1:

| Зависимость | Исходная версия |
|---|---|
| Eigen, преимущественно MPL-2.0 | [5.0.1](https://gitlab.com/libeigen/eigen/-/archive/5.0.1/eigen-5.0.1.tar.gz) |
| hclust-cpp / fastcluster | [2026-02-25](https://github.com/csukuangfj/hclust-cpp/tree/2026-02-25) |
| nlohmann/json | [3.12.0](https://github.com/nlohmann/json/tree/v3.12.0) |
| kaldi-decoder | [0.3.0](https://github.com/k2-fsa/kaldi-decoder/tree/v0.3.0) |
| kaldi-native-fbank | [1.22.4](https://github.com/csukuangfj/kaldi-native-fbank/tree/v1.22.4) |
| kaldifst | [1.8.0](https://github.com/k2-fsa/kaldifst/tree/v1.8.0) |
| KISS FFT | [febd4cae](https://github.com/mborgerding/kissfft/tree/febd4caeed32e33ad8b2e0bb5ea77542c40f18ec) |
| OpenFst | [1.8.5-2026-07-09](https://github.com/csukuangfj/openfst/tree/v1.8.5-2026-07-09) |
| simple-sentencepiece | [0.7](https://github.com/pkufool/simple-sentencepiece/tree/v0.7) |
| utf8proc | [2.12.0](https://github.com/JuliaStrings/utf8proc/tree/v2.12.0) |

Этот перечень касается указанных моделей и arm64-рантайма. Он не утверждает,
что лицензия весов распространяется на обучающие/тестовые записи, и не заменяет
отдельный перечень остальных ASR/LLM-моделей проекта. Полный upstream NOTICE
VoxLock сохранён с упоминанием его демо; это не означает, что все эти демо
включены в APK или получили лицензию весов.
