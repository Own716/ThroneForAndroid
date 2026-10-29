package io.nekohasekai.sagernet.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.bg.LiveUpdateFormatter
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ktx.remove
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.update.UpdateScheduler
import io.nekohasekai.sagernet.utils.AlwaysOnVpn
import io.nekohasekai.sagernet.utils.AppLocale
import io.nekohasekai.sagernet.utils.BatteryOptimizations
import io.nekohasekai.sagernet.utils.OemBackground
import io.nekohasekai.sagernet.utils.Theme
import moe.matsuri.nb4a.ui.ColorPickerPreference
import moe.matsuri.nb4a.ui.SimpleMenuPreference

/** Service mode, auto connect (remember_enable) and the connection behaviour switches. */
class GeneralSettingsFragment : SettingsScreenFragment(R.xml.settings_general) {

    private lateinit var autoConnect: SwitchPreference
    private lateinit var alwaysOn: Preference
    private lateinit var battery: Preference

    override fun bind() {
        pref<Preference>(Key.SERVICE_MODE).setOnPreferenceChangeListener { _, _ ->
            if (DataStore.serviceState.started) SagerNet.stopService()
            true
        }
        // The boot receiver follows the switch at once, not only at the next service start.
        autoConnect = pref(SettingsRegistry.REMEMBER_ENABLE.key)
        autoConnect.setOnPreferenceChangeListener { _, newValue ->
            BootReceiver.enabled = newValue as Boolean
            true
        }
        if (Build.VERSION.SDK_INT < 28) {
            pref<Preference>(Key.METERED_NETWORK).remove()
        }
        pref<SwitchPreference>(Key.HIDE_FROM_RECENT_APPS).setOnPreferenceChangeListener { _, newValue ->
            (activity as? MainActivity)?.applyHideFromRecentApps(newValue as Boolean)
            true
        }
        reloadOn(Key.METERED_NETWORK)

        alwaysOn = pref(KEY_ALWAYS_ON_VPN)
        alwaysOn.setOnPreferenceClickListener {
            if (!AlwaysOnVpn.openSettings(requireContext())) toast(R.string.vpn_settings_missing)
            true
        }
        battery = pref(KEY_BATTERY_OPTIMIZATION)
        battery.setOnPreferenceClickListener {
            val context = requireContext()
            if (!BatteryOptimizations.launch(context, BatteryOptimizations.actionIntent(context))) {
                toast(R.string.battery_settings_missing)
            }
            true
        }
        pref<Preference>(KEY_BACKGROUND_RESTRICTIONS).setOnPreferenceClickListener {
            OemBackground.open(requireContext())
            true
        }

        pref<Preference>(KEY_UPDATES).isVisible = BuildConfig.IN_APP_UPDATER
        pref<Preference>(Key.UPDATE_CHECK_AUTO).setOnPreferenceChangeListener { _, newValue ->
            UpdateScheduler.schedule(enabled = newValue as Boolean)
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
    }

    private fun refreshStates() {
        if (!::battery.isInitialized) return
        val context = requireContext()
        battery.setSummary(
            when (BatteryOptimizations.state(context)) {
                BatteryOptimizations.State.UNRESTRICTED -> R.string.battery_state_unrestricted
                BatteryOptimizations.State.OPTIMIZED -> R.string.battery_state_optimized
                BatteryOptimizations.State.RESTRICTED -> R.string.battery_state_restricted
            }
        )
        val alwaysOnState = AlwaysOnVpn.state()
        alwaysOn.setSummary(
            when (alwaysOnState) {
                AlwaysOnVpn.State.OFF -> R.string.always_on_vpn_off
                AlwaysOnVpn.State.ON -> R.string.always_on_vpn_on
                AlwaysOnVpn.State.LOCKDOWN -> R.string.always_on_vpn_lockdown
                AlwaysOnVpn.State.UNKNOWN -> R.string.always_on_vpn_unknown
            }
        )
        val superseded = alwaysOnState == AlwaysOnVpn.State.ON || alwaysOnState == AlwaysOnVpn.State.LOCKDOWN
        autoConnect.setSummary(if (superseded) R.string.auto_connect_superseded else R.string.auto_connect_summary)
    }

    private companion object {
        const val KEY_ALWAYS_ON_VPN = "alwaysOnVpn"
        const val KEY_BATTERY_OPTIMIZATION = "batteryOptimization"
        const val KEY_BACKGROUND_RESTRICTIONS = "backgroundRestrictions"
        const val KEY_UPDATES = "cag_updates"
    }
}

/** Theme, language, profile list and notification options (all Android-only). */
class AppearanceSettingsFragment : SettingsScreenFragment(R.xml.settings_appearance) {

