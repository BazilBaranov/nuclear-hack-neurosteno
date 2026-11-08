package dev.neurostenografist.inference

object HardwareAcceleration {
    /** Reserve a core for Android/UI instead of saturating every logical CPU. */
    fun cpuThreads(available: Int = Runtime.getRuntime().availableProcessors()): Int = (available - 1).coerceIn(1, 4)
}

object NativeAcceleration {
    init { System.loadLibrary("neuro_nemotron") }

    /** Registered ggml devices; availability is not proof that a model used one. */
    fun availableDevices(): List<String> = nativeDevices().toList()

    private external fun nativeDevices(): Array<String>
}
