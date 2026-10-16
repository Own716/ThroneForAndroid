package io.nekohasekai.sagernet.ui.settings

import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.group.DeviceDetails
import io.nekohasekai.sagernet.group.RequestIdentity
import io.nekohasekai.sagernet.group.SubscriptionScheduler
import io.nekohasekai.sagernet.ktx.USER_AGENT
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import kotlin.math.abs

/**
 * Basic Settings › Subscription (dialog_basic_settings.ui, Subscription tab) and the network knobs of the app's own
 * requests (Use proxy, Ignore TLS errors, the Android-only minimum TLS version).
 */
class SubscriptionSettingsFragment : SettingsScreenFragment(R.xml.settings_subscriptions) {

    override fun bind() {
        val userAgent = pref<EditTextPreference>(SettingsRegistry.USER_AGENT2.key)
        userAgent.summaryProvider = DefaultSummaryProvider(USER_AGENT)
        userAgent.setOnBindEditTextListener {
            it.setSingleLine()
            it.hint = USER_AGENT
        }
        gateText(userAgent.key, SettingChecks::headerValue, reload = false)

        bindAutoUpdate()

        for ((key, tip) in listOf(
            SettingsRegistry.SUB_TLS_VERSION.key to R.string.sub_tls_version_tip,
            SettingsRegistry.SUB_HTTP_VERSION.key to R.string.sub_http_version_tip,
        )) {
            pref<SimpleMenuPreference>(key).summaryProvider =
                Preference.SummaryProvider<SimpleMenuPreference> { "${it.entry}\n${getString(tip)}" }
        }
        pref<SwitchPreference>(SettingsRegistry.SUB_RESPECT_SERVER_INTERVAL.key).setOnPreferenceChangeListener { _, newValue ->
            DataStore.subRespectServerInterval = newValue as Boolean
            SubscriptionScheduler.schedule()
            true
        }

        val sendHwid = pref<SwitchPreference>(SettingsRegistry.SUB_SEND_HWID.key)
        sendHwid.summary = hwidSummary(DataStore.subCustomHwidParams)
        val params = pref<EditTextPreference>(SettingsRegistry.SUB_CUSTOM_HWID_PARAMS.key)
        params.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        params.setOnBindEditTextListener {
            it.setSingleLine()
            it.hint = getString(R.string.grp_sub_custom_hwid_params_hint)
        }
        params.setOnPreferenceChangeListener { _, newValue ->
            val raw = newValue?.toString().orEmpty()
            val value = raw.trim()
            sendHwid.summary = hwidSummary(value)
            if (value != raw) {
                params.text = value
                return@setOnPreferenceChangeListener false
            }
            true
        }
        validation.bind(params.key, SettingChecks::hwidParams).gate()
    }

    /** sub_auto_update is sign-encoded minutes: negative is off, the magnitude is kept while off. */
    private fun bindAutoUpdate() {
        val enabled = pref<SwitchPreference>(KEY_AUTO_UPDATE)
        val interval = pref<EditTextPreference>(KEY_AUTO_UPDATE_INTERVAL)
        fun minutes() = abs(DataStore.subAutoUpdate).takeIf { it > 0 } ?: -SettingsRegistry.SUB_AUTO_UPDATE.default

        enabled.isChecked = DataStore.subAutoUpdate > 0
        interval.text = minutes().toString()
        interval.isEnabled = enabled.isChecked
        interval.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
            getString(R.string.auto_update_interval_sum, minutes())
        }
        interval.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)

        enabled.setOnPreferenceChangeListener { _, newValue ->
            val on = newValue as Boolean
            DataStore.subAutoUpdate = if (on) minutes() else -minutes()
            interval.isEnabled = on
            SubscriptionScheduler.schedule()
            true
        }
        interval.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue?.toString()?.trim()?.toIntOrNull() ?: return@setOnPreferenceChangeListener false
            DataStore.subAutoUpdate = if (enabled.isChecked) value else -value
            SubscriptionScheduler.schedule()
            true
        }
        validation.bind(interval.key, SettingChecks.autoUpdateMinutes).gate()
    }

    /** The four values the requests send (the desktop's Send HWID tooltip), after the custom parameters. */
    private fun hwidSummary(customParams: String): String {
        val device = RequestIdentity.applyCustomHwidParams(DeviceDetails.get(), customParams)
        fun shown(value: String) = value.ifEmpty { getString(R.string.grp_not_available) }
        return getString(
            R.string.grp_sub_send_hwid_values,
            shown(device.hwid), shown(device.os), shown(device.osVersion), shown(device.model),
        )
    }

    private companion object {
        const val KEY_AUTO_UPDATE = "subAutoUpdateEnabled"
        const val KEY_AUTO_UPDATE_INTERVAL = "subAutoUpdateInterval"
    }
}
