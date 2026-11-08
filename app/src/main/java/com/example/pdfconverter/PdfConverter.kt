package com.example.pdfconverter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Environment
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

enum class OutputFormat(val label: String, val extension: String) {
    TIFF("TIFF", "tif"),
    JPEG("JPEG", "jpeg"),
    JPG("JPG", "jpg")
}

data class ConversionOptions(
    val format: OutputFormat,
    val alsoJpegPages: Boolean = false,
    val dpi: Int = 150,
    val jpegQuality: Int = 90,
    val pageFrom: Int = 1,
    val pageTo: Int? = null
)

data class PdfDocumentInfo(
    val displayName: String,
    val pageCount: Int
)

data class ConversionResult(
    val outputDir: File,
    val createdFiles: List<File>,
    val primaryFile: File?,
    val pageCount: Int
)

class ConversionException(message: String, cause: Throwable? = null) : Exception(message, cause)

object PdfConverter {
    private const val MAX_BITMAP_SIDE = 4096
    private const val MIN_DPI = 72
    private const val MAX_DPI = 300

    fun inspect(context: Context, uri: Uri): PdfDocumentInfo {
        val displayName = queryDisplayName(context, uri)
        val tempPdf = copyToCache(context, uri)
        try {
            openRenderer(tempPdf).use { session ->
                if (session.renderer.pageCount <= 0) {
                    throw ConversionException("This PDF has no pages to convert.")
                }
                return PdfDocumentInfo(displayName, session.renderer.pageCount)
            }
        } finally {
            tempPdf.delete()
        }
    }

    fun convert(
        context: Context,
        inputUri: Uri,
        outputName: String,
        options: ConversionOptions,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): ConversionResult {
        val dpi = options.dpi.coerceIn(MIN_DPI, MAX_DPI)
        val jpegQuality = options.jpegQuality.coerceIn(50, 100)
        val baseName = sanitizeFileName(outputName)
        val outputDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "PDFToImage_${System.currentTimeMillis()}"
        )
        if (!outputDir.mkdirs() && !outputDir.isDirectory) {
            throw ConversionException("Unable to create the output folder.")
        }

        cleanupOldOutputs(context, outputDir)

        val tempPdf = copyToCache(context, inputUri)
        val createdFiles = mutableListOf<File>()
        var tiffWriter: TiffWriter? = null
        val jpegExtension = when {
            options.format == OutputFormat.JPEG -> "jpeg"
            options.format == OutputFormat.JPG -> "jpg"
            options.alsoJpegPages -> "jpg"
            else -> null
        }
        val writeTiff = options.format == OutputFormat.TIFF
        val writeJpeg = jpegExtension != null

        if (!writeTiff && !writeJpeg) {
            throw ConversionException("Choose TIFF, JPEG, or JPG.")
        }

        try {
            openRenderer(tempPdf).use { session ->
                val renderer = session.renderer
                if (renderer.pageCount <= 0) {
                    throw ConversionException("This PDF has no pages to convert.")
                }

                val lastPage = options.pageTo?.coerceAtMost(renderer.pageCount) ?: renderer.pageCount
                val firstPage = options.pageFrom.coerceAtLeast(1)
                if (firstPage > lastPage) {
                    throw ConversionException("The selected page range is empty.")
                }

                val total = lastPage - firstPage + 1
                if (writeTiff) {
                    tiffWriter = TiffWriter.create(File(outputDir, "$baseName.tif"))
                }

                for (pageNumber in firstPage..lastPage) {
                    onProgress(pageNumber - firstPage + 1, total)
                    val pageIndex = pageNumber - 1
                    val bitmap = renderPage(renderer, pageIndex, dpi)
                    try {
                        if (writeJpeg) {
                            val imageFile = File(
                                outputDir,
                                "${baseName}_page_${pageNumber.toString().padStart(3, '0')}.$jpegExtension"
                            )
                            writeJpeg(bitmap, imageFile, jpegQuality)
                            createdFiles.add(imageFile)
                        }
                        if (writeTiff) {
                            tiffWriter?.addPage(bitmap)
                        }
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }

                tiffWriter?.finish()
                tiffWriter = null

                if (writeTiff) {
                    val tiffFile = File(outputDir, "$baseName.tif")
                    if (!tiffFile.exists() || tiffFile.length() == 0L) {
                        throw ConversionException("TIFF file was not created.")
                    }
                    createdFiles.add(0, tiffFile)
                }

                val primaryFile = createdFiles.firstOrNull {
                    it.extension.lowercase() in setOf("tif", "tiff")
                } ?: createdFiles.firstOrNull()

                return ConversionResult(
                    outputDir = outputDir,
                    createdFiles = createdFiles.toList(),
                    primaryFile = primaryFile,
                    pageCount = total
                )
            }
        } catch (error: kotlin.coroutines.cancellation.CancellationException) {
            tiffWriter?.runCatching { close() }
            outputDir.deleteRecursively()
            throw error
        } catch (error: ConversionException) {
            tiffWriter?.runCatching { close() }
            outputDir.deleteRecursively()
            throw error
        } catch (error: OutOfMemoryError) {
            tiffWriter?.runCatching { close() }
            outputDir.deleteRecursively()
            throw ConversionException(
                "This PDF is too large for the selected quality. Try a lower DPI or fewer pages.",
                error
            )
        } catch (error: SecurityException) {
            tiffWriter?.runCatching { close() }
            outputDir.deleteRecursively()
            throw ConversionException("This PDF is password protected or cannot be opened.", error)
        } catch (error: Exception) {
            tiffWriter?.runCatching { close() }
            outputDir.deleteRecursively()
            throw ConversionException(error.message ?: "Conversion failed.", error)
        } finally {
            tempPdf.delete()
        }
    }

