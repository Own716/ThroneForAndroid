package io.nekohasekai.sagernet.outbound.common

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.import.ClashProxy
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.outbound.link.ParsedLink
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.acceptOptional
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.readQueryInt
import io.nekohasekai.sagernet.validation.vtr

/** muxProtocols (multiplex.h). */
private val MUX_PROTOCOLS = listOf("smux", "yamux", "h2mux")

/** kMuxCustom (multiplex.h:9-10): "Custom" in the profile's Keep Default / On / Off / Custom multiplex menu. */
const val MUX_CUSTOM = 3

/**
 * A multiplex edited through the Keep Default (0) / On (1) / Off (2) / Custom ([MUX_CUSTOM]) menu (multiplex.h:64-82,
 * xrayMultiplex.h): Custom is On with settings of its own, and [S] is one complete setup of them.
 */
interface MuxStateModel<S> {
    fun getMuxState(): Int

    /** Every state but Custom drops the own settings; Custom takes them from [setCustomSettings]. */
    fun saveMuxState(state: Int)

    /** The own settings with unset ones taken from [ctx]'s preset; the preset alone when there are none. */
    fun customSettings(ctx: BuildContext): S

    fun setCustomSettings(settings: S)
}

/** The Checks of multiplex.h. */
object MuxChecks {
    @JvmStatic
    fun muxProtocol(protocol: String): String = Validation.oneOf(protocol, MUX_PROTOCOLS)

    /** A warning: brutal is emitted and stored only under a Custom multiplex (Keep Default and Off drop it). */
    @JvmStatic
    fun brutalNeedsMux(brutal: Boolean, muxOn: Boolean): String =
        if (!brutal || muxOn) "" else vtr(R.string.validation_mux_brutal_needs_custom)
}

/** MuxSettings (multiplex.h:18-36, multiplex.cpp:191-241): the global preset, or a profile's own under Custom. */
data class MuxSettings(
    val protocol: String = "",
    /** Pool by max_connections + min_streams instead of max_streams per connection. */
    val by_connections: Boolean = false,
    val max_streams: Int = 0,
    val max_connections: Int = 0,
    val min_streams: Int = 0,
    val padding: Boolean = false,
    val brutal: Boolean = false,
    val brutal_up_mbps: Int = 0,
    val brutal_down_mbps: Int = 0,
) {
    /** MuxSettings::Build (multiplex.cpp:221-241): the enabled multiplex object for the core config. */
    fun build(): JsonObject {
        val obj = JsonObject()
        obj["enabled"] = true
        if (protocol.isNotEmpty()) obj["protocol"] = protocol
        if (by_connections) {
            if (max_connections > 0) obj["max_connections"] = max_connections
            if (min_streams > 0) obj["min_streams"] = min_streams
        } else if (max_streams > 0) {
            obj["max_streams"] = max_streams
        }
        if (padding) obj["padding"] = true
        if (brutal) {
            val options = TcpBrutal()
            options.enabled = true
            options.up_mbps = brutal_up_mbps
            options.down_mbps = brutal_down_mbps
            obj["brutal"] = options.exportToJson()
        }
        return obj
    }

    companion object {
        /** MuxSettings::Preset (multiplex.cpp:191-205). */
        @JvmStatic
        fun preset(ctx: BuildContext) = MuxSettings(
            protocol = ctx.muxProtocol,
            by_connections = ctx.muxMode == "max_connections",
            max_streams = ctx.muxConcurrency,
            max_connections = ctx.muxMaxConnections,
            min_streams = ctx.muxMinStreams,
            padding = ctx.muxPadding,
            brutal = ctx.muxBrutal,
            brutal_up_mbps = ctx.muxBrutalUpMbps,
            brutal_down_mbps = ctx.muxBrutalDownMbps,
        )
    }
}

