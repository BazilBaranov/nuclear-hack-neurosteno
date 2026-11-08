package dev.neurostenografist.data

import android.content.Context

/**
 * User settings, snapshot behind the run request (a running job does not observe
 * live edits). Recording sample rate and colors live in SharedPreferences.
 *
 * [PrefsBacking] exists so JVM tests can drive the exact same read/write schema
 * without an Android device; the [Context] constructor wires SharedPreferences
 * under the hood with identical keys.
 */
interface PrefsBacking {
    fun getInt(
        key: String,
        def: Int,
    ): Int

    fun putInt(
        key: String,
        value: Int,
    )

    fun getString(
        key: String,
        def: String,
    ): String

    fun putString(
        key: String,
        value: String,
    )
}

class SettingsStore(
    val backing: PrefsBacking,
) {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Registers a change listener (fired synchronously on every write); returns the unsubscribe action. */
    fun addChangeListener(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private fun notifyChanged() {
        listeners.forEach { it() }
    }
    constructor(context: Context) : this(
        SharedPrefsBacking(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
    )

    var recordSampleRate: Int
        get() = backing.getInt(KEY_RATE, DEFAULT_RATE)
        set(value) {
            require(value in RECORD_RATES) { "unsupported recording sample rate $value" }
            backing.putInt(KEY_RATE, value)
            notifyChanged()
        }

    /** ARGB explicit custom primary color (used when [paletteMode] is custom). */
    var primaryColor: Int
        get() = backing.getInt(KEY_PRIMARY, DEFAULT_PRIMARY)
        set(value) {
            backing.putInt(KEY_PRIMARY, value)
            notifyChanged()
        }

    var secondaryColor: Int
        get() = backing.getInt(KEY_SECONDARY, DEFAULT_SECONDARY)
        set(value) {
            backing.putInt(KEY_SECONDARY, value)
            notifyChanged()
        }

    /** Explicit system-vs-custom palette selection, persisted and shown in Settings. */
    var paletteMode: String
        get() = backing.getString(KEY_PALETTE_MODE, MODE_SYSTEM)
        set(value) {
            require(value == MODE_SYSTEM || value == MODE_CUSTOM) { "unsupported palette mode $value" }
            backing.putString(KEY_PALETTE_MODE, value)
            notifyChanged()
        }

    fun isDefaultPalette(): Boolean =
        backing.getInt(KEY_PRIMARY, DEFAULT_PRIMARY) == DEFAULT_PRIMARY &&
            backing.getInt(KEY_SECONDARY, DEFAULT_SECONDARY) == DEFAULT_SECONDARY

    /** True when the user explicitly picked a custom palette. */
    fun isCustomPalette(): Boolean = paletteMode == MODE_CUSTOM

    private class SharedPrefsBacking(
        private val prefs: android.content.SharedPreferences,
    ) : PrefsBacking {
        override fun getInt(
            key: String,
            def: Int,
        ): Int = prefs.getInt(key, def)

        override fun putInt(
            key: String,
            value: Int,
        ) {
            prefs.edit().putInt(key, value).apply()
        }

        override fun getString(
            key: String,
            def: String,
        ): String = prefs.getString(key, def) ?: def

        override fun putString(
            key: String,
            value: String,
        ) {
            prefs.edit().putString(key, value).apply()
        }
    }

    companion object {
        val RECORD_RATES = setOf(16_000, 48_000)
        const val DEFAULT_RATE = 16_000
        const val DEFAULT_PRIMARY = 0xFF4F5BD5.toInt()
        const val DEFAULT_SECONDARY = 0xFF26A69A.toInt()
        const val MODE_SYSTEM = "system"
        const val MODE_CUSTOM = "custom"
        private const val PREFS_NAME = "settings"
        private const val KEY_RATE = "record_sample_rate"
        private const val KEY_PRIMARY = "primary_color"
        private const val KEY_SECONDARY = "secondary_color"
        private const val KEY_PALETTE_MODE = "palette_mode"
    }
}
