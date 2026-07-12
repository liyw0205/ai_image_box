package com.aiimagebox.generation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReferenceMediaPolicyTest {
    @Test
    fun identifiesImageAndVideoByExistingFileExtension() {
        val image = File.createTempFile("reference", ".png")
        val video = File.createTempFile("reference", ".mp4")
        try {
            assertEquals(ReferenceMediaPolicy.Kind.IMAGE, ReferenceMediaPolicy.kindForPath(image.absolutePath))
            assertEquals(ReferenceMediaPolicy.Kind.VIDEO, ReferenceMediaPolicy.kindForPath(video.absolutePath))
            assertTrue(ReferenceMediaPolicy.isSupportedByCurrentAdapters(ReferenceMediaPolicy.Kind.IMAGE))
            assertFalse(ReferenceMediaPolicy.isSupportedByCurrentAdapters(ReferenceMediaPolicy.Kind.VIDEO))
        } finally {
            image.delete()
            video.delete()
        }
    }

    @Test
    fun rejectsMissingAndUnknownReferencesBeforeQueueing() {
        assertEquals(ReferenceMediaPolicy.Kind.MISSING, ReferenceMediaPolicy.kindForPath("/not/found/reference.png"))
        val unknown = File.createTempFile("reference", ".bin")
        try {
            assertEquals(ReferenceMediaPolicy.Kind.UNKNOWN, ReferenceMediaPolicy.kindForPath(unknown.absolutePath))
            assertFalse(ReferenceMediaPolicy.isSupportedByCurrentAdapters(ReferenceMediaPolicy.Kind.UNKNOWN))
        } finally {
            unknown.delete()
        }
    }
}