/** TcpBrutal (multiplex.h, multiplex.cpp). */
class TcpBrutal {
    @JvmField var enabled: Boolean = false
    @JvmField var up_mbps: Int = 0
    @JvmField var down_mbps: Int = 0

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** TcpBrutal::ParseFromLink (multiplex.cpp). */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        if (q.has("brutal_enabled")) enabled = q.value("brutal_enabled") == "true"
        up_mbps = readQueryInt(q, "brutal_up_mbps", up_mbps)
        down_mbps = readQueryInt(q, "brutal_down_mbps", down_mbps)
        return true
    }

    /** TcpBrutal::ParseFromJson (multiplex.cpp). */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("enabled")) enabled = obj.bool("enabled")
        if (obj.contains("up_mbps")) up_mbps = obj.int("up_mbps")
        if (obj.contains("down_mbps")) down_mbps = obj.int("down_mbps")
        return true
    }

    /** TcpBrutal::ExportToLink (multiplex.cpp). */
    fun exportToLink(): List<Pair<String, String>> {
        if (!enabled) return emptyList()
        val q = ArrayList<Pair<String, String>>()
        q.add("brutal_enabled" to "true")
        if (up_mbps > 0) q.add("brutal_up_mbps" to up_mbps.toString())
        if (down_mbps > 0) q.add("brutal_down_mbps" to down_mbps.toString())
        return q
    }

    /** TcpBrutal::ExportToJson (multiplex.cpp): sing-box wants both rates, so a missing one is written as 1. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (!enabled) return obj
        obj["enabled"] = enabled
        obj["up_mbps"] = if (up_mbps <= 0) 1 else up_mbps
        obj["down_mbps"] = if (down_mbps <= 0) 1 else down_mbps
        return obj
    }

    /** TcpBrutal::Build (multiplex.cpp). */
    fun build(ctx: BuildContext): JsonObject = exportToJson()
}

/** Multiplex (multiplex.h, multiplex.cpp). `unspecified` true means "Keep Default". */
class Multiplex : MuxStateModel<MuxSettings> {
    @JvmField var enabled: Boolean = false
    @JvmField var unspecified: Boolean = true
    @JvmField var protocol: String = ""
    @JvmField var max_connections: Int = 0
    @JvmField var min_streams: Int = 0
    @JvmField var max_streams: Int = 0
    @JvmField var padding: Boolean = false
    @JvmField var brutal: TcpBrutal = TcpBrutal()

    /** Multiplex::getMuxState (multiplex.h:64-69): explicitly enabled with settings of its own is Custom. */
    override fun getMuxState(): Int = when {
        unspecified -> 0
        !enabled -> 2
        hasOwnSettings() -> MUX_CUSTOM
        else -> 1
    }

    /** Multiplex::saveMuxState (multiplex.h:71-76). */
    override fun saveMuxState(state: Int) {
        unspecified = state == 0
        enabled = state == 1 || state == MUX_CUSTOM
        if (state != MUX_CUSTOM) setCustomSettings(MuxSettings())
    }

    /** Multiplex::HasOwnSettings (multiplex.cpp:151-154). */
    fun hasOwnSettings(): Boolean =
        protocol.isNotEmpty() || max_connections > 0 || min_streams > 0 || max_streams > 0 || padding || brutal.enabled

    /** Multiplex::IsCustom (multiplex.h:79). */
    fun isCustom(): Boolean = !unspecified && enabled && hasOwnSettings()

    /** Multiplex::CustomSettings (multiplex.cpp:156-177): the pool is taken as a whole, padding and brutal from the profile only. */
    override fun customSettings(ctx: BuildContext): MuxSettings {
        val preset = MuxSettings.preset(ctx)
        if (!hasOwnSettings()) return preset
        var settings = preset.copy(padding = padding, brutal = brutal.enabled)
        if (protocol.isNotEmpty()) settings = settings.copy(protocol = protocol)
        // The core lets max_connections win over max_streams; with neither, the preset's pool applies.
        if (max_connections > 0) {
            settings = settings.copy(by_connections = true, max_connections = max_connections, min_streams = min_streams)
        } else if (max_streams > 0) {
            settings = settings.copy(by_connections = false, max_streams = max_streams)
        }
        if (brutal.enabled) settings = settings.copy(brutal_up_mbps = brutal.up_mbps, brutal_down_mbps = brutal.down_mbps)
        return settings
    }

    /** Multiplex::SetCustomSettings (multiplex.cpp:179-189). */
    override fun setCustomSettings(settings: MuxSettings) {
        protocol = settings.protocol
        max_connections = if (settings.by_connections) settings.max_connections else 0
        min_streams = if (settings.by_connections) settings.min_streams else 0
        max_streams = if (settings.by_connections) 0 else settings.max_streams
        padding = settings.padding
        brutal.enabled = settings.brutal
        brutal.up_mbps = settings.brutal_up_mbps
        brutal.down_mbps = settings.brutal_down_mbps
    }

