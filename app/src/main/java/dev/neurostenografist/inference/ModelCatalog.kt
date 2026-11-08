package dev.neurostenografist.inference

import dev.neurostenografist.core.AUTO_MODEL_ID
import dev.neurostenografist.core.Progress
import dev.neurostenografist.core.RunRequest
import dev.neurostenografist.core.SpeechLanguage

/**
 * Explicit catalog of the model bundles this build supports. Unknown or untested
 * downloaded architectures are deliberately absent: they must never be selectable.
 *
 * Model weights are NOT packaged in the APK. Every downloadable bundle pins a
 * concrete public HTTPS revision — Hugging Face `resolve/<commit-sha>` URLs, which
 * are immutable per revision — plus the exact SHA-256 and byte size of every
 * member; [ModelStore] downloads and verifies them into app-private storage.
 * Inference stays fully local/offline after the download; there is no server
 * fallback at any stage. The one legacy APK-asset bundle without a per-file
 * public source (see [ZIPFORMER_RU]) is not downloadable and remains usable only
 * from a private-storage copy installed by an older build.
 */

/** One file inside a bundle; size and sha256 must exactly match the pinned source.
 *  `downloadUrl` overrides the bundle-level URL for members whose public filename
 *  differs from the local one (e.g. remote `decoder.onnx` saved as
 *  `decoder.int8.onnx`) or that live on a different host; empty means the member
 *  is fetched from `ModelBundle.downloadBaseUrl + "/" + name`. */
data class BundleFile(
    val name: String,
    val sha256: String,
    val bytes: Long,
    val downloadUrl: String = "",
)

enum class BundleKind {
    ASR,
    DIARIZATION,
    LLM,
    ENHANCEMENT,
    ISOLATION,
    ACCELERATION,
}

data class ModelBundle(
    val id: String,
    val kind: BundleKind,
    val displayName: String,
    /** BCP-47-ish language codes this bundle supports (ASR only; diarization is language-independent). */
    val languages: List<String>,
    val assetDir: String,
    val files: List<BundleFile>,
    /** Pinned HTTPS prefix for members without their own [BundleFile.downloadUrl]; member URL = this + "/" + name. */
    val downloadBaseUrl: String = "",
) {
    val totalBytes: Long = files.sumOf { it.bytes }

    /** Pinned HTTPS URL for one member: the per-file override, else base + member name; "" when none. */
    fun memberUrl(file: BundleFile): String =
        file.downloadUrl.ifEmpty {
            if (downloadBaseUrl.isEmpty()) "" else "$downloadBaseUrl/${file.name}"
        }

    /** True only when every member has a pinned public download source. */
    val downloadable: Boolean
        get() = files.isNotEmpty() && files.all { memberUrl(it).isNotEmpty() }
}

/** Raised for actionable model availability problems. */
class ModelBundleException(
    message: String,
) : IllegalStateException(message)

object ModelCatalog {
    const val WHISPER_TINY = "whisper-tiny-multilingual"
    const val WHISPER_BASE = "whisper-base-multilingual"
    const val WHISPER_SMALL = "whisper-small-multilingual"
    const val ZIPFORMER_RU = "zipformer-ru-small"
    const val ZIPFORMER_RU_2025 = "zipformer-ru-2025-04-20"
    const val DIARIZATION = "diarization-pyannote-seg3"
    const val GIGAAM_V3 = "gigaam-v3-rnnt-ru"
    const val PUNCTUATION = "qwen3.5-0.8b-punctuation"
    const val PUNCTUATION_LITERT = "qwen3.5-0.8b-punctuation-litert"
    private const val LEGACY_PUNCTUATION = "qwen2.5-0.5b-punctuation"
    const val DENOISE = "gtcrn-denoise"
    const val ISOLATION = "voxlock-isolation"
    const val WHISPER_TINY_LITERT = "whisper-tiny-litert"
    const val PYANNOTE_LITERT = "pyannote-wespeaker-litert"
    const val NEMOTRON_LITERT = "diarization-nemotron-3-litert"
    const val NEMOTRON = "diarization-nemotron-3"

