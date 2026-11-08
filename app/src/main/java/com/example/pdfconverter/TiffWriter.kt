package com.example.pdfconverter

import android.graphics.Bitmap
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Streams an uncompressed little-endian RGB TIFF so large PDFs are not held fully in memory.
 */
class TiffWriter private constructor(
    private val file: RandomAccessFile
) : Closeable {

    private var nextIfdPointerOffset = 4L
    private var pageCount = 0
    private var closed = false

    fun addPage(bitmap: Bitmap) {
        require(!bitmap.isRecycled) { "Page bitmap has already been recycled." }
        require(bitmap.width > 0 && bitmap.height > 0) { "Page bitmap is empty." }

        val imageBytes = bitmapToRgbBytes(bitmap)
        val ifdStart = file.length()
        require(ifdStart <= MAX_TIFF_OFFSET) { "TIFF file is too large to write with 32-bit offsets." }

        val ifdSize = 2 + (ENTRY_COUNT * 12) + 4
        val bitsOffset = ifdStart + ifdSize
        val xResOffset = bitsOffset + 6
        val yResOffset = xResOffset + 8
        val imageOffset = yResOffset + 8
        require(imageOffset + imageBytes.size <= MAX_TIFF_OFFSET) {
            "TIFF file is too large to write with 32-bit offsets."
        }

        file.seek(ifdStart)
        writeShortLE(ENTRY_COUNT)
        writeEntry(256, TYPE_LONG, 1, bitmap.width.toLong())
        writeEntry(257, TYPE_LONG, 1, bitmap.height.toLong())
        writeEntry(258, TYPE_SHORT, 3, bitsOffset)
        writeEntry(259, TYPE_SHORT, 1, 1) // no compression
        writeEntry(262, TYPE_SHORT, 1, 2) // RGB
        writeEntry(273, TYPE_LONG, 1, imageOffset)
        writeEntry(277, TYPE_SHORT, 1, 3)
        writeEntry(278, TYPE_LONG, 1, bitmap.height.toLong())
        writeEntry(279, TYPE_LONG, 1, imageBytes.size.toLong())
        writeEntry(282, TYPE_RATIONAL, 1, xResOffset)
        writeEntry(283, TYPE_RATIONAL, 1, yResOffset)
        writeEntry(296, TYPE_SHORT, 1, 2) // inches
        writeUInt32(0) // next IFD, patched on the following page

        writeBitsPerSample()
        writeRational(72, 1)
        writeRational(72, 1)
        file.write(imageBytes)

        file.seek(nextIfdPointerOffset)
        writeUInt32(ifdStart)
        nextIfdPointerOffset = ifdStart + 2 + (ENTRY_COUNT * 12)
        pageCount++
    }

    fun finish() {
        try {
            if (pageCount == 0) {
                throw ConversionException("At least one page is required to generate a TIFF.")
            }
        } finally {
            file.close()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        file.close()
    }

    private fun writeHeader() {
        file.seek(0)
        file.write(byteArrayOf(0x49, 0x49, 0x2A, 0x00))
        writeUInt32(0)
    }

    private fun writeEntry(tag: Int, type: Int, count: Int, value: Long) {
        writeShortLE(tag)
        writeShortLE(type)
        writeUInt32(count.toLong())
        writeUInt32(value)
    }

    private fun writeBitsPerSample() {
        writeShortLE(8)
        writeShortLE(8)
        writeShortLE(8)
    }

    private fun writeRational(numerator: Int, denominator: Int) {
        writeUInt32(numerator.toLong())
        writeUInt32(denominator.toLong())
    }

    private fun writeShortLE(value: Int) {
        file.write(value and 0xFF)
        file.write((value ushr 8) and 0xFF)
    }

    private fun writeUInt32(value: Long) {
        val bits = value and 0xFFFFFFFFL
        file.write((bits and 0xFF).toInt())
        file.write(((bits ushr 8) and 0xFF).toInt())
        file.write(((bits ushr 16) and 0xFF).toInt())
        file.write(((bits ushr 24) and 0xFF).toInt())
    }

    companion object {
        private const val TYPE_SHORT = 3
        private const val TYPE_LONG = 4
        private const val TYPE_RATIONAL = 5
        private const val ENTRY_COUNT = 12
        private const val MAX_TIFF_OFFSET = 0xFFFFFFFFL

        fun create(outputFile: File): TiffWriter {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists() && !outputFile.delete()) {
                throw ConversionException("Unable to replace an existing TIFF file.")
            }
            val randomAccessFile = RandomAccessFile(outputFile, "rw")
            return TiffWriter(randomAccessFile).also { it.writeHeader() }
        }

        private fun bitmapToRgbBytes(bitmap: Bitmap): ByteArray {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val rgb = ByteArray(width * height * 3)
            var index = 0
            for (pixel in pixels) {
                rgb[index++] = ((pixel shr 16) and 0xFF).toByte()
                rgb[index++] = ((pixel shr 8) and 0xFF).toByte()
                rgb[index++] = (pixel and 0xFF).toByte()
            }
            return rgb
        }
    }
}
