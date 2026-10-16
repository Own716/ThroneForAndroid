package io.nekohasekai.sagernet.outbound.common

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.QtStrings
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

private val QUIC_RECEIVE_WINDOWS = listOf("stream_receive_window", "connection_receive_window")

/** memoryBytesJson (QUICFields.cpp): sing-box MemoryBytes takes a JSON integer, or a string that must carry a unit ("1m"); a bare digit string fails. */
private fun memoryBytesJson(text: String): Any {
    if (text.isEmpty() || !text.all { it in '0'..'9' }) return text
    return text.toLongOrNull() ?: text
}

/** memoryBytesText (QUICFields.cpp): a stored number as its digits; a negative or fractional one is reported and dropped. */
private fun memoryBytesText(obj: JsonObject, key: String): String {
    val value = obj[key]
    if (value !is Long && value !is Double) return obj.string(key)
    val bytes = when (value) {
        is Long -> value
        is Double -> if (value == Math.rint(value) && value >= 0 && value < 9.223372036854775807E18) value.toLong() else -1L
        else -> -1L
    }
    if (bytes >= 0) return bytes.toString()
    ParseDiag.warn(key, vtr(R.string.validation_quic_receive_window))
    return ""
}

/** The Checks of QUICFields.h. */
object QuicChecks {
    /** quic-go silently clamps any other size into 1200..1452. */
    @JvmStatic
    fun quicInitialPacketSize(text: String): String {
        val value = Validation.toInt(text)
        if (text.isBlank() || value == 0L || (value != null && value in 1200L..1452L)) return ""
        return vtr(R.string.validation_quic_initial_packet_size)
    }
}

/**
 * QUICFields (include/configs/common/QUICFields.h, src/configs/common/QUICFields.cpp): built flat into the
 * hysteria/tuic/masque outbounds; empty / 0 means "no override".
 */
