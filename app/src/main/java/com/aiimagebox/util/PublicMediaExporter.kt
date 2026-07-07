package com.aiimagebox.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object PublicMediaExporter {
    fun exportImage(
        context: Context,
        source: File,
        displayName: String = source.name,
        mimeType: String = "image/png",
    ): String {
        require(source.isFile) { "Source image does not exist: ${source.absolutePath}" }
        val cleanName = displayName.ifBlank { source.name }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportImageScoped(context, source, cleanName, mimeType)
        } else {
            exportImageLegacy(source, cleanName)
        }
    }

    private fun exportImageScoped(
        context: Context,
        source: File,
        displayName: String,
        mimeType: String,
    ): String {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType.ifBlank { "image/png" })
            put(MediaStore.Images.Media.RELATIVE_PATH, "$PUBLIC_DIR/$APP_DIR")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Cannot create MediaStore entry.")
        runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Cannot open MediaStore output stream.")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }.onFailure {
            resolver.delete(uri, null, null)
            throw it
        }.getOrThrow()
        return "$PUBLIC_DIR/$APP_DIR/$displayName"
    }

    private fun exportImageLegacy(source: File, displayName: String): String {
        val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), APP_DIR)
        targetDir.mkdirs()
        val target = uniqueFile(File(targetDir, displayName))
        source.copyTo(target, overwrite = true)
        return target.absolutePath
    }

    private fun uniqueFile(file: File): File {
        if (!file.exists()) return file
        val name = file.nameWithoutExtension
        val ext = file.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        var index = 1
        while (true) {
            val candidate = File(file.parentFile, "${name}_$index$ext")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private const val PUBLIC_DIR = "Pictures"
    private const val APP_DIR = "AI Image Box"
}
