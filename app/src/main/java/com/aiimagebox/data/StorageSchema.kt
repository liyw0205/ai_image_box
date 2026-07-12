package com.aiimagebox.data

import org.json.JSONArray
import org.json.JSONObject

/** Normalizes legacy array files and current versioned object files at one boundary. */
object StorageSchema {
    const val CURRENT_VERSION = 2

    fun readArray(root: Any?, key: String): JSONArray = when (root) {
        is JSONObject -> root.optJSONArray(key) ?: JSONArray()
        is JSONArray -> root
        else -> JSONArray()
    }

    fun versioned(key: String, values: JSONArray): JSONObject = JSONObject()
        .put("schema_version", CURRENT_VERSION)
        .put("updated_at", System.currentTimeMillis())
        .put(key, values)
}
