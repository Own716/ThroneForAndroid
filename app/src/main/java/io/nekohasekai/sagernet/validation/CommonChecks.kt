package io.nekohasekai.sagernet.validation

import io.nekohasekai.sagernet.R

/** The Checks of include/configs/common/utils.h, shared by the editors and the import check. */
object CommonChecks {

    /** parseHeaderPairs (utils.cpp): Name=value pairs separated by spaces, values optionally quoted; empty when one is unpaired. */
    @JvmStatic
    fun parseHeaderPairs(rawHeader: String): List<String> {
        var inQuote = false
        val curr = StringBuilder()
        val list = ArrayList<String>()
        for (ch in rawHeader) {
            if (inQuote) {
                if (ch == '"') {
                    inQuote = false
                    list.add(curr.toString())
                    curr.setLength(0)
                } else {
                    curr.append(ch)
                }
                continue
            }
            when (ch) {
                '"' -> inQuote = true
                ' ', '=' -> if (curr.isNotEmpty()) {
                    list.add(curr.toString())
                    curr.setLength(0)
                }

                else -> curr.append(ch)
            }
        }
        if (curr.isNotEmpty()) list.add(curr.toString())
        return if (list.size % 2 != 0) emptyList() else list
    }

    /** The editors' header text: Name=value pairs separated by spaces, values optionally quoted. */
    @JvmStatic
    fun headerPairs(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        if (t.contains('|')) return vtr(R.string.validation_header_pipe)
        val pairs = parseHeaderPairs(t)
        var ok = t.count { it == '"' } % 2 == 0 && pairs.isNotEmpty()
        var i = 0
        while (ok && i < pairs.size) {
            ok = pairs[i].isNotBlank()
            i += 2
        }
        return if (ok) "" else vtr(R.string.validation_header_pairs)
    }

    /** An HTTP Host header: host[:port], comma separated when [allowList]. */
    @JvmStatic
    fun hostHeader(text: String, allowList: Boolean): String {
        val rule: Rule = { Validation.hostPort(it, false) }
        if (allowList) return Validation.eachItem(text, Validation.Split.Comma, rule)
        return if (text.isBlank()) "" else rule(text)
    }

    /** An SNI: a domain name or IP address, without a scheme or port. */
    @JvmStatic
    fun serverName(text: String): String {
        val message = Validation.host(text, Empty.Allowed)
        if (message.isEmpty()) return ""
        if (Validation.hostPort(text, true).isEmpty()) return vtr(R.string.validation_sni_port)
        return message
    }
}
