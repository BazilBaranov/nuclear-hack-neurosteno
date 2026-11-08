package dev.neurostenografist.data

import dev.neurostenografist.core.AudioSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the audio sources usable for transcription (imported or recorded).
 * Single-file store like [RunStore]; the source audio itself is never deleted
 * from here — deletion only removes the entry.
 *
 * A fresh install has no file yet and lists as empty. Writes go to a temp file
 * and rename over the record; on any failure the previous good record remains.
 * Mutations are serialized so concurrent writers cannot corrupt the file.
 */
class SourceStore(
    private val file: File,
) {
    fun list(): List<AudioSource> =
        synchronized(this) {
            if (!file.isFile) return emptyList()
            decode(file.readText())
        }

    fun upsert(source: AudioSource) {
        synchronized(this) {
            require(source.id.isNotBlank()) { "source id must not be blank" }
            val mutable = listLocked().toMutableList()
            mutable.removeAll { it.id == source.id }
            mutable.add(0, source)
            write(mutable)
        }
    }

    fun delete(id: String) {
        synchronized(this) {
            require(id.isNotBlank()) { "source id must not be blank" }
            write(listLocked().filterNot { it.id == id })
        }
    }

    private fun listLocked(): List<AudioSource> {
        if (!file.isFile) return emptyList()
        return decode(file.readText())
    }

    private fun write(sources: List<AudioSource>) {
        file.parentFile?.mkdirs()
        val body =
            JSONArray().apply {
                sources.forEach { source ->
                    put(
                        JSONObject()
                            .put("id", source.id)
                            .put("uri", source.uri)
                            .put("displayName", source.displayName)
                            .put("durationMs", source.durationMs ?: JSONObject.NULL),
                    )
                }
            }
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeText(body.toString())
            check(tmp.renameTo(file)) { "cannot persist sources" }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun decode(text: String): List<AudioSource> {
        if (text.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                AudioSource(
                    id = o.getString("id"),
                    uri = o.getString("uri"),
                    displayName = o.getString("displayName"),
                    durationMs = if (o.isNull("durationMs")) null else o.getLong("durationMs"),
                )
            }
        }.getOrDefault(emptyList())
    }
}
