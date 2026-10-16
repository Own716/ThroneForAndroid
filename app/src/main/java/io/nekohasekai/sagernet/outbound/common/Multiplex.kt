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

/** The Checks of multiplex.h. */
object MuxChecks {
    @JvmStatic
    fun muxProtocol(protocol: String): String = Validation.oneOf(protocol, MUX_PROTOCOLS)

    /** A warning for a speed build sends as 1 Mbps; text that is not a number is left to an int rule. */
    @JvmStatic
    fun brutalSpeed(text: String): String {
        val value = Validation.toInt(text)
        if (text.isNotBlank() && (value == null || value > 0)) return ""
        return vtr(R.string.validation_mux_brutal_speed)
    }

    /** A warning: brutal is emitted and stored only while multiplex is explicitly On (Keep Default drops it). */
    @JvmStatic
    fun brutalNeedsMux(brutal: Boolean, muxOn: Boolean): String =
        if (!brutal || muxOn) "" else vtr(R.string.validation_mux_brutal_needs_mux)
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
class Multiplex {
    @JvmField var enabled: Boolean = false
    @JvmField var unspecified: Boolean = true
    @JvmField var protocol: String = ""
    @JvmField var max_connections: Int = 0
    @JvmField var min_streams: Int = 0
    @JvmField var max_streams: Int = 0
    @JvmField var padding: Boolean = false
    @JvmField var brutal: TcpBrutal = TcpBrutal()

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

    /** Multiplex::Build (multiplex.cpp): starts from exportToJson, so an unspecified profile switched on by the global keeps only the defaults. */
    fun build(ctx: BuildContext): JsonObject {
        val obj = exportToJson()
        if (unspecified && ctx.muxDefaultOn) obj["enabled"] = true
        if (!obj.bool("enabled")) return JsonObject()
        if (protocol.isEmpty()) obj["protocol"] = ctx.muxProtocol
        if (max_streams == 0 && max_connections == 0 && min_streams == 0) obj["max_streams"] = ctx.muxConcurrency
        if (ctx.muxPadding) obj["padding"] = true
        return obj
    }

    /** Multiplex::Validate (multiplex.cpp); [ctx] supplies the global multiplex default. */
    fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        val explicitOn = !unspecified && enabled
        issues.addIssue(Severity.Warning, "multiplex.brutal", MuxChecks.brutalNeedsMux(brutal.enabled, explicitOn))
        if (!effectivelyOn(ctx)) return
        issues.addIssue(Severity.Error, "multiplex.protocol", MuxChecks.muxProtocol(protocol), protocol)
        if (explicitOn && brutal.enabled && (brutal.up_mbps <= 0 || brutal.down_mbps <= 0)) {
            issues.addIssue(Severity.Warning, "multiplex.brutal", vtr(R.string.validation_mux_brutal_both_speeds))
        }
    }
}
