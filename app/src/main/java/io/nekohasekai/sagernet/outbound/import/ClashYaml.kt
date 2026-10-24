package io.nekohasekai.sagernet.outbound.import

import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonNull
import io.nekohasekai.sagernet.outbound.json.JsonObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.composer.Composer
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.events.Event
import org.yaml.snakeyaml.events.MappingStartEvent
import org.yaml.snakeyaml.events.NodeEvent
import org.yaml.snakeyaml.events.ScalarEvent
import org.yaml.snakeyaml.events.SequenceStartEvent
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.NodeId
import org.yaml.snakeyaml.nodes.NodeTuple
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.parser.ParserImpl
import org.yaml.snakeyaml.reader.StreamReader
import org.yaml.snakeyaml.resolver.Resolver
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

    /**
     * [proxies] with what Parser::clash reports about a document without proxies. YAML errors propagate as exceptions.
     * A plain mapping is read one top-level value, and one sequence item, at a time; a document with anchors, explicit
     * tags, a top-level merge key or another root is composed whole, and its root's values constructed one at a time.
     * Both give what Yaml(SafeConstructor()).load gives.
     */
    @JvmStatic
    fun document(text: String): Document {
        val clean = sanitize(text)
        return try {
            StreamingRead(clean).read()
        } catch (e: Unsupported) {
            loadWhole(clean)
        }
    }

    // sanitizeClashYaml (SubscriptionParser.cpp:242-247): NULs and a leading BOM confuse the YAML reader.
    private fun sanitize(text: String): String {
        var s = text.replace("\u0000", "")
        if (s.startsWith("\uFEFF")) s = s.substring(1)
        return s
    }

    /** Yaml(SafeConstructor()).load: the same Composer and LoaderOptions, so aliases and their limit are unchanged. */
    private fun loadWhole(text: String): Document {
        val root = Composer(ParserImpl(StreamReader(text), false), Resolver(), LoaderOptions()).getSingleNode()
        val constructor = NodeConstructor()
        if (root is MappingNode && root.tag == Tag.MAP && !root.isTwoStepsConstruction) return constructor.readRoot(root)
        return rootDocument(root?.let(constructor::constructNode))
    }

    private fun rootDocument(data: Any?): Document {
        val root = data as? Map<*, *> ?: return Document(null, false, false)
        return Document((root["proxies"] as? List<*>)?.let(::convertEntries), true, root.containsKey("proxy-providers"))
    }

    /** Each entry is released once converted, so the maps and their JsonObjects are never all alive together. */
    private fun convertEntries(entries: List<*>): List<JsonObject> {
        val out = ArrayList<JsonObject>(entries.size)
        @Suppress("UNCHECKED_CAST")
        val slots = entries as? ArrayList<Any?>
        for (i in entries.indices) {
            out.add(toEntry(entries[i]))
            slots?.set(i, null)
        }
        return out
    }

    private fun toEntry(value: Any?): JsonObject = (convert(value) as? JsonObject) ?: JsonObject()

    private fun convert(value: Any?): Any = when (value) {
        null -> JsonNull
        is Map<*, *> -> JsonObject().also { obj -> for ((k, v) in value) obj[k.toString()] = convert(v) }
        is List<*> -> JsonArray().also { arr -> for (v in value) arr.add(convert(v)) }
        is String, is Boolean, is Long, is Int, is Short, is Byte, is Double, is Float, is BigInteger, is BigDecimal -> value
        else -> value.toString()
    }

    /** The document needs what only the whole load does exactly. */
    private class Unsupported : RuntimeException()

    /** SafeConstructor over one node at a time, each as a document of its own. */
    private class NodeConstructor : SafeConstructor() {
        fun constructNode(node: Node): Any? = constructDocument(node)

        /**
         * constructMapping2ndStep for a plain root, each value constructed as a document of its own and dropped unless
         * it is `proxies`. A node shared through an alias is constructed again instead of reused: flattenMapping left
         * it without merge keys or duplicates, so it constructs to an equal value. Recursive keys take the whole path.
         */
        fun readRoot(root: MappingNode): Document {
            flattenMapping(root)
            if (root.value.any { it.keyNode.isTwoStepsConstruction }) return rootDocument(constructNode(root))
            var proxies: Any? = null
            var proxyProviders = false
            for (tuple in root.value) {
                val key = constructNode(tuple.keyNode)
                val value = constructNode(tuple.valueNode)
                if (key == "proxies") proxies = value else if (key == "proxy-providers") proxyProviders = true
            }
            return Document((proxies as? List<*>)?.let(::convertEntries), true, proxyProviders)
        }
    }

    /** A top-level value: the first failure constructing it and, for `proxies`, its converted items. */
    private class Value(@JvmField val failure: RuntimeException?, @JvmField val items: List<JsonObject>?) {
        companion object {
            val NONE = Value(null, null)
        }
    }

    /**
     * The root mapping read field by field. Without anchors no node is shared, so each node constructs on its own as it
     * would inside the whole document. Failures are thrown in SafeConstructor's order once the stream is read: a key
     * first, then the values of the fields that survive the duplicate keys (a repeated key keeps its last value).
     */
    private class StreamingRead(text: String) {
        private val parser = ParserImpl(StreamReader(text), false)
        private val resolver = Resolver()
        private val constructor = NodeConstructor()

        fun read(): Document {
            parser.getEvent()
            if (parser.checkEvent(Event.ID.StreamEnd)) throw Unsupported()
            parser.getEvent()
            val root = parser.getEvent()
            if (root !is MappingStartEvent || root.tag != null || root.anchor != null) throw Unsupported()
            val fields = ArrayList<Value>()
            val lastField = HashMap<Any?, Int>()
            var keyFailure: RuntimeException? = null
            while (!parser.checkEvent(Event.ID.MappingEnd)) {
                val keyNode = compose()
                // A merged mapping can bring in `proxies` itself.
                if (keyNode.tag == Tag.MERGE) throw Unsupported()
                var key: Any? = null
                if (keyFailure == null) {
                    try {
                        key = constructor.constructNode(keyNode)
                    } catch (e: RuntimeException) {
                        keyFailure = e
                    }
                }
                val construct = keyFailure == null
                val value = readValue(construct, keep = construct && key == "proxies")
                if (construct) lastField.put(key, fields.size)?.let { fields[it] = Value.NONE }
                fields.add(value)
            }
            parser.getEvent()
            parser.getEvent()
            if (!parser.checkEvent(Event.ID.StreamEnd)) throw Unsupported()
            keyFailure?.let { throw it }
            for (field in fields) field.failure?.let { throw it }
            val proxies = lastField["proxies"]?.let { fields[it].items }
            return Document(proxies, true, "proxy-providers" in lastField)
        }

        /** A sequence is composed and constructed item by item; [keep] converts the items into entries. */
        private fun readValue(construct: Boolean, keep: Boolean): Value {
            val start = parser.peekEvent()
            if (start !is SequenceStartEvent || start.tag != null || start.anchor != null) {
                val node = compose()
                if (!construct) return Value.NONE
                return try {
                    constructor.constructNode(node)
                    Value.NONE
                } catch (e: RuntimeException) {
                    Value(e, null)
                }
            }
            parser.getEvent()
            val items = if (keep) ArrayList<JsonObject>() else null
            var failure: RuntimeException? = null
            while (!parser.checkEvent(Event.ID.SequenceEnd)) {
                val node = compose()
                if (!construct || failure != null) continue
                val item = try {
                    constructor.constructNode(node)
                } catch (e: RuntimeException) {
                    failure = e
                    continue
                }
                items?.add(toEntry(item))
            }
            parser.getEvent()
            return Value(failure, items)
        }

        /** Composer.composeNode for a document without anchors or explicit tags. */
        private fun compose(): Node {
            val event = parser.getEvent()
            // Without anchors there are no aliases: the whole path takes over before anything is shared.
            if (event is NodeEvent && event.anchor != null) throw Unsupported()
            return composeNode(event)
        }

        private fun composeNode(event: Event): Node = when (event) {
            is ScalarEvent -> {
                if (event.tag != null) throw Unsupported()
                val tag = resolver.resolve(NodeId.scalar, event.value, event.implicit.canOmitTagInPlainScalar())
                ScalarNode(tag, true, event.value, event.startMark, event.endMark, event.scalarStyle)
            }

            is SequenceStartEvent -> {
                if (event.tag != null) throw Unsupported()
                val children = ArrayList<Node>()
                val tag = resolver.resolve(NodeId.sequence, null, event.implicit)
                val node = SequenceNode(tag, true, children, event.startMark, null, event.flowStyle)
                while (!parser.checkEvent(Event.ID.SequenceEnd)) children.add(compose())
                node.setEndMark(parser.getEvent().endMark)
                node
            }

            is MappingStartEvent -> {
                if (event.tag != null) throw Unsupported()
                val children = ArrayList<NodeTuple>()
                val tag = resolver.resolve(NodeId.mapping, null, event.implicit)
                val node = MappingNode(tag, true, children, event.startMark, null, event.flowStyle)
                while (!parser.checkEvent(Event.ID.MappingEnd)) {
                    val key = compose()
                    if (key.tag == Tag.MERGE) node.setMerged(true)
                    children.add(NodeTuple(key, compose()))
                }
                node.setEndMark(parser.getEvent().endMark)
                node
            }

            else -> throw Unsupported()
        }
    }
}
