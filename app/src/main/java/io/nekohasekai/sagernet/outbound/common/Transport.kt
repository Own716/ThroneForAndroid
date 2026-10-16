package io.nekohasekai.sagernet.outbound.common

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.import.ClashProxy
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.outbound.link.ParsedLink
import io.nekohasekai.sagernet.validation.CommonChecks
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.acceptOptional
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.readQueryInt
import io.nekohasekai.sagernet.validation.vtr

/** singBoxTransports (transport.h): sing-box V2RayTransportOptions types; tcp (or empty) means no transport. */
val SING_BOX_TRANSPORTS = listOf("tcp", "ws", "httpupgrade", "http", "grpc", "quic")

/** SingBoxTransportType (transport.h): sing-box matches types exactly in lower case; websocket is Xray's alias, which share links and v2rayN JSON carry too. */
fun singBoxTransportType(type: String): String {
    val t = type.trim().lowercase()
    return if (t == "websocket") "ws" else t
}

/**
 * TlsForTransport (transport.cpp): sing-quic dials the quic transport with a std TLS config, which a uTLS one
 * refuses, so uTLS is dropped there. REALITY keeps it, since it cannot run without uTLS; validate refuses that profile.
 */
fun tlsForTransport(tls: JsonObject, transportType: String): JsonObject {
    if (transportType != "quic" || tls.contains("reality") || !tls.contains("utls")) return tls
    return tls.copy().also { it.remove("utls") }
}

/** The core decodes each type strictly, so a key kept from another type in the editor would refuse to start. */
private val TRANSPORT_KEYS = mapOf(
    "http" to listOf("host", "path", "method", "headers", "idle_timeout", "ping_timeout"),
    "ws" to listOf("path", "headers", "max_early_data", "early_data_header_name"),
    "quic" to emptyList(),
    "grpc" to listOf("service_name", "idle_timeout", "ping_timeout", "permit_without_stream"),
    "httpupgrade" to listOf("host", "path", "headers"),
)

/** The Checks of transport.h. */
object TransportChecks {
    @JvmStatic
    fun transportType(type: String): String {
        val t = type.trim()
        if (t.isEmpty() || t in SING_BOX_TRANSPORTS) return ""
        return vtr(R.string.validation_transport_type, t)
    }

    /** A warning: the Host header may carry a port; http takes a comma list. */
    @JvmStatic
    fun transportHost(host: String, type: String): String = CommonChecks.hostHeader(host, type == "http")

    /** [tls]: TLS is on; [reality]: the Reality key is set. [utls] is not an error: see [quicTransportUtls]. */
    @Suppress("UNUSED_PARAMETER")
    @JvmStatic
    fun quicTransport(tls: Boolean, utls: Boolean, reality: Boolean): String = when {
        !tls -> vtr(R.string.validation_transport_quic_tls)
        reality -> vtr(R.string.validation_transport_quic_reality)
        else -> ""
    }

    /** A warning: [utls] is the profile's own fingerprint (not unsafe), which [tlsForTransport] drops. */
    @JvmStatic
    fun quicTransportUtls(utls: Boolean): String = if (utls) vtr(R.string.validation_transport_quic_utls) else ""
}

/** Transport (include/configs/common/transport.h, src/configs/common/transport.cpp): the sing-box V2Ray transport. */
class Transport {
    @JvmField var type: String = ""
    @JvmField var host: String = ""
    @JvmField var path: String = ""
    @JvmField var method: String = ""
    @JvmField var headers: MutableList<String> = ArrayList()
    @JvmField var idle_timeout: String = ""
    @JvmField var ping_timeout: String = ""
    @JvmField var max_early_data: Int = 0
    @JvmField var early_data_header_name: String = ""
    @JvmField var service_name: String = ""

