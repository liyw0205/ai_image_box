package com.aiimagebox

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aiimagebox.databinding.ActivityMainBinding
import com.aiimagebox.data.ChannelStore
import com.aiimagebox.data.ProviderChannel
import com.aiimagebox.data.SecureKeyStore
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONObject
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var channelStore: ChannelStore
    private var currentTab: Tab = Tab.STUDIO
    private var syncingNav = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        channelStore = ChannelStore((application as AIImageBoxApp).appDirectories)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentTab = Tab.fromId(savedInstanceState?.getInt(KEY_TAB) ?: R.id.nav_studio)
        wireNavigation()
        render(currentTab)
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
            if (currentTab == Tab.CHANNELS) {
                showChannelDialog(null)
            } else {
                Toast.makeText(this, getString(R.string.toast_next_milestone), Toast.LENGTH_SHORT).show()
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
        if (tab == Tab.CHANNELS) renderChannelList()
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
        row.addView(actionButton(if (channel.enabled) R.string.action_disable else R.string.action_enable) {
            channelStore.setEnabled(channel.id, !channel.enabled)
            renderChannelList()
        })
        row.addView(actionButton(R.string.action_copy) {
            channelStore.duplicate(channel.id)
            renderChannelList()
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
            }
            .show()
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
            R.string.action_view_queue,
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
