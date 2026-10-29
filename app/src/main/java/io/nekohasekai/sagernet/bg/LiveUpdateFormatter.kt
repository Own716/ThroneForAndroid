package io.nekohasekai.sagernet.bg

import android.content.Context
import android.text.format.Formatter
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorProfiles
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorRuntime
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.outbound.types.AutoSelector
import java.util.Locale

object LiveUpdateFormatter {

    const val PRESET_STANDARD = "standard"
    const val PRESET_COMPACT = "compact"
    const val PRESET_DETAILED = "detailed"
    const val PRESET_CUSTOM = "custom"

    const val DEFAULT_STANDARD_COLLAPSED = "{profile}"
    const val DEFAULT_STANDARD_TITLE = "{group}"
    const val DEFAULT_STANDARD_CONTENT = "↓ {down}   ↑ {up}"
    const val DEFAULT_STANDARD_SUBTEXT = "{profile}"

    const val DEFAULT_COMPACT_COLLAPSED = "{group}"
    const val DEFAULT_COMPACT_TITLE = "{profile}"
    const val DEFAULT_COMPACT_CONTENT = "↓ {down}  ↑ {up}"
    const val DEFAULT_COMPACT_SUBTEXT = "{ip}"

    const val DEFAULT_DETAILED_COLLAPSED = "[{group}] {profile}"
    const val DEFAULT_DETAILED_TITLE = "[{group}] {profile}"
    const val DEFAULT_DETAILED_CONTENT = "↓ {down}  ↑ {up}  |  {ip}"
    const val DEFAULT_DETAILED_SUBTEXT = "{traffic_total} ({duration})"

    data class Templates(
        val collapsed: String,
        val title: String,
        val content: String,
        val subText: String,
    )

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
        val ip: String,
        val server: String,
    )

    fun buildInfo(
        context: Context,
        profile: ProxyEntity?,
        stats: SpeedDisplayData,
        startTimeMs: Long,
        currentEgressIp: String? = null,
    ): LiveUpdateInfo {
        var profileName = ""
        var groupName = ""
        var ip = currentEgressIp.orEmpty()
        var server = ""
        var protocol = ""
        var latency = ""

        val isAutoSelector = profile?.type == AutoSelectorProfiles.TYPE || profile?.outbound is AutoSelector

        if (isAutoSelector) {
            groupName = profile?.displayName().orEmpty()
            val active = AutoSelectorRuntime.activeMember()
            if (active != null) {
                profileName = active.first
                val member = if (active.second > 0) SagerDatabase.proxyDao.getById(active.second) else null
                if (member != null) {
                    if (ip.isEmpty()) {
                        ip = member.ipOut?.ifEmpty { null } ?: member.outbound.server.ifEmpty { null } ?: member.displayAddress()
                    }
                    server = member.outbound.server.ifEmpty { member.displayAddress() }
                    protocol = member.type.uppercase(Locale.getDefault())
                    if (member.latency > 0) latency = "${member.latency}ms"
                }
            } else {
                profileName = groupName
            }
        } else if (profile != null) {
            groupName = if (profile.groupId != 0L) SagerDatabase.groupDao.getById(profile.groupId)?.displayName().orEmpty() else ""
            profileName = profile.displayName()
            if (ip.isEmpty()) {
                ip = profile.ipOut?.ifEmpty { null } ?: profile.outbound.server.ifEmpty { null } ?: profile.displayAddress()
            }
            server = profile.outbound.server.ifEmpty { profile.displayAddress() }
            protocol = profile.type.uppercase(Locale.getDefault())
            if (profile.latency > 0) latency = "${profile.latency}ms"
        }

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
            ip = ip,
            server = server,
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
            ip = "104.28.19.24",
            server = "hk01.example.com",
        )
    }

    fun format(template: String, info: LiveUpdateInfo): String {
        if (template.isEmpty()) return ""
        var res = template
            .replace("{profile}", info.profileName)
            .replace("{group}", info.groupName)
            .replace("{ip}", info.ip)
            .replace("{server}", info.server)
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
        if (info.ip.isEmpty()) {
            res = res.replace("()", "").replace("（）", "")
        }
        return res.replace(Regex("\\s+"), " ").trim()
    }

    fun getTemplates(): Templates {
        return when (DataStore.liveUpdatePreset) {
            PRESET_COMPACT -> Templates(
                DEFAULT_COMPACT_COLLAPSED,
                DEFAULT_COMPACT_TITLE,
                DEFAULT_COMPACT_CONTENT,
                DEFAULT_COMPACT_SUBTEXT
            )
            PRESET_DETAILED -> Templates(
                DEFAULT_DETAILED_COLLAPSED,
                DEFAULT_DETAILED_TITLE,
                DEFAULT_DETAILED_CONTENT,
                DEFAULT_DETAILED_SUBTEXT
            )
            PRESET_CUSTOM -> Templates(
                DataStore.liveUpdateCollapsedFormat.ifEmpty { DEFAULT_STANDARD_COLLAPSED },
                DataStore.liveUpdateTitleFormat.ifEmpty { DEFAULT_STANDARD_TITLE },
                DataStore.liveUpdateContentFormat.ifEmpty { DEFAULT_STANDARD_CONTENT },
                DataStore.liveUpdateSubtextFormat.ifEmpty { DEFAULT_STANDARD_SUBTEXT }
            )
            else -> Templates(
                DEFAULT_STANDARD_COLLAPSED,
                DEFAULT_STANDARD_TITLE,
                DEFAULT_STANDARD_CONTENT,
                DEFAULT_STANDARD_SUBTEXT
            )
        }
    }
}
