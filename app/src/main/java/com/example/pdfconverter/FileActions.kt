package com.example.pdfconverter

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream

object FileActions {
    fun mimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "tif", "tiff" -> "image/tiff"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    fun share(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        val uris = files.map { fileProviderUri(context, it) }
        val type = if (files.all { mimeType(it) == mimeType(files.first()) }) {
            mimeType(files.first())
        } else {
            "*/*"
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                this.type = type
                putExtra(Intent.EXTRA_STREAM, uris.first())
                clipData = ClipData.newRawUri(files.first().name, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                this.type = type
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                val clip = ClipData.newRawUri(files.first().name, uris.first())
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        grantUriPermissions(context, intent, uris)
        context.startActivity(
            Intent.createChooser(intent, "Share converted files").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    fun open(context: Context, file: File) {
        val uri = fileProviderUri(context, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(file.name, uri)
        }
        grantUriPermissions(context, intent, listOf(uri))
        try {
            context.startActivity(Intent.createChooser(intent, "Open ${file.name}"))
        } catch (_: Exception) {
            Toast.makeText(context, "No app available to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyToUri(context: Context, source: File, destination: Uri) {
        context.contentResolver.openOutputStream(destination)?.use { output ->
            copyToStream(source, output)
        } ?: throw ConversionException("Unable to save to the selected location.")
    }

    fun saveJpegsToGallery(context: Context, files: List<File>): Int {
        val images = files.filter { it.extension.lowercase() in setOf("jpg", "jpeg") }
        if (images.isEmpty()) return 0
        var saved = 0
        for (file in images) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PDF Converter")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val uri = context.contentResolver.insert(collection, values)
                ?: throw ConversionException("Unable to save ${file.name} to the gallery.")
            context.contentResolver.openOutputStream(uri)?.use { output ->
                copyToStream(file, output)
            } ?: throw ConversionException("Unable to write ${file.name} to the gallery.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            saved++
        }
        return saved
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format("%.1f MB", mb)
    }

    private fun copyToStream(source: File, output: OutputStream) {
        source.inputStream().use { input -> input.copyTo(output) }
        output.flush()
    }

    private fun fileProviderUri(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    class CreateNamedDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
        override fun createIntent(context: Context, input: Pair<String, String>): Intent {
            return Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(input.first)
                .putExtra(Intent.EXTRA_TITLE, input.second)
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
            return intent?.takeIf { resultCode == Activity.RESULT_OK }?.data
        }
    }

    private fun grantUriPermissions(context: Context, intent: Intent, uris: List<Uri>) {
        val resolved = context.packageManager.queryIntentActivities(intent, 0)
        for (resolveInfo in resolved) {
            val packageName = resolveInfo.activityInfo.packageName
            for (uri in uris) {
                context.grantUriPermission(
                    packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }
}
