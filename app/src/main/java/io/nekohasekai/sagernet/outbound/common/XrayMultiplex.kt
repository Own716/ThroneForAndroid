package io.nekohasekai.sagernet.outbound.common

import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.import.ClashProxy
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.outbound.link.LinkQuery
import io.nekohasekai.sagernet.outbound.link.ParsedLink
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.acceptOptional
import io.nekohasekai.sagernet.validation.addIssue

/** xrayXudpProxyUDP443Modes (xrayMultiplex.h:8), in the order of the UDP/443 menu. */
val xrayXudpProxyUDP443Modes = listOf("reject", "allow", "skip")

/** kXrayMuxMaxCount (xrayMultiplex.h:13-14): Xray decodes every mux count into an int16. */
const val XRAY_MUX_MAX_COUNT = 32767

/** The Checks of xrayMultiplex.h. */
object XrayMuxChecks {
    @JvmStatic
    fun xrayXudpProxyUDP443(mode: String): String = Validation.oneOf(mode, xrayXudpProxyUDP443Modes)
}

private fun countMessage(text: String) = Validation.int(text, -1, XRAY_MUX_MAX_COUNT.toLong())

/** readQueryCount (xrayMultiplex.cpp:11-16): the item as a count, else [current] (reported when present but unusable). */
private fun readQueryCount(query: LinkQuery, key: String, current: Int?): Int? {
    if (!query.has(key)) return current
    val text = query.value(key).trim()
    if (!acceptOptional(key, countMessage(text), text)) return current
    return Validation.toInt(text)?.toInt() ?: current
}

/** readJsonCount (xrayMultiplex.cpp:18-25): ParseFromJson is also the DB loader, so only an import drops values. */
private fun readJsonCount(obj: JsonObject, key: String, current: Int?): Int? {
    if (!obj.contains(key)) return current
    val value = obj.int(key)
    val text = value.toString()
    if (ParseDiag.active() && !acceptOptional("mux.$key", countMessage(text), text)) return current
    return value
}

/** XrayMuxSettings (xrayMultiplex.h:20-35, xrayMultiplex.cpp:154-180): the global preset, or a profile's own under Custom. */
data class XrayMuxSettings(
    /** Streams per connection; 0 is Xray's 8 and -1 sends TCP outside the mux. */
    val concurrency: Int = 8,
    /** Throne's Xray fork caps the pool here; 0 leaves it unbounded. */
    val max_connections: Int = 0,
    /** 0 carries UDP on the TCP connections, -1 sends it outside the mux. */
    val xudp_concurrency: Int = 16,
    val xudp_proxy_udp443: String = "reject",
) {
    /** XrayMuxSettings::Build (xrayMultiplex.cpp:172-180): the enabled mux object for the Xray config. */
    fun build(): JsonObject {
        val obj = JsonObject()
        obj["enabled"] = true
        if (concurrency != 0) obj["concurrency"] = concurrency
        if (max_connections > 0) obj["maxConnections"] = max_connections
        if (xudp_concurrency != 0) obj["xudpConcurrency"] = xudp_concurrency
        if (xudp_proxy_udp443.isNotEmpty()) obj["xudpProxyUDP443"] = xudp_proxy_udp443
        return obj
    }

    companion object {
        /** XrayMuxSettings::Preset (xrayMultiplex.cpp:154-162). */
        @JvmStatic
        fun preset(ctx: BuildContext) = XrayMuxSettings(
            concurrency = ctx.xrayMuxConcurrency,
            max_connections = ctx.xrayMuxMaxConnections,
            xudp_concurrency = ctx.xrayMuxXudpConcurrency,
            xudp_proxy_udp443 = ctx.xrayMuxXudpProxyUDP443,
        )
    }
}

/** xrayMultiplex (include/configs/common/xrayMultiplex.h, src/configs/common/xrayMultiplex.cpp). `useDefault` true means "Keep Default". */
class XrayMultiplex : MuxStateModel<XrayMuxSettings> {
    @JvmField var enabled: Boolean = false
    @JvmField var useDefault: Boolean = true
    // Unset ones follow the preset; Xray gives 0 and -1 meanings of their own, so they cannot stand for unset.
    @JvmField var concurrency: Int? = null
    @JvmField var maxConnections: Int? = null
    @JvmField var xudpConcurrency: Int? = null
    @JvmField var xudpProxyUDP443: String = ""

