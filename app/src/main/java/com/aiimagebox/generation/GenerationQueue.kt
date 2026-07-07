package com.aiimagebox.generation

import java.util.ArrayDeque

data class GenerationQueueItem(
    val request: GenerationRequest,
    val status: GenerationStatus,
    val queuedAtMillis: Long = System.currentTimeMillis(),
    val startedAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
    val errorMessage: String? = null,
    val resultByteCount: Int? = null,
)

data class GenerationQueueState(
    val items: List<GenerationQueueItem> = emptyList(),
) {
    val queuedCount: Int = items.count { it.status == GenerationStatus.QUEUED }
    val runningCount: Int = items.count { it.status == GenerationStatus.RUNNING }
    val succeededCount: Int = items.count { it.status == GenerationStatus.SUCCEEDED }
    val failedCount: Int = items.count { it.status == GenerationStatus.FAILED }
    val cancelledCount: Int = items.count { it.status == GenerationStatus.CANCELLED }
    val isIdle: Boolean = queuedCount == 0 && runningCount == 0
}

class GenerationQueue(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val pendingIds = ArrayDeque<String>()
    private val itemsById = LinkedHashMap<String, GenerationQueueItem>()

    fun enqueue(request: GenerationRequest): GenerationQueueItem = synchronized(lock) {
        require(request.id.isNotBlank()) { "Generation request id cannot be blank." }
        require(request.prompt.isNotBlank()) { "Generation prompt cannot be blank." }
        require(request.target.channelId.isNotBlank()) { "Generation target channelId cannot be blank." }
        require(request.target.model.isNotBlank()) { "Generation target model cannot be blank." }
        require(!itemsById.containsKey(request.id)) { "Generation request already exists: ${request.id}" }

        val item = GenerationQueueItem(
            request = request,
            status = GenerationStatus.QUEUED,
            queuedAtMillis = clock(),
        )
        itemsById[request.id] = item
        pendingIds.addLast(request.id)
        item
    }

    fun startNext(): GenerationQueueItem? = synchronized(lock) {
        var next: GenerationQueueItem? = null
        while (next == null && pendingIds.isNotEmpty()) {
            val id = pendingIds.pollFirst() ?: break
            val current = itemsById[id] ?: continue
            if (current.status != GenerationStatus.QUEUED) continue

            val updated = current.copy(
                status = GenerationStatus.RUNNING,
                startedAtMillis = clock(),
                errorMessage = null,
            )
            itemsById[id] = updated
            next = updated
        }
        next
    }

    fun complete(id: String, resultByteCount: Int): GenerationQueueItem? = synchronized(lock) {
        val current = itemsById[id] ?: return@synchronized null
        if (current.status != GenerationStatus.RUNNING) return@synchronized null

        val updated = current.copy(
            status = GenerationStatus.SUCCEEDED,
            completedAtMillis = clock(),
            errorMessage = null,
            resultByteCount = resultByteCount,
        )
        itemsById[id] = updated
        updated
    }

    fun fail(id: String, error: Throwable): GenerationQueueItem? = synchronized(lock) {
        val current = itemsById[id] ?: return@synchronized null
        if (current.status != GenerationStatus.RUNNING) return@synchronized null

        val updated = current.copy(
            status = GenerationStatus.FAILED,
            completedAtMillis = clock(),
            errorMessage = error.message ?: error::class.java.simpleName,
        )
        itemsById[id] = updated
        updated
    }

    fun cancel(id: String, reason: String? = null): GenerationQueueItem? = synchronized(lock) {
        val current = itemsById[id] ?: return@synchronized null
        if (current.status != GenerationStatus.QUEUED && current.status != GenerationStatus.RUNNING) {
            return@synchronized null
        }

        pendingIds.remove(id)
        val updated = current.copy(
            status = GenerationStatus.CANCELLED,
            completedAtMillis = clock(),
            errorMessage = reason,
        )
        itemsById[id] = updated
        updated
    }

    fun cancelQueued(reason: String? = null): List<GenerationQueueItem> = synchronized(lock) {
        buildList {
            while (true) {
                val id = pendingIds.pollFirst() ?: break
                val current = itemsById[id] ?: continue
                if (current.status != GenerationStatus.QUEUED) continue

                val updated = current.copy(
                    status = GenerationStatus.CANCELLED,
                    completedAtMillis = clock(),
                    errorMessage = reason,
                )
                itemsById[id] = updated
                add(updated)
            }
        }
    }

    fun removeFinished(): Int = synchronized(lock) {
        val finished = itemsById
            .filterValues {
                it.status == GenerationStatus.SUCCEEDED ||
                    it.status == GenerationStatus.FAILED ||
                    it.status == GenerationStatus.CANCELLED
            }
            .keys
            .toList()

        finished.forEach(itemsById::remove)
        finished.size
    }

    fun snapshot(): GenerationQueueState = synchronized(lock) {
        GenerationQueueState(itemsById.values.toList())
    }
}
