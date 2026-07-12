package com.aiimagebox.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportFileNameTest {
    @Test
    fun nameIsReadableAndAvoidsRepeatedExportCollision() {
        assertEquals("image_100.png", ExportFileName.unique("image.png", 100))
        assertEquals("bad_name_101.mp4", ExportFileName.unique("bad name.mp4", 101))
    }
}
