package com.aiimagebox

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aiimagebox.databinding.ActivityMainBinding
import com.aiimagebox.data.AppDirectories
import com.aiimagebox.data.ChannelStore
import com.aiimagebox.data.AttemptRecord as StoredAttemptRecord
import com.aiimagebox.data.GeneratedAsset as StoredGeneratedAsset
import com.aiimagebox.data.GenerationMode as StoredGenerationMode
import com.aiimagebox.data.GenerationRecord
import com.aiimagebox.data.GenerationStatus as StoredGenerationStatus
import com.aiimagebox.data.GenerationStore
import com.aiimagebox.data.GenerationTask
import com.aiimagebox.data.MediaReference as StoredMediaReference
import com.aiimagebox.data.ProviderChannel
import com.aiimagebox.data.SecureKeyStore
import com.aiimagebox.generation.GenerationEvent
import com.aiimagebox.generation.GenerationManager
import com.aiimagebox.generation.GenerationParameters
import com.aiimagebox.generation.GenerationProviderException
import com.aiimagebox.generation.GenerationQueueItem
import com.aiimagebox.generation.GenerationRequest
import com.aiimagebox.generation.GenerationStatus as QueueGenerationStatus
import com.aiimagebox.generation.GenerationTarget
import com.aiimagebox.provider.ProviderRegistry
import com.aiimagebox.ui.StudioForm
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var appDirectories: AppDirectories
    private lateinit var channelStore: ChannelStore
    private lateinit var generationStore: GenerationStore
    private lateinit var generationManager: GenerationManager
    private var currentTab: Tab = Tab.STUDIO
    private var syncingNav = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appDirectories = (application as AIImageBoxApp).appDirectories
        channelStore = ChannelStore(appDirectories)
        generationStore = GenerationStore(appDirectories)
        generationManager = GenerationManager()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentTab = Tab.fromId(savedInstanceState?.getInt(KEY_TAB) ?: R.id.nav_studio)
        wireNavigation()
        wireStudioForm()
        observeGenerationEvents()
        restorePendingTasks()
        render(currentTab)
    }

    override fun onDestroy() {
        generationManager.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_TAB, currentTab.itemId)
        super.onSaveInstanceState(outState)
    }

    private fun wireNavigation() {
        val listener = NavigationBarView.OnItemSelectedListener { item ->
            if (syncingNav) return@OnItemSelectedListener true
            render(Tab.fromId(item.itemId))
            true
        }
        binding.bottomNav.setOnItemSelectedListener(listener)
        binding.navRail.setOnItemSelectedListener(listener)

        binding.primaryAction.setOnClickListener {
            when (currentTab) {
                Tab.CHANNELS -> showChannelDialog(null)
                Tab.TASKS -> clearFinishedTasks()
                else -> Toast.makeText(this, getString(R.string.toast_next_milestone), Toast.LENGTH_SHORT).show()
            }
        }
        binding.secondaryAction.setOnClickListener {
            if (currentTab == Tab.CHANNELS) {
                renderChannelList()
            } else {
                Toast.makeText(this, getString(R.string.toast_provider_coming), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun render(tab: Tab) {
        currentTab = tab
        syncingNav = true
        binding.bottomNav.selectedItemId = tab.itemId
        binding.navRail.selectedItemId = tab.itemId
        syncingNav = false

        binding.toolbar.title = getString(tab.titleRes)
        binding.sectionLabel.text = getString(tab.badgeRes)
        binding.headline.text = getString(tab.headlineRes)
        binding.body.text = getString(tab.bodyRes)
        binding.statusText.text = getString(tab.statusRes)
        binding.primaryAction.text = getString(tab.primaryActionRes)
        binding.secondaryAction.visibility = if (tab == Tab.CHANNELS) View.VISIBLE else View.GONE
        binding.secondaryAction.text = getString(R.string.action_refresh_channels)
        binding.channelPanel.visibility = if (tab == Tab.CHANNELS) View.VISIBLE else View.GONE
        binding.studioForm.visibility = if (tab == Tab.STUDIO) View.VISIBLE else View.GONE
        binding.taskPanel.visibility = if (tab == Tab.TASKS) View.VISIBLE else View.GONE
        binding.historyPanel.visibility = if (tab == Tab.HISTORY) View.VISIBLE else View.GONE
        if (tab == Tab.STUDIO) bindStudioChannels()
        if (tab == Tab.TASKS) renderTaskPanel()
        if (tab == Tab.HISTORY) renderHistoryPanel()
        if (tab == Tab.CHANNELS) renderChannelList()
    }

    private fun wireStudioForm() {
        binding.studioForm.setOnSubmitListener { request ->
            submitStudioRequest(request)
        }
    }

    private fun bindStudioChannels() {
        binding.studioForm.bindChannels(channelStore.load())
    }

    private fun submitStudioRequest(request: StudioForm.StudioSubmitRequest) {
        val channel = channelStore.load().firstOrNull { it.id == request.channelId && it.enabled }
        if (channel == null) {
            binding.studioForm.setStatus(getString(R.string.studio_generate_no_channel))
            return
        }

        val generationRequest = GenerationRequest(
            prompt = request.prompt,
            target = GenerationTarget.fromChannel(channel, request.model),
            parameters = GenerationParameters(
                aspectRatio = request.aspectRatio,
                resolution = imageSize(request.resolution, request.aspectRatio),
                count = request.quantity,
                responseFormat = "b64_json",
            ),
        )
        createStoredTask(generationRequest)
        binding.studioForm.setSubmitting(true)
        binding.studioForm.setStatus(getString(R.string.studio_generate_enqueued, generationRequest.id.take(8)))
        generationManager.enqueue(generationRequest)
    }

    private fun createStoredTask(request: GenerationRequest): GenerationTask {
        return generationStore.createTask(
            GenerationTask(
                id = request.id,
                mode = StoredGenerationMode.IMAGE,
                status = StoredGenerationStatus.QUEUED,
                prompt = request.prompt,
                channelId = request.target.channelId,
                channelName = request.target.channelName,
                providerType = request.target.providerType,
                model = request.target.model,
                parametersJson = JSONObject()
                    .put("aspect_ratio", request.parameters.aspectRatio.ifBlank { "1:1" })
                    .put("resolution", request.parameters.resolution ?: request.parameters.size ?: "1024x1024")
                    .put("count", request.parameters.count)
                    .toString(),
            ),
        )
    }

    private fun restorePendingTasks() {
        val channelsById = channelStore.load().associateBy { it.id }
        var restoredCount = 0
        var blockedCount = 0
        generationStore.loadTasks()
            .filter { it.status == StoredGenerationStatus.QUEUED || it.status == StoredGenerationStatus.RUNNING }
            .forEach { task ->
                val channel = channelsById[task.channelId]
                if (channel != null && channel.enabled) {
                    generationStore.updateTask(task.id) {
                        it.copy(
                            status = StoredGenerationStatus.QUEUED,
                            errorMessage = getString(R.string.task_restored_after_restart),
                            startedAt = null,
                            completedAt = null,
                        )
                    }
                    generationManager.enqueue(task.toGenerationRequest(channel))
                    restoredCount++
                } else {
                    val updated = generationStore.updateTask(task.id) {
                        it.copy(
                            status = StoredGenerationStatus.FAILED,
                            errorMessage = getString(R.string.task_restore_channel_missing),
                            completedAt = System.currentTimeMillis(),
                        )
                    }
                    if (updated != null) generationStore.appendRecord(updated)
                    blockedCount++
                }
            }
        if (restoredCount > 0 || blockedCount > 0) {
            binding.studioForm.setStatus(getString(R.string.task_restore_summary, restoredCount, blockedCount))
        }
    }

    private fun GenerationTask.toGenerationRequest(channel: ProviderChannel): GenerationRequest {
        val parameters = runCatching { JSONObject(parametersJson) }.getOrDefault(JSONObject())
        return GenerationRequest(
            id = id,
            prompt = prompt,
            target = GenerationTarget.fromChannel(channel, model.ifBlank { channel.defaultModel }),
            parameters = GenerationParameters(
                aspectRatio = parameters.optString("aspect_ratio", "1:1"),
                resolution = parameters.optString("resolution", "1024x1024"),
                count = parameters.optInt("count", 1).coerceIn(1, 10),
                responseFormat = "b64_json",
            ),
            createdAtMillis = createdAt,
        )
    }

    private fun observeGenerationEvents() {
        lifecycleScope.launch {
            generationManager.events.collect { event ->
                handleGenerationEvent(event)
            }
        }
    }

    private suspend fun handleGenerationEvent(event: GenerationEvent) {
        when (event) {
            is GenerationEvent.Enqueued -> Unit
            is GenerationEvent.Started -> {
                withContext(Dispatchers.IO) {
                    generationStore.updateTask(event.requestId) {
                        it.copy(
                            status = StoredGenerationStatus.RUNNING,
                            errorMessage = "",
                            startedAt = System.currentTimeMillis(),
                        )
                    }
                }
                if (currentTab == Tab.TASKS) renderTaskPanel()
                binding.studioForm.setSubmitting(true)
                binding.studioForm.setStatus(
                    getString(
                        R.string.studio_generate_started,
                        event.item.request.target.channelName.ifBlank { event.item.request.target.channelId },
                        event.item.request.target.model,
                    ),
                )
            }
            is GenerationEvent.Succeeded -> {
                val savedPaths = withContext(Dispatchers.IO) {
                    saveGenerationSuccess(event)
                }
                val savedSummary = savedPaths.toSavedSummary()
                if (currentTab == Tab.TASKS) renderTaskPanel()
                if (currentTab == Tab.HISTORY) renderHistoryPanel()
                binding.studioForm.setSubmitting(!generationManager.snapshot().isIdle)
                binding.studioForm.setResultPlaceholder(
                    getString(R.string.studio_generate_succeeded_multi, savedPaths.size, savedSummary),
                )
                binding.studioForm.setStatus(getString(R.string.studio_generate_succeeded_multi, savedPaths.size, savedSummary))
            }
            is GenerationEvent.Failed -> {
                withContext(Dispatchers.IO) {
                    val updated = generationStore.updateTask(event.requestId) {
                        it.copy(
                            status = StoredGenerationStatus.FAILED,
                            errorMessage = event.error.message ?: event.error::class.java.simpleName,
                            completedAt = System.currentTimeMillis(),
                            attempts = it.attempts + storedAttempt(event, StoredGenerationStatus.FAILED, event.error.message ?: ""),
                        )
                    }
                    if (updated != null) generationStore.appendRecord(updated)
                }
                if (currentTab == Tab.TASKS) renderTaskPanel()
                if (currentTab == Tab.HISTORY) renderHistoryPanel()
                binding.studioForm.setSubmitting(!generationManager.snapshot().isIdle)
                binding.studioForm.setStatus(
                    getString(R.string.studio_generate_failed, event.error.message ?: event.error::class.java.simpleName),
                )
            }
            is GenerationEvent.Cancelled -> {
                withContext(Dispatchers.IO) {
                    val updated = generationStore.updateTask(event.requestId) {
                        it.copy(
                            status = StoredGenerationStatus.CANCELED,
                            errorMessage = event.reason.orEmpty(),
                            completedAt = System.currentTimeMillis(),
                        )
                    }
                    if (updated != null) generationStore.appendRecord(updated)
                }
                if (currentTab == Tab.TASKS) renderTaskPanel()
                if (currentTab == Tab.HISTORY) renderHistoryPanel()
                binding.studioForm.setSubmitting(!generationManager.snapshot().isIdle)
                binding.studioForm.setStatus(getString(R.string.studio_generate_cancelled, event.reason.orEmpty()))
            }
        }
    }

    private fun saveGenerationSuccess(event: GenerationEvent.Succeeded): List<String> {
        val request = event.item.request
        val result = event.result
        val savedAt = System.currentTimeMillis()
        val assets = result.assets.mapIndexed { index, generatedAsset ->
            val ext = extensionForMime(generatedAsset.mimeType)
            val suffix = if (result.assets.size > 1) "_${index + 1}" else ""
            val file = File(appDirectories.generatedImages, "image_${savedAt}_${request.id.take(8)}$suffix.$ext")
            file.writeBytes(generatedAsset.bytes)
            val dimensions = imageDimensions(file)
            StoredGeneratedAsset(
                mode = StoredGenerationMode.IMAGE,
                media = StoredMediaReference(
                    filePath = file.absolutePath,
                    mimeType = generatedAsset.mimeType,
                    displayName = file.name,
                    sizeBytes = generatedAsset.bytes.size.toLong(),
                    width = dimensions.first,
                    height = dimensions.second,
                ),
                channelId = request.target.channelId,
                channelName = request.target.channelName,
                providerType = request.target.providerType,
                model = request.target.model,
                metadataJson = JSONObject(generatedAsset.metadata.ifEmpty { result.metadata }).toString(),
            )
        }
        val updated = generationStore.updateTask(request.id) {
            it.copy(
                status = StoredGenerationStatus.SUCCEEDED,
                assets = it.assets + assets,
                attempts = it.attempts + storedAttempt(event, StoredGenerationStatus.SUCCEEDED, ""),
                errorMessage = "",
                completedAt = System.currentTimeMillis(),
            )
        }
        if (updated != null) generationStore.appendRecord(updated)
        return assets.map { it.media.filePath }
    }

    private fun storedAttempt(
        event: GenerationEvent,
        status: StoredGenerationStatus,
        error: String,
    ): StoredAttemptRecord {
        val item = when (event) {
            is GenerationEvent.Succeeded -> event.item
            is GenerationEvent.Failed -> event.item
            is GenerationEvent.Cancelled -> event.item
            is GenerationEvent.Enqueued -> event.item
            is GenerationEvent.Started -> event.item
        }
        val now = System.currentTimeMillis()
        val providerError = (event as? GenerationEvent.Failed)?.error as? GenerationProviderException
        val requestJson = JSONObject()
            .put("prompt", item.request.prompt)
            .put("channel_id", item.request.target.channelId)
            .put("model", item.request.target.model)
            .put("aspect_ratio", item.request.parameters.aspectRatio)
            .put("resolution", item.request.parameters.resolution ?: item.request.parameters.size.orEmpty())
            .put("count", item.request.parameters.count)
            .toString()
        val responseJson = providerError?.providerResult?.let { result ->
            JSONObject()
                .put("provider_status", result.status.name)
                .put("provider_request_id", result.requestId)
                .put("http_status", result.httpStatus ?: JSONObject.NULL)
                .put("elapsed_ms", result.elapsedMillis)
                .put("raw_preview", result.rawPreview)
                .put("error", result.error)
                .toString()
        } ?: "{}"
        return StoredAttemptRecord(
            taskId = item.request.id,
            attemptNumber = 1,
            status = status,
            channelId = item.request.target.channelId,
            channelName = item.request.target.channelName,
            providerType = item.request.target.providerType,
            model = item.request.target.model,
            requestJson = requestJson,
            responseJson = responseJson,
            httpStatusCode = providerError?.providerResult?.httpStatus,
            errorMessage = error.ifBlank { providerError?.providerResult?.error.orEmpty() },
            startedAt = item.startedAtMillis ?: now,
            endedAt = now,
            durationMs = providerError?.providerResult?.elapsedMillis ?: (item.startedAtMillis ?: now).let { now - it },
        )
    }

    private fun imageSize(resolution: String, aspectRatio: String): String {
        val longSide = resolution.toIntOrNull()?.coerceIn(256, 4096) ?: 1024
        val (width, height) = when (aspectRatio) {
            "3:4" -> (longSide * 3 / 4) to longSide
            "4:3" -> longSide to (longSide * 3 / 4)
            "16:9" -> longSide to (longSide * 9 / 16)
            else -> longSide to longSide
        }
        return "${width}x${height}"
    }

    private fun extensionForMime(mimeType: String): String {
        return when (mimeType.lowercase().substringBefore(';')) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "png"
        }
    }

    private fun imageDimensions(file: File): Pair<Int?, Int?> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val width = options.outWidth.takeIf { it > 0 }
        val height = options.outHeight.takeIf { it > 0 }
        return width to height
    }

    private fun decodeThumbnail(filePath: String, maxSidePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxSidePx || bounds.outHeight / sampleSize > maxSidePx) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize.coerceAtLeast(1) }
        return BitmapFactory.decodeFile(filePath, options)
    }

    private fun List<String>.toSavedSummary(): String {
        if (isEmpty()) return "-"
        val preview = take(3).joinToString("\n")
        return if (size > 3) "$preview\n..." else preview
    }

    private fun renderTaskPanel() {
        val state = generationManager.snapshot()
        binding.taskSummary.text = getString(
            R.string.task_summary,
            state.queuedCount,
            state.runningCount,
            state.succeededCount,
            state.failedCount,
            state.cancelledCount,
        )
        binding.taskEmpty.visibility = if (state.items.isEmpty()) View.VISIBLE else View.GONE
        binding.taskList.removeAllViews()
        state.items.asReversed().forEach { item ->
            binding.taskList.addView(taskCard(item))
        }
    }

    private fun taskCard(item: GenerationQueueItem): View {
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(color(R.color.aib_surface))
            strokeColor = color(R.color.aib_line)
            strokeWidth = dp(1)
            radius = dp(8).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(10)
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        content.addView(TextView(this).apply {
            text = item.request.prompt.take(80).ifBlank { item.request.id.take(8) }
            setTextColor(color(R.color.aib_text))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = getString(
                R.string.task_card_detail,
                item.request.target.channelName.ifBlank { item.request.target.channelId },
                item.request.target.model,
                item.status.displayName(),
                item.resultByteCount?.let { "$it bytes" } ?: "-",
                item.errorMessage.orEmpty().ifBlank { "-" },
            )
            setTextColor(color(R.color.aib_text_secondary))
            textSize = 14f
            setPadding(0, dp(7), 0, 0)
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        when (item.status) {
            QueueGenerationStatus.QUEUED,
            QueueGenerationStatus.RUNNING
            -> actions.addView(actionButton(R.string.action_cancel_task) { cancelTask(item) })
            QueueGenerationStatus.FAILED,
            QueueGenerationStatus.CANCELLED,
            QueueGenerationStatus.SUCCEEDED
            -> actions.addView(actionButton(R.string.action_retry_task) { retryTask(item) })
        }
        actions.addView(actionButton(R.string.action_view_detail) { showTaskDetails(item.request.id) })
        if (actions.childCount > 0) content.addView(actions)
        card.addView(content)
        return card
    }

    private fun QueueGenerationStatus.displayName(): String {
        return when (this) {
            QueueGenerationStatus.QUEUED -> getString(R.string.task_status_queued)
            QueueGenerationStatus.RUNNING -> getString(R.string.task_status_running)
            QueueGenerationStatus.SUCCEEDED -> getString(R.string.task_status_succeeded)
            QueueGenerationStatus.FAILED -> getString(R.string.task_status_failed)
            QueueGenerationStatus.CANCELLED -> getString(R.string.task_status_cancelled)
        }
    }

    private fun cancelTask(item: GenerationQueueItem) {
        val cancelled = generationManager.cancel(item.request.id, getString(R.string.task_cancelled_by_user))
        if (cancelled) {
            Toast.makeText(this, getString(R.string.task_cancel_sent, item.request.id.take(8)), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.task_cancel_unavailable), Toast.LENGTH_SHORT).show()
        }
        renderTaskPanel()
    }

    private fun retryTask(item: GenerationQueueItem) {
        val retry = item.request.copy(id = java.util.UUID.randomUUID().toString(), createdAtMillis = System.currentTimeMillis())
        createStoredTask(retry)
        generationManager.enqueue(retry)
        binding.studioForm.setSubmitting(true)
        binding.studioForm.setStatus(getString(R.string.task_retry_enqueued, retry.id.take(8)))
        Toast.makeText(this, getString(R.string.task_retry_enqueued, retry.id.take(8)), Toast.LENGTH_SHORT).show()
        renderTaskPanel()
    }

    private fun clearFinishedTasks() {
        val removed = generationManager.clearFinished()
        Toast.makeText(this, getString(R.string.task_clear_finished_done, removed), Toast.LENGTH_SHORT).show()
        renderTaskPanel()
    }

    private fun showTaskDetails(taskId: String) {
        val task = generationStore.getTask(taskId)
        if (task == null) {
            Toast.makeText(this, getString(R.string.task_detail_missing), Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.task_detail_title)
            .setMessage(task.detailText())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun renderHistoryPanel() {
        val records = generationStore.listRecentRecords(20)
        binding.historySummary.text = getString(R.string.history_summary, records.size)
        binding.historyEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        binding.historyList.removeAllViews()
        records.forEach { record ->
            binding.historyList.addView(historyCard(record))
        }
    }

    private fun historyCard(record: GenerationRecord): View {
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(color(R.color.aib_surface))
            strokeColor = color(R.color.aib_line)
            strokeWidth = dp(1)
            radius = dp(8).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(10)
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        thumbnailView(record.assets.firstOrNull()?.media?.filePath.orEmpty())?.let { content.addView(it) }
        content.addView(TextView(this).apply {
            text = record.prompt.take(80).ifBlank { record.taskId.take(8) }
            setTextColor(color(R.color.aib_text))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, 0)
        })
        val filePath = record.assets.firstOrNull()?.media?.filePath.orEmpty().ifBlank { "-" }
        content.addView(TextView(this).apply {
            text = getString(
                R.string.history_card_detail,
                record.channelName.ifBlank { record.channelId },
                record.model,
                record.status.wireName,
                record.assets.size,
                filePath,
                record.errorMessage.ifBlank { "-" },
            )
            setTextColor(color(R.color.aib_text_secondary))
            textSize = 14f
            setPadding(0, dp(7), 0, 0)
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        actions.addView(actionButton(R.string.action_reuse_parameters) { reuseHistoryRecord(record) })
        actions.addView(actionButton(R.string.action_view_detail) { showHistoryDetails(record) })
        content.addView(actions)
        card.addView(content)
        return card
    }

    private fun thumbnailView(filePath: String): ImageView? {
        val file = File(filePath)
        if (!file.isFile) return null
        val bitmap = decodeThumbnail(file.absolutePath, dp(420)) ?: return null
        return ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = false
            contentDescription = getString(R.string.history_thumbnail)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(180),
            )
        }
    }

    private fun reuseHistoryRecord(record: GenerationRecord) {
        val parameters = runCatching { JSONObject(record.parametersJson) }.getOrDefault(JSONObject())
        render(Tab.STUDIO)
        binding.studioForm.applyDraft(
            prompt = record.prompt,
            channelId = record.channelId,
            model = record.model,
            aspectRatio = parameters.optString("aspect_ratio", "1:1"),
            resolution = parameters.optString("resolution", "1024"),
            quantity = parameters.optInt("count", 1).coerceIn(1, 4),
        )
        binding.studioForm.setStatus(getString(R.string.history_reuse_applied, record.taskId.take(8)))
    }

    private fun showHistoryDetails(record: GenerationRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_detail_title)
            .setMessage(record.detailText())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun GenerationTask.detailText(): String {
        val files = assets
            .map { it.media.filePath }
            .filter { it.isNotBlank() }
            .take(5)
            .joinToString("\n")
            .ifBlank { "-" }
        val attempt = attempts.lastOrNull()?.detailText() ?: getString(R.string.detail_attempt_empty)
        return listOf(
            getString(R.string.detail_line_prompt, prompt),
            getString(R.string.detail_line_target, channelName.ifBlank { channelId }, model),
            getString(R.string.detail_line_status, status.displayName()),
            getString(R.string.detail_line_parameters, parametersJson),
            getString(R.string.detail_line_assets, files),
            getString(R.string.detail_line_error, errorMessage.ifBlank { "-" }),
            getString(R.string.detail_line_attempt, attempt),
        ).joinToString("\n\n")
    }

    private fun GenerationRecord.detailText(): String {
        val files = assets
            .map { it.media.filePath }
            .filter { it.isNotBlank() }
            .take(5)
            .joinToString("\n")
            .ifBlank { "-" }
        val attempt = attempts.lastOrNull()?.detailText() ?: getString(R.string.detail_attempt_empty)
        return listOf(
            getString(R.string.detail_line_prompt, prompt),
            getString(R.string.detail_line_target, channelName.ifBlank { channelId }, model),
            getString(R.string.detail_line_status, status.displayName()),
            getString(R.string.detail_line_parameters, parametersJson),
            getString(R.string.detail_line_assets, files),
            getString(R.string.detail_line_error, errorMessage.ifBlank { "-" }),
            getString(R.string.detail_line_attempt, attempt),
        ).joinToString("\n\n")
    }

    private fun StoredAttemptRecord.detailText(): String {
        val response = runCatching { JSONObject(responseJson) }.getOrNull()
        val rawPreview = response?.optString("raw_preview", "").orEmpty()
            .ifBlank { response?.optString("_raw", "").orEmpty() }
            .ifBlank { "-" }
            .take(1200)
        return getString(
            R.string.detail_attempt_format,
            attemptNumber,
            status.displayName(),
            httpStatusCode?.toString() ?: "-",
            durationMs?.toString() ?: "-",
            errorMessage.ifBlank { "-" },
            rawPreview,
        )
    }

    private fun StoredGenerationStatus.displayName(): String {
        return when (this) {
            StoredGenerationStatus.QUEUED -> getString(R.string.task_status_queued)
            StoredGenerationStatus.RUNNING -> getString(R.string.task_status_running)
            StoredGenerationStatus.SUCCEEDED -> getString(R.string.task_status_succeeded)
            StoredGenerationStatus.FAILED -> getString(R.string.task_status_failed)
            StoredGenerationStatus.CANCELED -> getString(R.string.task_status_cancelled)
        }
    }

    private fun simpleCard(title: String, detail: String): View {
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(color(R.color.aib_surface))
            strokeColor = color(R.color.aib_line)
            strokeWidth = dp(1)
            radius = dp(8).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(10)
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        content.addView(TextView(this).apply {
            text = title
            setTextColor(color(R.color.aib_text))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = detail
            setTextColor(color(R.color.aib_text_secondary))
            textSize = 14f
            setPadding(0, dp(7), 0, 0)
        })
        card.addView(content)
        return card
    }

    private fun renderChannelList() {
        val channels = channelStore.load()
        val enabledCount = channels.count { it.enabled }
        binding.channelSummary.text = getString(R.string.channel_summary, channels.size, enabledCount)
        binding.channelEmpty.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE
        binding.channelList.removeAllViews()

        channels.forEach { channel ->
            binding.channelList.addView(channelCard(channel))
        }
    }

    private fun channelCard(channel: ProviderChannel): View {
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(color(R.color.aib_surface))
            strokeColor = color(R.color.aib_line)
            strokeWidth = dp(1)
            radius = dp(8).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(10)
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        content.addView(TextView(this).apply {
            text = if (channel.enabled) channel.name else getString(R.string.channel_disabled_name, channel.name)
            setTextColor(color(R.color.aib_text))
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = getString(
                R.string.channel_detail,
                channel.providerType,
                channel.baseUrl.ifBlank { getString(R.string.channel_no_base_url) },
                channel.targetLabels(),
                if (channel.apiKey != null) getString(R.string.channel_key_saved) else getString(R.string.channel_key_empty),
            )
            setTextColor(color(R.color.aib_text_secondary))
            textSize = 14f
            setPadding(0, dp(7), 0, 0)
        })
        content.addView(channelActions(channel))
        card.addView(content)
        return card
    }

    private fun channelActions(channel: ProviderChannel): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        row.addView(actionButton(R.string.action_edit) { showChannelDialog(channel) })
        row.addView(actionButton(R.string.action_test_models) { testChannelModels(channel) })
        row.addView(actionButton(if (channel.enabled) R.string.action_disable else R.string.action_enable) {
            channelStore.setEnabled(channel.id, !channel.enabled)
            renderChannelList()
            bindStudioChannels()
        })
        row.addView(actionButton(R.string.action_copy) {
            channelStore.duplicate(channel.id)
            renderChannelList()
            bindStudioChannels()
        })
        row.addView(actionButton(R.string.action_delete) { confirmDelete(channel) })
        return row
    }

    private fun actionButton(textRes: Int, onClick: () -> Unit): MaterialButton {
        return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(textRes)
            setTextColor(color(R.color.aib_text))
            minHeight = dp(40)
            minimumHeight = dp(40)
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginEnd = dp(8)
            }
        }
    }

    private fun confirmDelete(channel: ProviderChannel) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_channel_title)
            .setMessage(getString(R.string.delete_channel_message, channel.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                channelStore.delete(channel.id)
                renderChannelList()
                bindStudioChannels()
            }
            .show()
    }

    private fun testChannelModels(channel: ProviderChannel) {
        val adapter = ProviderRegistry.forChannel(channel)
        if (adapter == null) {
            Toast.makeText(this, getString(R.string.channel_test_no_adapter, channel.providerType), Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, getString(R.string.channel_test_running, channel.name), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { adapter.listModels(channel) }
            if (!result.success) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.channel_test_failed_title)
                    .setMessage(result.error.ifBlank { result.rawPreview })
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            val modelsText = result.models
                .take(50)
                .joinToString("\n") { it.id }
                .ifBlank { "-" }
            val message = if (result.models.isEmpty()) {
                getString(R.string.channel_test_empty, result.httpStatus?.toString() ?: "-", result.elapsedMillis)
            } else {
                getString(
                    R.string.channel_test_models,
                    result.httpStatus?.toString() ?: "-",
                    result.elapsedMillis,
                    result.models.size,
                    modelsText,
                )
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.channel_test_success_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun showChannelDialog(existing: ProviderChannel?) {
        val name = editText(R.string.field_channel_name, existing?.name.orEmpty())
        val providerType = editText(R.string.field_provider_type, existing?.providerType ?: "openai_compatible_image")
        val baseUrl = editText(R.string.field_base_url, existing?.baseUrl.orEmpty())
        val apiKey = editText(R.string.field_api_key, "")
        apiKey.hint = if (existing?.apiKey != null) getString(R.string.field_api_key_saved_hint) else getString(R.string.field_api_key)
        apiKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val defaultModel = editText(R.string.field_default_model, existing?.defaultModel.orEmpty())
        val enabledModels = editText(R.string.field_enabled_models, existing?.enabledModels?.joinToString(", ").orEmpty())
        val timeout = editText(R.string.field_timeout, (existing?.timeoutSeconds ?: 280).toString())
        timeout.inputType = InputType.TYPE_CLASS_NUMBER
        val proxy = editText(R.string.field_proxy, existing?.proxy.orEmpty())
        val extra = editText(R.string.field_extra_json, existing?.extraJson ?: "{}")
        extra.minLines = 3
        val enabled = SwitchMaterial(this).apply {
            text = getString(R.string.field_enabled)
            setTextColor(color(R.color.aib_text))
            isChecked = existing?.enabled ?: true
            setPadding(0, dp(8), 0, dp(8))
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
            addView(name)
            addView(providerType)
            addView(baseUrl)
            addView(apiKey)
            addView(defaultModel)
            addView(enabledModels)
            addView(timeout)
            addView(proxy)
            addView(extra)
            addView(enabled)
        }
        val scroll = ScrollView(this).apply { addView(form) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.add_channel_title else R.string.edit_channel_title)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                saveChannelFromDialog(
                    dialog = dialog,
                    existing = existing,
                    name = name.text.toString(),
                    providerType = providerType.text.toString(),
                    baseUrl = baseUrl.text.toString(),
                    apiKey = apiKey.text.toString(),
                    defaultModel = defaultModel.text.toString(),
                    enabledModels = enabledModels.text.toString(),
                    timeout = timeout.text.toString(),
                    proxy = proxy.text.toString(),
                    extra = extra.text.toString(),
                    enabled = enabled.isChecked,
                )
            }
        }
        dialog.show()
    }

    private fun saveChannelFromDialog(
        dialog: AlertDialog,
        existing: ProviderChannel?,
        name: String,
        providerType: String,
        baseUrl: String,
        apiKey: String,
        defaultModel: String,
        enabledModels: String,
        timeout: String,
        proxy: String,
        extra: String,
        enabled: Boolean,
    ) {
        val cleanName = name.trim()
        val cleanProviderType = providerType.trim()
        if (cleanName.isBlank() || cleanProviderType.isBlank()) {
            Toast.makeText(this, R.string.channel_required_fields, Toast.LENGTH_SHORT).show()
            return
        }
        val cleanExtra = extra.trim().ifBlank { "{}" }
        if (runCatching { JSONObject(cleanExtra) }.isFailure) {
            Toast.makeText(this, R.string.channel_invalid_extra, Toast.LENGTH_SHORT).show()
            return
        }
        val encryptedKey = runCatching {
            if (apiKey.isBlank()) existing?.apiKey else SecureKeyStore.encrypt(apiKey)
        }.getOrElse {
            Toast.makeText(this, getString(R.string.channel_key_encrypt_failed, it.message.orEmpty()), Toast.LENGTH_LONG).show()
            return
        }
        val modelList = enabledModels
            .split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val channel = ProviderChannel(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = cleanName,
            providerType = cleanProviderType,
            baseUrl = baseUrl.trim(),
            apiKey = encryptedKey,
            defaultModel = defaultModel.trim(),
            enabledModels = modelList,
            timeoutSeconds = timeout.toIntOrNull()?.coerceIn(10, 900) ?: 280,
            enabled = enabled,
            proxy = proxy.trim(),
            extraJson = cleanExtra,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        channelStore.upsert(channel)
        dialog.dismiss()
        renderChannelList()
        bindStudioChannels()
    }

    private fun editText(labelRes: Int, value: String): EditText {
        return EditText(this).apply {
            hint = getString(labelRes)
            setText(value)
            setTextColor(color(R.color.aib_text))
            setHintTextColor(color(R.color.aib_hint))
            textSize = 15f
            setSingleLine(false)
            maxLines = 4
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(8)
            }
        }
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private enum class Tab(
        val itemId: Int,
        val titleRes: Int,
        val badgeRes: Int,
        val headlineRes: Int,
        val bodyRes: Int,
        val statusRes: Int,
        val primaryActionRes: Int,
    ) {
        STUDIO(
            R.id.nav_studio,
            R.string.tab_studio,
            R.string.badge_studio,
            R.string.studio_headline,
            R.string.studio_body,
            R.string.studio_status,
            R.string.action_start_draft,
        ),
        TASKS(
            R.id.nav_tasks,
            R.string.tab_tasks,
            R.string.badge_tasks,
            R.string.tasks_headline,
            R.string.tasks_body,
            R.string.tasks_status,
            R.string.action_clear_finished,
        ),
        HISTORY(
            R.id.nav_history,
            R.string.tab_history,
            R.string.badge_history,
            R.string.history_headline,
            R.string.history_body,
            R.string.history_status,
            R.string.action_open_history,
        ),
        CHANNELS(
            R.id.nav_channels,
            R.string.tab_channels,
            R.string.badge_channels,
            R.string.channels_headline,
            R.string.channels_body,
            R.string.channels_status,
            R.string.action_add_channel,
        );

        companion object {
            fun fromId(itemId: Int): Tab = values().firstOrNull { it.itemId == itemId } ?: STUDIO
        }
    }

    companion object {
        private const val KEY_TAB = "current_tab"
    }
}