    /** xrayMultiplex::dropLegacyXudpDefault (xrayMultiplex.cpp:28-31): every On profile used to store the then built-in xudpConcurrency 16; alone it is no setting of its own. */
    private fun dropLegacyXudpDefault() {
        if (concurrency == null && maxConnections == null && xudpProxyUDP443.isEmpty() && xudpConcurrency == 16) xudpConcurrency = null
    }

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** xrayMultiplex::ParseFromLink (xrayMultiplex.cpp:33-48): counts in -1..kXrayMuxMaxCount. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        if (q.has("mux")) {
            enabled = q.value("mux").replace("1", "true") == "true"
            useDefault = false
        }
        concurrency = readQueryCount(q, "mux_concurrency", concurrency)
        maxConnections = readQueryCount(q, "mux_max_connections", maxConnections)
        xudpConcurrency = readQueryCount(q, "mux_xudp_concurrency", xudpConcurrency)
        if (q.has("mux_xudp_proxy_udp443")) {
            val value = q.value("mux_xudp_proxy_udp443").trim()
            if (acceptOptional("mux_xudp_proxy_udp443", XrayMuxChecks.xrayXudpProxyUDP443(value), value)) xudpProxyUDP443 = value
        }
        dropLegacyXudpDefault()
        return true
    }

    /** xrayMultiplex::ParseFromJson (xrayMultiplex.cpp:50-62). */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("enabled")) {
            enabled = obj.bool("enabled")
            useDefault = false
        }
        concurrency = readJsonCount(obj, "concurrency", concurrency)
        maxConnections = readJsonCount(obj, "maxConnections", maxConnections)
        xudpConcurrency = readJsonCount(obj, "xudpConcurrency", xudpConcurrency)
        if (obj.contains("xudpProxyUDP443")) {
            val value = obj.string("xudpProxyUDP443")
            if (!ParseDiag.active() || acceptOptional("mux.xudpProxyUDP443", XrayMuxChecks.xrayXudpProxyUDP443(value), value)) {
                xudpProxyUDP443 = value
            }
        }
        dropLegacyXudpDefault()
        return true
    }

    /** xrayMultiplex::ParseFromClash (xrayMultiplex.cpp:64-75): `max-streams` wins over the connection pair. */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        val smux = proxy.obj("smux")
        enabled = smux.bool("enabled")
        useDefault = false
        val maxStreams = smux.int("max-streams")
        val smuxMaxConnections = smux.int("max-connections")
        if (maxStreams > 0) {
            concurrency = maxStreams
        } else if (smuxMaxConnections > 0) {
            // Under a cap, concurrency is when another connection opens: sing-mux's min_streams.
            maxConnections = smuxMaxConnections
            val minStreams = smux.int("min-streams")
            if (minStreams > 0) concurrency = minStreams
        }
        return true
    }

    /** xrayMultiplex::ExportToLink (xrayMultiplex.cpp:77-88). */
    fun exportToLink(): List<Pair<String, String>> {
        if (useDefault) return emptyList()
        val q = ArrayList<Pair<String, String>>()
        q.add("mux" to if (enabled) "true" else "false")
        if (enabled) {
            concurrency?.let { q.add("mux_concurrency" to it.toString()) }
            maxConnections?.let { q.add("mux_max_connections" to it.toString()) }
            xudpConcurrency?.let { q.add("mux_xudp_concurrency" to it.toString()) }
            if (xudpProxyUDP443.isNotEmpty()) q.add("mux_xudp_proxy_udp443" to xudpProxyUDP443)
        }
        return q
    }

    /** xrayMultiplex::ExportToJson (xrayMultiplex.cpp:90-101): {} for Keep Default, {enabled:false} for Off. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (useDefault) return obj
        obj["enabled"] = enabled
        if (!enabled) return obj
        concurrency?.let { obj["concurrency"] = it }
        maxConnections?.let { obj["maxConnections"] = it }
        xudpConcurrency?.let { obj["xudpConcurrency"] = it }
        if (xudpProxyUDP443.isNotEmpty()) obj["xudpProxyUDP443"] = xudpProxyUDP443
        return obj
    }

    /** xrayMultiplex::getMuxState (xrayMultiplex.h:47-52): explicitly enabled with settings of its own is Custom. */
    override fun getMuxState(): Int = when {
        useDefault -> 0
        !enabled -> 2
        hasOwnSettings() -> MUX_CUSTOM
        else -> 1
    }

    /** xrayMultiplex::saveMuxState (xrayMultiplex.h:54-59). */
    override fun saveMuxState(state: Int) {
        useDefault = state == 0
        enabled = state == 1 || state == MUX_CUSTOM
        if (state != MUX_CUSTOM) clearOwnSettings()
    }

    /** xrayMultiplex::HasOwnSettings (xrayMultiplex.cpp:112-114). */
    fun hasOwnSettings(): Boolean =
        concurrency != null || maxConnections != null || xudpConcurrency != null || xudpProxyUDP443.isNotEmpty()

    /** xrayMultiplex::IsCustom (xrayMultiplex.h:62). */
    fun isCustom(): Boolean = !useDefault && enabled && hasOwnSettings()

    /** xrayMultiplex::CustomSettings (xrayMultiplex.cpp:116-123). */
    override fun customSettings(ctx: BuildContext): XrayMuxSettings {
        val preset = XrayMuxSettings.preset(ctx)
        return XrayMuxSettings(
            concurrency = concurrency ?: preset.concurrency,
            max_connections = maxConnections ?: preset.max_connections,
            xudp_concurrency = xudpConcurrency ?: preset.xudp_concurrency,
            xudp_proxy_udp443 = xudpProxyUDP443.ifEmpty { preset.xudp_proxy_udp443 },
        )
    }

    /** xrayMultiplex::SetCustomSettings (xrayMultiplex.cpp:125-130). */
    override fun setCustomSettings(settings: XrayMuxSettings) {
        concurrency = settings.concurrency
        maxConnections = settings.max_connections
        xudpConcurrency = settings.xudp_concurrency
        xudpProxyUDP443 = settings.xudp_proxy_udp443
    }

    /** xrayMultiplex::ClearOwnSettings (xrayMultiplex.cpp:132-137). */
    fun clearOwnSettings() {
        concurrency = null
        maxConnections = null
        xudpConcurrency = null
        xudpProxyUDP443 = ""
    }

    /** xrayMultiplex::Build (xrayMultiplex.cpp:103-105). */
    fun build(ctx: BuildContext): JsonObject = buildMux(ctx, ctx.xrayMuxDefaultOn)

    /** xrayMultiplex::BuildMux (xrayMultiplex.cpp:107-110): Keep Default resolves to [defaultOn]; only Custom builds from the own settings. */
    fun buildMux(ctx: BuildContext, defaultOn: Boolean): JsonObject {
        if (if (useDefault) !defaultOn else !enabled) return JsonObject()
        return (if (isCustom()) customSettings(ctx) else XrayMuxSettings.preset(ctx)).build()
    }

    /** xrayMultiplex::Validate (xrayMultiplex.cpp:139-152): only a Custom mux builds from the profile's own settings. */
    fun validate(issues: MutableList<Issue>) {
        if (!isCustom()) return
        for ((key, value) in listOf(
            "mux.concurrency" to concurrency,
            "mux.maxConnections" to maxConnections,
            "mux.xudpConcurrency" to xudpConcurrency,
        )) {
            if (value == null) continue
            val text = value.toString()
            issues.addIssue(Severity.Error, key, countMessage(text), text)
        }
        issues.addIssue(Severity.Error, "mux.xudpProxyUDP443", XrayMuxChecks.xrayXudpProxyUDP443(xudpProxyUDP443), xudpProxyUDP443)
    }
}