    override fun bind() {
        val appTheme = pref<ColorPickerPreference>(Key.APP_THEME)
        val useSystemTheme = pref<SwitchPreference>(Key.USE_SYSTEM_THEME)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            useSystemTheme.isVisible = false
        } else {
            useSystemTheme.setOnPreferenceChangeListener { _, newValue ->
                appTheme.isEnabled = !(newValue as Boolean)
                applyThemeInstantly()
                true
            }
            appTheme.isEnabled = !DataStore.useSystemTheme
        }
        appTheme.setOnPreferenceChangeListener { _, _ ->
            applyThemeInstantly()
            true
        }
        pref<SimpleMenuPreference>(Key.NIGHT_THEME).setOnPreferenceChangeListener { _, newTheme ->
            Theme.currentNightMode = (newTheme as String).toInt()
            Theme.applyNightTheme()
            true
        }
        // AMOLED black: recreate the host so the overlay is applied or removed
        pref<SwitchPreference>(Key.AMOLED_THEME).setOnPreferenceChangeListener { _, _ ->
            applyThemeInstantly()
            true
        }
        pref<SimpleMenuPreference>(Key.APP_LANGUAGE).setOnPreferenceChangeListener { _, newValue ->
            AppLocale.apply(newValue as String)
            true
        }
        reloadOn(Key.SHOW_DIRECT_SPEED)

        val liveUpdateEnabled = pref<SwitchPreference>(Key.LIVE_UPDATE_ENABLED)
        val liveUpdateShowCapsule = pref<SwitchPreference>(Key.LIVE_UPDATE_SHOW_CAPSULE)
        val liveUpdatePreset = pref<SimpleMenuPreference>(Key.LIVE_UPDATE_PRESET)
        val liveUpdateCollapsed = pref<EditTextPreference>(Key.LIVE_UPDATE_COLLAPSED_FORMAT)
        val liveUpdateTitle = pref<EditTextPreference>(Key.LIVE_UPDATE_TITLE_FORMAT)
        val liveUpdateContent = pref<EditTextPreference>(Key.LIVE_UPDATE_CONTENT_FORMAT)
        val liveUpdateSubtext = pref<EditTextPreference>(Key.LIVE_UPDATE_SUBTEXT_FORMAT)
        val liveUpdatePreview = pref<Preference>("liveUpdatePreview")

        fun notifyNotificationUpdate() {
            if (DataStore.serviceState.started) {
                val intent = Intent(Action.REFRESH_NOTIFICATION)
                intent.setPackage(requireContext().packageName)
                requireContext().sendBroadcast(intent)
            }
        }

        fun updateLiveUpdatePreview() {
            val mockInfo = LiveUpdateFormatter.getMockInfo(requireContext())
            val tpls = LiveUpdateFormatter.getTemplates()
            val renderedCollapsed = LiveUpdateFormatter.format(tpls.collapsed, mockInfo)
            val renderedTitle = LiveUpdateFormatter.format(tpls.title, mockInfo)
            val renderedContent = LiveUpdateFormatter.format(tpls.content, mockInfo)
            val renderedSub = LiveUpdateFormatter.format(tpls.subText, mockInfo)
            liveUpdatePreview.summary = getString(
                R.string.live_update_preview_summary_format,
                renderedCollapsed,
                renderedTitle,
                renderedContent,
                renderedSub
            )
        }

        fun syncCustomFieldVisibility(preset: String) {
            val isCustom = preset == LiveUpdateFormatter.PRESET_CUSTOM
            liveUpdateCollapsed.isEnabled = isCustom
            liveUpdateTitle.isEnabled = isCustom
            liveUpdateContent.isEnabled = isCustom
            liveUpdateSubtext.isEnabled = isCustom

            when (preset) {
                LiveUpdateFormatter.PRESET_STANDARD -> {
                    liveUpdateCollapsed.text = LiveUpdateFormatter.DEFAULT_STANDARD_COLLAPSED
                    liveUpdateTitle.text = LiveUpdateFormatter.DEFAULT_STANDARD_TITLE
                    liveUpdateContent.text = LiveUpdateFormatter.DEFAULT_STANDARD_CONTENT
                    liveUpdateSubtext.text = LiveUpdateFormatter.DEFAULT_STANDARD_SUBTEXT
                }
                LiveUpdateFormatter.PRESET_COMPACT -> {
                    liveUpdateCollapsed.text = LiveUpdateFormatter.DEFAULT_COMPACT_COLLAPSED
                    liveUpdateTitle.text = LiveUpdateFormatter.DEFAULT_COMPACT_TITLE
                    liveUpdateContent.text = LiveUpdateFormatter.DEFAULT_COMPACT_CONTENT
                    liveUpdateSubtext.text = LiveUpdateFormatter.DEFAULT_COMPACT_SUBTEXT
                }
                LiveUpdateFormatter.PRESET_DETAILED -> {
                    liveUpdateCollapsed.text = LiveUpdateFormatter.DEFAULT_DETAILED_COLLAPSED
                    liveUpdateTitle.text = LiveUpdateFormatter.DEFAULT_DETAILED_TITLE
                    liveUpdateContent.text = LiveUpdateFormatter.DEFAULT_DETAILED_CONTENT
                    liveUpdateSubtext.text = LiveUpdateFormatter.DEFAULT_DETAILED_SUBTEXT
                }
            }
        }

