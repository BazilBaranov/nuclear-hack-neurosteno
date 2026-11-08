package dev.neurostenografist.audio

import dev.neurostenografist.audio.decoder.readMp4MovieDurationUs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

class Mp4MovieDurationTest {
    @Test fun realAacFixtureUsesPresentationSecondNotPrimingInclusiveMediaDuration() {
        val relative = "src/androidTest/assets/audio/sine44k_mono.m4a"
        val fixture = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        assertEquals(1_000_000L, fixture.inputStream().use(::readMp4MovieDurationUs))
    }

    @Test fun versionOneAndExtendedBoxSizesKeepSubMillisecondPrecision() {
        val header = data { writeInt(1 shl 24); writeLong(0); writeLong(0); writeInt(44_100); writeLong(44_111) }
        val file = box("ftyp", ByteArray(8)) + box("moov", box("mvhd", header, extended = true))
        assertEquals(1_000_249L, readMp4MovieDurationUs(file.inputStream()))
    }

    @Test fun skipsMdatAndWorksWithOneByteReadsAndNoSeek() {
        val file = box("ftyp", ByteArray(8)) + box("mdat", ByteArray(97)) +
            box("moov", box("free", ByteArray(13)) + movieHeader())
        val input = object : ByteArrayInputStream(file) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(1, len))
            override fun skip(n: Long): Long = 0
        }
        assertEquals(1_000_000L, readMp4MovieDurationUs(input))
    }

    @Test fun truncatedAndParentEscapingBoxesFallBack() {
        val valid = box("ftyp", ByteArray(8)) + box("moov", movieHeader())
        assertNull(readMp4MovieDurationUs(valid.copyOf(valid.size - 1).inputStream()))
        val oversizedChild = data { writeInt(4096); writeBytes("mvhd"); writeInt(0) }
        assertNull(readMp4MovieDurationUs((box("ftyp", ByteArray(8)) + box("moov", oversizedChild)).inputStream()))
    }

    @Test fun invalidSizesUnknownDurationAndOverflowFallBack() {
        for (header in listOf(
            data { writeInt(4); writeBytes("ftyp") },
            data { writeInt(1); writeBytes("ftyp"); writeLong(Long.MAX_VALUE) },
            data { writeInt(0); writeBytes("ftyp") },
        )) assertNull(readMp4MovieDurationUs(header.inputStream()))
        for (header in listOf(
            movieHeader(scale = 0),
            movieHeader(ticks = -1),
            box("mvhd", data { writeInt(1 shl 24); writeLong(0); writeLong(0); writeInt(1); writeLong(Long.MAX_VALUE) }),
        )) assertNull(readMp4MovieDurationUs((box("ftyp", ByteArray(8)) + box("moov", header)).inputStream()))
    }

    @Test fun nonMp4ReturnsNoOverride() {
        assertNull(readMp4MovieDurationUs("RIFF----WAVE".byteInputStream()))
    }

    private fun movieHeader(scale: Int = 44_100, ticks: Int = 44_100) =
        box("mvhd", data { writeInt(0); writeLong(0); writeInt(scale); writeInt(ticks) })

    private fun box(type: String, payload: ByteArray, extended: Boolean = false): ByteArray = data {
        writeInt(if (extended) 1 else payload.size + 8)
        writeBytes(type)
        if (extended) writeLong(payload.size + 16L)
        write(payload)
    }

    private fun data(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).use(write)
    }.toByteArray()
}
