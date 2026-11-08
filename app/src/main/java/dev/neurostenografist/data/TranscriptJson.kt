package dev.neurostenografist.data

import dev.neurostenografist.core.Turn
import org.json.JSONObject

/**
 * Strict export: exactly the public assignment schema, no run metadata.
 * Written field-by-field (escaping via JSONObject.quote) because JSONObject.toString()
 * key order differs between Android and the JVM test implementation.
 */
object TranscriptJson {
    fun encode(turns: List<Turn>): String {
        val sb = StringBuilder("{\"transcript\":[")
        turns.forEachIndexed { index, turn ->
            if (index > 0) sb.append(',')
            sb.append("{\"speaker_id\":").append(JSONObject.quote(turn.speakerId))
            sb.append(",\"start_ms\":").append(turn.startMs)
            sb.append(",\"end_ms\":").append(turn.endMs)
            sb.append(",\"text\":").append(JSONObject.quote(turn.text)).append('}')
        }
        return sb.append("]}").toString()
    }
}
