package io.nekohasekai.sagernet.bg

import android.content.Context
import android.text.format.Formatter
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import java.util.Locale

object LiveUpdateFormatter {

    const val PRESET_STANDARD = "standard"
    const val PRESET_COMPACT = "compact"
    const val PRESET_DETAILED = "detailed"
    const val PRESET_CUSTOM = "custom"

    const val DEFAULT_STANDARD_TITLE = "{profile}"
    const val DEFAULT_STANDARD_CONTENT = "↓ {down}   ↑ {up}"
    const val DEFAULT_STANDARD_SUBTEXT = "{traffic_total}"

    const val DEFAULT_COMPACT_TITLE = "Throne"
    const val DEFAULT_COMPACT_CONTENT = "↓ {down}  ↑ {up}"
    const val DEFAULT_COMPACT_SUBTEXT = "{profile}"

    const val DEFAULT_DETAILED_TITLE = "[{group}] {profile}"
    const val DEFAULT_DETAILED_CONTENT = "↓ {down}  ↑ {up}  |  {traffic_total}"
    const val DEFAULT_DETAILED_SUBTEXT = "{duration}"

    data class LiveUpdateInfo(
        val profileName: String,
        val groupName: String,
        val downSpeed: String,
        val upSpeed: String,
        val totalDownTraffic: String,
        val totalUpTraffic: String,
        val totalTraffic: String,
        val duration: String,
        val protocol: String,
        val latency: String,
        val directDownSpeed: String,
        val directUpSpeed: String,
    )

    fun buildInfo(
        context: Context,
        profile: ProxyEntity?,
        stats: SpeedDisplayData,
        startTimeMs: Long,
    ): LiveUpdateInfo {
        val profileName = profile?.displayName() ?: ""
        val groupName = profile?.let {
            if (it.groupId != 0L) SagerDatabase.groupDao.getById(it.groupId)?.displayName() else null
        } ?: ""

        val downSpeed = Formatter.formatFileSize(context, stats.rxRateProxy) + "/s"
        val upSpeed = Formatter.formatFileSize(context, stats.txRateProxy) + "/s"
        val totalDownTraffic = Formatter.formatFileSize(context, stats.rxTotal)
        val totalUpTraffic = Formatter.formatFileSize(context, stats.txTotal)
        val totalTraffic = Formatter.formatFileSize(context, stats.rxTotal + stats.txTotal)

        val durationSec = if (startTimeMs > 0) ((System.currentTimeMillis() - startTimeMs) / 1000).coerceAtLeast(0) else 0L
        val hours = durationSec / 3600
        val minutes = (durationSec % 3600) / 60
        val seconds = durationSec % 60
        val duration = if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }

        val protocol = profile?.type?.uppercase(Locale.getDefault()) ?: ""
        val latency = if (profile != null && profile.latency > 0) "${profile.latency}ms" else ""
        val directDownSpeed = Formatter.formatFileSize(context, stats.rxRateDirect) + "/s"
        val directUpSpeed = Formatter.formatFileSize(context, stats.txRateDirect) + "/s"

        return LiveUpdateInfo(
            profileName = profileName,
            groupName = groupName,
            downSpeed = downSpeed,
            upSpeed = upSpeed,
            totalDownTraffic = totalDownTraffic,
            totalUpTraffic = totalUpTraffic,
            totalTraffic = totalTraffic,
            duration = duration,
            protocol = protocol,
            latency = latency,
            directDownSpeed = directDownSpeed,
            directUpSpeed = directUpSpeed,
        )
    }

    fun getMockInfo(context: Context): LiveUpdateInfo {
        return LiveUpdateInfo(
            profileName = "Hong Kong 01",
            groupName = "Premium",
            downSpeed = "12.5 MB/s",
            upSpeed = "1.2 MB/s",
            totalDownTraffic = "1.4 GB",
            totalUpTraffic = "230 MB",
            totalTraffic = "1.6 GB",
            duration = "01:23:45",
            protocol = "VLESS",
            latency = "45ms",
            directDownSpeed = "0 B/s",
            directUpSpeed = "0 B/s",
        )
    }

    fun format(template: String, info: LiveUpdateInfo): String {
        if (template.isEmpty()) return ""
        var res = template
            .replace("{profile}", info.profileName)
            .replace("{group}", info.groupName)
            .replace("{down}", info.downSpeed)
            .replace("{up}", info.upSpeed)
            .replace("{traffic_down}", info.totalDownTraffic)
            .replace("{traffic_up}", info.totalUpTraffic)
            .replace("{traffic_total}", info.totalTraffic)
            .replace("{duration}", info.duration)
            .replace("{protocol}", info.protocol)
            .replace("{latency}", info.latency)
            .replace("{direct_down}", info.directDownSpeed)
            .replace("{direct_up}", info.directUpSpeed)

        if (info.groupName.isEmpty()) {
            res = res.replace("[]", "").replace("【】", "")
        }
        return res.replace(Regex("\\s+"), " ").trim()
    }

    fun getTemplates(): Triple<String, String, String> {
        return when (DataStore.liveUpdatePreset) {
            PRESET_COMPACT -> Triple(DEFAULT_COMPACT_TITLE, DEFAULT_COMPACT_CONTENT, DEFAULT_COMPACT_SUBTEXT)
            PRESET_DETAILED -> Triple(DEFAULT_DETAILED_TITLE, DEFAULT_DETAILED_CONTENT, DEFAULT_DETAILED_SUBTEXT)
            PRESET_CUSTOM -> Triple(
                DataStore.liveUpdateTitleFormat.ifEmpty { DEFAULT_STANDARD_TITLE },
                DataStore.liveUpdateContentFormat.ifEmpty { DEFAULT_STANDARD_CONTENT },
                DataStore.liveUpdateSubtextFormat.ifEmpty { DEFAULT_STANDARD_SUBTEXT }
            )
            else -> Triple(DEFAULT_STANDARD_TITLE, DEFAULT_STANDARD_CONTENT, DEFAULT_STANDARD_SUBTEXT)
        }
    }
}