    fun renderPreview(context: Context, uri: Uri, maxSide: Int = 720): Bitmap {
        val tempPdf = copyToCache(context, uri)
        try {
            openRenderer(tempPdf).use { session ->
                if (session.renderer.pageCount <= 0) {
                    throw ConversionException("This PDF has no pages to convert.")
                }
                return renderPage(session.renderer, 0, dpi = 72, maxSide = maxSide)
            }
        } finally {
            tempPdf.delete()
        }
    }

    private fun renderPage(
        renderer: PdfRenderer,
        pageIndex: Int,
        dpi: Int,
        maxSide: Int = MAX_BITMAP_SIDE
    ): Bitmap {
        val page = renderer.openPage(pageIndex)
        try {
            val scale = dpi / 72f
            var width = max(1, (page.width * scale).roundToInt())
            var height = max(1, (page.height * scale).roundToInt())
            val largest = max(width, height)
            if (largest > maxSide) {
                val fit = maxSide / largest.toFloat()
                width = max(1, (width * fit).roundToInt())
                height = max(1, (height * fit).roundToInt())
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            return bitmap
        } finally {
            page.close()
        }
    }

    private fun writeJpeg(bitmap: Bitmap, target: File, quality: Int) {
        FileOutputStream(target).use { stream ->
            val compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            if (!compressed) {
                throw ConversionException("Unable to write JPEG output.")
            }
            stream.flush()
        }
        if (target.length() == 0L) {
            throw ConversionException("JPEG output was empty.")
        }
    }

    private fun copyToCache(context: Context, uri: Uri): File {
        val tempPdf = File(context.cacheDir, "input_${System.currentTimeMillis()}.pdf")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempPdf.outputStream().use { output -> input.copyTo(output) }
            } ?: throw ConversionException("Unable to open the selected PDF.")
        } catch (error: ConversionException) {
            tempPdf.delete()
            throw error
        } catch (error: IOException) {
            tempPdf.delete()
            throw ConversionException("Unable to read the selected PDF.", error)
        }
        if (tempPdf.length() == 0L) {
            tempPdf.delete()
            throw ConversionException("The selected PDF is empty.")
        }
        return tempPdf
    }

    private fun openRenderer(pdfFile: File): RendererSession {
        val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            RendererSession(pfd, PdfRenderer(pfd))
        } catch (error: SecurityException) {
            pfd.close()
            throw ConversionException("This PDF is password protected or cannot be opened.", error)
        } catch (error: IOException) {
            pfd.close()
            throw ConversionException("This file is not a readable PDF.", error)
        } catch (error: Exception) {
            pfd.close()
            throw ConversionException("Unable to open this PDF.", error)
        }
    }

    private fun cleanupOldOutputs(context: Context, keep: File) {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return
        root.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("PDFToImage_") && it.absolutePath != keep.absolutePath }
            ?.forEach { old -> old.deleteRecursively() }
    }

    fun queryDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        val name = cursor.getString(index)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
    }

    fun sanitizeFileName(input: String): String {
        return input.substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "converted" }
            .take(80)
    }

    private class RendererSession(
        private val pfd: ParcelFileDescriptor,
        val renderer: PdfRenderer
    ) : AutoCloseable {
        override fun close() {
            try {
                renderer.close()
            } finally {
                pfd.close()
            }
        }
    }
}
