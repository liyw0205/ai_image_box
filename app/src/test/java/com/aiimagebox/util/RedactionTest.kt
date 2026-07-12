package com.aiimagebox.util

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactionTest {
    @Test
    fun recursivelyRedactsNestedAndEmbeddedCredentials() {
        val source = JSONObject()
            .put("parameters", JSONObject()
                .put("api_key", "raw-key")
                .put("headers", JSONArray().put(JSONObject().put("Authorization", "Bearer abc.def"))))
            .put("agent_output", "{\"cookie\":\"session-value\",\"note\":\"safe\"}")
            .put("error", "request failed with token=raw-token")

        val result = Redaction.redactJsonObject(source)

        assertEquals(Redaction.MASK, result.getJSONObject("parameters").getString("api_key"))
        assertEquals(Redaction.MASK, result.getJSONObject("parameters").getJSONArray("headers").getJSONObject(0).getString("Authorization"))
        val embedded = JSONObject(result.getString("agent_output"))
        assertEquals(Redaction.MASK, embedded.getString("cookie"))
        assertEquals("safe", embedded.getString("note"))
        assertFalse(result.getString("error").contains("raw-token"))
    }

    @Test
    fun diagnosticsRedactionCoversSerializedTaskParametersAndMetadata() {
        val serialized = JSONObject()
            .put("parameters", JSONObject().put("nested", JSONObject().put("secret", "hidden")))
            .put("assets", JSONArray().put(JSONObject().put("metadata", JSONObject().put("access_token", "hidden-token"))))

        val result = DiagnosticsExporter.redactForExport(serialized)

        assertEquals(Redaction.MASK, result.getJSONObject("parameters").getJSONObject("nested").getString("secret"))
        assertEquals(Redaction.MASK, result.getJSONArray("assets").getJSONObject(0).getJSONObject("metadata").getString("access_token"))
        assertTrue(result.toString().contains(Redaction.MASK))
    }
}
