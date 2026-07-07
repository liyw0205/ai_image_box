package com.aiimagebox.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.core.widget.doAfterTextChanged
import com.aiimagebox.data.ProviderChannel
import com.aiimagebox.databinding.ViewStudioFormBinding

class StudioForm @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val binding = ViewStudioFormBinding.inflate(LayoutInflater.from(context), this, true)
    private val targetOptions = mutableListOf<StudioChannelTarget>()
    private var selectedTargetIndex = 0
    private var quantity = DEFAULT_QUANTITY
    private var submitting = false

    var onSubmit: ((StudioSubmitRequest) -> Unit)? = null

    init {
        binding.studioAspectGroup.check(binding.studioAspectSquare.id)
        binding.studioResolutionGroup.check(binding.studioResolution1024.id)
        binding.studioPromptInput.doAfterTextChanged { updateSubmitState() }
        binding.studioNextTargetButton.setOnClickListener { selectNextTarget() }
        binding.studioQuantityMinus.setOnClickListener { setQuantity(quantity - 1) }
        binding.studioQuantityPlus.setOnClickListener { setQuantity(quantity + 1) }
        binding.studioSubmitButton.setOnClickListener { submitCurrentForm() }

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

    fun setPrompt(prompt: CharSequence) {
        binding.studioPromptInput.setText(prompt)
        binding.studioPromptInput.setSelection(binding.studioPromptInput.text?.length ?: 0)
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

    fun setResultPlaceholder(message: CharSequence) {
        binding.studioResultPlaceholder.text = message
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
        )
    }

    private fun selectNextTarget() {
        if (targetOptions.isEmpty()) return
        selectedTargetIndex = (selectedTargetIndex + 1) % targetOptions.size
        renderSelectedTarget()
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
        binding.studioChannelMeta.text = "类型：${target.providerType}"
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

    private fun selectedResolution(): String {
        return when (binding.studioResolutionGroup.checkedButtonId) {
            binding.studioResolution1536.id -> "1536"
            binding.studioResolution2048.id -> "2048"
            else -> "1024"
        }
    }

    private fun ProviderChannel.toStudioTargets(): List<StudioChannelTarget> {
        if (!enabled) return emptyList()
        val models = enabledModels
            .ifEmpty { listOf(defaultModel) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("") }

        return models.map { model ->
            StudioChannelTarget(
                channelId = id,
                channelName = name,
                providerType = providerType,
                baseUrl = baseUrl,
                model = model,
                timeoutSeconds = timeoutSeconds,
                proxy = proxy,
            )
        }
    }

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
    )

    companion object {
        private const val MIN_QUANTITY = 1
        private const val MAX_QUANTITY = 4
        private const val DEFAULT_QUANTITY = 1
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
    }
}