    /** Multiplex::EffectivelyOn (multiplex.cpp): On, or Keep Default with the global multiplex default on. */
    fun effectivelyOn(ctx: BuildContext): Boolean = enabled || (unspecified && ctx.muxDefaultOn)

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** Multiplex::ParseFromLink (multiplex.cpp). */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        if (q.has("mux")) {
            enabled = q.value("mux") == "true"
            unspecified = false
        } else {
            unspecified = true
        }
        if (q.has("mux_protocol")) {
            val value = q.value("mux_protocol").trim()
            if (acceptOptional("mux_protocol", MuxChecks.muxProtocol(value), value)) protocol = value
        }
        max_connections = readQueryInt(q, "mux_max_connections", max_connections)
        min_streams = readQueryInt(q, "mux_min_streams", min_streams)
        max_streams = readQueryInt(q, "mux_max_streams", max_streams)
        if (q.has("mux_padding")) padding = q.value("mux_padding") == "true"
        brutal.parseFromLink(url)
        return true
    }

    /** Multiplex::ParseFromJson (multiplex.cpp); also the DB loader, so only an import ([ParseDiag.active]) drops values. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) {
            unspecified = true
            return false
        }
        if (obj.contains("enabled")) {
            enabled = obj.bool("enabled")
            unspecified = false
        } else {
            unspecified = true
        }
        if (obj.contains("protocol")) {
            val value = obj.string("protocol")
            if (!ParseDiag.active() || acceptOptional("multiplex.protocol", MuxChecks.muxProtocol(value), value)) protocol = value
        }
        if (obj.contains("max_connections")) max_connections = obj.int("max_connections")
        if (obj.contains("min_streams")) min_streams = obj.int("min_streams")
        if (obj.contains("max_streams")) max_streams = obj.int("max_streams")
        if (obj.contains("padding")) padding = obj.bool("padding")
        if (obj.contains("brutal")) brutal.parseFromJson(obj.obj("brutal"))
        return true
    }

    /** Multiplex::ParseFromClash (multiplex.cpp): `max-streams` wins over the connection pair. */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        val smux = proxy.obj("smux")
        enabled = smux.bool("enabled")
        unspecified = false
        val smuxProtocol = smux.string("protocol").trim()
        if (smuxProtocol.isNotEmpty() && acceptOptional("smux.protocol", MuxChecks.muxProtocol(smuxProtocol), smuxProtocol)) {
            protocol = smuxProtocol
        }
        val maxStreams = smux.int("max-streams")
        if (maxStreams > 0) {
            max_streams = maxStreams
        } else {
            max_connections = smux.int("max-connections")
            min_streams = smux.int("min-streams")
        }
        padding = smux.bool("padding")
        return true
    }

    /** Multiplex::ExportToLink (multiplex.cpp). */
    fun exportToLink(): List<Pair<String, String>> {
        if (unspecified) return emptyList()
        val q = ArrayList<Pair<String, String>>()
        q.add("mux" to if (enabled) "true" else "false")
        if (enabled) {
            if (protocol.isNotEmpty()) q.add("mux_protocol" to protocol)
            if (max_connections > 0) q.add("mux_max_connections" to max_connections.toString())
            if (min_streams > 0) q.add("mux_min_streams" to min_streams.toString())
            if (max_streams > 0) q.add("mux_max_streams" to max_streams.toString())
            if (padding) q.add("mux_padding" to "true")
            q.addAll(brutal.exportToLink())
        }
        return q
    }

    /** Multiplex::ExportToJson (multiplex.cpp): {} for Keep Default, {enabled:false} for Off. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (unspecified) return obj
        obj["enabled"] = enabled
        if (!enabled) return obj
        if (protocol.isNotEmpty()) obj["protocol"] = protocol
        if (max_connections > 0) obj["max_connections"] = max_connections
        if (min_streams > 0) obj["min_streams"] = min_streams
        if (max_streams > 0) obj["max_streams"] = max_streams
        if (padding) obj["padding"] = padding
        if (brutal.enabled) obj["brutal"] = brutal.exportToJson()
        return obj
    }

    /** Multiplex::Build (multiplex.cpp:140-144): Keep Default (with the default on) and On emit the preset only. */
    fun build(ctx: BuildContext): JsonObject {
        if (!effectivelyOn(ctx)) return JsonObject()
        return (if (isCustom()) customSettings(ctx) else MuxSettings.preset(ctx)).build()
    }

    /** Multiplex::Validate (multiplex.cpp:256-270): only a Custom multiplex builds from the profile's own settings. */
    @Suppress("UNUSED_PARAMETER")
    fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        val custom = isCustom()
        issues.addIssue(Severity.Warning, "multiplex.brutal", MuxChecks.brutalNeedsMux(brutal.enabled, custom))
        if (!custom) return
        issues.addIssue(Severity.Error, "multiplex.protocol", MuxChecks.muxProtocol(protocol), protocol)
        if (brutal.enabled && (brutal.up_mbps <= 0 || brutal.down_mbps <= 0)) {
            issues.addIssue(Severity.Warning, "multiplex.brutal", vtr(R.string.validation_mux_brutal_both_speeds))
        }
    }
}