class QuicFields {
    @JvmField var idle_timeout: String = ""
    @JvmField var keep_alive_period: String = ""
    @JvmField var stream_receive_window: String = ""
    @JvmField var connection_receive_window: String = ""
    @JvmField var max_concurrent_streams: Int = 0
    @JvmField var initial_packet_size: Int = 0
    @JvmField var disable_path_mtu_discovery: Boolean = false
    @JvmField var disable_path_mtu_discovery_unspecified: Boolean = true

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** QUICFields::ParseFromLink (QUICFields.cpp); a hysteria port-hopping link has an invalid port and is still read. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid && !url.invalidPortOnly) return false
        val q = url.query
        fun checked(key: String, current: String, rule: (String) -> String): String {
            if (!q.has(key)) return current
            val value = q.value(key).trim()
            return if (acceptOptional(key, rule(value), value)) value else current
        }
        idle_timeout = checked("quic_idle_timeout", idle_timeout) { Validation.duration(it) }
        keep_alive_period = checked("quic_keep_alive_period", keep_alive_period) { Validation.duration(it) }
        stream_receive_window = checked("quic_stream_receive_window", stream_receive_window) { Validation.memoryBytes(it, true) }
        connection_receive_window = checked("quic_connection_receive_window", connection_receive_window) { Validation.memoryBytes(it, true) }
        max_concurrent_streams = readQueryInt(q, "quic_max_concurrent_streams", max_concurrent_streams)
        if (q.has("quic_initial_packet_size")) {
            val value = q.value("quic_initial_packet_size").trim()
            if (acceptOptional("quic_initial_packet_size", QuicChecks.quicInitialPacketSize(value), value)) initial_packet_size = QtStrings.toInt(value)
        }
        if (q.has("quic_disable_path_mtu_discovery")) {
            disable_path_mtu_discovery = q.value("quic_disable_path_mtu_discovery") == "true"
            disable_path_mtu_discovery_unspecified = false
        } else {
            disable_path_mtu_discovery_unspecified = true
        }
        return true
    }

    /** QUICFields::ParseFromJson (QUICFields.cpp); also the DB loader, so only an import ([ParseDiag.active]) drops values. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        val importing = ParseDiag.active()
        fun checked(key: String, current: String, value: String, rule: (String) -> String): String {
            if (!obj.contains(key)) return current
            return if (!importing || acceptOptional(key, rule(value), value)) value else current
        }
        idle_timeout = checked("idle_timeout", idle_timeout, obj.string("idle_timeout").trim()) { Validation.duration(it) }
        keep_alive_period = checked("keep_alive_period", keep_alive_period, obj.string("keep_alive_period").trim()) { Validation.duration(it) }
        if (obj.contains("stream_receive_window")) {
            stream_receive_window = checked("stream_receive_window", stream_receive_window, memoryBytesText(obj, "stream_receive_window")) {
                Validation.memoryBytes(it, true)
            }
        }
        if (obj.contains("connection_receive_window")) {
            connection_receive_window = checked("connection_receive_window", connection_receive_window, memoryBytesText(obj, "connection_receive_window")) {
                Validation.memoryBytes(it, true)
            }
        }
        if (obj.contains("max_concurrent_streams")) max_concurrent_streams = obj.int("max_concurrent_streams")
        if (obj.contains("initial_packet_size")) {
            val value = obj.int("initial_packet_size")
            val text = value.toString()
            if (!importing || acceptOptional("initial_packet_size", QuicChecks.quicInitialPacketSize(text), text)) initial_packet_size = value
        }
        if (obj.contains("disable_path_mtu_discovery")) {
            disable_path_mtu_discovery = obj.bool("disable_path_mtu_discovery")
            disable_path_mtu_discovery_unspecified = false
        } else {
            disable_path_mtu_discovery_unspecified = true
        }
        return true
    }

    /** QUICFields::ExportToLink (QUICFields.cpp). */
    fun exportToLink(): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        if (idle_timeout.isNotEmpty()) q.add("quic_idle_timeout" to idle_timeout)
        if (keep_alive_period.isNotEmpty()) q.add("quic_keep_alive_period" to keep_alive_period)
        if (stream_receive_window.isNotEmpty()) q.add("quic_stream_receive_window" to stream_receive_window)
        if (connection_receive_window.isNotEmpty()) q.add("quic_connection_receive_window" to connection_receive_window)
        if (max_concurrent_streams > 0) q.add("quic_max_concurrent_streams" to max_concurrent_streams.toString())
        if (initial_packet_size > 0) q.add("quic_initial_packet_size" to initial_packet_size.toString())
        if (!disable_path_mtu_discovery_unspecified) q.add("quic_disable_path_mtu_discovery" to if (disable_path_mtu_discovery) "true" else "false")
        return q
    }

    /** QUICFields::ExportToJson (QUICFields.cpp). */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (idle_timeout.isNotEmpty()) obj["idle_timeout"] = idle_timeout
        if (keep_alive_period.isNotEmpty()) obj["keep_alive_period"] = keep_alive_period
        if (stream_receive_window.isNotEmpty()) obj["stream_receive_window"] = stream_receive_window
        if (connection_receive_window.isNotEmpty()) obj["connection_receive_window"] = connection_receive_window
        if (max_concurrent_streams > 0) obj["max_concurrent_streams"] = max_concurrent_streams
        if (initial_packet_size > 0) obj["initial_packet_size"] = initial_packet_size
        if (!disable_path_mtu_discovery_unspecified) obj["disable_path_mtu_discovery"] = disable_path_mtu_discovery
        return obj
    }

    /** QUICFields::Build (QUICFields.cpp): the global h2_/quic_ settings fill in whatever the profile leaves empty; bare byte counts become JSON numbers. */
    fun build(ctx: BuildContext): JsonObject {
        val obj = exportToJson()
        val idleTimeout = ctx.h2IdleTimeout.trim()
        val keepAlivePeriod = ctx.h2KeepAlivePeriod.trim()
        val streamReceiveWindow = ctx.h2StreamReceiveWindow.trim()
        val connectionReceiveWindow = ctx.h2ConnectionReceiveWindow.trim()
        if (idle_timeout.isEmpty() && idleTimeout.isNotEmpty()) obj["idle_timeout"] = idleTimeout
        if (keep_alive_period.isEmpty() && keepAlivePeriod.isNotEmpty()) obj["keep_alive_period"] = keepAlivePeriod
        if (stream_receive_window.isEmpty() && streamReceiveWindow.isNotEmpty()) obj["stream_receive_window"] = streamReceiveWindow
        if (connection_receive_window.isEmpty() && connectionReceiveWindow.isNotEmpty()) obj["connection_receive_window"] = connectionReceiveWindow
        if (max_concurrent_streams <= 0 && ctx.h2MaxConcurrentStreams > 0) obj["max_concurrent_streams"] = ctx.h2MaxConcurrentStreams
        if (initial_packet_size <= 0 && ctx.quicInitialPacketSize > 0) obj["initial_packet_size"] = ctx.quicInitialPacketSize
        if (disable_path_mtu_discovery_unspecified && ctx.quicDisablePathMtuDiscovery) obj["disable_path_mtu_discovery"] = true
        for (key in QUIC_RECEIVE_WINDOWS) {
            if (obj.contains(key)) obj[key] = memoryBytesJson(obj.string(key).trim())
        }
        return obj
    }

    /** QUICFields::Validate (QUICFields.cpp). */
    fun validate(issues: MutableList<Issue>) {
        issues.addIssue(Severity.Error, "idle_timeout", Validation.duration(idle_timeout), idle_timeout)
        issues.addIssue(Severity.Error, "keep_alive_period", Validation.duration(keep_alive_period), keep_alive_period)
        issues.addIssue(Severity.Error, "stream_receive_window", Validation.memoryBytes(stream_receive_window, true), stream_receive_window)
        issues.addIssue(Severity.Error, "connection_receive_window", Validation.memoryBytes(connection_receive_window, true), connection_receive_window)
        val packetSize = initial_packet_size.toString()
        issues.addIssue(Severity.Error, "initial_packet_size", QuicChecks.quicInitialPacketSize(packetSize), packetSize)
    }
}