    /** Never persisted: the parsed source asked for the raw TCP HTTP header, which sing-box reproduces only without TLS. */
    @JvmField var rawHttpHeader: Boolean = false

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** Transport::ParseFromLink (transport.cpp). */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        fun checked(key: String, current: String, rule: (String) -> String): String {
            if (!q.has(key)) return current
            val value = q.value(key).trim()
            return if (acceptOptional(key, rule(value), value)) value else current
        }
        val linkType = singBoxTransportType(q.value("type"))
        val rawHttp = (linkType.isEmpty() || linkType == "tcp" || linkType == "raw") && q.value("headerType") == "http"
        if (q.has("type")) type = if (linkType == "raw") "tcp" else linkType
        if (rawHttp || type == "h2") {
            type = "http"
            method = "GET"
        }
        // a link naming no transport (the throneExtra of a V2RayN link) keeps what the first parse found
        if (q.has("type") || rawHttp) rawHttpHeader = rawHttp
        if (q.has("host")) host = q.valueFully("host")
        if (q.has("path")) path = q.valueFully("path")
        // the raw header's server accepts any of its comma-listed paths, while sing-box sends exactly one
        if (rawHttp) path = QtStrings.sectionFirstSkipEmpty(path, ',').trim()
        method = checked("method", method) { Validation.httpToken(it) }
        val rawHeaders = q.valueFully("headers")
        if (rawHeaders.isNotEmpty()) {
            headers = QtStrings.split(rawHeaders, "|")
            if (headers.size % 2 != 0) {
                ParseDiag.warn("headers", vtr(R.string.validation_transport_headers_pairs))
                headers.clear()
            }
        }
        idle_timeout = checked("idle_timeout", idle_timeout) { Validation.duration(it) }
        ping_timeout = checked("ping_timeout", ping_timeout) { Validation.duration(it) }
        max_early_data = readQueryInt(q, "max_early_data", max_early_data)
        if (q.has("early_data_header_name")) early_data_header_name = q.value("early_data_header_name")
        if (q.has("serviceName")) service_name = q.valueFully("serviceName")
        return true
    }

    /** Transport::ParseFromJson (transport.cpp); also the DB loader, so only an import ([ParseDiag.active]) drops values. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("type")) type = singBoxTransportType(obj.string("type"))
        if (obj.contains("host")) {
            host = if (obj.isArray("host")) obj.array("host").strings().joinToString(",") else obj.string("host")
        }
        if (obj.contains("path")) path = obj.string("path")
        val importing = ParseDiag.active()
        fun checked(key: String, current: String, rule: (String) -> String): String {
            if (!obj.contains(key)) return current
            val value = obj.string(key).trim()
            return if (!importing || acceptOptional("transport.$key", rule(value), value)) value else current
        }
        method = checked("method", method) { Validation.httpToken(it) }
        if (obj.contains("headers") && obj.isObject("headers")) {
            val headerObj = obj.obj("headers").copy()
            if (type == "ws") {
                if (headerObj.contains("Host")) {
                    if (headerObj.isString("Host")) {
                        host = headerObj.string("Host")
                    } else if (headerObj.isArray("Host")) {
                        for (v in headerObj.array("Host")) {
                            if (v is String) {
                                host = v
                                break
                            }
                        }
                    }
                    headerObj.remove("Host")
                }
            }
            headers = JsonValues.objectToPairList(headerObj)
        }
        idle_timeout = checked("idle_timeout", idle_timeout) { Validation.duration(it) }
        ping_timeout = checked("ping_timeout", ping_timeout) { Validation.duration(it) }
        if (obj.contains("max_early_data")) max_early_data = obj.int("max_early_data")
        if (obj.contains("early_data_header_name")) early_data_header_name = obj.string("early_data_header_name")
        if (obj.contains("service_name")) service_name = obj.string("service_name")
        return true
    }

    /**
     * Transport::ParseFromClash (transport.cpp): ws / httpupgrade, grpc, h2 and http-opts in that priority; false when
     * none applies. mihomo sends the ws-opts Host header as the HTTP Host and uses servername only for the SNI.
     * Android also maps `network: grpc` without a service name and `network: http` without a method, which Clash
     * sends as GET.
     */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        val network = proxy.string("network")
        val ws = proxy.obj("ws-opts")
        val wsPath = ws.string("path")
        if (wsPath.isNotEmpty() || network == "ws") {
            if (ws.bool("v2ray-http-upgrade")) {
                type = "httpupgrade"
            } else {
                type = "ws"
                early_data_header_name = ws.string("early-data-header-name")
                max_early_data = ws.int("max-early-data")
            }
            var headerHost = ""
            for ((key, value) in ws.stringMap("headers")) {
                // Host belongs in the host field, which exportToJson writes back as the ws Host header.
                if (key.equals("host", ignoreCase = true)) {
                    if (headerHost.isEmpty()) headerHost = value
                    continue
                }
                headers.add(key)
                headers.add(value)
            }
            val servername = proxy.string("servername")
            if (headerHost.isNotEmpty()) host = headerHost else if (servername.isNotEmpty()) host = servername
            path = wsPath
            return true
        }
        val grpcServiceName = proxy.obj("grpc-opts").string("grpc-service-name")
        if (grpcServiceName.isNotEmpty() || network == "grpc") {
            type = "grpc"
            service_name = grpcServiceName
            return true
        }
        val h2 = proxy.obj("h2-opts")
        val h2Path = h2.string("path")
        if (h2Path.isNotEmpty() || network == "h2") {
            type = "http"
            h2.strings("host").firstOrNull()?.let { host = it }
            path = h2Path
            return true
        }
        val http = proxy.obj("http-opts")
        val httpMethod = http.string("method")
        if (httpMethod.isNotEmpty() || network == "http") {
            type = "http"
            http.stringListMap("headers")["Host"]?.firstOrNull()?.let { host = it }
            http.strings("path").firstOrNull()?.let { path = it }
            method = httpMethod.ifEmpty { "GET" }
            rawHttpHeader = true
            return true
        }
        return false
    }

    /** Transport::ExportToLink (transport.cpp). */
    fun exportToLink(): List<Pair<String, String>> = exportToLink(true)

    /** In plaintext the http transport is the raw HTTP header, which other clients spell type=tcp&headerType=http. */
    fun exportToLink(tlsEnabled: Boolean): List<Pair<String, String>> {
        if (type.isEmpty() || type == "tcp") return emptyList()
        val q = ArrayList<Pair<String, String>>()
        if (type == "http" && !tlsEnabled) {
            q.add("type" to "tcp")
            q.add("headerType" to "http")
        } else {
            q.add("type" to type)
        }
        if (host.isNotEmpty()) q.add("host" to host)
        if (path.isNotEmpty()) q.add("path" to path)
        if (method.isNotEmpty()) q.add("method" to method)
        if (headers.isNotEmpty()) q.add("headers" to headers.joinToString("|"))
        if (idle_timeout.isNotEmpty()) q.add("idle_timeout" to idle_timeout)
        if (ping_timeout.isNotEmpty()) q.add("ping_timeout" to ping_timeout)
        if (max_early_data > 0) q.add("max_early_data" to max_early_data.toString())
        if (early_data_header_name.isNotEmpty()) q.add("early_data_header_name" to early_data_header_name)
        if (service_name.isNotEmpty()) q.add("serviceName" to service_name)
        return q
    }

    /** Transport::ExportToJson (transport.cpp). */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (type.isEmpty() || type == "tcp") return obj
        obj["type"] = type
        if (path.contains("?ed=")) {
            val spl = path.split("?ed=")
            obj["path"] = spl[0]
            obj["max_early_data"] = QtStrings.toInt(spl[1])
            obj["early_data_header_name"] = "Sec-WebSocket-Protocol"
        } else {
            if (path.isNotEmpty()) obj["path"] = path
            if (max_early_data > 0) obj["max_early_data"] = max_early_data
            if (early_data_header_name.isNotEmpty()) obj["early_data_header_name"] = early_data_header_name
        }
        if (method.isNotEmpty()) obj["method"] = method
        if (headers.isNotEmpty()) obj["headers"] = JsonValues.pairListToObject(headers)
        if (host.isNotEmpty()) {
            if (type == "http" || type == "httpupgrade") obj["host"] = Hosts.toAceHost(host)
            if (type == "ws") {
                val headersObj = obj["headers"] as? JsonObject ?: JsonObject()
                headersObj["Host"] = Hosts.toAceHost(host)
                obj["headers"] = headersObj
            }
        }
        if (idle_timeout.isNotEmpty()) obj["idle_timeout"] = idle_timeout
        if (ping_timeout.isNotEmpty()) obj["ping_timeout"] = ping_timeout
        if (service_name.isNotEmpty()) obj["service_name"] = service_name
        return obj
    }

    /** Transport::ExportIdentity (transport.cpp). */
    fun exportIdentity(): JsonObject {
        val obj = JsonObject()
        if (type.isEmpty() || type == "tcp") return obj
        obj["type"] = type
        return obj
    }

    /** Transport::Build (transport.cpp): a comma list of http hosts becomes an array of trimmed hosts; keys of other types are dropped. */
    fun build(ctx: BuildContext): JsonObject {
        val obj = exportToJson()
        val exported = obj.string("host")
        if (type == "http" && exported.contains(',')) {
            val hosts = QtStrings.splitSkipEmpty(exported, ",").map { it.trim() }
            obj["host"] = JsonValues.stringArray(hosts)
        }
        TRANSPORT_KEYS[type]?.let { keys ->
            for (key in obj.keys()) if (key != "type" && key !in keys) obj.remove(key)
        }
        return obj
    }

    /** Transport::Validate (transport.cpp); see [TransportChecks.quicTransport] for the flags. */
    fun validate(issues: MutableList<Issue>, tls: Boolean, utls: Boolean, reality: Boolean) {
        issues.addIssue(Severity.Error, "transport.type", TransportChecks.transportType(type), type)
        if (type == "http" || type == "ws" || type == "httpupgrade") {
            issues.addIssue(Severity.Warning, "transport.host", TransportChecks.transportHost(host, type), host)
        }
        if (type == "http") issues.addIssue(Severity.Warning, "transport.method", Validation.httpToken(method), method)
        if (type == "http" || type == "grpc") {
            issues.addIssue(Severity.Error, "transport.idle_timeout", Validation.duration(idle_timeout), idle_timeout)
            issues.addIssue(Severity.Error, "transport.ping_timeout", Validation.duration(ping_timeout), ping_timeout)
        }
        if (type == "quic") {
            issues.addIssue(Severity.Error, "transport.type", TransportChecks.quicTransport(tls, utls, reality))
            if (tls && !reality) issues.addIssue(Severity.Warning, "tls.utls.fingerprint", TransportChecks.quicTransportUtls(utls))
        }
    }
}
