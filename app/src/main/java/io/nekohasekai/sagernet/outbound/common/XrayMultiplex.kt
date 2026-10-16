package io.nekohasekai.sagernet.outbound.common

import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.import.ClashProxy
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.outbound.link.ParsedLink
import io.nekohasekai.sagernet.validation.readQueryInt

/** xrayMultiplex (include/configs/common/xrayMultiplex.h, src/configs/common/xrayMultiplex.cpp). `useDefault` true means "Keep Default". */
class XrayMultiplex {
    @JvmField var enabled: Boolean = false
    @JvmField var useDefault: Boolean = true
    @JvmField var concurrency: Int = 0
    @JvmField var xudpConcurrency: Int = 16

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** xrayMultiplex::ParseFromLink (xrayMultiplex.cpp): any whole number is kept, negative ones included. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        if (q.has("mux")) {
            enabled = q.value("mux").replace("1", "true") == "true"
            useDefault = false
        }
        concurrency = readQueryInt(q, "mux_concurrency", concurrency, Int.MIN_VALUE)
        xudpConcurrency = readQueryInt(q, "mux_xudp_concurrency", xudpConcurrency, Int.MIN_VALUE)
        return true
    }

    /** xrayMultiplex::ParseFromJson (xrayMultiplex.cpp). */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("enabled")) {
            enabled = obj.bool("enabled")
            useDefault = false
        }
        if (obj.contains("concurrency")) concurrency = obj.int("concurrency")
        if (obj.contains("xudpConcurrency")) xudpConcurrency = obj.int("xudpConcurrency")
        return true
    }

    /** xrayMultiplex::ParseFromClash (xrayMultiplex.cpp). */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        val smux = proxy.obj("smux")
        enabled = smux.bool("enabled")
        useDefault = false
        val maxStreams = smux.int("max-streams")
        if (maxStreams > 0) concurrency = maxStreams
        return true
    }

    /** xrayMultiplex::ExportToLink (xrayMultiplex.cpp). */
    fun exportToLink(): List<Pair<String, String>> {
        if (useDefault) return emptyList()
        val q = ArrayList<Pair<String, String>>()
        q.add("mux" to if (enabled) "true" else "false")
        if (enabled) {
            if (concurrency > 0) q.add("mux_concurrency" to concurrency.toString())
            if (xudpConcurrency > 0) q.add("mux_xudp_concurrency" to xudpConcurrency.toString())
        }
        return q
    }

    /** xrayMultiplex::ExportToJson (xrayMultiplex.cpp). */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (useDefault) return obj
        obj["enabled"] = enabled
        if (!enabled) return obj
        if (concurrency > 0) obj["concurrency"] = concurrency
        if (xudpConcurrency > 0) obj["xudpConcurrency"] = xudpConcurrency
        return obj
    }

    /** xrayMultiplex::Build (xrayMultiplex.cpp). */
    fun build(ctx: BuildContext): JsonObject {
        val obj = exportToJson()
        if (useDefault && ctx.xrayMuxDefaultOn) obj["enabled"] = true
        if (!obj.bool("enabled")) return JsonObject()
        if (ctx.xrayMuxConcurrency > 0 && concurrency <= 0) obj["concurrency"] = ctx.xrayMuxConcurrency
        if (xudpConcurrency > 0) obj["xudpConcurrency"] = xudpConcurrency
        return obj
    }
}