        liveUpdateEnabled.setOnPreferenceChangeListener { _, _ ->
            view?.post {
                updateLiveUpdatePreview()
                notifyNotificationUpdate()
            }
            needReload()
            true
        }

        liveUpdateShowCapsule.setOnPreferenceChangeListener { _, _ ->
            view?.post {
                notifyNotificationUpdate()
            }
            true
        }

        syncCustomFieldVisibility(DataStore.liveUpdatePreset)
        updateLiveUpdatePreview()

        liveUpdatePreset.setOnPreferenceChangeListener { _, newValue ->
            val newPreset = newValue as String
            syncCustomFieldVisibility(newPreset)
            view?.post {
                updateLiveUpdatePreview()
                notifyNotificationUpdate()
            }
            needReload()
            true
        }

        val onFormatChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
            view?.post {
                updateLiveUpdatePreview()
                notifyNotificationUpdate()
            }
            needReload()
            true
        }
        liveUpdateCollapsed.onPreferenceChangeListener = onFormatChangeListener
        liveUpdateTitle.onPreferenceChangeListener = onFormatChangeListener
        liveUpdateContent.onPreferenceChangeListener = onFormatChangeListener
        liveUpdateSubtext.onPreferenceChangeListener = onFormatChangeListener

        liveUpdatePreview.setOnPreferenceClickListener {
            val mockInfo = LiveUpdateFormatter.getMockInfo(requireContext())
            val tpls = LiveUpdateFormatter.getTemplates()
            val renderedCollapsed = LiveUpdateFormatter.format(tpls.collapsed, mockInfo)
            val renderedTitle = LiveUpdateFormatter.format(tpls.title, mockInfo)
            val renderedContent = LiveUpdateFormatter.format(tpls.content, mockInfo)
            val renderedSub = LiveUpdateFormatter.format(tpls.subText, mockInfo)

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.live_update_preview_dialog_title)
                .setMessage(
                    getString(
                        R.string.live_update_preview_dialog_message,
                        renderedCollapsed,
                        renderedTitle,
                        renderedContent,
                        renderedSub
                    )
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
            true
        }

        pref<MultiSelectListPreference>(Key.NOTIFICATION_ACTIONS).apply {
            summaryProvider = Preference.SummaryProvider<MultiSelectListPreference> { p ->
                val chosen = p.values
                p.entryValues.indices.filter { p.entryValues[it].toString() in chosen }
                    .joinToString(", ") { p.entries[it] }
                    .ifEmpty { getString(androidx.preference.R.string.not_set) }
            }
            setOnPreferenceChangeListener { _, newValue ->
                if ((newValue as Set<*>).size > 3) {
                    toast(R.string.notification_actions_too_many)
                    false
                } else {
                    needReload()
                    true
                }
            }
        }
        notificationPermission = pref(KEY_NOTIFICATION_PERMISSION)
        notificationPermission.setOnPreferenceClickListener {
            openNotificationSettings()
            true
        }
    }

    private lateinit var notificationPermission: Preference

    override fun onResume() {
        super.onResume()
        if (::notificationPermission.isInitialized) {
            notificationPermission.setSummary(
                if (NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()) {
                    R.string.notification_permission_allowed
                } else {
                    R.string.notification_permission_blocked
                }
            )
        }
    }

    private fun openNotificationSettings() {
        val context = requireContext()
        val app = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            app
        }
        for (candidate in listOf(intent, app)) {
            try {
                startActivity(candidate)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }

    // Appearance changes apply at once: the recreated host applies the theme and the AMOLED overlay
    // from DataStore in onCreate; posted to the next frame to stay out of the running preference/dialog transaction
    private fun applyThemeInstantly() {
        val host = activity ?: return
        host.window?.decorView?.post {
            if (!host.isFinishing && !host.isDestroyed) {
                ActivityCompat.recreate(host)
            }
        }
    }

    private companion object {
        const val KEY_NOTIFICATION_PERMISSION = "notificationPermission"
    }
}