    // Pinned public HTTPS revisions (Hugging Face `resolve/<sha>`). Hashes/sizes were
    // verified against the LFS object ids of these exact revisions; do not bump to a
    // moving reference ("main") — that would silently change model bytes.
    private const val WHISPER_TINY_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/65176e2deb88badc814a94058666cadccc29b61c"
    private const val WHISPER_BASE_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/bb53ee204431c90d314c1cc08d28d23e5b7927cc"
    private const val WHISPER_SMALL_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/8f3c18b358db4d1f2fc1eae49d75cd20989e4309"
    private const val ZIPFORMER_RU_2025_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20/resolve/641de8d322c05b9087ad2927ccda4bda3cccc159"

    /**
     * Auto priority per detected language; ranked, preferred-first. Selection is
     * always made against the *ready* (installed+verified) set only, so entries not
     * yet downloaded are simply skipped. Manual compatibility comes from each bundle.
     */
    val autoPriority: Map<String, List<String>> =
        mapOf(
            "ru" to listOf(GIGAAM_V3, ZIPFORMER_RU_2025, WHISPER_BASE, WHISPER_SMALL, WHISPER_TINY),
            "en" to listOf(WHISPER_BASE, WHISPER_SMALL, WHISPER_TINY),
        )

    val supportedLanguages = autoPriority.keys.toList()

