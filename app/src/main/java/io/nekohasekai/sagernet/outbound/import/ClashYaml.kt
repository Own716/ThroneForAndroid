package io.nekohasekai.sagernet.outbound.import

import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonNull
import io.nekohasekai.sagernet.outbound.json.JsonObject
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.math.BigDecimal
import java.math.BigInteger

/** The Clash YAML document reduced to its `proxies` sequence, each entry as a [JsonObject] tree. */
object ClashYaml {

    /**
     * Parser::clash (SubscriptionParser.cpp:415-442): null when the document is not a mapping with a `proxies`
     * sequence; the entries are returned untyped, [ClashProxy] applies the desktop's field conversions. YAML
     * errors propagate as exceptions (the caller reports them like the desktop's "YAML Exception" warning).
     */
    @JvmStatic
    fun proxies(text: String): List<JsonObject>? = document(text).proxies

    /** The parsed document: [proxies] null without a `proxies` sequence; [proxyProviders] whether the mapping lists `proxy-providers`. */
    class Document(@JvmField val proxies: List<JsonObject>?, @JvmField val mapping: Boolean, @JvmField val proxyProviders: Boolean)

    /** [proxies] with what Parser::clash reports about a document without proxies. YAML errors propagate as exceptions. */
    @JvmStatic
    fun document(text: String): Document {
        val root = Yaml(SafeConstructor()).load<Any?>(sanitize(text)) as? Map<*, *> ?: return Document(null, false, false)
        val proxies = (root["proxies"] as? List<*>)?.map { (convert(it) as? JsonObject) ?: JsonObject() }
        return Document(proxies, true, root.containsKey("proxy-providers"))
    }

    // sanitizeClashYaml (SubscriptionParser.cpp:242-247): NULs and a leading BOM confuse the YAML reader.
    private fun sanitize(text: String): String {
        var s = text.replace("\u0000", "")
        if (s.startsWith("\uFEFF")) s = s.substring(1)
        return s
    }

    private fun convert(value: Any?): Any = when (value) {
        null -> JsonNull
        is Map<*, *> -> JsonObject().also { obj -> for ((k, v) in value) obj[k.toString()] = convert(v) }
        is List<*> -> JsonArray().also { arr -> for (v in value) arr.add(convert(v)) }
        is String, is Boolean, is Long, is Int, is Short, is Byte, is Double, is Float, is BigInteger, is BigDecimal -> value
        else -> value.toString()
    }
}
