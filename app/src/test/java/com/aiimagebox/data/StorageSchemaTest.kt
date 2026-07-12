package com.aiimagebox.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageSchemaTest {
    @Test
    fun readsLegacyArrayAndWritesCurrentVersionedObject() {
        val legacy = JSONArray().put(JSONObject().put("name", "legacy"))
        assertEquals("legacy", StorageSchema.readArray(legacy, "channels").getJSONObject(0).getString("name"))

        val current = StorageSchema.versioned("channels", legacy)
        assertEquals(StorageSchema.CURRENT_VERSION, current.getInt("schema_version"))
        assertEquals("legacy", StorageSchema.readArray(current, "channels").getJSONObject(0).getString("name"))
    }
}
