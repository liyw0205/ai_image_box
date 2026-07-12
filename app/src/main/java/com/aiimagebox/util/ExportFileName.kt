package com.aiimagebox.util

object ExportFileName {
    fun unique(sourceName: String, timestamp: Long = System.currentTimeMillis()): String {
        val base = sourceName.substringBeforeLast(".").replace(Regex("[^A-Za-z0-9._-]"), "_")
            .replace(Regex("^_+|_+$"), "")
            .ifBlank { "ai_image_box" }
        val extension = sourceName.substringAfterLast(".", "").lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
        return "${base.take(72)}_$timestamp${extension?.let { ".$it" }.orEmpty()}"
    }
}
