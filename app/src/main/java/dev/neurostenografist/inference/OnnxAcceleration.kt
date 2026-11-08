package dev.neurostenografist.inference

import android.content.Context
import android.os.Build
import org.json.JSONArray
import java.io.File
import java.util.UUID

data class OnnxBackend(
    val provider: String,
    val description: String,
    val requestedNnapi: Boolean,
    val profileDir: File? = null,
)

internal data class NnapiDevice(val name: String, val type: Int)
internal data class OnnxProfileEvidence(val nnapiExecutions: Int, val cpuExecutions: Int)

/** Driver discovery is a capability check; only execution profiles prove node placement. */
object OnnxAcceleration {
    private external fun nativeDevices(): Array<String>

    fun choose(
        context: Context,
        stage: String,
        preferNnapi: Boolean = true,
        diagnostic: Boolean = false,
    ): OnnxBackend {
        val api = Build.VERSION.SDK_INT
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val devices = if (preferNnapi && api in 29..33 && isNnapiAbi(abi)) {
            try {
                System.loadLibrary("neuro_nnapi_probe")
                nativeDevices().mapNotNull { line ->
                    val parts = line.split('\t', limit = 2)
                    if (parts.size == 2) parts[0].toIntOrNull()?.let { NnapiDevice(parts[1], it) } else null
                }
            } catch (_: LinkageError) {
                emptyList()
            } catch (_: SecurityException) {
                emptyList()
            }
        } else emptyList()
        val selection = selectOnnxBackend(preferNnapi, api, abi, devices)
        if (!diagnostic) return selection
        require(stage.matches(Regex("[a-zA-Z0-9_-]+"))) { "Некорректное имя этапа профилирования" }
        val directory = File(context.cacheDir, "onnx-profiles/$stage-${UUID.randomUUID()}")
        check(directory.mkdirs()) { "Не удалось создать каталог профилирования" }
        // Opt-in only: ORT stores per-node events in memory until the session closes.
        val config = File(directory, "session.config")
        config.writeText("ProfilingFilePrefix=${File(directory, "trace").absolutePath}\nLogSeverityLevel=2\n")
        return selection.copy(provider = "${selection.provider}:${config.absolutePath}", profileDir = directory)
    }

    /** Call after closing all sessions so ORT has flushed their profiles. */
    fun evidence(backend: OnnxBackend): String {
        val files = backend.profileDir?.listFiles { file -> file.extension == "json" }.orEmpty()
        var nnapi = 0
        var cpu = 0
        for (file in files) {
            // Diagnostic traces can be large; never load an unbounded file into the UI heap.
            if (file.length() > 8L * 1024 * 1024) continue
            val result = try { parseOnnxProfile(file.readText()) } catch (_: Exception) { continue }
            nnapi += result.nnapiExecutions
            cpu += result.cpuExecutions
        }
        return if (nnapi + cpu > 0) {
            "${backend.description}. Выполнений узлов NNAPI: $nnapi; CPU: $cpu"
        } else if (backend.requestedNnapi) {
            "${backend.description}. Распределение операций не измерено; возможен CPU fallback"
        } else backend.description
    }
}

private fun isNnapiAbi(abi: String): Boolean = abi == "arm64-v8a" || abi == "x86_64"

internal fun selectOnnxBackend(preferNnapi: Boolean, api: Int, abi: String, devices: List<NnapiDevice>): OnnxBackend {
    if (!preferNnapi) return OnnxBackend("cpu", "CPU выбран", false)
    if (api >= 34) return OnnxBackend("cpu", "ONNX: CPU (Android 14+)", false)
    // The local AAR supports NNAPI on Android 9. Driver enumeration starts at API 29.
    if (api < 28 || !isNnapiAbi(abi)) return OnnxBackend("cpu", "CPU: NNAPI недоступен для этой сборки или версии Android", false)
    if (api == 28) return OnnxBackend("nnapi", "NNAPI запрошен; Android 9 не позволяет проверить драйвер заранее", true)
    val hardware = devices.filter { it.type == 3 || it.type == 4 }
    if (hardware.isEmpty()) return OnnxBackend("cpu", "CPU: аппаратный драйвер NNAPI не обнаружен", false)
    return OnnxBackend("nnapi", "NNAPI запрошен (${hardware.joinToString { it.name }}); CPU для остальных операций", true)
}

internal fun parseOnnxProfile(json: String): OnnxProfileEvidence {
    val events = JSONArray(json)
    var nnapi = 0
    var cpu = 0
    for (index in 0 until events.length()) {
        val event = events.optJSONObject(index) ?: continue
        if (event.optString("cat") != "Node") continue
        when (event.optJSONObject("args")?.optString("provider")) {
            "NnapiExecutionProvider" -> nnapi++
            "CPUExecutionProvider" -> cpu++
        }
    }
    return OnnxProfileEvidence(nnapi, cpu)
}
