package com.aiimagebox.util

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter

object JsonFiles {
    fun readJsonValue(file: File): Any? {
        if (!file.exists()) return null
        val text = file.readText(Charsets.UTF_8).trim()
        if (text.isBlank()) return null
        val value = JSONTokener(text).nextValue()
        return if (value == JSONObject.NULL) null else value
    }

    fun readObject(file: File): JSONObject? = readJsonValue(file) as? JSONObject

    fun readArray(file: File): JSONArray? = readJsonValue(file) as? JSONArray

    fun writeObject(file: File, value: JSONObject, indentSpaces: Int = 2) {
        writeTextAtomic(file, value.toString(indentSpaces))
    }

    fun writeArray(file: File, value: JSONArray, indentSpaces: Int = 2) {
        writeTextAtomic(file, value.toString(indentSpaces))
    }

    fun appendJsonLine(file: File, value: JSONObject) {
        file.parentFile?.mkdirs()
        BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8)).use { writer ->
            writer.write(value.toString())
            writer.newLine()
        }
    }

    fun readJsonLines(file: File): List<JSONObject> {
        if (!file.exists()) return emptyList()
        val items = mutableListOf<JSONObject>()
        file.forEachLine(Charsets.UTF_8) { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank()) return@forEachLine
            val item = runCatching { JSONTokener(trimmed).nextValue() as? JSONObject }.getOrNull()
            if (item != null) items.add(item)
        }
        return items
    }

    private fun writeTextAtomic(file: File, text: String) {
        val parent = file.parentFile
        parent?.mkdirs()
        val temp = File(parent ?: file.absoluteFile.parentFile, "${file.name}.tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (file.exists() && !file.delete()) {
            temp.delete()
            throw IOException("Unable to replace ${file.absolutePath}")
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("Unable to move ${temp.absolutePath} to ${file.absolutePath}")
        }
    }
}
