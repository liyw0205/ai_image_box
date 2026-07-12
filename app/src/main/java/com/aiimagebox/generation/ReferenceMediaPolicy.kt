package com.aiimagebox.generation

import java.io.File

internal object ReferenceMediaPolicy {
    enum class Kind {
        IMAGE,
        VIDEO,
        UNKNOWN,
        MISSING,
    }

    fun kindForPath(filePath: String): Kind {
        val path = filePath.trim()
        if (path.isBlank() || !File(path).isFile) return Kind.MISSING
        return when (path.substringAfterLast(".", "").lowercase()) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif" -> Kind.IMAGE
            "mp4", "m4v", "webm", "mov", "avi", "mkv" -> Kind.VIDEO
            else -> Kind.UNKNOWN
        }
    }

    fun isSupportedByCurrentAdapters(kind: Kind): Boolean = kind == Kind.IMAGE
}
