package dev.neurostenografist.audio

import android.content.Context
import android.net.Uri
import dev.neurostenografist.audio.decoder.Pcm
import dev.neurostenografist.audio.decoder.StreamingPcm16k
import java.io.IOException
import java.io.InputStream
import kotlin.math.roundToInt

/** The file is a container/encoding this build does not decode; caller shows it as unsupported. */
class UnsupportedAudioException(
    message: String,
) : IOException(message)

/**
 * Decodes local audio onto the original 16 kHz mono timeline. WAV (RIFF/PCM16) is parsed
 * directly so it stays unit-testable on the JVM; compressed containers go through
 * MediaCodec on Android (added with the import screen).
 *
 * Only the final 16 kHz PCM is retained; source-rate PCM is consumed in small buffers.
 * The output is capped at 30 minutes and additionally bounded by the process heap.
 */
object AudioDecoder {
    const val TARGET_SAMPLE_RATE = 16_000

    /** KSDATAFORMAT_SUBTYPE_PCM little-endian GUID (00100000-0000-0010-8000-00aa00389b71). */
    private val PCM_SUBFORMAT_GUID =
        byteArrayOf(
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
            0x80.toByte(), 0x00, 0x00, 0xaa.toByte(), 0x00, 0x38, 0x9b.toByte(), 0x71,
        )

    /** Reads the container magic and routes to the WAV parser or MediaCodec. */
    fun decodeTo16k(
        context: Context,
        uri: Uri,
    ): FloatArray {
        val resolver = context.contentResolver
        val head = ByteArray(12)
        // One stream for detection, then reopen: the WAV parser needs RIFF at byte zero.
        val read =
            resolver.openInputStream(uri)?.use { input ->
                readFully(input, head)
            } ?: throw IOException("cannot open $uri")
        val isWav =
            read >= 12 &&
                head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() &&
                head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() &&
                head[8] == 'W'.code.toByte() && head[9] == 'A'.code.toByte() &&
                head[10] == 'V'.code.toByte() && head[11] == 'E'.code.toByte()
        return if (isWav) {
            val reopened =
                resolver.openInputStream(uri)
                    ?: throw IOException("cannot reopen $uri after WAV detection")
            reopened.use(::decodeWavTo16k)
        } else {
            MediaDecoder.decodeTo16k(context, uri)
        }
    }

    fun decodeWavTo16k(input: InputStream): FloatArray {
        val header = ByteArray(12)
        if (readFully(input, header) != 12 || !header.asciiEquals(0, "RIFF") || !header.asciiEquals(8, "WAVE")) {
            throw IOException("Файл не является RIFF/WAVE")
        }
        var sampleRate = 0
        var channels = 0
        val chunkHeader = ByteArray(8)
        val scratch = ByteArray(8_192)
        var metadataBytes = 0L
        while (true) {
            val count = readFully(input, chunkHeader)
            if (count == 0) throw IOException("В WAV отсутствует блок data")
            if (count != 8) throw IOException("Повреждён заголовок блока WAV")
            val size = le32u(chunkHeader, 4)
            when {
                chunkHeader.asciiEquals(0, "fmt ") -> {
                    if (size < 16 || size > 1_048_576) throw IOException("Некорректный блок fmt: $size байт")
                    val fmt = ByteArray(minOf(size, 40).toInt())
                    if (readFully(input, fmt) != fmt.size) throw IOException("Обрезан блок fmt")
                    val format = le16(fmt, 0)
                    val bits = le16(fmt, 14)
                    channels = le16(fmt, 2)
                    sampleRate = le32s(fmt, 4)
                    if (format == 0xFFFE) {
                        if (size < 40) throw IOException("Некорректный расширенный fmt: need 40 bytes")
                        if (!fmt.copyOfRange(24, 40).contentEquals(PCM_SUBFORMAT_GUID)) {
                            throw UnsupportedAudioException("Неподдерживаемый WAV subformat GUID")
                        }
                    } else if (format != 1) {
                        throw UnsupportedAudioException("Неподдерживаемый WAV: format=$format")
                    }
                    if (bits != 16) throw UnsupportedAudioException("Поддерживается WAV PCM16, получено $bits бит")
                    if (channels !in 1..32 || sampleRate !in 1..384_000) throw IOException("Некорректная частота или число каналов WAV")
                    skipFully(input, size - fmt.size, scratch)
                }
                chunkHeader.asciiEquals(0, "data") -> {
                    if (sampleRate == 0) throw IOException("Перед data отсутствует блок fmt")
                    val frameBytes = channels * 2
                    val frames = size / frameBytes
                    val limit = StreamingPcm16k.audioSampleLimit()
                    if (frames * TARGET_SAMPLE_RATE > limit.toLong() * sampleRate) {
                        throw StreamingPcm16k.audioLimitException(limit)
                    }
                    val output = StreamingPcm16k(sampleRate, channels, limit)
                    val bytes = ByteArray((8_192 / frameBytes) * frameBytes)
                    var remaining = frames * frameBytes
                    while (remaining > 0) {
                        val wanted = minOf(bytes.size.toLong(), remaining).toInt()
                        if (readFully(input, bytes, wanted) != wanted) throw IOException("Обрезан блок аудиоданных WAV")
                        output.append(Pcm.pcm16Le(bytes, 0, wanted))
                        remaining -= wanted
                    }
                    skipFully(input, size % frameBytes, scratch)
                    return output.finish()
                }
                else -> {
                    metadataBytes += size
                    if (metadataBytes > 16 * 1_024 * 1_024) throw IOException("Слишком большой блок метаданных WAV")
                    skipFully(input, size, scratch)
                }
            }
            if (size and 1L != 0L) skipFully(input, 1, scratch)
        }
    }