    val bundles: List<ModelBundle> =
        listOf(
            ModelBundle(
                id = PUNCTUATION, kind = BundleKind.LLM,
                displayName = "Пунктуация — Qwen 3.5 0.8B (локальная LLM)",
                languages = supportedLanguages, assetDir = "models/punctuation",
                downloadBaseUrl = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/ed4bdcdbcce9f095bb49e8759176c64e45fa294b",
                files = listOf(BundleFile("Qwen3.5-0.8B-Q4_K_M.gguf",
                    "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517", 532_517_120)),
            ),
            ModelBundle(
                id = PUNCTUATION_LITERT, kind = BundleKind.ACCELERATION,
                displayName = "Пунктуация — Qwen 3.5 0.8B LiteRT (Android 14+)",
                languages = supportedLanguages, assetDir = "models/punctuation-litert",
                downloadBaseUrl = "https://huggingface.co/litert-community/Qwen3.5-0.8B/resolve/48d30958f97dffb6864d755d8ae342794d07556c",
                files = listOf(BundleFile("Qwen3.5-0.8B_int8.litertlm",
                    "25447e2550082aed6145bbc755ec4da5d535f98f6a0c386865fdae2ccb88aed1", 964_566_992)),
            ),
            ModelBundle(
                id = WHISPER_TINY_LITERT, kind = BundleKind.ACCELERATION,
                displayName = "Whisper tiny — LiteRT-энкодер (Android 14+)",
                languages = supportedLanguages, assetDir = "models/whisper-lite",
                downloadBaseUrl = "https://github.com/john-rocky/LiteRT-Models/releases/download/v2",
                files = listOf(
                    BundleFile("mel_filters.bin", "4f2701b1d287d74a0dc9871026e9519d98cb76426615f2539b0d151a0ae4ec2e", 64_320),
                    BundleFile("vocab.json", "fb6fd3eb3a64a273b6ab0127adf0b3e317e21caa9a812edf87118e6f51bf10da", 987_090),
                    BundleFile("whisper_decoder.onnx", "3d9c2997d206e575a1bc0e90297c28c1d26bf4490b60cbf43ac8becac61064f6", 198_806_743),
                    BundleFile("whisper_encoder.tflite", "64c7c1585b416ab19cf026690c7632cd2987c37e635384bc4a5c176cd6e4253f", 32_926_152),
                ),
            ),
            ModelBundle(
                id = NEMOTRON_LITERT, kind = BundleKind.ACCELERATION,
                displayName = "Nemotron 3 — LiteRT (Android 14+)",
                languages = emptyList(), assetDir = "models/nemotron-lite",
                downloadBaseUrl = "https://huggingface.co/litert-community/Nemotron-3-Diarization-LiteRT/resolve/c6e48fcb3519be365babc7bd74e3e0ae2dc5c855",
                files = listOf(
                    BundleFile("frontend_mel128_257.bin", "bce5ec5f194a5913f6508cee5a85512e7bad2352db8fc28f5c6ff75af8b09137", 131_584,
                        "https://huggingface.co/litert-community/Nemotron-3-Diarization-LiteRT/resolve/c6e48fcb3519be365babc7bd74e3e0ae2dc5c855/assets/frontend_mel128_257.bin"),
                    BundleFile("hann400.bin", "c427e2029118cf789649e5a4d439b6115d0dd0cbf95dcd22f65e3c848add8c5b", 1_600,
                        "https://huggingface.co/litert-community/Nemotron-3-Diarization-LiteRT/resolve/c6e48fcb3519be365babc7bd74e3e0ae2dc5c855/assets/hann400.bin"),
                    BundleFile("silence_embeds.bin", "d4417b3c0eabdf7c47032fac2b5b5a7ee83d819a6ddda8fd8eaf74e2b5cc4ac7", 2_048,
                        "https://huggingface.co/litert-community/Nemotron-3-Diarization-LiteRT/resolve/c6e48fcb3519be365babc7bd74e3e0ae2dc5c855/assets/silence_embeds.bin"),
                    BundleFile("nemotron3_diar_encoder_offline_fp16.tflite", "b312fd74b1931af83185d84f017dd330d0478887db7ccf6ff0e4b65ff0dda28b", 198_694_768),
                    BundleFile("nemotron3_diar_frontend.tflite", "c01df58c31b425183c5a008552b517e53007fee39748baa2a4c49c1a55072810", 2_098_556),
                ),
            ),
            ModelBundle(
                id = PYANNOTE_LITERT, kind = BundleKind.ACCELERATION,
                displayName = "Pyannote + WeSpeaker — LiteRT-эмбеддинги (Android 14+)",
                languages = emptyList(), assetDir = "models/pyannote-lite",
                downloadBaseUrl = "https://huggingface.co/litert-community/Speaker-Diarization-LiteRT/resolve/e42b3797b1f517063b05b84997926d27bede7628",
                files = listOf(
                    BundleFile("pyannote_seg30.onnx", "4c96c9adfae4ef83c07909cac98630c886255f8866b5807db39cf3e46572a8a2", 5_924_521),
                    BundleFile("wespeaker_emb_fp16.tflite", "4853c284fdb3e39bd41f692d0bc3fd5068bf78782792d8ef02a3283c9d97554d", 13_352_192),
                ),
            ),
            // Keep the ID valid for saved runs.
            ModelBundle(
                id = LEGACY_PUNCTUATION, kind = BundleKind.LLM,
                displayName = "Пунктуация — Qwen 2.5 0.5B (устаревшая)",
                languages = supportedLanguages, assetDir = "models/punctuation",
                files = listOf(BundleFile("qwen2.5-0.5b-instruct-q4_k_m.gguf",
                    "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db", 491400032)),
            ),
            ModelBundle(
                id = DENOISE, kind = BundleKind.ENHANCEMENT,
                displayName = "Подавление шума — GTCRN", languages = emptyList(), assetDir = "models/gtcrn",
                files = listOf(BundleFile("gtcrn.onnx", "e77603ac0c23dac3227dd2d7135b3a585cbee2679048aecfa886657d3ae1b534", 535638,
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx")),
            ),
            ModelBundle(
                id = ISOLATION, kind = BundleKind.ISOLATION,
                displayName = "Одновременная речь — VoxLock", languages = emptyList(),
                assetDir = "models/voice-isolation",
                files = listOf(
                    BundleFile("enroll.onnx", "5d06048c156bb8cd422d29848e6d38fb26a7b39f6f7fd92eccf2a6ed1ce003c6", 12476014, "https://github.com/arbazsiddiqui/voxlock/releases/download/sherpa-onnx-models/voxlock_enroll.onnx"),
                    BundleFile("chunk_int8.onnx", "df61a9d0062b0adbcf1182302b1eb8b815f15ac47ea4923e8e164c1aff64121d", 66608910, "https://github.com/arbazsiddiqui/voxlock/releases/download/sherpa-onnx-models/voxlock_chunk_int8.onnx"),
                ),
            ),
            ModelBundle(
                id = WHISPER_TINY,
                kind = BundleKind.ASR,
                displayName = "Whisper tiny многоязычный (ONNX, INT8)",
                languages = supportedLanguages,
                assetDir = "models/whisper-tiny",
                downloadBaseUrl = WHISPER_TINY_URL,
                files =
                    listOf(
                        BundleFile(
                            "tiny-encoder.int8.onnx",
                            "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434",
                            12_937_772,
                        ),
                        BundleFile(
                            "tiny-decoder.int8.onnx",
                            "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925",
                            89_855_401,
                        ),
                        BundleFile("tiny-tokens.txt", "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126", 816_730),
                    ),
            ),
            ModelBundle(
                id = WHISPER_BASE,
                kind = BundleKind.ASR,
                displayName = "Whisper base многоязычный (ONNX, INT8)",
                languages = supportedLanguages,
                assetDir = "models/whisper-base",
                downloadBaseUrl = WHISPER_BASE_URL,
                files =
                    listOf(
                        BundleFile(
                            "base-encoder.int8.onnx",
                            "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11",
                            29_120_534,
                        ),
                        BundleFile(
                            "base-decoder.int8.onnx",
                            "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d",
                            130_672_026,
                        ),
                        BundleFile("base-tokens.txt", "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126", 816_730),
                    ),
            ),
            ModelBundle(
                id = WHISPER_SMALL,
                kind = BundleKind.ASR,
                displayName = "Whisper small многоязычный (ONNX, INT8)",
                languages = supportedLanguages,
                assetDir = "models/whisper-small",
                downloadBaseUrl = WHISPER_SMALL_URL,
                files =
                    listOf(
                        BundleFile(
                            "small-encoder.int8.onnx",
                            "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9",
                            112_442_483,
                        ),
                        BundleFile(
                            "small-decoder.int8.onnx",
                            "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee",
                            262_226_114,
                        ),
                        BundleFile("small-tokens.txt", "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126", 816_730),
                    ),
            ),
            ModelBundle(
                id = ZIPFORMER_RU,
                kind = BundleKind.ASR,
                displayName = "Zipformer RU 2024 (INT8, только ранее установленный)",
                languages = listOf("ru"),
                assetDir = "models/zipformer-ru",
                // No per-file public source exists for this 2024-09-18 export (it is
                // published only as a GitHub release tar.bz2); weights must NOT be
                // silently swapped under this id. Not downloadable: only an old APK's
                // installed private-storage copy can serve it.
                files =
                    listOf(
                        BundleFile("encoder.int8.onnx", "75653125bf3621d86a2b140ffd84a1c3b7610fc8cd49f2b6d70cf5786be0d085", 25_090_964),
                        BundleFile("decoder.int8.onnx", "435752764fb93c3e3e8d2537b9d008700114c7e126461e7808de30ef4aed4696", 1_307_950),
                        BundleFile("joiner.int8.onnx", "a93e0afc2c82e94d366157b4a8b5f4cba6973ddea8f36ac94010b430be7823f1", 259_572),
                        BundleFile("tokens.txt", "93bbbc0bae6b78c0bbb743d4aa9fded3bb5ff3aac5f0200e3a769a5a05e0fdf6", 6_388),
                    ),
            ),
            ModelBundle(
                id = ZIPFORMER_RU_2025,
                kind = BundleKind.ASR,
                // NON-streaming transducer: the STT engine must drive it with an
                // OfflineRecognizer transducer config, not the streaming API.
                displayName = "Zipformer RU 2025-04-20 (ONNX, INT8)",
                languages = listOf("ru"),
                assetDir = "models/zipformer-ru-2025",
                downloadBaseUrl = ZIPFORMER_RU_2025_URL,
                files =
                    listOf(
                        BundleFile(
                            "encoder.int8.onnx",
                            "eb6c12fbad810d5bc3e427802e604604c69b5943a91feebc43424dd09d9ec407",
                            70_876_638,
                        ),
                        // Remote publishes an fp32 `decoder.onnx`; kept under the local
                        // name the zipformer engine already expects.
                        BundleFile(
                            "decoder.int8.onnx",
                            "dcbe1ffa0211e77ca6d3a80164df13fbda3ec00e47d12b9f449f89572df12136",
                            2_093_080,
                            downloadUrl = "$ZIPFORMER_RU_2025_URL/decoder.onnx",
                        ),
                        BundleFile(
                            "joiner.int8.onnx",
                            "93f2e1d12b78d53e7802f1606488c14bb3d764b15fadf5ef6c022f6ba1fa40f7",
                            259_417,
                        ),
                        BundleFile("tokens.txt", "93bbbc0bae6b78c0bbb743d4aa9fded3bb5ff3aac5f0200e3a769a5a05e0fdf6", 6_388),
                    ),
            ),
            ModelBundle(
                id = GIGAAM_V3,
                kind = BundleKind.ASR,
                displayName = "GigaAM-v3 RNNT RU (INT8; не GigaAM-3.5)",
                languages = listOf("ru"),
                assetDir = "models/gigaam-v3",
                downloadBaseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-transducer-giga-am-v3-russian-2025-12-16/resolve/0424d62637b37d8b7b1eb0f75a04d78dec55fdcc",
                files =
                    listOf(
                        BundleFile("encoder.int8.onnx", "0ec54e5f130a91c0f228d21795ede10b31c992a1f73e45dcd827e843a71a9b50", 224_570_814),
                        BundleFile("decoder.onnx", "5d1cfef155d8e07f213bd2f3229b5f6c5c29355c7fa5c1b9eb62cb1925725254", 3_331_651),
                        BundleFile("joiner.onnx", "fd1d02f45c2ad3d6b67cc149811ad794ab4b020ed49a0a9e2790a8619d1cddd8", 1_440_448),
                        BundleFile("tokens.txt", "17cc514451bcceac9c280068c71502f8448f99e9fb1456b8d0761651fd0392f2", 196),
                    ),
            ),
            ModelBundle(
                id = NEMOTRON,
                kind = BundleKind.DIARIZATION,
                displayName = "NVIDIA Nemotron-3-Diarization (GGUF Q8, до 8 говорящих)",
                languages = emptyList(),
                assetDir = "models/nemotron-3",
                downloadBaseUrl = "https://huggingface.co/nvidia/Nemotron-3-Diarization/resolve/f667ed73aee57d40cc39428eb768b4fd87a0a29e",
                files =
                    listOf(
                        BundleFile(
                            "Nemotron-3-Diarization.q8_0.gguf",
                            "08456d9e22cd9a323c0364d98375f3746d6e68507ebb705cd46438c534c7a3a1",
                            107_012_128,
                        ),
                    ),
            ),
            ModelBundle(
                id = DIARIZATION,
                kind = BundleKind.DIARIZATION,
                displayName = "Разделение говорящих — Pyannote 3.0 + TitaNet-S",
                languages = emptyList(), // language-independent
                assetDir = "models/diarization",
                // Same bytes as the previously bundled assets, pinned per file under
                // their public (differently named) sources; local names unchanged so
                // already-installed copies stay verified and the diarizer keeps working.
                files =
                    listOf(
                        BundleFile(
                            "segmentation.int8.onnx",
                            "d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d",
                            1_540_506,
                            downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/9403a6902bb58e3d5ae8c7e77c3422de279db2e0/model.int8.onnx",
                        ),
                        BundleFile(
                            "titanet-small.onnx",
                            "ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e",
                            40_257_283,
                            downloadUrl = "https://huggingface.co/FluffyBunnies/titanet-small-onnx/resolve/5fae6d4e517a019cab845fd98935fd5b3776dfed/titanet.onnx",
                        ),
                    ),
            ),
        )

    // Older bundles remain resolvable for existing runs and turn retries.
    val selectableBundles = bundles.filterNot { it.id == ZIPFORMER_RU || it.id == LEGACY_PUNCTUATION }

    fun optionalModelIds(request: RunRequest, hasOverlap: Boolean = true): List<String> = buildList {
        if (request.punctuation) add(PUNCTUATION)
        if (request.denoise) add(DENOISE)
        if (request.separateOverlappingSpeech && hasOverlap) add(ISOLATION)
    }

    fun requiredModelIds(request: RunRequest): Set<String> = buildSet {
        if (request.language == SpeechLanguage.AUTO) add(WHISPER_TINY)
        add(request.diarizationModelId)
        if (request.modelId != AUTO_MODEL_ID) add(request.modelId)
        addAll(optionalModelIds(request))
    }

    fun byId(id: String): ModelBundle = bundles.firstOrNull { it.id == id } ?: throw ModelBundleException("unknown bundle '$id'")

    fun installedAsrIds(): List<String> = bundles.filter { it.kind == BundleKind.ASR }.map { it.id }
}

/** Progress with a real fraction derived from extracted or downloaded bytes. */
fun extractProgress(
    doneBytes: Long,
    totalBytes: Long,
) = memberProgress("installing", doneBytes, totalBytes)

/** Progress for any install stage, fraction derived from transferred bytes. */
fun memberProgress(
    stage: String,
    doneBytes: Long,
    totalBytes: Long,
) = Progress(stage, if (totalBytes <= 0) null else (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f))
