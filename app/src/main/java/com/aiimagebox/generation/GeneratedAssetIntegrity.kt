package com.aiimagebox.generation

import java.io.File

/** Validates provider bytes before they become durable history media. */
object GeneratedAssetIntegrity {
    private const val MIN_IMAGE_BYTES = 8
    private const val MIN_VIDEO_BYTES = 12

    enum class MediaKind { IMAGE, VIDEO }

    data class Verdict(val valid: Boolean, val reason: String = "")

    fun validateBytes(bytes: ByteArray, declaredMimeType: String, expectedKind: MediaKind): Verdict {
        val minimum = if (expectedKind == MediaKind.VIDEO) MIN_VIDEO_BYTES else MIN_IMAGE_BYTES
        if (bytes.size < minimum) return Verdict(false, "结果文件过小（${bytes.size} 字节）")
        if (looksLikeHtml(bytes)) return Verdict(false, "结果内容是 HTML 错误页")

        val actualKind = detectKind(bytes)
        if (actualKind != null && actualKind != expectedKind) {
            return Verdict(false, "结果内容类型与预期不一致")
        }
        if (actualKind == null && !isStreamingPlaylist(bytes, expectedKind)) {
            return Verdict(false, "无法识别结果文件格式")
        }
        val declaredKind = kindForMime(declaredMimeType)
        if (declaredKind != null && declaredKind != expectedKind) {
            return Verdict(false, "声明 MIME 类型与预期不一致")
        }
        return Verdict(true)
    }

    fun validateFile(file: File, declaredMimeType: String, expectedKind: MediaKind): Verdict {
        if (!file.isFile) return Verdict(false, "结果文件不存在")
        return validateBytes(file.readBytes(), declaredMimeType, expectedKind)
    }

    fun requireValid(bytes: ByteArray, declaredMimeType: String, expectedKind: MediaKind) {
        validateBytes(bytes, declaredMimeType, expectedKind).takeUnless { it.valid }?.let {
            throw IllegalStateException("生成结果完整性校验失败：${it.reason}")
        }
    }

    private fun kindForMime(mimeType: String): MediaKind? = when {
        mimeType.lowercase().startsWith("image/") -> MediaKind.IMAGE
        mimeType.lowercase().startsWith("video/") || mimeType.equals("application/vnd.apple.mpegurl", true) -> MediaKind.VIDEO
        else -> null
    }

    private fun detectKind(bytes: ByteArray): MediaKind? = when {
        isPng(bytes) || isJpeg(bytes) || isGif(bytes) || isWebp(bytes) -> MediaKind.IMAGE
        isIsoBmff(bytes) || isWebm(bytes) -> MediaKind.VIDEO
        else -> null
    }

    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        val preview = bytes.copyOfRange(0, minOf(bytes.size, 512)).toString(Charsets.UTF_8).trimStart().lowercase()
        return preview.startsWith("<!doctype html") || preview.startsWith("<html") || preview.startsWith("<head") || preview.startsWith("<body")
    }

    private fun isStreamingPlaylist(bytes: ByteArray, expectedKind: MediaKind): Boolean =
        expectedKind == MediaKind.VIDEO && bytes.copyOfRange(0, minOf(bytes.size, 64)).toString(Charsets.UTF_8).trimStart().startsWith("#EXTM3U")

    private fun isPng(bytes: ByteArray): Boolean = bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
    private fun isJpeg(bytes: ByteArray): Boolean = bytes.size >= 3 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() && bytes[2] == (-1).toByte()
    private fun isGif(bytes: ByteArray): Boolean = bytes.size >= 6 && (bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII) == "GIF87a" || bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII) == "GIF89a")
    private fun isWebp(bytes: ByteArray): Boolean = bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
    private fun isIsoBmff(bytes: ByteArray): Boolean = bytes.size >= 8 && bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp"
    private fun isWebm(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))
}