    private fun skipFully(input: InputStream, bytes: Long, scratch: ByteArray) {
        var remaining = bytes
        while (remaining > 0) {
            val wanted = minOf(remaining, scratch.size.toLong()).toInt()
            if (readFully(input, scratch, wanted) != wanted) throw IOException("Обрезан блок WAV")
            remaining -= wanted
        }
    }

    fun resampleTo16k(
        mono: FloatArray,
        sourceRate: Int,
    ): FloatArray {
        require(sourceRate > 0) { "invalid source rate $sourceRate" }
        if (sourceRate == TARGET_SAMPLE_RATE) return mono
        if (sourceRate % TARGET_SAMPLE_RATE == 0) {
            // Integer ratio: box-average each group, which also attenuates aliasing.
            val factor = sourceRate / TARGET_SAMPLE_RATE
            val outSize = mono.size / factor
            return FloatArray(outSize) { i ->
                var sum = 0f
                for (k in 0 until factor) sum += mono[i * factor + k]
                sum / factor
            }
        }
        // Non-integer ratio (e.g. 44.1 kHz): linear interpolation, length rounded to the
        // nearest 16 kHz frame count so duration stays stable.
        val ratio = sourceRate.toDouble() / TARGET_SAMPLE_RATE
        val outSize = (mono.size / ratio).roundToInt()
        return FloatArray(outSize) { i ->
            val position = i * ratio
            val index = position.toInt()
            val fraction = (position - index).toFloat()
            val first = mono[index]
            val second = mono.getOrElse(index + 1) { first }
            first + (second - first) * fraction
        }
    }

    private fun readFully(
        input: InputStream,
        buffer: ByteArray,
        length: Int = buffer.size,
    ): Int {
        var off = 0
        while (off < length) {
            val n = input.read(buffer, off, length - off)
            if (n < 0) break
            if (n == 0) {
                val byte = input.read()
                if (byte < 0) break
                buffer[off++] = byte.toByte()
            } else {
                off += n
            }
        }
        return off
    }

    private fun ByteArray.asciiEquals(
        offset: Int,
        prefix: String,
    ): Boolean {
        if (size < offset + prefix.length) return false
        for (i in prefix.indices) if (this[offset + i] != prefix[i].code.toByte()) return false
        return true
    }

    private fun u8(
        data: ByteArray,
        index: Int,
    ): Int = data[index].toInt() and 0xff

    private fun le16(
        data: ByteArray,
        index: Int,
    ): Int = u8(data, index) or (u8(data, index + 1) shl 8)

    private fun le32u(
        data: ByteArray,
        index: Int,
    ): Long =
        (u8(data, index).toLong()) or
            (u8(data, index + 1).toLong() shl 8) or
            (u8(data, index + 2).toLong() shl 16) or
            (u8(data, index + 3).toLong() shl 24)

    private fun le32s(
        data: ByteArray,
        index: Int,
    ): Int = le32u(data, index).toInt()
}
