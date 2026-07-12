package com.aiimagebox.data

import java.io.File

data class AppDirectories(
    val root: File,
    val config: File,
    val records: File,
    val cache: File,
    val requestImages: File,
    val generatedImages: File,
    val generatedVideos: File,
    val generatedThumbnails: File,
    val responseBodies: File,
    val diagnostics: File,
) {
    companion object {
        fun ensure(filesDir: File): AppDirectories {
            val root = File(filesDir, "ai_image_box")
            val dirs = AppDirectories(
                root = root,
                config = File(root, "config"),
                records = File(root, "records"),
                cache = File(root, "cache"),
                requestImages = File(root, "cache/request_images"),
                generatedImages = File(root, "cache/generated_images"),
                generatedVideos = File(root, "cache/generated_videos"),
                generatedThumbnails = File(root, "cache/generated_thumbnails"),
                responseBodies = File(root, "cache/response_bodies"),
                diagnostics = File(root, "diagnostics"),
            )
            listOf(
                dirs.root,
                dirs.config,
                dirs.records,
                dirs.cache,
                dirs.requestImages,
                dirs.generatedImages,
                dirs.generatedVideos,
                dirs.generatedThumbnails,
                dirs.responseBodies,
                dirs.diagnostics,
            ).forEach { it.mkdirs() }
            return dirs
        }
    }
}

