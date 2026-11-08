package dev.neurostenografist.core

/** Raised when no installed model satisfies the requested language/selection. */
class ModelUnavailableException(
    message: String,
) : IllegalStateException(message)

/**
 * Deterministic language/model policy: [AUTO] walks the ranked priority list for the
 * resolved language and returns the first fully installed bundle; a manual ID must be
 * both compatible (present in that language's priority list) and installed, otherwise
 * the selection fails instead of being silently substituted.
 */
object ModelChoice {
    const val AUTO = "auto"

    fun select(
        language: String,
        requestedId: String,
        installedIds: Set<String>,
        priority: Map<String, List<String>>,
    ): String {
        val ranked =
            priority[language.lowercase()]
                ?: throw ModelUnavailableException("no enabled ASR models for language '$language'")
        if (requestedId != AUTO) {
            if (requestedId !in ranked) {
                // Never substitute: manual request against a different detected
                // language is an error, the user decides what to do next.
                throw ModelUnavailableException(
                    "model '$requestedId' does not match detected language '$language'; no substitution is made",
                )
            }
            if (requestedId !in installedIds) {
                throw ModelUnavailableException("model '$requestedId' is not installed")
            }
            return requestedId
        }
        return ranked.firstOrNull { it in installedIds }
            ?: throw ModelUnavailableException(
                "no installed model for language '$language' (ranked: $ranked)",
            )
    }
}
