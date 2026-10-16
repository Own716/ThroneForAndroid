package io.nekohasekai.sagernet.validation

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.outbound.link.Base64Strict
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.ui.json.engine.JsonTree
import io.nekohasekai.sagernet.ui.json.engine.JsonType
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

enum class Severity { Error, Warning }

enum class Empty { Allowed, Required }

enum class Family { Any, V4, V6 }

/** Validation::Issue (Validation.hpp): [value] is shown to the user next to the message, so never a password, key or UUID. */
data class Issue(
    @JvmField val severity: Severity,
    @JvmField val field: String,
    @JvmField val message: String,
    @JvmField val value: String = "",
)

/** Validation::Rule: an empty string for acceptable text, otherwise one translated sentence. */
typealias Rule = (String) -> String

/** The `add` helper every desktop Validate defines: an empty [message] adds nothing. */
fun MutableList<Issue>.addIssue(severity: Severity, field: String, message: String, value: String = "") {
    if (message.isNotEmpty()) add(Issue(severity, field, message, value))
}

/** QCoreApplication::translate("Validation", …) on Android: resolved on the calling thread, from any process. */
fun vtr(@StringRes id: Int, vararg args: Any): String = if (args.isEmpty()) app.getString(id) else app.getString(id, *args)

fun vplural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    app.resources.getQuantityString(id, count, *(if (args.isEmpty()) arrayOf<Any>(count) else args))

/**
 * The shared field grammar (include/global/Validation.hpp, src/global/Validation.cpp). Every check returns an empty
 * string for acceptable text, otherwise one translated sentence without a field label. Grammars mirror the core's own
 * parsers on trimmed text; whitespace-only text counts as empty. Safe on any thread. Callers store trimmed text.
 */
object Validation {

    enum class RangeSep { Colon, Dash }

    /** sing-box colon ranges: checkOrder = false plus [portRangeOrder] as a warning; Xray and mieru dash ranges keep checkOrder. */
    data class PortRangeStyle(
        @JvmField val sep: RangeSep = RangeSep.Colon,
        @JvmField val allowSingle: Boolean = false,
        @JvmField val allowOpenEnds: Boolean = false,
        @JvmField val checkOrder: Boolean = true,
    )

    data class DurationRule(
        @JvmField val empty: Empty = Empty.Allowed,
        @JvmField val allowNegative: Boolean = false,
        @JvmField val wholeSeconds: Boolean = false,
        @JvmField val minNs: Long? = null,
        @JvmField val maxNs: Long? = null,
    )

    enum class Split { Comma, Newline, CommaOrNewline, Whitespace }

    private enum class IpKind { Invalid, V4, V6 }

    private class ParsedIp(val kind: IpKind, val zoned: Boolean)

    private val INVALID_IP = ParsedIp(IpKind.Invalid, false)

    private fun digit(c: Char) = c in '0'..'9'

