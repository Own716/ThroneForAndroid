package io.nekohasekai.sagernet.bg

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
import android.os.Build
import android.os.Bundle
import org.json.JSONObject
import android.text.format.Formatter
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileOrder
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.appHttpClient
import io.nekohasekai.sagernet.ktx.appUserAgent
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.SwitchActivity
import io.nekohasekai.sagernet.ui.test.TestFormat
import io.nekohasekai.sagernet.widget.ExitIp
import io.nekohasekai.sagernet.utils.Theme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import java.util.Locale

/**
 * User can customize visibility of notification since Android 8.
 * The default visibility:
 *
 * Android 8.x: always visible due to system limitations
 * VPN:         always invisible because of VPN notification/icon
 * Other:       always visible
 *
 * See also: https://github.com/aosp-mirror/platform_frameworks_base/commit/070d142993403cc2c42eca808ff3fafcee220ac4
 */
class ServiceNotification(
    private val service: BaseService.Interface,
    private val title: String,
    channel: String,
    visible: Boolean = false,
) : BroadcastReceiver() {
    companion object {
        const val notificationId = 1
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        // notificationActions entries; the template shows at most three buttons, in this order.
        const val ACTION_STOP = "stop"
        const val ACTION_PREVIOUS = "previous"
        const val ACTION_NEXT = "next"
        const val ACTION_SWITCH = "switch"
        const val ACTION_RESET = "reset"
        val DEFAULT_ACTIONS = listOf(ACTION_STOP, ACTION_NEXT, ACTION_SWITCH)
        private val ACTION_ORDER = listOf(ACTION_STOP, ACTION_PREVIOUS, ACTION_NEXT, ACTION_SWITCH, ACTION_RESET)

        fun notificationActions(): List<String> {
            val stored = DataStore.configurationStore.getStringList(Key.NOTIFICATION_ACTIONS) ?: return DEFAULT_ACTIONS
            return ACTION_ORDER.filter { it in stored }.take(3)
        }

        fun genTitle(ent: ProxyEntity): String {
            val gn = if (DataStore.showGroupInNotification)
                SagerDatabase.groupDao.getById(ent.groupId)?.displayName() else null
            return if (gn == null) ent.displayName() else "[$gn] ${ent.displayName()}"
        }
    }

    var listenPostSpeed = SagerNet.power.isInteractive

    /** Whether the screen is on; the traffic looper stops polling the core while it is off. */
    val screenOn = MutableStateFlow(SagerNet.power.isInteractive)

    private val connectStartTime = System.currentTimeMillis()
    private var lastStats: SpeedDisplayData = SpeedDisplayData()
    private var currentEgressIp: String? = null
    private var currentEgressCountry: String? = null

    private fun applyLiveUpdateProperties(
        builder: NotificationCompat.Builder,
        showCapsule: Boolean,
        collapsedTitle: String,
        expandedTitle: String,
        contentText: String,
        subText: String?
    ) {
        builder.setOngoing(true)
        if (showCapsule) {
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
            builder.priority = NotificationCompat.PRIORITY_LOW

            try {
                val method = builder.javaClass.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                method.invoke(builder, true)
            } catch (_: Throwable) {}

            builder.addExtras(Bundle().apply {
                putBoolean("android.requestPromotedOngoing", true)
                try {
                    val focusParam = JSONObject().apply {
                        put("type", 1)
                        put("baseInfo", JSONObject().apply {
                            put("title", collapsedTitle)
                            put("content", contentText)
                            if (!subText.isNullOrEmpty()) {
                                put("subTitle", subText)
                            }
                        })
                    }
                    putString("miui.focus.param", focusParam.toString())
                } catch (_: Throwable) {}
            })
        } else {
            builder.setCategory(NotificationCompat.CATEGORY_SERVICE)
            builder.priority = NotificationCompat.PRIORITY_LOW

            try {
                val method = builder.javaClass.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                method.invoke(builder, false)
            } catch (_: Throwable) {}

            builder.addExtras(Bundle().apply {
                putBoolean("android.requestPromotedOngoing", false)
                remove("miui.focus.param")
            })
        }
    }

    suspend fun postNotificationSpeedUpdate(stats: SpeedDisplayData) {
        lastStats = stats
        useBuilder {
            if (DataStore.liveUpdateEnabled) {
                val profileId = service.data.proxy?.profile?.id ?: DataStore.selectedProxy
                val profile = service.data.proxy?.profile ?: SagerDatabase.proxyDao.getById(profileId)
                val info = LiveUpdateFormatter.buildInfo(service as Context, profile, stats, connectStartTime, currentEgressIp, currentEgressCountry)
                val tpls = LiveUpdateFormatter.getTemplates()

                val formattedCollapsed = LiveUpdateFormatter.format(tpls.collapsed, info).ifEmpty { title }
                val formattedTitle = LiveUpdateFormatter.format(tpls.title, info).ifEmpty { title }
                val formattedContent = LiveUpdateFormatter.format(tpls.content, info)
                val formattedSubText = LiveUpdateFormatter.format(tpls.subText, info)

                it.setContentTitle(formattedCollapsed)
                it.setContentText(formattedContent)
                if (formattedSubText.isNotEmpty()) {
                    it.setSubText(formattedSubText)
                } else {
                    it.setSubText(null)
                }

                val bigTextStyle = NotificationCompat.BigTextStyle()
                bigTextStyle.setBigContentTitle(formattedTitle)
                bigTextStyle.bigText(formattedContent.ifEmpty { formattedTitle })
                if (formattedSubText.isNotEmpty()) {
                    bigTextStyle.setSummaryText(formattedSubText)
                } else {
                    bigTextStyle.setSummaryText(null)
                }
                it.setStyle(bigTextStyle)

                applyLiveUpdateProperties(
                    it,
                    DataStore.liveUpdateShowCapsule,
                    formattedCollapsed,
                    formattedTitle,
                    formattedContent,
                    formattedSubText
                )
            } else if (showDirectSpeed) {
                val speedDetail = (service as Context).getString(
                    R.string.speed_detail, service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.txRateProxy)
                    ), service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.rxRateProxy)
                    ), service.getString(
                        R.string.speed,
                        Formatter.formatFileSize(service, stats.txRateDirect)
                    ), service.getString(
                        R.string.speed,
                        Formatter.formatFileSize(service, stats.rxRateDirect)
                    )
                )
                it.setStyle(NotificationCompat.BigTextStyle().bigText(speedDetail))
                it.setContentText(speedDetail)
                it.setSubText(
                    service.getString(
                        R.string.traffic,
                        Formatter.formatFileSize(service, stats.txTotal),
                        Formatter.formatFileSize(service, stats.rxTotal)
                    )
                )
            } else {
                val speedSimple = (service as Context).getString(
                    R.string.traffic, service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.txRateProxy)
                    ), service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.rxRateProxy)
                    )
                )
                it.setStyle(null)
                it.setContentText(speedSimple)
                it.setSubText(
                    service.getString(
                        R.string.traffic,
                        Formatter.formatFileSize(service, stats.txTotal),
                        Formatter.formatFileSize(service, stats.rxTotal)
                    )
                )
            }
        }
        update()
    }

    suspend fun postNotificationTitle(newTitle: String) {
        useBuilder {
            if (!DataStore.liveUpdateEnabled) {
                it.setContentTitle(newTitle)
            }
        }
        update()
    }

    /** A restart in place: the new profile's title and buttons, and no speed of the old one. */
    suspend fun refresh(newTitle: String) {
        currentEgressIp = null
        currentEgressCountry = null
        updateActions()
        useBuilder {
            if (DataStore.liveUpdateEnabled) {
                val profileId = service.data.proxy?.profile?.id ?: DataStore.selectedProxy
                val profile = service.data.proxy?.profile ?: SagerDatabase.proxyDao.getById(profileId)
                val info = LiveUpdateFormatter.buildInfo(service as Context, profile, lastStats, connectStartTime, currentEgressIp, currentEgressCountry)
                val tpls = LiveUpdateFormatter.getTemplates()

                val formattedCollapsed = LiveUpdateFormatter.format(tpls.collapsed, info).ifEmpty { newTitle }
                val formattedTitle = LiveUpdateFormatter.format(tpls.title, info).ifEmpty { newTitle }
                val formattedContent = LiveUpdateFormatter.format(tpls.content, info)
                val formattedSubText = LiveUpdateFormatter.format(tpls.subText, info)

                it.setContentTitle(formattedCollapsed)
                it.setContentText(formattedContent.ifEmpty { null })
                if (formattedSubText.isNotEmpty()) {
                    it.setSubText(formattedSubText)
                } else {
                    it.setSubText(null)
                }

                val bigTextStyle = NotificationCompat.BigTextStyle()
                bigTextStyle.setBigContentTitle(formattedTitle)
                bigTextStyle.bigText(formattedContent.ifEmpty { formattedTitle })
                if (formattedSubText.isNotEmpty()) {
                    bigTextStyle.setSummaryText(formattedSubText)
                } else {
                    bigTextStyle.setSummaryText(null)
                }
                it.setStyle(bigTextStyle)

                applyLiveUpdateProperties(
                    it,
                    DataStore.liveUpdateShowCapsule,
                    formattedCollapsed,
                    formattedTitle,
                    formattedContent,
                    formattedSubText
                )
            } else {
                it.setContentTitle(newTitle)
                it.setContentText(null)
                it.setSubText(null)
                it.setStyle(null)
            }
        }
        update()
        if (DataStore.liveUpdateEnabled) {
            probeEgress()
        }
    }

    suspend fun refreshLiveUpdate() {
        postNotificationSpeedUpdate(lastStats)
    }

    /** What a start waits for (an Xray geo asset download) under the title, [percent] -1 while unknown; null clears it. */
    suspend fun postStartProgress(text: String?, percent: Int) {
        useBuilder {
            it.setContentText(text)
            if (text == null) it.setProgress(0, 0, false) else it.setProgress(100, percent.coerceAtLeast(0), percent < 0)
        }
        update()
    }

    suspend fun postConnected() {
        updateActions()
        useBuilder { it.priority = NotificationCompat.PRIORITY_LOW }
        update()
        if (DataStore.liveUpdateEnabled) {
            probeEgress()
        }
    }

    private fun isAcceptableCountry(inferredNodeCountry: String, resultCountry: String): Boolean {
        if (resultCountry.isEmpty()) return false
        val clean = LiveUpdateFormatter.cleanCountryName(resultCountry)
        // If the current proxy node is an overseas node (e.g. 日本, 香港, 台湾, etc.),
        // reject "中国" / "China" returned from domestic direct connection leaks.
        if (inferredNodeCountry.isNotEmpty() && inferredNodeCountry != "中国" &&
            (clean == "中国" || resultCountry.contains("China", ignoreCase = true) || resultCountry.equals("CN", ignoreCase = true))
        ) {
            return false
        }
        return true
    }

    private fun probeEgress() {
        if (!DataStore.liveUpdateEnabled) return
        runOnDefaultDispatcher {
            val profileId = service.data.proxy?.profile?.id ?: DataStore.selectedProxy
            val profile = service.data.proxy?.profile ?: SagerDatabase.proxyDao.getById(profileId)
            val inferred = if (profile != null) LiveUpdateFormatter.inferCountryFromName(profile.displayName()) else ""

            val exitInfo = ExitIp.info
            if (exitInfo != null && exitInfo.ip.isNotEmpty()) {
                currentEgressIp = exitInfo.ip
                val candidateCountry = if (exitInfo.country.isNotEmpty()) {
                    LiveUpdateFormatter.cleanCountryName(exitInfo.country)
                } else if (exitInfo.countryCode.isNotEmpty()) {
                    LiveUpdateFormatter.cleanCountryName(TestFormat.countryName(exitInfo.countryCode))
                } else null
                if (!candidateCountry.isNullOrEmpty() && isAcceptableCountry(inferred, candidateCountry)) {
                    currentEgressCountry = candidateCountry
                }
                postNotificationSpeedUpdate(lastStats)
            }

            try {
                val (ip, country) = queryEgress(inferred)
                var changed = false
                if (!ip.isNullOrEmpty() && ip != currentEgressIp) {
                    currentEgressIp = ip
                    changed = true
                }
                if (!country.isNullOrEmpty() && country != currentEgressCountry) {
                    currentEgressCountry = country
                    changed = true
                }
                if (changed) {
                    postNotificationSpeedUpdate(lastStats)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun queryEgress(inferredCountry: String = ""): Pair<String?, String?> {
        val client = try {
            appHttpClient(viaProxy = true, timeoutSeconds = 6)
        } catch (_: Throwable) {
            return Pair(null, null)
        }
        val userAgent = appUserAgent()

        // 1. ip-api.com
        try {
            val lang = if (Locale.getDefault().language == "zh") "zh-CN" else "en"
            val req = Request.Builder()
                .url("http://ip-api.com/json/?fields=status,country,countryCode,query&lang=$lang")
                .header("User-Agent", userAgent)
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val obj = JSONObject(body)
                    if (obj.optString("status") == "success") {
                        val ip = obj.optString("query")
                        val country = obj.optString("country")
                        val countryCode = obj.optString("countryCode")
                        val resCountry = LiveUpdateFormatter.cleanCountryName(
                            if (country.isNotEmpty()) country else TestFormat.countryName(countryCode)
                        )
                        if (isAcceptableCountry(inferredCountry, resCountry)) {
                            return Pair(ip.ifEmpty { null }, resCountry.ifEmpty { null })
                        } else if (ip.isNotEmpty()) {
                            return Pair(ip, null)
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // 2. ipwho.is
        try {
            val req = Request.Builder()
                .url("https://ipwho.is/")
                .header("User-Agent", userAgent)
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val obj = JSONObject(body)
                    if (obj.optBoolean("success", false)) {
                        val ip = obj.optString("ip")
                        val countryCode = obj.optString("country_code")
                        val country = obj.optString("country")
                        val resCountry = LiveUpdateFormatter.cleanCountryName(
                            if (countryCode.isNotEmpty()) TestFormat.countryName(countryCode) else country
                        )
                        if (isAcceptableCountry(inferredCountry, resCountry)) {
                            return Pair(ip.ifEmpty { null }, resCountry.ifEmpty { null })
                        } else if (ip.isNotEmpty()) {
                            return Pair(ip, null)
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // 3. cloudflare trace
        try {
            val req = Request.Builder()
                .url("https://www.cloudflare.com/cdn-cgi/trace")
                .header("User-Agent", userAgent)
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val lines = resp.body?.string().orEmpty().lines()
                    var ip: String? = null
                    var loc: String? = null
                    for (line in lines) {
                        if (line.startsWith("ip=")) ip = line.removePrefix("ip=").trim()
                        if (line.startsWith("loc=")) loc = line.removePrefix("loc=").trim()
                    }
                    val resCountry = if (!loc.isNullOrEmpty()) LiveUpdateFormatter.cleanCountryName(TestFormat.countryName(loc)) else null
                    val acceptable = resCountry != null && isAcceptableCountry(inferredCountry, resCountry)
                    if (!ip.isNullOrEmpty() || acceptable) {
                        return Pair(ip, if (acceptable) resCountry else null)
                    }
                }
            }
        } catch (_: Throwable) {}

        // 4. ipify fallback
        try {
            val req = Request.Builder()
                .url("https://api.ipify.org")
                .header("User-Agent", userAgent)
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val ip = resp.body?.string().orEmpty().trim()
                    if (ip.isNotEmpty()) {
                        return Pair(ip, null)
                    }
                }
            }
        } catch (_: Throwable) {}

        return Pair(null, null)
    }

    private val showDirectSpeed = DataStore.showDirectSpeed

    private val builder = NotificationCompat.Builder(service as Context, channel)
        .setWhen(0)
        .setTicker(service.getString(R.string.forward_success))
        .setContentTitle(title)
        .setOnlyAlertOnce(true)
        .setContentIntent(SagerNet.configureIntent(service))
        .setSmallIcon(R.drawable.ic_throne_tile)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(if (visible) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)

    private val buildLock = Mutex()

    private suspend fun useBuilder(f: (NotificationCompat.Builder) -> Unit) {
        buildLock.withLock {
            f(builder)
        }
    }

    init {
        service as Context

        Theme.apply(app)
        Theme.apply(service)
        builder.color = service.getColorAttr(R.attr.colorPrimary)

        if (DataStore.liveUpdateEnabled) {
            builder.setOngoing(true)
            builder.priority = NotificationCompat.PRIORITY_LOW
            val profileId = service.data.proxy?.profile?.id ?: DataStore.selectedProxy
            val profile = service.data.proxy?.profile ?: SagerDatabase.proxyDao.getById(profileId)
            val info = LiveUpdateFormatter.buildInfo(service as Context, profile, SpeedDisplayData(), connectStartTime, currentEgressIp, currentEgressCountry)
            val tpls = LiveUpdateFormatter.getTemplates()
            val formattedCollapsed = LiveUpdateFormatter.format(tpls.collapsed, info).ifEmpty { title }
            val formattedTitle = LiveUpdateFormatter.format(tpls.title, info).ifEmpty { title }
            val formattedContent = LiveUpdateFormatter.format(tpls.content, info)
            val formattedSubText = LiveUpdateFormatter.format(tpls.subText, info)
            builder.setContentTitle(formattedCollapsed)
            if (formattedContent.isNotEmpty()) builder.setContentText(formattedContent)
            if (formattedSubText.isNotEmpty()) builder.setSubText(formattedSubText) else builder.setSubText(null)
            val bigTextStyle = NotificationCompat.BigTextStyle()
            bigTextStyle.setBigContentTitle(formattedTitle)
            bigTextStyle.bigText(formattedContent.ifEmpty { formattedTitle })
            if (formattedSubText.isNotEmpty()) {
                bigTextStyle.setSummaryText(formattedSubText)
            } else {
                bigTextStyle.setSummaryText(null)
            }
            builder.setStyle(bigTextStyle)
            applyLiveUpdateProperties(
                builder,
                DataStore.liveUpdateShowCapsule,
                formattedCollapsed,
                formattedTitle,
                formattedContent,
                formattedSubText
            )
        }

        service.registerReceiver(this, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })

        runOnMainDispatcher {
            updateActions()
            show()
        }
    }

    private suspend fun updateActions() {
        service as Context
        val actions = notificationActions()
        val profileId = service.data.proxy?.profile?.id ?: DataStore.selectedProxy
        val canCycle = (ACTION_NEXT in actions || ACTION_PREVIOUS in actions) && ProfileOrder.canCycle(profileId)
        useBuilder {
            it.clearActions()
            for (action in actions) when (action) {
                ACTION_STOP -> it.addAction(
                    broadcastAction(R.drawable.ic_notification_stop, service.getText(R.string.stop), Action.CLOSE, 1)
                )

                ACTION_PREVIOUS -> if (canCycle) it.addAction(
                    broadcastAction(
                        R.drawable.ic_notification_previous, service.getText(R.string.notification_previous),
                        Action.SWITCH_PREVIOUS, 2,
                    )
                )

                ACTION_NEXT -> if (canCycle) it.addAction(
                    broadcastAction(
                        R.drawable.ic_notification_next, service.getText(R.string.notification_next),
                        Action.SWITCH_NEXT, 3,
                    )
                )

                ACTION_SWITCH -> it.addAction(
                    NotificationCompat.Action.Builder(
                        R.drawable.ic_notification_switch, service.getText(R.string.notification_switch),
                        PendingIntent.getActivity(
                            service, 4,
                            Intent(service, SwitchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            flags,
                        )
                    ).setShowsUserInterface(true).build()
                )

                ACTION_RESET -> it.addAction(
                    broadcastAction(
                        R.drawable.ic_notification_reset, service.getText(R.string.reset_connections),
                        Action.RESET_UPSTREAM_CONNECTIONS, 5,
                    )
                )
            }
        }
    }

    private fun broadcastAction(icon: Int, title: CharSequence, action: String, requestCode: Int) =
        NotificationCompat.Action.Builder(
            icon, title, PendingIntent.getBroadcast(
                service as Context, requestCode, Intent(action).setPackage(service.packageName), flags
            )
        ).setShowsUserInterface(false).build()

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                screenOn.value = true
                listenPostSpeed = true
                if (DataStore.liveUpdateEnabled && (currentEgressIp.isNullOrEmpty() || currentEgressCountry.isNullOrEmpty())) {
                    probeEgress()
                }
            }
            Intent.ACTION_SCREEN_OFF -> {
                screenOn.value = false
                listenPostSpeed = false
            }
        }
    }


    private suspend fun show() =
        useBuilder {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    (service as Service).startForeground(
                        notificationId,
                        it.build(),
                        FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                    )
                } else {
                    (service as Service).startForeground(notificationId, it.build())
                }
            } catch (e: Exception) {
                Toast.makeText(
                    SagerNet.application,
                    "startForeground: $e",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private suspend fun update() = useBuilder {
        NotificationManagerCompat.from(service as Service).notify(notificationId, it.build())
    }

    fun destroy() {
        listenPostSpeed = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            (service as Service).stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            (service as Service).stopForeground(true)
        }
        service.unregisterReceiver(this)
    }
}
