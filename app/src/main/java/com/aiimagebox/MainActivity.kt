package com.aiimagebox

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
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
import com.aiimagebox.provider.ModelListResult
import com.aiimagebox.provider.ProviderRegistry
import com.aiimagebox.ui.StudioForm
import com.aiimagebox.util.PublicMediaExporter
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
import java.net.URI
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var appDirectories: AppDirectories
    private lateinit var channelStore: ChannelStore
    private lateinit var generationStore: GenerationStore
    private lateinit var generationManager: GenerationManager
    private var currentTab: Tab = Tab.STUDIO
    private var syncingNav = false
    private var latestResultPaths: List<String> = emptyList()
    private var pendingPublicExportPaths: List<String> = emptyList()

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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_WRITE_EXTERNAL_STORAGE) return

        val pendingPaths = pendingPublicExportPaths
        pendingPublicExportPaths = emptyList()
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            exportPathsToPublic(pendingPaths)
        } else {
            Toast.makeText(this, R.string.export_permission_denied, Toast.LENGTH_SHORT).show()
        }
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
        binding.studioForm.setOnSavePublicListener {
            exportPathsToPublic(latestResultPaths)
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
            target = GenerationTarget.fromChannel(channel, request.model)
                .copy(providerType = request.providerType.ifBlank { channel.providerType }),
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
            target = GenerationTarget.fromChannel(channel, model.ifBlank { channel.defaultModel })
                .copy(providerType = providerType.ifBlank { channel.providerType }),
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
                latestResultPaths = savedPaths
                if (currentTab == Tab.TASKS) renderTaskPanel()
                if (currentTab == Tab.HISTORY) renderHistoryPanel()
                binding.studioForm.setSubmitting(!generationManager.snapshot().isIdle)
                val successMessage = getString(R.string.studio_generate_succeeded_preview, savedPaths.size)
                binding.studioForm.setResultImages(savedPaths, successMessage)
                binding.studioForm.setStatus(successMessage)
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
        val successResult = (event as? GenerationEvent.Succeeded)?.result
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
        } ?: successResult?.metadata?.let { metadata ->
            JSONObject()
                .put("provider_status", "SUCCEEDED")
                .put("provider_request_id", metadata["provider_request_id"].orEmpty())
                .put("http_status", metadata["http_status"]?.toIntOrNull() ?: JSONObject.NULL)
                .put("elapsed_ms", metadata["elapsed_ms"]?.toLongOrNull() ?: JSONObject.NULL)
                .put("raw_preview", metadata["raw_preview"].orEmpty())
                .put("image_count", metadata["image_count"].orEmpty())
                .toString()
        } ?: "{}"
        val successHttpStatus = successResult?.metadata?.get("http_status")?.toIntOrNull()
        val successDurationMs = successResult?.metadata?.get("elapsed_ms")?.toLongOrNull()
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
            httpStatusCode = providerError?.providerResult?.httpStatus ?: successHttpStatus,
            errorMessage = error.ifBlank { providerError?.providerResult?.error.orEmpty() },
            startedAt = item.startedAtMillis ?: now,
            endedAt = now,
            durationMs = providerError?.providerResult?.elapsedMillis ?: successDurationMs ?: (item.startedAtMillis ?: now).let { now - it },
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

    private fun exportPathsToPublic(paths: List<String>) {
        val files = paths
            .map { File(it) }
            .filter { it.isFile }
        if (files.isEmpty()) {
            Toast.makeText(this, R.string.export_no_files, Toast.LENGTH_SHORT).show()
            return
        }
        if (requiresLegacyPublicWritePermission()) {
            pendingPublicExportPaths = paths
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_WRITE_EXTERNAL_STORAGE)
            return
        }

        lifecycleScope.launch {
            val exported = withContext(Dispatchers.IO) {
                files.mapNotNull { file ->
                    runCatching {
                        PublicMediaExporter.exportImage(
                            context = this@MainActivity,
                            source = file,
                            displayName = file.name,
                            mimeType = mimeTypeForFile(file),
                        )
                    }.getOrNull()
                }
            }
            if (exported.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.export_failed, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.export_succeeded, exported.size, exported.first()),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun exportRecordAssets(record: GenerationRecord) {
        exportPathsToPublic(record.assets.map { it.media.filePath })
    }

    private fun requiresLegacyPublicWritePermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
    }

    private fun mimeTypeForFile(file: File): String {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/png"
        }
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
        val actions = mutableListOf<UiAction>()
        when (item.status) {
            QueueGenerationStatus.QUEUED,
            QueueGenerationStatus.RUNNING
            -> actions.add(UiAction(R.string.action_cancel_task) { cancelTask(item) })
            QueueGenerationStatus.FAILED,
            QueueGenerationStatus.CANCELLED,
            QueueGenerationStatus.SUCCEEDED
            -> actions.add(UiAction(R.string.action_retry_task) { retryTask(item) })
        }
        actions.add(UiAction(R.string.action_view_detail) { showTaskDetails(item.request.id) })
        if (actions.isNotEmpty()) content.addView(actionGrid(actions))
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
        content.addView(
            actionGrid(
                listOf(
                    UiAction(R.string.action_reuse_parameters) { reuseHistoryRecord(record) },
                    UiAction(R.string.action_save_public) { exportRecordAssets(record) },
                    UiAction(R.string.action_view_detail) { showHistoryDetails(record) },
                ),
            ),
        )
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
        val attempt = attempts.lastOrNull()
        return listOf(
            getString(R.string.detail_section_request_info),
            getString(R.string.detail_line_prompt, prompt),
            getString(R.string.detail_line_target, channelName.ifBlank { channelId }, model),
            getString(R.string.detail_line_status, status.displayName()),
            getString(R.string.detail_section_request_data),
            attempt?.requestJson?.ifBlank { parametersJson } ?: parametersJson,
            getString(R.string.detail_section_response_info),
            attempt?.responseInfoText().orEmpty().ifBlank { getString(R.string.detail_attempt_empty) },
            getString(R.string.detail_section_response_data),
            attempt?.responseJson?.ifBlank { "{}" } ?: "{}",
            getString(R.string.detail_section_response_result),
            filesSummary(assets),
            getString(R.string.detail_line_error, errorMessage.ifBlank { "-" }),
        ).joinToString("\n\n")
    }

    private fun GenerationRecord.detailText(): String {
        val attempt = attempts.lastOrNull()
        return listOf(
            getString(R.string.detail_section_request_info),
            getString(R.string.detail_line_prompt, prompt),
            getString(R.string.detail_line_target, channelName.ifBlank { channelId }, model),
            getString(R.string.detail_line_status, status.displayName()),
            getString(R.string.detail_section_request_data),
            attempt?.requestJson?.ifBlank { parametersJson } ?: parametersJson,
            getString(R.string.detail_section_response_info),
            attempt?.responseInfoText().orEmpty().ifBlank { getString(R.string.detail_attempt_empty) },
            getString(R.string.detail_section_response_data),
            attempt?.responseJson?.ifBlank { "{}" } ?: "{}",
            getString(R.string.detail_section_response_result),
            filesSummary(assets),
            getString(R.string.detail_line_error, errorMessage.ifBlank { "-" }),
        ).joinToString("\n\n")
    }

    private fun StoredAttemptRecord.detailText(): String {
        return listOf(
            responseInfoText(),
            getString(R.string.detail_section_request_data),
            requestJson.ifBlank { "{}" },
            getString(R.string.detail_section_response_data),
            responseJson.ifBlank { "{}" },
        ).joinToString("\n\n")
    }

    private fun StoredAttemptRecord.responseInfoText(): String {
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

    private fun filesSummary(assets: List<StoredGeneratedAsset>): String {
        return assets
            .map { asset ->
                val media = asset.media
                val size = media.sizeBytes?.let { "$it bytes" } ?: "-"
                val dimensions = if (media.width != null && media.height != null) {
                    "${media.width}x${media.height}"
                } else {
                    "-"
                }
                "${media.displayName.ifBlank { media.filePath }}\n${media.mimeType} · $dimensions · $size\n${media.filePath}"
            }
            .take(5)
            .joinToString("\n\n")
            .ifBlank { "-" }
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
                channelModeLabel(channel),
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
        return actionGrid(
            listOf(
                UiAction(R.string.action_edit) { showChannelDialog(channel) },
                UiAction(R.string.action_fetch_models) { fetchChannelModels(channel) },
                UiAction(if (channel.enabled) R.string.action_disable else R.string.action_enable) {
                    channelStore.setEnabled(channel.id, !channel.enabled)
                    renderChannelList()
                    bindStudioChannels()
                },
                UiAction(R.string.action_copy) {
                    channelStore.duplicate(channel.id)
                    renderChannelList()
                    bindStudioChannels()
                },
                UiAction(R.string.action_delete) { confirmDelete(channel) },
            ),
        )
    }

    private fun actionGrid(actions: List<UiAction>, columns: Int = 2): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
            actions.chunked(columns).forEach { rowActions ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    rowActions.forEachIndexed { index, action ->
                        addView(actionButton(action.textRes, action.onClick).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f,
                            ).apply {
                                marginEnd = if (index < rowActions.lastIndex) dp(8) else 0
                                bottomMargin = dp(8)
                            }
                        })
                    }
                })
            }
        }
    }

    private fun actionButton(textRes: Int, onClick: () -> Unit): MaterialButton {
        return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(textRes)
            setTextColor(color(R.color.aib_text))
            minHeight = dp(40)
            minimumHeight = dp(40)
            isAllCaps = false
            setSingleLine(false)
            maxLines = 2
            setOnClickListener { onClick() }
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

    private fun fetchChannelModels(channel: ProviderChannel) {
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
            if (result.models.isEmpty()) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.channel_test_success_title)
                    .setMessage(getString(R.string.channel_test_empty, result.httpStatus?.toString() ?: "-", result.elapsedMillis))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } else {
                showModelPicker(channel, result)
            }
        }
    }

    private fun showModelPicker(channel: ProviderChannel, result: ModelListResult) {
        val models = result.models
            .map { it.id.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val selected = channel.enabledModels
            .ifEmpty { listOf(channel.defaultModel) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { it in models }
            .toMutableSet()
        val selectedTypes = modelTypeOverrides(channel.extraJson).toMutableMap()

        val status = TextView(this).apply {
            setTextColor(color(R.color.aib_text_secondary))
            textSize = 14f
            text = getString(
                R.string.channel_model_picker_summary,
                result.httpStatus?.toString() ?: "-",
                result.elapsedMillis,
                models.size,
            )
        }
        val filter = editText(R.string.field_model_filter, "")
        filter.setSingleLine(true)
        val count = TextView(this).apply {
            setTextColor(color(R.color.aib_text_secondary))
            textSize = 13f
            setPadding(0, 0, 0, dp(8))
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(360),
            )
        }

        fun filteredModels(): List<String> {
            val terms = filter.text.toString()
                .split(' ', ',', '\n', '\t')
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (terms.isEmpty()) return models
            return models.filter { model ->
                terms.all { term -> model.contains(term, ignoreCase = true) }
            }
        }

        fun renderModels() {
            val filtered = filteredModels()
            list.removeAllViews()
            count.text = getString(
                R.string.channel_model_filter_count,
                filtered.size,
                models.size,
                selected.size,
            )
            if (filtered.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = getString(R.string.channel_model_filter_empty)
                    setTextColor(color(R.color.aib_text_secondary))
                    textSize = 14f
                    setPadding(0, dp(12), 0, dp(12))
                })
                return
            }
            filtered.take(MAX_VISIBLE_MODELS).forEach { model ->
                val modelType = modelInterfaceTypeFor(model, selectedTypes[model])
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                }
                item.addView(CheckBox(this).apply {
                    text = model
                    setTextColor(color(R.color.aib_text))
                    textSize = 14f
                    maxLines = 6
                    isSingleLine = false
                    isChecked = model in selected
                    setPadding(0, 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) {
                            selected.add(model)
                        } else {
                            selected.remove(model)
                        }
                        count.text = getString(
                            R.string.channel_model_filter_count,
                            filtered.size,
                            models.size,
                            selected.size,
                        )
                    }
                })
                item.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(40), 0, 0, 0)
                    addView(TextView(this@MainActivity).apply {
                        text = getString(R.string.channel_model_type_row, modelType.label)
                        setTextColor(color(R.color.aib_text_secondary))
                        textSize = 13f
                        maxLines = 3
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(MaterialButton(this@MainActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = getString(R.string.action_change_model_type)
                        setTextColor(color(R.color.aib_text))
                        minHeight = dp(38)
                        minimumHeight = dp(38)
                        isAllCaps = false
                        setSingleLine(false)
                        maxLines = 2
                        setOnClickListener {
                            showModelTypeDialog(model, selectedTypes) {
                                renderModels()
                            }
                        }
                        layoutParams = LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT)
                    })
                })
                list.addView(item)
            }
            if (filtered.size > MAX_VISIBLE_MODELS) {
                list.addView(TextView(this).apply {
                    text = getString(R.string.channel_model_filter_limited, MAX_VISIBLE_MODELS, filtered.size)
                    setTextColor(color(R.color.aib_text_secondary))
                    textSize = 13f
                    setPadding(0, dp(8), 0, 0)
                })
            }
        }

        val quickActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
            addView(dialogButton(R.string.action_select_visible) {
                selected.addAll(filteredModels().take(MAX_VISIBLE_MODELS))
                renderModels()
            })
            addView(dialogButton(R.string.action_clear_visible) {
                selected.removeAll(filteredModels().take(MAX_VISIBLE_MODELS).toSet())
                renderModels()
            })
        }
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderModels()
            override fun afterTextChanged(s: Editable?) = Unit
        })

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
            addView(status)
            addView(filter)
            addView(quickActions)
            addView(count)
            addView(scroll)
        }
        renderModels()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.channel_model_picker_title, channel.name))
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_save_models, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val enabledModels = models.filter { it in selected }
                if (enabledModels.isEmpty()) {
                    Toast.makeText(this, R.string.channel_models_select_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val latest = channelStore.load().firstOrNull { it.id == channel.id } ?: channel
                val defaultModel = latest.defaultModel.takeIf { it in enabledModels } ?: enabledModels.first()
                val enabledModelTypes = enabledModels.associateWith { model ->
                    selectedTypes[model]?.takeIf { it.isNotBlank() } ?: inferModelInterfaceType(model).key
                }
                channelStore.upsert(
                    latest.copy(
                        defaultModel = defaultModel,
                        enabledModels = enabledModels,
                        extraJson = withModelTypes(latest.extraJson, enabledModels, enabledModelTypes),
                    ),
                )
                dialog.dismiss()
                renderChannelList()
                bindStudioChannels()
                Toast.makeText(this, getString(R.string.channel_models_saved, enabledModels.size), Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun dialogButton(textRes: Int, onClick: () -> Unit): MaterialButton {
        return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(textRes)
            setTextColor(color(R.color.aib_text))
            minHeight = dp(38)
            minimumHeight = dp(38)
            isAllCaps = false
            setSingleLine(false)
            maxLines = 2
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
    }

    private fun inferModelInterfaceType(model: String): ModelInterfaceType {
        val normalized = model.lowercase()
        return when {
            "seedance" in normalized ->
                modelInterfaceTypeByKey("seedance_video")
            listOf("grok", "grok-video").any { it in normalized } && "video" in normalized ->
                modelInterfaceTypeByKey("grok_video")
            listOf("veo", "sora", "video", "wan", "kling", "hailuo", "runway").any { it in normalized } ->
                modelInterfaceTypeByKey("openai_compatible_video")
            listOf("gemini", "imagen").any { it in normalized } ->
                modelInterfaceTypeByKey("gemini_image")
            "grok" in normalized ->
                modelInterfaceTypeByKey("grok_image")
            "agnes" in normalized ->
                modelInterfaceTypeByKey("agnes_image")
            listOf("gpt-image", "dall", "image", "flux", "stable", "sdxl", "sd-", "midjourney").any { it in normalized } ->
                modelInterfaceTypeByKey("openai_compatible_image")
            else -> modelInterfaceTypeByKey("openai_compatible_image")
        } ?: ModelInterfaceType("openai_compatible_image", getString(R.string.model_type_openai_image))
    }

    private fun modelInterfaceTypeFor(model: String, typeKey: String?): ModelInterfaceType {
        val cleanKey = typeKey?.trim().orEmpty()
        if (cleanKey.isNotBlank()) {
            return modelInterfaceTypeByKey(cleanKey) ?: ModelInterfaceType(cleanKey, cleanKey)
        }
        return inferModelInterfaceType(model)
    }

    private fun modelInterfaceTypeByKey(typeKey: String): ModelInterfaceType? {
        return modelInterfaceTypeOptions().firstOrNull { it.key == typeKey.trim() }
    }

    private fun modelInterfaceTypeOptions(): List<ModelInterfaceType> {
        return listOf(
            ModelInterfaceType("openai_compatible_image", getString(R.string.model_type_openai_image)),
            ModelInterfaceType("gemini_image", getString(R.string.model_type_gemini_image)),
            ModelInterfaceType("agnes_image", getString(R.string.model_type_agnes_image)),
            ModelInterfaceType("grok_image", getString(R.string.model_type_grok_image)),
            ModelInterfaceType("openai_compatible_video", getString(R.string.model_type_openai_video)),
            ModelInterfaceType("grok_video", getString(R.string.model_type_grok_video)),
            ModelInterfaceType("seedance_video", getString(R.string.model_type_seedance_video)),
        )
    }

    private fun showModelTypeDialog(
        model: String,
        selectedTypes: MutableMap<String, String>,
        onChanged: () -> Unit,
    ) {
        val types = modelInterfaceTypeOptions()
        val labels = types.map { it.label }.toTypedArray()
        val checked = types.indexOfFirst { it.key == modelInterfaceTypeFor(model, selectedTypes[model]).key }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.channel_model_type_title, model))
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                selectedTypes[model] = types[which].key
                dialog.dismiss()
                onChanged()
            }
            .setNeutralButton(R.string.action_use_inferred_type) { _, _ ->
                selectedTypes.remove(model)
                onChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun modelTypeOverrides(extraJson: String): Map<String, String> {
        val modelTypes = runCatching {
            JSONObject(extraJson.ifBlank { "{}" }).optJSONObject("model_types")
        }.getOrNull() ?: return emptyMap()

        val values = mutableMapOf<String, String>()
        val keys = modelTypes.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = modelTypes.optString(key, "").trim()
            if (key.isNotBlank() && value.isNotBlank()) values[key] = value
        }
        return values
    }

    private fun withModelTypes(
        extraJson: String,
        models: List<String>,
        typeOverrides: Map<String, String> = emptyMap(),
    ): String {
        val extra = runCatching { JSONObject(extraJson.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        val modelTypes = JSONObject()
        models.forEach { model ->
            val typeKey = typeOverrides[model]?.takeIf { it.isNotBlank() } ?: inferModelInterfaceType(model).key
            modelTypes.put(model, typeKey)
        }
        extra.put("model_types", modelTypes)
        return extra.toString()
    }

    private fun channelModeLabel(channel: ProviderChannel): String {
        val type = channel.providerType.lowercase()
        if ("video" !in type) return getString(R.string.channel_mode_image)
        return when (videoProviderKey(channel.extraJson)) {
            VIDEO_PROVIDER_SEEDANCE -> getString(R.string.channel_mode_seedance_video)
            VIDEO_PROVIDER_GROK -> getString(R.string.channel_mode_grok_video)
            else -> getString(R.string.channel_mode_video)
        }
    }

    private fun videoProviderKey(extraJson: String): String {
        return runCatching {
            JSONObject(extraJson.ifBlank { "{}" }).optString(KEY_VIDEO_PROVIDER, "").trim().lowercase()
        }.getOrDefault("")
    }

    private fun setExtraJsonValue(extra: EditText, key: String, value: String?) {
        val json = runCatching { JSONObject(extra.text?.toString().orEmpty().ifBlank { "{}" }) }
            .getOrElse { JSONObject() }
        if (value == null) {
            json.remove(key)
        } else {
            json.put(key, value)
        }
        extra.setText(json.toString())
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
        extra.visibility = View.GONE
        val enabled = SwitchMaterial(this).apply {
            text = getString(R.string.field_enabled)
            setTextColor(color(R.color.aib_text))
            isChecked = existing?.enabled ?: true
            setPadding(0, dp(8), 0, dp(8))
        }
        val channelMode = channelModeRow(providerType, extra)
        val extraVisual = extraVisualPanel(providerType, extra)
        val extraMode = extraModeRow(extraVisual, extra)

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
            addView(channelMode)
            addView(name)
            addView(baseUrl)
            addView(apiKey)
            addView(defaultModel)
            addView(enabledModels)
            addView(timeout)
            addView(proxy)
            addView(extraMode)
            addView(extraVisual)
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

    private fun channelModeRow(providerType: EditText, extra: EditText): View {
        return dialogButtonGrid(
            listOf(
                UiAction(R.string.channel_mode_image) {
                    providerType.setText("openai_compatible_image")
                    setExtraJsonValue(extra, KEY_VIDEO_PROVIDER, null)
                },
                UiAction(R.string.channel_mode_video) {
                    providerType.setText("openai_compatible_video")
                    if (videoProviderKey(extra.text.toString()).isBlank()) {
                        setExtraJsonValue(extra, KEY_VIDEO_PROVIDER, VIDEO_PROVIDER_GROK)
                    }
                },
            ),
        )
    }

    private fun extraModeRow(visualPanel: View, jsonEditor: EditText): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.field_extra_visual)
                setTextColor(color(R.color.aib_text))
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(6))
            })
            addView(dialogButtonGrid(
                listOf(
                    UiAction(R.string.extra_mode_visual) {
                        jsonEditor.visibility = View.GONE
                        visualPanel.visibility = View.VISIBLE
                    },
                    UiAction(R.string.extra_mode_json) {
                        visualPanel.visibility = View.GONE
                        jsonEditor.visibility = View.VISIBLE
                    },
                ),
            ))
        }
    }

    private fun extraVisualPanel(providerType: EditText, extra: EditText): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.extra_visual_hint)
                setTextColor(color(R.color.aib_text_secondary))
                textSize = 13f
                setPadding(0, 0, 0, dp(8))
            })
            addView(dialogButtonGrid(
                listOf(
                    UiAction(R.string.channel_mode_image) {
                        providerType.setText("openai_compatible_image")
                        setExtraJsonValue(extra, KEY_VIDEO_PROVIDER, null)
                    },
                    UiAction(R.string.channel_mode_grok_video) {
                        providerType.setText("openai_compatible_video")
                        setExtraJsonValue(extra, KEY_VIDEO_PROVIDER, VIDEO_PROVIDER_GROK)
                    },
                    UiAction(R.string.channel_mode_seedance_video) {
                        providerType.setText("openai_compatible_video")
                        setExtraJsonValue(extra, KEY_VIDEO_PROVIDER, VIDEO_PROVIDER_SEEDANCE)
                    },
                ),
            ))
        }
    }

    private fun channelTemplateRow(
        name: EditText,
        providerType: EditText,
        baseUrl: EditText,
        defaultModel: EditText,
        enabledModels: EditText,
    ): View {
        return dialogButtonGrid(
            CHANNEL_TEMPLATES.map { template ->
                UiAction(template.labelRes) {
                    if (name.text.isBlank()) name.setText(getString(template.labelRes))
                    providerType.setText(template.providerType)
                    baseUrl.setText(template.baseUrl)
                    defaultModel.setText(template.defaultModel)
                    if (enabledModels.text.isBlank() && template.defaultModel.isNotBlank()) {
                        enabledModels.setText(template.defaultModel)
                    }
                }
            },
        )
    }

    private fun providerTypePresetRow(providerType: EditText): View {
        return dialogButtonGrid(
            PROVIDER_TYPE_PRESETS.map { preset ->
                UiAction(preset.labelRes) {
                    providerType.setText(preset.providerType)
                }
            },
        )
    }

    private fun dialogButtonGrid(actions: List<UiAction>, columns: Int = 2): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
            actions.chunked(columns).forEach { rowActions ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    rowActions.forEachIndexed { index, action ->
                        addView(dialogButton(action.textRes, action.onClick).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f,
                            ).apply {
                                marginEnd = if (index < rowActions.lastIndex) dp(8) else 0
                                bottomMargin = dp(8)
                            }
                        })
                    }
                })
            }
        }
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
        val cleanBaseUrl = baseUrl.trim().trimEnd('/')
        if (ProviderRegistry.get(cleanProviderType) != null) {
            if (cleanBaseUrl.isBlank()) {
                Toast.makeText(this, R.string.channel_base_url_required, Toast.LENGTH_SHORT).show()
                return
            }
            if (!isValidHttpBaseUrl(cleanBaseUrl)) {
                Toast.makeText(this, R.string.channel_invalid_base_url, Toast.LENGTH_SHORT).show()
                return
            }
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
        val modelTypeSource = modelList.ifEmpty { listOf(defaultModel.trim()).filter { it.isNotBlank() } }
        val channel = ProviderChannel(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = cleanName,
            providerType = cleanProviderType,
            baseUrl = cleanBaseUrl,
            apiKey = encryptedKey,
            defaultModel = defaultModel.trim(),
            enabledModels = modelList,
            timeoutSeconds = timeout.toIntOrNull()?.coerceIn(10, 900) ?: 280,
            enabled = enabled,
            proxy = proxy.trim(),
            extraJson = withModelTypes(cleanExtra, modelTypeSource),
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        channelStore.upsert(channel)
        dialog.dismiss()
        renderChannelList()
        bindStudioChannels()
    }

    private fun isValidHttpBaseUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
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
        private const val MAX_VISIBLE_MODELS = 200
        private const val KEY_VIDEO_PROVIDER = "video_provider"
        private const val VIDEO_PROVIDER_GROK = "grok"
        private const val VIDEO_PROVIDER_SEEDANCE = "seedance"
        private val CHANNEL_TEMPLATES = listOf(
            ChannelTemplate(
                labelRes = R.string.channel_template_openai,
                providerType = "openai_compatible_image",
                baseUrl = "https://api.openai.com",
                defaultModel = "gpt-image-1",
            ),
            ChannelTemplate(
                labelRes = R.string.channel_template_compatible,
                providerType = "openai_compatible_image",
                baseUrl = "",
                defaultModel = "",
            ),
            ChannelTemplate(
                labelRes = R.string.channel_template_local,
                providerType = "openai_compatible_image",
                baseUrl = "http://127.0.0.1:8000",
                defaultModel = "",
            ),
        )
        private val PROVIDER_TYPE_PRESETS = listOf(
            ProviderTypePreset(R.string.channel_provider_openai_image, "openai_compatible_image"),
            ProviderTypePreset(R.string.channel_provider_gemini_image, "gemini_image"),
            ProviderTypePreset(R.string.channel_provider_agnes_image, "agnes_image"),
            ProviderTypePreset(R.string.channel_provider_grok_image, "grok_image"),
            ProviderTypePreset(R.string.channel_provider_video, "openai_compatible_video"),
        )
        private const val REQUEST_WRITE_EXTERNAL_STORAGE = 2001
    }

    private data class ChannelTemplate(
        val labelRes: Int,
        val providerType: String,
        val baseUrl: String,
        val defaultModel: String,
    )

    private data class ProviderTypePreset(
        val labelRes: Int,
        val providerType: String,
    )

    private data class ModelInterfaceType(
        val key: String,
        val label: String,
    )

    private data class UiAction(
        val textRes: Int,
        val onClick: () -> Unit,
    )
}
