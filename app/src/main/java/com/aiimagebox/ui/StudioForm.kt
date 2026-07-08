package com.aiimagebox.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.text.InputType
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.aiimagebox.R
import com.aiimagebox.data.ProviderChannel
import com.aiimagebox.databinding.ViewStudioFormBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

class StudioForm @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val binding = ViewStudioFormBinding.inflate(LayoutInflater.from(context), this, true)
    private val presetStore = context.getSharedPreferences(PREFS_PROMPT_PRESETS, Context.MODE_PRIVATE)
    private val targetOptions = mutableListOf<StudioChannelTarget>()
    private var selectedTargetIndex = 0
    private var quantity = DEFAULT_QUANTITY
    private var submitting = false
    private var referenceImagePath = ""

    var onSubmit: ((StudioSubmitRequest) -> Unit)? = null
    var onSavePublic: (() -> Unit)? = null
    var onPickReferenceImage: (() -> Unit)? = null

    init {
        binding.studioAspectGroup.check(binding.studioAspectSquare.id)
        binding.studioResolutionGroup.check(binding.studioResolution1024.id)
        binding.studioPromptInput.doAfterTextChanged { updateSubmitState() }
        binding.studioPromptPresetButton.setOnClickListener { showPromptPresetPicker() }
        binding.studioNextTargetButton.setOnClickListener { showTargetPicker() }
        binding.studioQuantityMinus.setOnClickListener { setQuantity(quantity - 1) }
        binding.studioQuantityPlus.setOnClickListener { setQuantity(quantity + 1) }
        binding.studioSubmitButton.setOnClickListener { submitCurrentForm() }
        binding.studioSavePublicButton.setOnClickListener { onSavePublic?.invoke() }
        binding.studioPickReferenceButton.setOnClickListener { onPickReferenceImage?.invoke() }
        binding.studioClearReferenceButton.setOnClickListener { clearReferenceImage() }

        setQuantity(DEFAULT_QUANTITY)
        renderSelectedTarget()
        setStatus(TEXT_STATUS_WAITING)
        setResultPlaceholder(TEXT_RESULT_PLACEHOLDER)
        updateSubmitState()
    }

    fun bindChannels(channels: List<ProviderChannel>) {
        bindTargets(channels.flatMap { channel -> channel.toStudioTargets() })
    }

    fun bindTargets(targets: List<StudioChannelTarget>) {
        targetOptions.clear()
        targetOptions.addAll(targets)
        selectedTargetIndex = 0
        renderSelectedTarget()
        updateSubmitState()
    }

    fun setOnSubmitListener(listener: ((StudioSubmitRequest) -> Unit)?) {
        onSubmit = listener
    }

    fun setOnSavePublicListener(listener: (() -> Unit)?) {
        onSavePublic = listener
    }

    fun setOnPickReferenceImageListener(listener: (() -> Unit)?) {
        onPickReferenceImage = listener
    }

    fun setPrompt(prompt: CharSequence) {
        binding.studioPromptInput.setText(prompt)
        binding.studioPromptInput.setSelection(binding.studioPromptInput.text?.length ?: 0)
    }

    private fun showPromptPresetPicker() {
        val presets = DEFAULT_PROMPT_PRESETS + loadCustomPromptPresets()
        val labels = presets.map { preset ->
            val preview = preset.prompt.replace(Regex("\\s+"), " ").take(PRESET_PREVIEW_CHARS)
            "${preset.title}\n$preview"
        }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.studio_prompt_preset_title)
            .setItems(labels) { _, which ->
                val preset = presets[which]
                setPrompt(preset.prompt)
                setStatus(context.getString(R.string.studio_prompt_preset_applied, preset.title))
            }
            .setNeutralButton(R.string.action_add_preset) { _, _ -> showAddPromptPresetDialog() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddPromptPresetDialog() {
        val nameInput = dialogEditText(R.string.field_prompt_preset_name, singleLine = true)
        val promptInput = dialogEditText(R.string.field_prompt_preset_prompt, singleLine = false).apply {
            minLines = 4
            setText(binding.studioPromptInput.text?.toString().orEmpty())
            setSelection(text?.length ?: 0)
        }
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
            addView(nameInput)
            addView(promptInput)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.studio_prompt_preset_add_title)
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = nameInput.text?.toString()?.trim().orEmpty()
                val prompt = promptInput.text?.toString()?.trim().orEmpty()
                if (title.isBlank() || prompt.isBlank()) {
                    setStatus(context.getString(R.string.studio_prompt_preset_required))
                    return@setOnClickListener
                }
                val preset = PromptPreset(title = title, prompt = prompt)
                saveCustomPromptPreset(preset)
                setPrompt(prompt)
                setStatus(context.getString(R.string.studio_prompt_preset_saved, title))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun dialogEditText(labelRes: Int, singleLine: Boolean): EditText {
        return EditText(context).apply {
            hint = context.getString(labelRes)
            setTextColor(context.getColor(R.color.aib_text))
            setHintTextColor(context.getColor(R.color.aib_hint))
            textSize = 15f
            setSingleLine(singleLine)
            maxLines = if (singleLine) 1 else 8
            inputType = if (singleLine) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(8)
            }
        }
    }

    fun applyDraft(
        prompt: CharSequence,
        channelId: String,
        model: String,
        aspectRatio: String,
        resolution: String,
        quantity: Int,
    ) {
        setPrompt(prompt)
        selectTarget(channelId, model)
        selectAspectRatio(aspectRatio)
        selectResolution(resolution)
        setQuantity(quantity)
        updateSubmitState()
    }

    fun setSubmitting(isSubmitting: Boolean) {
        submitting = isSubmitting
        binding.studioStatusProgress.visibility = if (isSubmitting) View.VISIBLE else View.GONE
        binding.studioSubmitButton.text = if (isSubmitting) TEXT_SUBMIT_RUNNING else TEXT_SUBMIT
        if (isSubmitting) {
            setStatus(TEXT_STATUS_SUBMITTING)
        }
        updateSubmitState()
    }

    fun setStatus(message: CharSequence) {
        binding.studioStatusBody.text = message
    }

    fun setReferenceImage(filePath: String) {
        referenceImagePath = filePath.trim()
        val bitmap = decodePreview(referenceImagePath, dp(420))
        if (bitmap != null) {
            binding.studioReferenceImage.setImageBitmap(bitmap)
            binding.studioReferenceImage.visibility = View.VISIBLE
            binding.studioReferenceStatus.text = context.getString(R.string.studio_reference_selected)
            binding.studioClearReferenceButton.visibility = View.VISIBLE
        } else {
            clearReferenceImage()
        }
    }

    fun clearReferenceImage() {
        referenceImagePath = ""
        binding.studioReferenceImage.setImageDrawable(null)
        binding.studioReferenceImage.visibility = View.GONE
        binding.studioReferenceStatus.text = context.getString(R.string.studio_reference_empty)
        binding.studioClearReferenceButton.visibility = View.GONE
    }

    fun setResultPlaceholder(message: CharSequence) {
        binding.studioResultImage.visibility = View.GONE
        binding.studioResultImage.setImageDrawable(null)
        binding.studioResultGallery.removeAllViews()
        binding.studioResultGalleryScroll.visibility = View.GONE
        binding.studioResultPlaceholder.text = message
        binding.studioSavePublicButton.visibility = View.GONE
    }

    fun setResultImage(filePath: String, message: CharSequence, canSavePublic: Boolean = true) {
        setResultImages(listOf(filePath), message, canSavePublic)
    }

    fun setResultImages(filePaths: List<String>, message: CharSequence, canSavePublic: Boolean = true) {
        val paths = filePaths.map { it.trim() }.filter { it.isNotBlank() }
        val hasMainPreview = showMainPreview(paths.firstOrNull().orEmpty())
        renderResultGallery(paths)
        if (!hasMainPreview) {
            binding.studioResultImage.visibility = View.GONE
            binding.studioResultImage.setImageDrawable(null)
        }
        binding.studioResultPlaceholder.text = message
        binding.studioSavePublicButton.visibility = if (canSavePublic && paths.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun showMainPreview(filePath: String): Boolean {
        val bitmap = decodePreview(filePath, mainPreviewMaxSide())
        if (bitmap != null) {
            binding.studioResultImage.setImageBitmap(bitmap)
            binding.studioResultImage.visibility = View.VISIBLE
            return true
        } else {
            binding.studioResultImage.visibility = View.GONE
            binding.studioResultImage.setImageDrawable(null)
            return false
        }
    }

    private fun renderResultGallery(filePaths: List<String>) {
        binding.studioResultGallery.removeAllViews()
        if (filePaths.size <= 1) {
            binding.studioResultGalleryScroll.visibility = View.GONE
            return
        }

        filePaths.take(MAX_GALLERY_ITEMS).forEach { path ->
            val bitmap = decodePreview(path, dp(112))
            val item = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(92), dp(92)).apply {
                    marginEnd = dp(8)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(context.getColor(R.color.aib_surface2))
                contentDescription = context.getString(R.string.studio_result_image)
                setOnClickListener { showMainPreview(path) }
                if (bitmap != null) {
                    setImageBitmap(bitmap)
                }
            }
            binding.studioResultGallery.addView(item)
        }
        binding.studioResultGalleryScroll.visibility = View.VISIBLE
    }

    private fun submitCurrentForm() {
        val request = buildSubmitRequest()
        if (request == null) {
            updateSubmitState()
            return
        }

        val callback = onSubmit
        if (callback == null) {
            setStatus(TEXT_STATUS_NO_LISTENER)
        } else {
            callback(request)
        }
    }

    private fun buildSubmitRequest(): StudioSubmitRequest? {
        val prompt = binding.studioPromptInput.text?.toString()?.trim().orEmpty()
        if (prompt.isBlank()) {
            setStatus(TEXT_STATUS_NO_PROMPT)
            return null
        }

        val target = selectedTarget()
        if (target == null) {
            setStatus(TEXT_STATUS_NO_CHANNEL)
            return null
        }

        return StudioSubmitRequest(
            prompt = prompt,
            channelId = target.channelId,
            channelName = target.channelName,
            providerType = target.providerType,
            baseUrl = target.baseUrl,
            model = target.model,
            aspectRatio = selectedAspectRatio(),
            resolution = selectedResolution(),
            quantity = quantity,
            timeoutSeconds = target.timeoutSeconds,
            proxy = target.proxy,
            referenceImagePath = referenceImagePath,
        )
    }

    private fun showTargetPicker() {
        if (targetOptions.isEmpty()) return
        val labels = targetOptions.map { target ->
            val modelLabel = target.model.ifBlank { TEXT_CHANNEL_MODEL_UNKNOWN }
            "${target.channelName} / $modelLabel\n${target.typeLabel()}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.studio_channel_select_title)
            .setSingleChoiceItems(labels, selectedTargetIndex) { dialog, which ->
                selectedTargetIndex = which
                renderSelectedTarget()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun selectTarget(channelId: String, model: String) {
        val index = targetOptions.indexOfFirst {
            it.channelId == channelId && (model.isBlank() || it.model == model)
        }.takeIf { it >= 0 } ?: targetOptions.indexOfFirst { it.channelId == channelId }
        if (index >= 0) {
            selectedTargetIndex = index
            renderSelectedTarget()
        }
    }

    private fun renderSelectedTarget() {
        val target = selectedTarget()
        if (target == null) {
            binding.studioChannelName.text = TEXT_CHANNEL_EMPTY_TITLE
            binding.studioChannelMeta.text = TEXT_CHANNEL_EMPTY_BODY
            binding.studioChannelEndpoint.visibility = View.GONE
            binding.studioNextTargetButton.isEnabled = false
            return
        }

        val modelLabel = target.model.ifBlank { TEXT_CHANNEL_MODEL_UNKNOWN }
        binding.studioChannelName.text = "${target.channelName} · $modelLabel"
        binding.studioChannelMeta.text = context.getString(R.string.studio_channel_type, target.typeLabel())
        binding.studioChannelEndpoint.visibility = View.VISIBLE
        binding.studioChannelEndpoint.text = "接口：${target.baseUrl.ifBlank { "-" }}"
        binding.studioNextTargetButton.isEnabled = targetOptions.size > 1
    }

    private fun selectedTarget(): StudioChannelTarget? {
        return targetOptions.getOrNull(selectedTargetIndex)
    }

    private fun setQuantity(nextQuantity: Int) {
        quantity = nextQuantity.coerceIn(MIN_QUANTITY, MAX_QUANTITY)
        binding.studioQuantityValue.text = "$quantity 张"
        binding.studioQuantityMinus.isEnabled = quantity > MIN_QUANTITY
        binding.studioQuantityPlus.isEnabled = quantity < MAX_QUANTITY
    }

    private fun updateSubmitState() {
        val hasPrompt = binding.studioPromptInput.text?.toString()?.trim().orEmpty().isNotEmpty()
        binding.studioSubmitButton.isEnabled = !submitting && hasPrompt && selectedTarget() != null
    }

    private fun selectedAspectRatio(): String {
        return when (binding.studioAspectGroup.checkedButtonId) {
            binding.studioAspectPortrait.id -> "3:4"
            binding.studioAspectLandscape.id -> "4:3"
            binding.studioAspectWide.id -> "16:9"
            else -> "1:1"
        }
    }

    private fun selectAspectRatio(aspectRatio: String) {
        val buttonId = when (aspectRatio.trim()) {
            "3:4", "portrait" -> binding.studioAspectPortrait.id
            "4:3", "landscape" -> binding.studioAspectLandscape.id
            "16:9", "wide" -> binding.studioAspectWide.id
            else -> binding.studioAspectSquare.id
        }
        binding.studioAspectGroup.check(buttonId)
    }

    private fun selectedResolution(): String {
        return when (binding.studioResolutionGroup.checkedButtonId) {
            binding.studioResolution1536.id -> "1536"
            binding.studioResolution2048.id -> "2048"
            else -> "1024"
        }
    }

    private fun selectResolution(resolution: String) {
        val normalized = resolution.trim().lowercase()
        val longSide = if ("x" in normalized) {
            normalized.split('x')
                .mapNotNull { it.toIntOrNull() }
                .maxOrNull()
                ?.toString()
        } else {
            normalized.filter { it.isDigit() }
        }.orEmpty()
        val buttonId = when (longSide) {
            "1536" -> binding.studioResolution1536.id
            "2048" -> binding.studioResolution2048.id
            else -> binding.studioResolution1024.id
        }
        binding.studioResolutionGroup.check(buttonId)
    }

    private fun decodePreview(filePath: String, maxSidePx: Int): android.graphics.Bitmap? {
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

    private fun mainPreviewMaxSide(): Int {
        return (resources.displayMetrics.widthPixels * 1.5f).toInt().coerceAtLeast(640)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    private fun ProviderChannel.toStudioTargets(): List<StudioChannelTarget> {
        if (!enabled) return emptyList()
        val modelTypes = modelTypeOverrides(extraJson)
        val models = enabledModels
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return models.map { model ->
            StudioChannelTarget(
                channelId = id,
                channelName = name,
                providerType = modelTypes[model]?.takeIf { it.isNotBlank() } ?: providerType,
                baseUrl = baseUrl,
                model = model,
                timeoutSeconds = timeoutSeconds,
                proxy = proxy,
            )
        }
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

    private fun StudioChannelTarget.typeLabel(): String {
        return when (providerType.trim()) {
            "openai_compatible_image" -> context.getString(R.string.model_type_openai_image)
            "gemini_image" -> context.getString(R.string.model_type_gemini_image)
            "agnes_image" -> context.getString(R.string.model_type_agnes_image)
            "grok_image" -> context.getString(R.string.model_type_grok_image)
            "openai_compatible_video" -> context.getString(R.string.model_type_openai_video)
            "grok_video" -> context.getString(R.string.model_type_grok_video)
            "seedance_video" -> context.getString(R.string.model_type_seedance_video)
            else -> providerType.ifBlank { "-" }
        }
    }

    private fun loadCustomPromptPresets(): List<PromptPreset> {
        val raw = presetStore.getString(KEY_CUSTOM_PROMPT_PRESETS, "").orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val presets = mutableListOf<PromptPreset>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val title = item.optString("title", "").trim()
            val prompt = item.optString("prompt", "").trim()
            if (title.isNotBlank() && prompt.isNotBlank()) {
                presets.add(PromptPreset(title = title, prompt = prompt))
            }
        }
        return presets
    }

    private fun saveCustomPromptPreset(preset: PromptPreset) {
        val presets = (loadCustomPromptPresets().filterNot { it.title == preset.title } + preset)
            .takeLast(MAX_CUSTOM_PROMPT_PRESETS)
        val array = JSONArray()
        presets.forEach { item ->
            array.put(
                JSONObject()
                    .put("title", item.title)
                    .put("prompt", item.prompt),
            )
        }
        presetStore.edit().putString(KEY_CUSTOM_PROMPT_PRESETS, array.toString()).apply()
    }

    private data class PromptPreset(
        val title: String,
        val prompt: String,
    )

    data class StudioChannelTarget(
        val channelId: String,
        val channelName: String,
        val providerType: String,
        val baseUrl: String,
        val model: String,
        val timeoutSeconds: Int,
        val proxy: String = "",
    )

    data class StudioSubmitRequest(
        val prompt: String,
        val channelId: String,
        val channelName: String,
        val providerType: String,
        val baseUrl: String,
        val model: String,
        val aspectRatio: String,
        val resolution: String,
        val quantity: Int,
        val timeoutSeconds: Int,
        val proxy: String = "",
        val referenceImagePath: String = "",
    )

    companion object {
        private const val MIN_QUANTITY = 1
        private const val MAX_QUANTITY = 4
        private const val MAX_GALLERY_ITEMS = 12
        private const val MAX_CUSTOM_PROMPT_PRESETS = 50
        private const val PRESET_PREVIEW_CHARS = 48
        private const val DEFAULT_QUANTITY = 1
        private const val PREFS_PROMPT_PRESETS = "studio_prompt_presets"
        private const val KEY_CUSTOM_PROMPT_PRESETS = "custom_prompt_presets"
        private const val TEXT_SUBMIT = "开始生成"
        private const val TEXT_SUBMIT_RUNNING = "提交中"
        private const val TEXT_STATUS_WAITING = "填写提示词并绑定可用渠道后即可提交。"
        private const val TEXT_STATUS_NO_PROMPT = "请先填写提示词。"
        private const val TEXT_STATUS_NO_CHANNEL = "请先绑定或启用一个可用渠道。"
        private const val TEXT_STATUS_NO_LISTENER = "表单请求已整理完成，等待主线程接入提交逻辑。"
        private const val TEXT_STATUS_SUBMITTING = "正在提交生成任务。"
        private const val TEXT_RESULT_PLACEHOLDER = "生成结果会在这里展示，当前仅保留占位区域。"
        private const val TEXT_CHANNEL_EMPTY_TITLE = "无可用渠道"
        private const val TEXT_CHANNEL_EMPTY_BODY = "请先在渠道页启用至少一个模型。"
        private const val TEXT_CHANNEL_MODEL_UNKNOWN = "未指定模型"
        private val DEFAULT_PROMPT_PRESETS = listOf(
            PromptPreset(
                title = "手办化",
                prompt = "参考上传图片，将主体改造成精致收藏级手办，保留人物主要特征、发型、服装和姿态，材质为高质量PVC与树脂，带透明底座，棚拍灯光，细节清晰。",
            ),
            PromptPreset(
                title = "真人化",
                prompt = "参考上传图片，将主体转换为真实人物照片风格，保留原始五官特征、发型、服装和整体气质，自然皮肤质感，真实光影，高清摄影。",
            ),
            PromptPreset(
                title = "变COS",
                prompt = "参考上传图片，将主体改造成高质量COS写真，保留人物特征，替换为指定角色风格服装与道具，妆造精致，摄影棚灯光，画面清晰。",
            ),
            PromptPreset(
                title = "变真人",
                prompt = "参考上传图片，将二次元或插画角色转换为真实真人形象，保留角色辨识度、发型、服装配色和气质，写实摄影风格，自然光影。",
            ),
            PromptPreset(
                title = "Q版化",
                prompt = "参考上传图片，将主体转换为可爱的Q版形象，大头小身比例，保留关键外观特征和服装元素，表情灵动，色彩明快，干净背景。",
            ),
        )
    }
}
