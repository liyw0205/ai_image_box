package com.aiimagebox.data

import com.aiimagebox.util.JsonFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class GenerationStore(appDirectories: AppDirectories) {
    private val taskStateFile: File = File(appDirectories.records, TASK_STATE_FILE)
    private val recordsFile: File = File(appDirectories.records, RECORDS_FILE)

    @Synchronized
    fun loadTasks(): List<GenerationTask> {
        return runCatching {
            when (val value = JsonFiles.readJsonValue(taskStateFile)) {
                is JSONObject -> tasksFromArray(value.optJSONArray("tasks") ?: JSONArray())
                is JSONArray -> tasksFromArray(value)
                else -> emptyList()
            }
        }.getOrElse { emptyList() }
    }

    @Synchronized
    fun saveTasks(tasks: List<GenerationTask>) {
        val array = JSONArray()
        tasks.forEach { array.put(it.toJson()) }
        JsonFiles.writeObject(
            taskStateFile,
            JSONObject()
                .put("schema_version", SCHEMA_VERSION)
                .put("updated_at", System.currentTimeMillis())
                .put("tasks", array),
        )
    }

    @Synchronized
    fun createTask(task: GenerationTask): GenerationTask {
        val now = System.currentTimeMillis()
        val stored = task.copy(
            createdAt = if (task.createdAt > 0L) task.createdAt else now,
            updatedAt = now,
        )
        val tasks = loadTasks().filterNot { it.id == stored.id }.toMutableList()
        tasks.add(stored)
        saveTasks(tasks)
        return stored
    }

    @Synchronized
    fun updateTask(task: GenerationTask): GenerationTask {
        val now = System.currentTimeMillis()
        val tasks = loadTasks().toMutableList()
        val index = tasks.indexOfFirst { it.id == task.id }
        val stored = if (index >= 0) {
            task.copy(createdAt = tasks[index].createdAt, updatedAt = now)
        } else {
            task.copy(updatedAt = now)
        }
        if (index >= 0) {
            tasks[index] = stored
        } else {
            tasks.add(stored)
        }
        saveTasks(tasks)
        return stored
    }

    @Synchronized
    fun updateTask(taskId: String, transform: (GenerationTask) -> GenerationTask): GenerationTask? {
        val tasks = loadTasks().toMutableList()
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index < 0) return null

        val current = tasks[index]
        val updated = transform(current).copy(
            id = current.id,
            createdAt = current.createdAt,
            updatedAt = System.currentTimeMillis(),
        )
        tasks[index] = updated
        saveTasks(tasks)
        return updated
    }

    @Synchronized
    fun getTask(taskId: String): GenerationTask? = loadTasks().firstOrNull { it.id == taskId }

    @Synchronized
    fun deleteTask(taskId: String) {
        saveTasks(loadTasks().filterNot { it.id == taskId })
    }

    @Synchronized
    fun appendAttempt(taskId: String, attempt: AttemptRecord): GenerationTask? {
        return updateTask(taskId) { task ->
            val now = System.currentTimeMillis()
            val storedAttempt = attempt.copy(
                taskId = task.id,
                attemptNumber = attempt.attemptNumber.takeIf { it > 0 } ?: (task.attempts.size + 1),
            )
            task.copy(
                status = storedAttempt.status,
                attempts = task.attempts + storedAttempt,
                startedAt = task.startedAt ?: storedAttempt.startedAt,
                completedAt = if (storedAttempt.status.isTerminal) {
                    storedAttempt.endedAt ?: task.completedAt ?: now
                } else {
                    task.completedAt
                },
                errorMessage = storedAttempt.errorMessage.ifBlank { task.errorMessage },
            )
        }
    }

    @Synchronized
    fun appendRecord(record: GenerationRecord): GenerationRecord {
        JsonFiles.appendJsonLine(recordsFile, record.toJson())
        return record
    }

    @Synchronized
    fun appendRecord(task: GenerationTask): GenerationRecord {
        val record = task.toRecord()
        appendRecord(record)
        return record
    }

    @Synchronized
    fun listRecentRecords(limit: Int = DEFAULT_RECORD_LIMIT): List<GenerationRecord> {
        if (limit <= 0) return emptyList()
        return JsonFiles.readJsonLines(recordsFile)
            .takeLast(limit.coerceAtMost(MAX_RECORD_LIMIT))
            .mapNotNull { value -> runCatching { GenerationRecord.fromJson(value) }.getOrNull() }
            .asReversed()
    }

    private fun tasksFromArray(array: JSONArray): List<GenerationTask> {
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(GenerationTask.fromJson(item))
            }
        }
    }

    companion object {
        const val TASK_STATE_FILE = "task_state.json"
        const val RECORDS_FILE = "generation_records.jsonl"
        const val SCHEMA_VERSION = 1
        const val DEFAULT_RECORD_LIMIT = 50
        const val MAX_RECORD_LIMIT = 500
    }
}
