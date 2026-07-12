package com.aiimagebox.generation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedAssetIntegrityTest {
    @Test
    fun acceptsRecognizedImageAndVideoBytes() {
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        val mp4 = byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112, 105, 115, 111, 109)

        assertTrue(GeneratedAssetIntegrity.validateBytes(png, "image/png", GeneratedAssetIntegrity.MediaKind.IMAGE).valid)
        assertTrue(GeneratedAssetIntegrity.validateBytes(mp4, "video/mp4", GeneratedAssetIntegrity.MediaKind.VIDEO).valid)
    }

    @Test
    fun rejectsEmptyHtmlAndMismatchedResults() {
        val html = "<!doctype html><html><body>upstream failed</body></html>".toByteArray()
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

        assertFalse(GeneratedAssetIntegrity.validateBytes(ByteArray(0), "image/png", GeneratedAssetIntegrity.MediaKind.IMAGE).valid)
        assertFalse(GeneratedAssetIntegrity.validateBytes(html, "video/mp4", GeneratedAssetIntegrity.MediaKind.VIDEO).valid)
        assertFalse(GeneratedAssetIntegrity.validateBytes(png, "image/png", GeneratedAssetIntegrity.MediaKind.VIDEO).valid)
    }
}
