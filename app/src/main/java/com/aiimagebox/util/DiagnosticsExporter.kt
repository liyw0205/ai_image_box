package com.aiimagebox.util

import android.os.Build
import com.aiimagebox.data.AppDirectories
import com.aiimagebox.data.GenerationStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticsExporter {
    fun create(appDirectories: AppDirectories, generationStore: GenerationStore): File {
        val output = File(appDirectories.diagnostics, "diagnostics_${System.currentTimeMillis()}.zip")
        val records = JSONArray().also { array ->
            generationStore.listRecentRecords(100).forEach { record -> array.put(redactForExport(record.toJson(redact = true))) }
        }
        val tasks = JSONArray().also { array ->
            generationStore.loadTasks().takeLast(100).forEach { task -> array.put(redactForExport(task.toJson(redact = true))) }
        }
        val summary = JSONObject()
            .put("created_at", System.currentTimeMillis())
            .put("device", Build.MANUFACTURER + " " + Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("cache_bytes", directorySize(appDirectories.cache))
            .put("task_count", tasks.length())
            .put("record_count", records.length())
        ZipOutputStream(output.outputStream()).use { zip ->
            zip.writeJson("summary.json", summary)
            zip.writeJson("tasks.json", tasks)
            zip.writeJson("records.json", records)
        }
        return output
    }

    internal fun redactForExport(value: JSONObject): JSONObject = Redaction.redactJsonObject(value)

    fun directorySize(directory: File): Long = directory.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }

    private fun ZipOutputStream.writeJson(name: String, value: Any) {
        putNextEntry(ZipEntry(name))
        write(value.toString().toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