    private fun hexDigit(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun asciiAlnum(c: Char) = c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z'

    private fun allDigits(s: CharSequence) = s.isNotEmpty() && s.all(::digit)

    private fun signedDigits(s: String) = allDigits(if (s.startsWith('-')) s.substring(1) else s)

    private fun emptyMessage(empty: Empty) = if (empty == Empty.Required) required("") else ""

    private fun short(text: String) = if (text.length > 40) text.take(40) + "…" else text

    private fun parseLong(s: String): Long? {
        if (s.isEmpty()) return null
        val neg = s[0] == '-'
        var i = if (neg) 1 else 0
        if (i == s.length) return null
        val limit: ULong = Long.MAX_VALUE.toULong() + (if (neg) 1uL else 0uL)
        var v: ULong = 0uL
        while (i < s.length) {
            if (!digit(s[i])) return null
            val d = (s[i] - '0').toULong()
            if (v > (limit - d) / 10uL) return null
            v = v * 10uL + d
            i++
        }
        if (!neg) return v.toLong()
        return if (v == limit) Long.MIN_VALUE else -v.toLong()
    }

    /** strconv.ParseUint(s, 10, 16): decimal digits only, leading zeros allowed. */
    private fun parseUint16(text: String): Int? {
        if (!allDigits(text)) return null
        var i = 0
        while (i < text.length - 1 && text[i] == '0') i++
        val s = text.substring(i)
        if (s.length > 5) return null
        val v = s.toInt()
        return if (v > 65535) null else v
    }

    /** Go netip parseIPv4Fields. */
    private fun ipv4Fields(s: String): Boolean {
        var v = 0
        var pos = 0
        var digLen = 0
        for (i in s.indices) {
            val c = s[i]
            if (digit(c)) {
                if (digLen == 1 && v == 0) return false
                v = v * 10 + (c - '0')
                digLen++
                if (v > 255) return false
            } else if (c == '.') {
                if (i == 0 || i == s.length - 1 || s[i - 1] == '.') return false
                if (pos == 3) return false
                pos++
                v = 0
                digLen = 0
            } else {
                return false
            }
        }
        return pos == 3
    }

    /** Go netip parseIPv6: null when invalid, else whether a zone follows. */
    private fun ipv6(input: String): Boolean? {
        var s = input
        var zoned = false
        val pct = s.indexOf('%')
        if (pct >= 0) {
            if (pct == s.length - 1) return null
            zoned = true
            s = s.substring(0, pct)
        }
        val n = s.length
        var p = 0
        var ellipsis = -1
        if (n >= 2 && s[0] == ':' && s[1] == ':') {
            ellipsis = 0
            p = 2
            if (p == n) return zoned
        }
        var i = 0
        while (i < 16) {
            var off = 0
            var acc = 0
            while (p + off < n) {
                val c = s[p + off]
                if (!hexDigit(c)) break
                acc = (acc shl 4) + Character.digit(c, 16)
                if (off > 3 || acc > 0xFFFF) return null
                off++
            }
            if (off == 0) return null
            if (p + off < n && s[p + off] == '.') {
                if (ellipsis < 0 && i != 12) return null
                if (i + 4 > 16) return null
                if (!ipv4Fields(s.substring(p))) return null
                p = n
                i += 4
                break
            }
            i += 2
            p += off
            if (p == n) break
            if (s[p] != ':' || p + 1 == n) return null
            p++
            if (s[p] == ':') {
                if (ellipsis >= 0) return null
                ellipsis = i
                p++
                if (p == n) break
            }
        }
        if (p != n) return null
        if (i < 16) return if (ellipsis >= 0) zoned else null
        return if (ellipsis < 0) zoned else null
    }

    /** Go netip.ParseAddr dispatches on the first '.' or ':'. */
    private fun parseIp(s: String): ParsedIp {
        for (c in s) {
            if (c == '.') return if (ipv4Fields(s)) ParsedIp(IpKind.V4, false) else INVALID_IP
            if (c == ':') return ipv6(s)?.let { ParsedIp(IpKind.V6, it) } ?: INVALID_IP
            if (c == '%') break
        }
        return INVALID_IP
    }

    private fun ipMessage(family: Family): String = when (family) {
        Family.V4 -> vtr(R.string.validation_ipv4)
        Family.V6 -> vtr(R.string.validation_ipv6)
        Family.Any -> vtr(R.string.validation_ip)
    }

    private fun checkIp(ip: ParsedIp, family: Family): String {
        if (ip.kind == IpKind.Invalid) return ipMessage(family)
        if (family == Family.V4 && ip.kind != IpKind.V4) return ipMessage(family)
        if (family == Family.V6 && ip.kind != IpKind.V6) return ipMessage(family)
        if (ip.zoned) return vtr(R.string.validation_ip_zone)
        return ""
    }

    private fun portMessage() = vtr(R.string.validation_port)

    private fun hostMessage() = vtr(R.string.validation_host)

    private fun schemeMessage() = vtr(R.string.validation_host_scheme)

    /** Interface names never contain ':', so a colon in the zone is a port typed after the address. */
    private fun zoneHasColon(s: String): Boolean {
        val pct = s.indexOf('%')
        return pct >= 0 && s.substring(pct + 1).contains(':')
    }

    /** Meant as IPv6: netip reads IPv4 when a '.' comes before the first ':', so "1.2.3.4:443:user:pass" is not. */
    private fun ipv6Like(s: String): Boolean {
        val pct = s.indexOf('%')
        val address = if (pct < 0) s else s.substring(0, pct)
        val colon = address.indexOf(':')
        val dot = address.indexOf('.')
        if (colon < 0 || (dot in 0 until colon)) return false
        return address.all { hexDigit(it) || it == ':' || it == '.' }
    }

    /** The port text of a URL's authority, or "" when "://" does not directly follow the scheme. */
    private fun urlPort(t: String): String {
        val sep = t.indexOf("://")
        val letterFirst = sep > 0 && asciiAlnum(t[0]) && !digit(t[0])
        if (!letterFirst || !(0 until sep).all { val c = t[it]; asciiAlnum(c) || c == '+' || c == '-' || c == '.' }) return ""
        var end = sep + 3
        while (end < t.length && t[end] != '/' && t[end] != '?' && t[end] != '#') end++
        var host = t.substring(sep + 3, end)
        host = host.substring(host.lastIndexOf('@') + 1)
        if (host.startsWith('[')) {
            val close = host.indexOf(']')
            if (close < 0 || close + 1 >= host.length || host[close + 1] != ':') return ""
            return host.substring(close + 2)
        }
        val colon = host.lastIndexOf(':')
        return if (colon < 0) "" else host.substring(colon + 1)
    }

    private fun rangeMessage(min: Long, max: Long) = vtr(R.string.validation_range, min.toString(), max.toString())

    private val DURATION_UNITS = listOf(
        3600000000000uL to "h", 60000000000uL to "m", 1000000000uL to "s", 1000000uL to "ms", 1000uL to "us", 1uL to "ns",
    )

    private fun formatDuration(ns: Long): String {
        if (ns == 0L) return "0s"
        val out = StringBuilder()
        var v = ns.toULong()
        if (ns < 0) {
            out.append('-')
            v = 0uL - v
        }
        for ((unit, name) in DURATION_UNITS) {
            val q = v / unit
            if (q != 0uL) {
                out.append(q.toString()).append(name)
                v %= unit
            }
        }
        return out.toString()
    }

    private class Cursor(var i: Int)

    /** Go's regexp/syntax escapes (parse.go parseEscape + Perl/Unicode classes): advances past the escape, returns the offending token. */
    private fun re2Escape(p: String, cur: Cursor, inClass: Boolean): String {
        val i = cur.i
        val d = p[i + 1]
        val escape = "\\" + d
        val at = i + 2
        cur.i = at
        if (d.code < 0x80 && !asciiAlnum(d)) return ""
        when (d) {
            'p', 'P' -> {
                // Go knows only Unicode categories and scripts; PCRE adds Xan-style, L& and name=value properties.
                var name = ""
                if (at < p.length && p[at] == '{') {
                    val close = p.indexOf('}', at)
                    if (close < 0) return ""
                    name = p.substring(at + 1, close)
                    cur.i = close + 1
                } else if (at < p.length) {
                    name = p.substring(at, at + 1)
                    cur.i = at + 1
                }
                if (name.startsWith('^')) name = name.substring(1)
                // Name lookup ignores case, '_', '-' and ' ' in both engines; no Go name starts with X.
                name = name.trimStart('_', '-', ' ')
                if (name.startsWith('X', ignoreCase = true) || name.contains('&') || name.contains(':') || name.contains('=')) {
                    return escape + p.substring(at, cur.i)
                }
                return ""
            }

            'x' -> {
                // Go: exactly two hex digits, or one or more in braces up to 10FFFF.
                if (at < p.length && p[at] == '{') {
                    val close = p.indexOf('}', at)
                    if (close < 0) {
                        cur.i = p.length
                        return escape + p.substring(at)
                    }
                    cur.i = close + 1
                    val digits = p.substring(at + 1, close)
                    val ok = digits.isNotEmpty() && digits.all(::hexDigit) && (digits.toLongOrNull(16) ?: Long.MAX_VALUE) <= 0x10FFFF
                    return if (ok) "" else escape + p.substring(at, cur.i)
                }
                if (at + 1 < p.length && hexDigit(p[at]) && hexDigit(p[at + 1])) {
                    cur.i = at + 2
                    return ""
                }
                return escape + p.substring(at, minOf(p.length, at + 2))
            }

            'd', 'D', 's', 'S', 'w', 'W', 'a', 'f', 'n', 'r', 't', 'v', '0' -> return ""
            'A', 'b', 'B', 'z' -> if (!inClass) return ""
            '1', '2', '3', '4', '5', '6', '7' -> if (at < p.length && p[at] in '0'..'7') return ""
        }
        return escape
    }

    private fun goCaptureName(name: String) = name.isNotEmpty() && name.all { asciiAlnum(it) || it == '_' }

    private class Re2Group(val bad: String = "", val length: Int = 0, val opens: Boolean = true)

    /** After "(?": Go accepts only (?P<name>, (?<name> and the flags i, m, s, U with at most one '-' followed by a flag, ending in ':' or ')'. */
    private fun re2Group(rest: String): Re2Group {
        fun at(k: Int) = if (k < rest.length) rest[k] else '\u0000'
        fun token(length: Int) = Re2Group("(?" + rest.substring(0, minOf(length, rest.length)))
        val c0 = at(0)
        val c1 = at(1)
        if ((c0 == 'P' && c1 == '<') || (c0 == '<' && c1 != '=' && c1 != '!')) {
            val nameStart = if (c0 == 'P') 2 else 1
            val close = rest.indexOf('>')
            if (close >= 0 && !goCaptureName(rest.substring(minOf(nameStart, close), close))) return token(close + 1)
            return Re2Group("", close + 1, true)
        }
        if (c0 == '<' || (c0 == 'P' && (c1 == '=' || c1 == '>')) || (c0 == '-' && digit(c1))) return token(2)
        if (c0 == '=' || c0 == '!' || c0 == '>' || c0 == '(' || c0 == '#' || c0 == '|' || c0 == '\'' || c0 == 'R' || c0 == '&' ||
            c0 == '+' || digit(c0)
        ) {
            return token(1)
        }
        var negated = false
        var sawFlag = false
        for (k in rest.indices) {
            val f = rest[k]
            if (f == ':' || f == ')') {
                if (negated && !sawFlag) return token(k + 1)
                return Re2Group("", k + 1, f == ':')
            }
            if (f == '-') {
                if (negated) return token(k + 1)
                negated = true
                sawFlag = false
            } else if (f == 'i' || f == 'm' || f == 's' || f == 'U') {
                sawFlag = true
            } else {
                return token(k + 1)
            }
        }
        return Re2Group("", 0, true)
    }

    /** Exclusive end of a {n}, {n,} or {n,m} repeat at [i], or -1 when the brace is a literal; [out] gets min and max (-1 when unbounded). */
    private fun repeatEnd(p: String, i: Int, out: IntArray): Int {
        var j = i + 1
        // Go parseInt: a missing number or a leading zero ({,3}, {01}) makes the brace a literal.
        fun number(slot: Int): Boolean {
            val start = j
            while (j < p.length && digit(p[j])) j++
            if (j == start || (j - start > 1 && p[start] == '0')) return false
            out[slot] = if (j - start > 7) 100000000 else p.substring(start, j).toInt()
            return true
        }
        if (!number(0)) return -1
        out[1] = out[0]
        if (j < p.length && p[j] == ',') {
            j++
            if (j < p.length && p[j] == '}') out[1] = -1 else if (!number(1)) return -1
        }
        if (j >= p.length || p[j] != '}') return -1
        return j + 1
    }

    private val GO_ONLY_PROPERTIES = setOf(
        "assigned", "casedletter", "closepunctuation", "cntrl", "combiningmark", "connectorpunctuation", "control",
        "currencysymbol", "dashpunctuation", "decimalnumber", "digit", "enclosingmark", "finalpunctuation", "format",
        "initialpunctuation", "letter", "letternumber", "lineseparator", "lowercaseletter", "mark", "mathsymbol",
        "modifierletter", "modifiersymbol", "nonspacingmark", "number", "openpunctuation", "other", "otherletter",
        "othernumber", "otherpunctuation", "othersymbol", "paragraphseparator", "privateuse", "punct", "punctuation",
        "separator", "spaceseparator", "spacingmark", "surrogate", "symbol", "titlecaseletter", "unassigned",
        "uppercaseletter",
    )

    /** unicode.CategoryAliases and Assigned, which only Go knows; compared like Go's canonicalName. */
    private fun goOnlyProperty(name: String): Boolean {
        val canonical = StringBuilder()
        for (c in name) {
            if (c == '_' || c == '-' || c == ' ') continue
            if (!asciiAlnum(c)) return false
            canonical.append(c.lowercaseChar())
        }
        return canonical.toString() in GO_ONLY_PROPERTIES
    }

    /**
     * validationPcreCopy: a copy that Android's regex engine (ICU behind java.util.regex) judges like Go where Go is
     * laxer: class '-', '[', ']' and '&', repeated assertions and flag groups, category aliases and literal '{'. Unlike
     * the desktop's PCRE copy, named groups become plain groups (ICU names allow neither '_' nor duplicates) and the U
     * flag, which ICU lacks, becomes i.
     */
    private fun regexCopy(p: String): String {
        val n = p.length
        fun flagGroupsEnd(start: Int): Int {
            var i = start
            while (i + 1 < n && p[i] == '(' && p[i + 1] == '?') {
                val group = re2Group(p.substring(i + 2))
                if (group.bad.isNotEmpty() || group.opens) break
                i += 2 + group.length
            }
            return i
        }

        fun quantifierEnd(i: Int): Int {
            if (i >= n) return -1
            var end = -1
            if (p[i] == '*' || p[i] == '+' || p[i] == '?') {
                end = i + 1
            } else if (p[i] == '{') {
                end = repeatEnd(p, i, IntArray(2))
            }
            if (end in 1 until n && (p[end] == '?' || p[end] == '+')) end++
            return end
        }

        val out = StringBuilder(n + 16)
        var inClass = false
        var afterClass = false
        var classStart = 0
        var i = 0
        // ICU only takes the braced form, so Go's single-letter \pL is rewritten to \p{L}.
        fun property() {
            var end = minOf(n, i + 3)
            var name = p.substring(minOf(i + 2, end), end)
            var braced = false
            if (i + 2 < n && p[i + 2] == '{') {
                val close = p.indexOf('}', i + 2)
                if (close < 0) {
                    out.append(p, i, n)
                    i = n
                    return
                }
                end = close + 1
                name = p.substring(i + 3, close)
                braced = true
            }
            if (name.startsWith('^')) name = name.substring(1)
            when {
                goOnlyProperty(name) -> out.append("\\p{L}")
                !braced && name.isNotEmpty() -> out.append('\\').append(p[i + 1]).append('{').append(name).append('}')
                else -> out.append(p, i, end)
            }
            i = end
        }

        fun assertion(length: Int) {
            val token = p.substring(i, i + length)
            i += length
            if (quantifierEnd(flagGroupsEnd(i)) > 0) out.append("(?:").append(token).append(')') else out.append(token)
        }
        while (i < n) {
            val c = p[i]
            if (inClass) {
                if (c == '\\' && i + 1 < n) {
                    val d = p[i + 1]
                    if (d == 'p' || d == 'P') {
                        property()
                        afterClass = true
                        continue
                    }
                    out.append(p, i, i + 2)
                    i += 2
                    afterClass = d in "dDsSwW"
                    continue
                }
                if (c == '[' && i + 1 < n && p[i + 1] == ':') {
                    val end = p.indexOf(":]", i + 2)
                    if (end >= 0) {
                        out.append(p, i, end + 2)
                        i = end + 2
                        afterClass = true
                        continue
                    }
                }
                when {
                    c == '-' && afterClass -> out.append("\\-")
                    c == '[' || c == '&' -> out.append('\\').append(c)
                    c == ']' && i == classStart -> out.append("\\]")
                    else -> {
                        if (c == ']') inClass = false
                        out.append(c)
                    }
                }
                afterClass = false
                i++
                continue
            }
            if (c == '\\' && i + 1 < n) {
                val d = p[i + 1]
                if (d == 'Q') {
                    val end = p.indexOf("\\E", i + 2)
                    val stop = if (end < 0) n else end + 2
                    out.append(p, i, stop)
                    i = stop
                } else if (d == 'p' || d == 'P') {
                    property()
                } else if (d == 'b' || d == 'B' || d == 'A' || d == 'z') {
                    assertion(2)
                } else if (d in "xogkN" && i + 2 < n && p[i + 2] == '{') {
                    // The braces belong to the escape, so the literal-brace rewrite below must not reach them.
                    val close = p.indexOf('}', i + 3)
                    val stop = if (close < 0) n else close + 1
                    out.append(p, i, stop)
                    i = stop
                } else {
                    out.append(p, i, i + 2)
                    i += 2
                }
                continue
            }
            if (c == '^' || c == '$') {
                assertion(1)
                continue
            }
            if (c == '[') {
                out.append(c)
                i++
                if (i < n && p[i] == '^') out.append(p[i++])
                if (i < n && (p[i] == ':' || p[i] == '.' || p[i] == '=')) out.append('\\')
                inClass = true
                afterClass = false
                classStart = i
                continue
            }
            if (c == '(' && i + 1 < n && p[i + 1] == '?') {
                // Go's flag groups add no atom, so a repeat after them applies to what came before.
                val flags = flagGroupsEnd(i)
                if (flags > i) {
                    val groups = p.substring(i, flags).replace('U', 'i')
                    val repeat = quantifierEnd(flags)
                    if (repeat > 0) {
                        out.append(p, flags, repeat).append(groups)
                        i = repeat
                    } else {
                        out.append(groups)
                        i = flags
                    }
                    continue
                }
                val rest = p.substring(i + 2)
                val angle = rest.startsWith("<") && !rest.startsWith("<=") && !rest.startsWith("<!")
                val nameStart = if (rest.startsWith("P<")) 2 else if (angle) 1 else 0
                val close = if (nameStart > 0) rest.indexOf('>') else -1
                if (close > nameStart && goCaptureName(rest.substring(nameStart, close))) {
                    out.append('(')
                    i += 2 + close + 1
                    continue
                }
                val group = re2Group(rest)
                if (nameStart == 0 && group.bad.isEmpty() && group.opens && group.length > 0) {
                    out.append("(?").append(rest.substring(0, group.length).replace('U', 'i'))
                    i += 2 + group.length
                    continue
                }
                out.append("(?")
                i += 2
                continue
            }
            if (c == '{') {
                val end = repeatEnd(p, i, IntArray(2))
                if (end < 0) {
                    out.append("\\{")
                    i++
                } else {
                    out.append(p, i, end)
                    i = end
                }
                continue
            }
            // Only a '}' that closes a repeat is consumed above; Go takes any other as a literal, ICU refuses it.
            if (c == '}') {
                out.append("\\}")
                i++
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun re2Unsupported(p: String): String {
        fun unsupported(token: String) = vtr(R.string.validation_regex_re2_unsupported, token)
        fun tooManyRepeats() = vtr(R.string.validation_regex_re2_repeats)
        val n = p.length
        // Go repeatIsValid: an atom's weight is the largest product of the {n,m} counts nested inside it.
        val groups = ArrayList<Int>().apply { add(1) }
        var last = 0
        fun commit() {
            groups[groups.size - 1] = maxOf(groups.last(), last)
            last = 0
        }
        var inClass = false
        var classStart = 0
        val cur = Cursor(0)
        while (cur.i < n) {
            val i = cur.i
            val c = p[i]
            if (c == '\\') {
                if (i + 1 >= n) return ""
                if (!inClass) {
                    commit()
                    last = 1
                }
                if (!inClass && p[i + 1] == 'Q') {
                    val end = p.indexOf("\\E", i + 2)
                    if (end < 0) return ""
                    cur.i = end + 2
                    continue
                }
                val bad = re2Escape(p, cur, inClass)
                if (bad.isNotEmpty()) return unsupported(bad)
                continue
            }
            if (inClass) {
                if (c == '[' && i + 1 < n && p[i + 1] == ':') {
                    val end = p.indexOf(":]", i + 2)
                    if (end >= 0) {
                        cur.i = end + 2
                        continue
                    }
                }
                if (c == ']' && i > classStart) inClass = false
                cur.i++
                continue
            }
            when (c) {
                '[' -> {
                    commit()
                    last = 1
                    inClass = true
                    cur.i++
                    if (cur.i < n && p[cur.i] == '^') cur.i++
                    classStart = cur.i
                    continue
                }

                '(' -> {
                    if (i + 1 < n && p[i + 1] == '*') {
                        val close = p.indexOf(')', i)
                        return unsupported(if (close < 0) p.substring(i, i + 2) else p.substring(i, close + 1))
                    }
                    commit()
                    if (i + 1 < n && p[i + 1] == '?') {
                        val group = re2Group(p.substring(i + 2))
                        if (group.bad.isNotEmpty()) return unsupported(group.bad)
                        if (group.opens) groups.add(1)
                        cur.i = i + 2 + group.length
                        continue
                    }
                    groups.add(1)
                    cur.i++
                    continue
                }

                ')' -> {
                    commit()
                    if (groups.size > 1) last = groups.removeAt(groups.size - 1)
                    cur.i++
                    continue
                }

                '|' -> {
                    commit()
                    cur.i++
                    continue
                }

                '*', '+', '?' -> {
                    if (i + 1 < n && p[i + 1] == '+') return unsupported(p.substring(i, i + 2))
                    cur.i++
                    if (cur.i < n && p[cur.i] == '?') cur.i++
                    continue
                }

                '{' -> {
                    val bounds = IntArray(2)
                    val end = repeatEnd(p, i, bounds)
                    if (end >= 0) {
                        val min = bounds[0]
                        val max = bounds[1]
                        if (min > 1000 || max > 1000) return tooManyRepeats()
                        if (end < n && p[end] == '+') return unsupported(p.substring(i, end + 1))
                        if (max == 0) {
                            last = 1
                        } else {
                            val m = if (max < 0) min else max
                            if (m > 1) {
                                last *= m
                                if (last > 1000) return tooManyRepeats()
                            }
                        }
                        cur.i = end
                        if (cur.i < n && p[cur.i] == '?') cur.i++
                        continue
                    }
                }
            }
            commit()
            last = 1
            cur.i++
        }
        return ""
    }

    private val MEMORY_UNITS = mapOf(
        "b" to 1uL, "k" to (1uL shl 10), "kb" to (1uL shl 10), "m" to (1uL shl 20), "mb" to (1uL shl 20),
        "g" to (1uL shl 30), "gb" to (1uL shl 30), "t" to (1uL shl 40), "tb" to (1uL shl 40), "p" to (1uL shl 50),
        "pb" to (1uL shl 50), "e" to (1uL shl 60), "eb" to (1uL shl 60),
    )

    private val DURATION_UNIT_VALUES = mapOf(
        "ns" to 1uL, "us" to 1000uL, "µs" to 1000uL, "μs" to 1000uL, "ms" to 1000000uL,
        "s" to 1000000000uL, "m" to 60000000000uL, "h" to 3600000000000uL, "d" to 86400000000000uL,
    )

    private fun base64Char(c: Char, url: Boolean): Boolean {
        if (asciiAlnum(c)) return true
        return if (url) c == '-' || c == '_' else c == '+' || c == '/'
    }

    private fun decodeBase64(text: String, url: Boolean): ByteArray? {
        val padded = StringBuilder(text)
        while (padded.length % 4 != 0) padded.append('=')
        return Base64Strict.decode(padded.toString(), urlSafe = url)
    }

    /** gofrs/uuid v5 codec.go parseBytes. */
    private fun gofrsUuid(input: ByteArray): Boolean {
        var b = input
        when (b.size) {
            32, 36 -> {}
            34, 38 -> {
                if (b.first() != '{'.code.toByte() || b.last() != '}'.code.toByte()) return false
                b = b.copyOfRange(1, b.size - 1)
            }

            41, 45 -> {
                if (!String(b, Charsets.ISO_8859_1).startsWith("urn:uuid:")) return false
                b = b.copyOfRange(9, b.size)
            }

            else -> return false
        }
        for (i in b.indices) {
            val dash = b.size == 36 && (i == 8 || i == 13 || i == 18 || i == 23)
            val c = (b[i].toInt() and 0xFF).toChar()
            if (if (dash) c != '-' else !hexDigit(c)) return false
        }
        return true
    }

    /** Xray-core common/uuid/uuid.go ParseString: trailing bytes after the five groups are ignored. */
    private fun xrayUuid(text: ByteArray): Boolean {
        val l = text.size
        if (l < 32 || l > 36) return l != 0 && l <= 30
        var p = 0
        for (group in intArrayOf(8, 4, 4, 4, 12)) {
            if (p < l && text[p] == '-'.code.toByte()) p++
            if (l - p < group) return false
            for (k in 0 until group) if (!hexDigit((text[p + k].toInt() and 0xFF).toChar())) return false
            p += group
        }
        return true
    }

    private fun httpTokenChar(c: Char) = asciiAlnum(c) || (c.code in 1 until 0x80 && c in "!#$%&'*+-.^_`|~")

    @JvmStatic
    fun hasError(issues: Collection<Issue>): Boolean = issues.any { it.severity == Severity.Error }

    /** Optional '-', decimal digits only, overflow-checked. */
    @JvmStatic
    fun toInt(text: String): Long? = parseLong(text.trim())

    @JvmStatic
    fun required(text: String): String = if (text.isNotBlank()) "" else vtr(R.string.validation_required)

    @JvmStatic
    @JvmOverloads
    fun int(text: String, min: Long, max: Long, empty: Empty = Empty.Allowed): String {
        val t = text.trim()
        if (t.isEmpty()) return emptyMessage(empty)
        val v = parseLong(t) ?: return if (signedDigits(t)) rangeMessage(min, max) else vtr(R.string.validation_whole_number)
        if (v < min || v > max) return rangeMessage(min, max)
        return ""
    }

    @JvmStatic
    @JvmOverloads
    fun port(text: String, empty: Empty = Empty.Required): String {
        val t = text.trim()
        if (t.isEmpty()) return emptyMessage(empty)
        val v = parseLong(t)
        if (v == null || v < 1 || v > 65535) return portMessage()
        return ""
    }

    @JvmStatic
    @JvmOverloads
    fun portRange(text: String, style: PortRangeStyle = PortRangeStyle()): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        val colon = style.sep == RangeSep.Colon
        fun format() = vtr(if (colon) R.string.validation_port_range_colon_format else R.string.validation_port_range_dash_format)
        fun tooLarge() = vtr(R.string.validation_port_range_too_large)
        val at = t.indexOf(if (colon) ':' else '-')
        if (at < 0) {
            if (parseUint16(t) != null) {
                if (style.allowSingle) return ""
                return vtr(if (colon) R.string.validation_port_range_single_colon else R.string.validation_port_range_single_dash, t)
            }
            return if (allDigits(t)) tooLarge() else format()
        }
        // sing-box route/rule/rule_item_port_range.go and sing-quic hysteria ParsePorts: an empty side is 0 or 65535.
        val sides = arrayOf(t.substring(0, at), t.substring(at + 1))
        val values = intArrayOf(0, 65535)
        for (k in 0..1) {
            val side = sides[k]
            if (side.isEmpty()) {
                if (!style.allowOpenEnds) return format()
                continue
            }
            values[k] = parseUint16(side) ?: return if (allDigits(side)) tooLarge() else format()
        }
        if (style.checkOrder && values[0] > values[1]) return vtr(R.string.validation_port_range_order)
        return ""
    }

    /** Only the start > end message (the core accepts such a range, which matches nothing); empty unless the text is a well-formed range. */
    @JvmStatic
    @JvmOverloads
    fun portRangeOrder(text: String, style: PortRangeStyle = PortRangeStyle()): String {
        if (portRange(text, style.copy(checkOrder = false)).isNotEmpty()) return ""
        return portRange(text, style.copy(checkOrder = true))
    }

    /** "n" or "a-b" with a <= b (either order with [allowReversed]), both within [min, max]. */
    @JvmStatic
    @JvmOverloads
    fun intOrRange(text: String, min: Long, max: Long, empty: Empty = Empty.Allowed, allowReversed: Boolean = false): String {
        val t = text.trim()
        if (t.isEmpty()) return emptyMessage(empty)
        val negative = min < 0
        val dash = t.indexOf('-', if (negative && t.startsWith('-')) 1 else 0)
        val a = if (dash < 0) t else t.substring(0, dash)
        val b = if (dash < 0) t else t.substring(dash + 1)
        val values = LongArray(2)
        for ((k, part) in listOf(a, b).withIndex()) {
            if (!signedDigits(part) || (!negative && part.startsWith('-'))) return vtr(R.string.validation_int_or_range_format)
            val v = parseLong(part)
            if (v == null || v < min || v > max) return rangeMessage(min, max)
            values[k] = v
        }
        if (!allowReversed && values[0] > values[1]) return vtr(R.string.validation_int_range_order)
        return ""
    }

    /** sing common/json/badoption/internal/my_time/format.go ParseDuration, on UTF-8 bytes like the Go original. */
    @JvmStatic
    fun parseDurationNs(text: String): Long? {
        val s = text.trim().toByteArray(Charsets.UTF_8)
        val n = s.size
        val kLimit: ULong = 1uL shl 63
        fun digitAt(k: Int) = s[k].toInt() in 48..57
        var p = 0
        var neg = false
        if (p < n && (s[p] == '-'.code.toByte() || s[p] == '+'.code.toByte())) {
            neg = s[p] == '-'.code.toByte()
            p++
        }
        if (n - p == 1 && s[p] == '0'.code.toByte()) return 0
        if (p == n) return null
        var d: ULong = 0uL
        while (p < n) {
            var v: ULong = 0uL
            var f: ULong = 0uL
            var scale = 1.0
            if (!(s[p] == '.'.code.toByte() || digitAt(p))) return null
            val intStart = p
            while (p < n && digitAt(p)) {
                if (v > kLimit / 10uL) return null
                v = v * 10uL + (s[p] - '0'.code.toByte()).toULong()
                if (v > kLimit) return null
                p++
            }
            val pre = p != intStart
            var post = false
            if (p < n && s[p] == '.'.code.toByte()) {
                p++
                val fracStart = p
                var overflow = false
                while (p < n && digitAt(p)) {
                    if (!overflow) {
                        if (f > (kLimit - 1uL) / 10uL) {
                            overflow = true
                        } else {
                            val y = f * 10uL + (s[p] - '0'.code.toByte()).toULong()
                            if (y > kLimit) {
                                overflow = true
                            } else {
                                f = y
                                scale *= 10
                            }
                        }
                    }
                    p++
                }
                post = p != fracStart
            }
            if (!pre && !post) return null
            val unitStart = p
            while (p < n && s[p] != '.'.code.toByte() && !digitAt(p)) p++
            if (p == unitStart) return null
            val unit = DURATION_UNIT_VALUES[String(s, unitStart, p - unitStart, Charsets.UTF_8)] ?: return null
            if (v > kLimit / unit) return null
            v *= unit
            if (f > 0uL) {
                v += (f.toDouble() * (unit.toDouble() / scale)).toULong()
                if (v > kLimit) return null
            }
            d += v
            if (d > kLimit) return null
        }
        if (neg) return if (d == kLimit) Long.MIN_VALUE else -d.toLong()
        if (d > kLimit - 1uL) return null
        return d.toLong()
    }

    @JvmStatic
    @JvmOverloads
    fun duration(text: String, rule: DurationRule = DurationRule()): String {
        val t = text.trim()
        if (t.isEmpty()) return emptyMessage(rule.empty)
        val ns = parseDurationNs(t)
        if (ns == null) {
            val dot = t.indexOf('.')
            val bare = if (dot < 0) allDigits(t) else allDigits(t.substring(0, dot)) && allDigits(t.substring(dot + 1))
            if (bare) return vtr(R.string.validation_duration_unit, t)
            return vtr(R.string.validation_duration_format)
        }
        if (!rule.allowNegative && ns < 0) return vtr(R.string.validation_not_negative)
        if (rule.wholeSeconds && ns % 1000000000L != 0L) return vtr(R.string.validation_whole_seconds)
        if (rule.minNs != null && ns < rule.minNs) return vtr(R.string.validation_at_least, formatDuration(rule.minNs))
        if (rule.maxNs != null && ns > rule.maxNs) return vtr(R.string.validation_at_most, formatDuration(rule.maxNs))
        return ""
    }

    /** sing common/byteformats MemoryBytes; a bare integer is valid only where the builder emits it as a JSON number. */
    @JvmStatic
    @JvmOverloads
    fun memoryBytes(text: String, allowBareInteger: Boolean, empty: Empty = Empty.Allowed): String {
        val t = text.trim()
        if (t.isEmpty()) return emptyMessage(empty)
        fun wholeNumber() = vtr(R.string.validation_memory_whole)
        fun tooLarge() = vtr(R.string.validation_memory_too_large)
        var i = 0
        while (i < t.length && digit(t[i])) i++
        if (i == t.length) {
            if (!allowBareInteger) return vtr(R.string.validation_memory_unit_missing)
            return if (parseLong(t) != null) "" else tooLarge()
        }
        val digitNext = i + 1 < t.length && digit(t[i + 1])
        if (i == 0) {
            if (t[0] == '-' && digitNext) return vtr(R.string.validation_not_negative)
            if (t[0] == '.' && digitNext) return wholeNumber()
            return vtr(R.string.validation_memory_format)
        }
        if ((t[i] == '.' || t[i] == ',') && digitNext) return wholeNumber()
        val value = t.substring(0, i).toULongOrNull() ?: return tooLarge()
        val unit = t.substring(i).trim()
        val unitValue = MEMORY_UNITS[unit.lowercase()] ?: return vtr(R.string.validation_memory_unknown_unit, short(unit))
        if (value > ULong.MAX_VALUE / unitValue) return tooLarge()
        return ""
    }

    /** Go netip rules: four decimal octets without leading zeros, IPv6 without a zone. */
    @JvmStatic
    @JvmOverloads
    fun ip(text: String, family: Family = Family.Any, empty: Empty = Empty.Required): String {
        val t = text.trim()
        if (t.isEmpty()) return emptyMessage(empty)
        return checkIp(parseIp(t), family)
    }

    /** Go netip.ParsePrefix: split at the last '/', no zone, decimal bits without a sign or leading zeros. */
    @JvmStatic
    @JvmOverloads
    fun cidr(text: String, family: Family = Family.Any, allowBareIp: Boolean = false, empty: Empty = Empty.Required): String {
        val t = text.trim()
        if (t.isEmpty()) return emptyMessage(empty)
        fun needPrefix() = vtr(R.string.validation_cidr_prefix)
        val slash = t.lastIndexOf('/')
        if (slash < 0) {
            if (allowBareIp) return checkIp(parseIp(t), family)
            return needPrefix()
        }
        val ip = parseIp(t.substring(0, slash))
        checkIp(ip, family).let { if (it.isNotEmpty()) return it }
        val bits = t.substring(slash + 1)
        if (bits.contains('.')) return vtr(R.string.validation_cidr_netmask)
        if (!allDigits(bits)) return needPrefix()
        if (bits.length > 1 && bits[0] == '0') return vtr(R.string.validation_cidr_leading_zero)
        val maxBits = if (ip.kind == IpKind.V4) 32 else 128
        if (bits.length > 3 || bits.toInt() > maxBits) return vtr(R.string.validation_cidr_bits, maxBits.toString())
        return ""
    }

    /** IP literal (bracketed IPv6 allowed) or DNS name; no scheme, path, port or whitespace. */
    @JvmStatic
    @JvmOverloads
    fun host(text: String, empty: Empty = Empty.Required): String {
        val t = text.trim()
        if (t.isEmpty()) return emptyMessage(empty)
        if (t.contains("://")) return schemeMessage()
        if (t.any { it.isWhitespace() }) return vtr(R.string.validation_no_spaces)
        if (t.any { it == '/' || it == '?' || it == '#' || it == '@' }) return vtr(R.string.validation_host_path)
        fun portOwnField() = vtr(R.string.validation_port_own_field)
        if (t.startsWith('[')) {
            val close = t.indexOf(']')
            if (close < 0 || parseIp(t.substring(1, close)).kind != IpKind.V6) return hostMessage()
            val rest = t.substring(close + 1)
            if (rest.isEmpty()) return ""
            if (rest.startsWith(':') && allDigits(rest.substring(1))) return portOwnField()
            return hostMessage()
        }
        if (parseIp(t).kind != IpKind.Invalid) {
            if (!zoneHasColon(t)) return ""
            return if (allDigits(t.substring(t.lastIndexOf(':') + 1))) portOwnField() else ipMessage(Family.V6)
        }
        val colon = t.lastIndexOf(':')
        if (colon >= 0) {
            if (t.indexOf(':') != colon) return if (ipv6Like(t)) ipMessage(Family.V6) else hostMessage()
            if (allDigits(t.substring(colon + 1))) return portOwnField()
            return hostMessage()
        }
        // Digits and dots only: meant as an IPv4 address, which Go would otherwise resolve as a name and fail.
        if (t.all { digit(it) || it == '.' }) return ipMessage(Family.V4)
        var name = Hosts.toAce(t)
        if (name.endsWith('.')) name = name.dropLast(1)
        if (name.isEmpty() || name.length > 253) return hostMessage()
        for (label in name.split('.')) {
            if (label.isEmpty() || label.length > 63 || label.startsWith('-') || label.endsWith('-')) return hostMessage()
            for (c in label) if (!(c.code < 0x80 && asciiAlnum(c)) && c != '-' && c != '_') return hostMessage()
        }
        return ""
    }

    @JvmStatic
    @JvmOverloads
    fun hostPort(text: String, portRequired: Boolean, empty: Empty = Empty.Required): String {
        val t = text.trim()
        if (t.isEmpty()) return emptyMessage(empty)
        if (t.contains("://")) return schemeMessage()
        fun bracketMessage() = vtr(R.string.validation_ipv6_brackets)
        var port = ""
        var hasPort = false
        if (t.startsWith('[')) {
            val close = t.indexOf(']')
            if (close < 0 || parseIp(t.substring(1, close)).kind != IpKind.V6) return ipMessage(Family.V6)
            val rest = t.substring(close + 1)
            if (rest.isNotEmpty()) {
                if (!rest.startsWith(':')) return hostMessage()
                port = rest.substring(1)
                hasPort = true
            }
        } else {
            if (parseIp(t).kind == IpKind.V6) return if (portRequired || zoneHasColon(t)) bracketMessage() else ""
            var host = t
            val colon = t.lastIndexOf(':')
            if (colon >= 0) {
                host = t.substring(0, colon)
                port = t.substring(colon + 1)
                hasPort = true
                if (host.contains(':')) return if (ipv6Like(host)) bracketMessage() else hostMessage()
            }
            if (host.isEmpty()) return hostMessage()
            host(host, Empty.Required).let { if (it.isNotEmpty()) return it }
        }
        if (!hasPort) return if (portRequired) vtr(R.string.validation_port_after_colon) else ""
        val v = parseLong(port)
        if (v == null || v < 1 || v > 65535) return portMessage()
        return ""
    }

    @JvmStatic
    @JvmOverloads
    fun base64Key(text: String, bytes: Int, urlAlphabet: Boolean = false, padded: Boolean = true, empty: Empty = Empty.Required): String {
        val t = text.trim()
        if (t.isEmpty()) return emptyMessage(empty)
        val remainder = bytes % 3
        val dataChars = bytes / 3 * 4 + (if (remainder != 0) remainder + 1 else 0)
        val total = dataChars + (if (padded && remainder != 0) 3 - remainder else 0)
        var ok = t.length == total
        var i = 0
        while (ok && i < total) {
            ok = if (i < dataChars) base64Char(t[i], urlAlphabet) else t[i] == '='
            i++
        }
        if (ok) ok = decodeBase64(t, urlAlphabet)?.size == bytes
        if (ok) return ""
        val count = bytes.toString()
        val length = total.toString()
        return when {
            urlAlphabet && !padded -> vtr(R.string.validation_key_url_unpadded, count, length)
            urlAlphabet -> vtr(R.string.validation_key_url, count, length)
            !padded -> vtr(R.string.validation_key_unpadded, count, length)
            else -> vtr(R.string.validation_key, count, length)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun base64(text: String, empty: Empty = Empty.Allowed): String {
        val t = text.trim()
        if (t.isEmpty()) return emptyMessage(empty)
        var equals = 0
        while (equals < t.length && t[t.length - 1 - equals] == '=') equals++
        val data = t.length - equals
        var ok = data > 0 && equals <= 2 && (if (equals > 0) t.length % 4 == 0 else t.length % 4 != 1)
        var i = 0
        while (ok && i < data) {
            ok = base64Char(t[i], false)
            i++
        }
        if (ok) ok = decodeBase64(t, false)?.isNotEmpty() == true
        return if (ok) "" else vtr(R.string.validation_base64)
    }

    @JvmStatic
    @JvmOverloads
    fun hex(text: String, minChars: Int, maxChars: Int, evenLength: Boolean = true, empty: Empty = Empty.Allowed): String {
        val t = text.trim()
        if (t.isEmpty()) return emptyMessage(empty)
        if (!t.all(::hexDigit)) return vtr(R.string.validation_hex)
        if (evenLength && t.length % 2 != 0) return vtr(R.string.validation_even_length)
        if (maxChars > 0 && t.length > maxChars) return vtr(R.string.validation_max_chars, maxChars.toString())
        if (minChars > 0 && t.length < minChars) return vtr(R.string.validation_min_chars, minChars.toString())
        return ""
    }

    /** gofrs/uuid FromString: canonical, 32 hex digits, {braced} or urn:uuid: forms. */
    @JvmStatic
    @JvmOverloads
    fun uuid(text: String, empty: Empty = Empty.Required): String {
        val t = text.trim()
        if (t.isEmpty()) return emptyMessage(empty)
        return if (gofrsUuid(t.toByteArray(Charsets.UTF_8))) "" else vtr(R.string.validation_uuid)
    }

    /** Xray uuid.ParseString: a 1-30 byte custom id, or a 32-36 character UUID with optional dashes. */
    @JvmStatic
    @JvmOverloads
    fun xrayId(text: String, empty: Empty = Empty.Required): String {
        val t = text.trim()
        if (t.isEmpty()) return emptyMessage(empty)
        return if (xrayUuid(t.toByteArray(Charsets.UTF_8))) "" else vtr(R.string.validation_xray_id)
    }

    /** Tolerant parsing like QUrl ([LinkParser]); needs a host and a scheme from the list (any scheme when it is empty). */
    @JvmStatic
    @JvmOverloads
    fun url(text: String, schemes: List<String>, empty: Empty = Empty.Allowed): String {
        val t = text.trim()
        if (t.isEmpty()) return emptyMessage(empty)
        if (t.any { it.isWhitespace() }) return vtr(R.string.validation_no_spaces)
        val url = LinkParser.parse(t)
        if (schemes.isNotEmpty() && schemes.none { it.equals(url.scheme, ignoreCase = true) }) {
            val shown = schemes.map { it.lowercase() + "://" }
            val list = if (shown.size == 1) shown[0] else vtr(R.string.validation_list_or, shown.dropLast(1).joinToString(", "), shown.last())
            return vtr(R.string.validation_url_scheme, list)
        }
        val port = urlPort(t)
        if (port.isNotEmpty()) {
            val v = parseLong(port)
            if (v == null || v < 1 || v > 65535) return portMessage()
        }
        if (!url.isValid || url.scheme.isEmpty() || url.host.isEmpty()) return vtr(R.string.validation_url)
        return ""
    }

    @JvmStatic
    @JvmOverloads
    fun oneOf(text: String, allowed: List<String>, empty: Empty = Empty.Allowed, ignoreCase: Boolean = false): String {
        val t = text.trim()
        if (t.isEmpty()) return emptyMessage(empty)
        if (allowed.any { it.equals(t, ignoreCase) }) return ""
        val shown = ArrayList<String>()
        for (value in allowed) {
            if (value.isEmpty()) continue
            if (shown.size == 12) {
                shown.add("…")
                break
            }
            shown.add(value)
        }
        return vtr(R.string.validation_one_of, short(t), shown.joinToString(", "))
    }

    @JvmStatic
    @JvmOverloads
    fun httpToken(text: String, empty: Empty = Empty.Allowed): String {
        val t = text.trim()
        if (t.isEmpty()) return emptyMessage(empty)
        return if (t.all(::httpTokenChar)) "" else vtr(R.string.validation_http_token)
    }

    /** Validity for Android's own regex engine (ICU behind java.util.regex), where the desktop asks PCRE. */
    @JvmStatic
    fun regex(pattern: String): String {
        val t = pattern.trim()
        if (t.isEmpty()) return ""
        return try {
            Pattern.compile(t)
            ""
        } catch (e: PatternSyntaxException) {
            vtr(R.string.validation_regex, e.description ?: e.message.orEmpty())
        }
    }

    /**
     * The engine's validity of a copy rewritten where Go is laxer ([regexCopy]), plus what RE2 refuses. Known misses:
     * Unicode property and POSIX class names the two engines name differently.
     */
    @JvmStatic
    fun goRegex(pattern: String): String {
        val t = pattern.trim()
        regex(regexCopy(t)).let { if (it.isNotEmpty()) return it }
        return re2Unsupported(t)
    }

    @JvmStatic
    @JvmOverloads
    fun jsonObject(text: String, empty: Empty = Empty.Allowed): String {
        if (text.isBlank()) return emptyMessage(empty)
        val result = JsonTree.parse(text)
        if (!result.ok) {
            val lineColumn = JsonTree.lineColumn(text, result.errorSpan.offset)
            return vtr(R.string.validation_json, result.error, lineColumn[0].toString(), lineColumn[1].toString())
        }
        if (result.root?.type != JsonType.Object) return vtr(R.string.validation_json_object)
        return ""
    }

    /** Trimmed, empty items dropped. */
    @JvmStatic
    fun items(text: String, split: Split): List<String> {
        val parts = when (split) {
            Split.Comma -> text.split(',')
            Split.Newline -> text.split('\n')
            Split.CommaOrNewline -> text.replace('\n', ',').split(',')
            Split.Whitespace -> text.trim().split(Regex("\\s+"))
        }
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The first bad item as "Item 2 ("x"): <message>", or "Item 2: <message>" without [echoItem] (for keys and other secrets). */
    @JvmStatic
    @JvmOverloads
    fun eachItem(text: String, split: Split, item: Rule?, empty: Empty = Empty.Allowed, echoItem: Boolean = true): String {
        val items = items(text, split)
        if (items.isEmpty()) return emptyMessage(empty)
        if (item == null) return ""
        for ((i, value) in items.withIndex()) {
            val message = item(value)
            if (message.isEmpty()) continue
            if (!echoItem) return vtr(R.string.validation_item, (i + 1).toString(), message)
            return vtr(R.string.validation_item_echo, (i + 1).toString(), short(value), message)
        }
        return ""
    }

    /** Validation::Rules: the checks as [Rule] values, for lists ([list]) and editors that bind one rule per field. */
    object Rules {
        @JvmStatic
        fun required(): Rule = { Validation.required(it) }

        @JvmStatic
        @JvmOverloads
        fun int(min: Long, max: Long, empty: Empty = Empty.Allowed): Rule = { Validation.int(it, min, max, empty) }

        @JvmStatic
        @JvmOverloads
        fun port(empty: Empty = Empty.Required): Rule = { Validation.port(it, empty) }

        @JvmStatic
        @JvmOverloads
        fun portRange(style: PortRangeStyle = PortRangeStyle()): Rule = { Validation.portRange(it, style) }

        @JvmStatic
        @JvmOverloads
        fun portRangeOrder(style: PortRangeStyle = PortRangeStyle()): Rule = { Validation.portRangeOrder(it, style) }

        @JvmStatic
        @JvmOverloads
        fun intOrRange(min: Long, max: Long, empty: Empty = Empty.Allowed, allowReversed: Boolean = false): Rule =
            { Validation.intOrRange(it, min, max, empty, allowReversed) }

        @JvmStatic
        @JvmOverloads
        fun duration(rule: DurationRule = DurationRule()): Rule = { Validation.duration(it, rule) }

        @JvmStatic
        @JvmOverloads
        fun memoryBytes(allowBareInteger: Boolean, empty: Empty = Empty.Allowed): Rule = { Validation.memoryBytes(it, allowBareInteger, empty) }

        @JvmStatic
        @JvmOverloads
        fun ip(family: Family = Family.Any, empty: Empty = Empty.Required): Rule = { Validation.ip(it, family, empty) }

        @JvmStatic
        @JvmOverloads
        fun cidr(family: Family = Family.Any, allowBareIp: Boolean = false, empty: Empty = Empty.Required): Rule =
            { Validation.cidr(it, family, allowBareIp, empty) }

        @JvmStatic
        @JvmOverloads
        fun host(empty: Empty = Empty.Required): Rule = { Validation.host(it, empty) }

        @JvmStatic
        @JvmOverloads
        fun hostPort(portRequired: Boolean, empty: Empty = Empty.Required): Rule = { Validation.hostPort(it, portRequired, empty) }

        @JvmStatic
        @JvmOverloads
        fun base64Key(bytes: Int, urlAlphabet: Boolean = false, padded: Boolean = true, empty: Empty = Empty.Required): Rule =
            { Validation.base64Key(it, bytes, urlAlphabet, padded, empty) }

        @JvmStatic
        @JvmOverloads
        fun base64(empty: Empty = Empty.Allowed): Rule = { Validation.base64(it, empty) }

        @JvmStatic
        @JvmOverloads
        fun hex(minChars: Int, maxChars: Int, evenLength: Boolean = true, empty: Empty = Empty.Allowed): Rule =
            { Validation.hex(it, minChars, maxChars, evenLength, empty) }

        @JvmStatic
        @JvmOverloads
        fun uuid(empty: Empty = Empty.Required): Rule = { Validation.uuid(it, empty) }

        @JvmStatic
        @JvmOverloads
        fun xrayId(empty: Empty = Empty.Required): Rule = { Validation.xrayId(it, empty) }

        @JvmStatic
        @JvmOverloads
        fun url(schemes: List<String>, empty: Empty = Empty.Allowed): Rule = { Validation.url(it, schemes, empty) }

        @JvmStatic
        @JvmOverloads
        fun oneOf(allowed: List<String>, empty: Empty = Empty.Allowed, ignoreCase: Boolean = false): Rule =
            { Validation.oneOf(it, allowed, empty, ignoreCase) }

        @JvmStatic
        @JvmOverloads
        fun httpToken(empty: Empty = Empty.Allowed): Rule = { Validation.httpToken(it, empty) }

        @JvmStatic
        fun regex(): Rule = { Validation.regex(it) }

        @JvmStatic
        fun goRegex(): Rule = { Validation.goRegex(it) }

        @JvmStatic
        @JvmOverloads
        fun jsonObject(empty: Empty = Empty.Allowed): Rule = { Validation.jsonObject(it, empty) }

        @JvmStatic
        @JvmOverloads
        fun list(split: Split, item: Rule?, empty: Empty = Empty.Allowed, echoItem: Boolean = true): Rule =
            { Validation.eachItem(it, split, item, empty, echoItem) }

        @JvmStatic
        fun allOf(vararg rules: Rule?): Rule = { text ->
            rules.firstNotNullOfOrNull { rule -> rule?.invoke(text)?.takeIf { it.isNotEmpty() } } ?: ""
        }
    }
}
