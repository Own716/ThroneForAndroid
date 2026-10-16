package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.import.ClashProxy
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.SecurityLevel
import io.nekohasekai.sagernet.outbound.common.Multiplex
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.Base64Strict
import io.nekohasekai.sagernet.outbound.link.LinkBuilder
import io.nekohasekai.sagernet.outbound.link.LinkCodec
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.outbound.link.ParsedLink
import io.nekohasekai.sagernet.validation.Empty
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.vtr

/** shadowsocksMethods (shadowsocks.h). */
val shadowsocksMethods = listOf(
    "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305", "none", "aes-128-gcm",
    "aes-192-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305", "aes-128-ctr", "aes-192-ctr",
    "aes-256-ctr", "aes-128-cfb", "aes-192-cfb", "aes-256-cfb", "rc4-md5", "chacha20-ietf", "xchacha20",
)

/** shadowsocksPlugins (shadowsocks.h): the SIP003 plugins the core registers. */
val shadowsocksPlugins = listOf("", "obfs-local", "v2ray-plugin")

/** Share links use either base64 alphabet. */
private fun shadowsocksDecodeBase64(text: String): String =
    Base64Strict.decodeToString(text, urlSafe = true).ifEmpty { Base64Strict.decodeToString(text) }

/** The core matches method names exactly; links and subscriptions often upper-case them. */
private fun shadowsocksMethodName(method: String): String = method.trim().lowercase()

/** simple-obfs and Clash's obfs are the obfs-local plugin the core registers. */
private fun shadowsocksPluginName(plugin: String): String {
    val name = plugin.trim()
    return if (name == "simple-obfs" || name == "obfs") "obfs-local" else name
}

/** The Checks of shadowsocks.h. */
object ShadowsocksChecks {

    /** sing-box transport/sip003/args.go ParsePluginOptions: ';'-separated key[=value], backslash escapes, first value wins. */
    private fun pluginArgs(opts: String, args: MutableMap<String, String>): String {
        val n = opts.length
        var i = 0
        fun read(terms: String, out: StringBuilder): Boolean {
            while (i < n && opts[i] !in terms) {
                if (opts[i] == '\\' && ++i == n) return false
                out.append(opts[i])
                i++
            }
            return true
        }

        fun dangling() = vtr(R.string.validation_ss_plugin_opts_dangling)
        while (i < n) {
            val key = StringBuilder()
            var value = "1"
            if (!read("=;", key)) return dangling()
            if (key.isEmpty()) return vtr(R.string.validation_ss_plugin_opts_name)
            if (i < n && opts[i] == '=') {
                i++
                val out = StringBuilder()
                if (!read(";", out)) return dangling()
                value = out.toString()
            }
            i++
            if (!args.containsKey(key.toString())) args[key.toString()] = value
        }
        return ""
    }

    /** strconv.Atoi: one optional + or - sign, then decimal digits, no spaces. */
    private fun atoi(text: String): Boolean {
        val plus = text.startsWith('+')
        val rest = if (plus) text.substring(1) else text
        return rest == rest.trim() && !(plus && rest.startsWith('-')) && Validation.toInt(rest) != null
    }

    private fun keyMessage(key: String, bytes: Int): String {
        // The core decodes the key as written, so a space anywhere breaks it.
        if (key.any { it.isWhitespace() }) return vtr(R.string.validation_no_spaces)
        return Validation.base64Key(key, bytes)
    }

    /** 2022 methods take ':'-separated base64 keys of the cipher's size; every other method but none needs a password. */
    @JvmStatic
    fun shadowsocksPassword(method: String, password: String): String {
        if (method == "none") return ""
        val aes128 = method == "2022-blake3-aes-128-gcm"
        val chacha = method == "2022-blake3-chacha20-poly1305"
        if (!aes128 && !chacha && method != "2022-blake3-aes-256-gcm") return if (password.isEmpty()) Validation.required(password) else ""
        val keys = password.split(':')
        if (chacha && keys.size > 1) return vtr(R.string.validation_ss_chacha_single_key)
        for ((i, key) in keys.withIndex()) {
            val message = keyMessage(key, if (aes128) 16 else 32)
            if (message.isEmpty()) continue
            if (keys.size == 1) return message
            return vtr(R.string.validation_item, (i + 1).toString(), message)
        }
        return ""
    }

    /** plugin_opts as the core's SIP003 parser reads them, plus the modes obfs-local and v2ray-plugin accept. */
    @JvmStatic
    fun shadowsocksPluginOpts(plugin: String, opts: String): String {
        if (plugin.isEmpty()) return ""
        val args = HashMap<String, String>()
        pluginArgs(opts, args).let { if (it.isNotEmpty()) return it }
        if (plugin == "obfs-local") {
            val mode = args["obfs"] ?: "http"
            if (mode != "http" && mode != "tls") return vtr(R.string.validation_ss_obfs_mode, mode)
        } else if (plugin == "v2ray-plugin") {
            val mode = args["mode"] ?: "websocket"
            if (mode != "websocket" && mode != "quic") return vtr(R.string.validation_ss_v2ray_mode, mode)
            val mux = args["mux"]
            if (mode == "websocket" && mux != null && !atoi(mux)) return vtr(R.string.validation_ss_v2ray_mux)
        }
        return ""
    }
}

/** shadowsocks (include/configs/outbounds/shadowsocks.h, src/configs/outbounds/shadowsocks.cpp). */
class Shadowsocks : Outbound("shadowsocks") {
    @JvmField var method: String = ""
    @JvmField var password: String = ""
    @JvmField var plugin: String = ""
    @JvmField var plugin_opts: String = ""
    @JvmField var uot: Boolean = false
    @JvmField var multiplex: Multiplex = Multiplex()

    override fun hasMux(): Boolean = true
    override fun getMux(): Multiplex = multiplex

    /** shadowsocks::ParseFromLink (shadowsocks.cpp): method and password are left to [validate]. */
    override fun parseFromLink(link: String): Boolean {
        val url: ParsedLink
        if (QtStrings.substrBefore(link, "#").contains("@")) {
            url = LinkParser.parse(link)
        } else {
            // v2rayN format: the whole URL after the scheme is base64
            var linkN = shadowsocksDecodeBase64(QtStrings.substrBefore(QtStrings.substrAfter(link, "://"), "#"))
            if (linkN.isEmpty()) {
                ParseDiag.error("userinfo", vtr(R.string.validation_ss_link_format))
                return false
            }
            if (link.contains("#")) linkN += "#" + QtStrings.substrAfter(link, "#")
            url = LinkParser.parse("https://$linkN")
        }
        if (!url.isValid) return false
        val q = url.query
        // the desktop re-parses url.toString(), which round-trips to the same components
        super.parseFromLink(url)

        // a percent-encoded "method%3Apassword" (SIP002 plain form) is split after decoding; base64 never holds a ':'
        val (linkMethod, linkPassword) = url.userInfoPair()
        if (linkPassword.isEmpty()) {
            // traditional format: "method:password" base64 in the username
            val methodPassword = shadowsocksDecodeBase64(url.userName)
            if (methodPassword.isEmpty()) {
                ParseDiag.error("userinfo", vtr(R.string.validation_ss_userinfo_base64))
                return false
            }
            // without the colon the decoded text may be the bare password, which validate would echo as the method
            if (!methodPassword.contains(':')) {
                ParseDiag.error("userinfo", vtr(R.string.validation_ss_userinfo_format))
                return false
            }
            method = QtStrings.substrBefore(methodPassword, ":")
            password = QtStrings.substrAfter(methodPassword, ":")
        } else {
            method = linkMethod
            password = linkPassword
        }
        method = shadowsocksMethodName(method)

        val pluginSpec = q.valueFully("plugin")
        plugin = shadowsocksPluginName(QtStrings.substrBefore(pluginSpec, ";"))
        plugin_opts = if (pluginSpec.contains(';')) QtStrings.substrAfter(pluginSpec, ";").trim() else ""
        if (q.has("plugin-opts")) plugin_opts = q.valueFully("plugin-opts").trim()
        if (q.has("uot")) uot = q.value("uot") == "true" || QtStrings.toInt(q.value("uot")) > 0
        // the desktop hands the original text to the multiplex parser (not a valid URL in the v2rayN form)
        multiplex.parseFromLink(link)

        return server.isNotEmpty()
    }

    /** shadowsocks::ParseFromClash (shadowsocks.cpp): the v2ray-plugin and obfs option blocks become SIP003 option strings. */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "ss") return false
        baseParseFromClash(proxy)
        method = shadowsocksMethodName(proxy.string("cipher"))
        password = proxy.string("password")
        uot = proxy.bool("udp-over-tcp")
        val clashPlugin = proxy.string("plugin")
        if (clashPlugin.isNotEmpty()) {
            val opts = proxy.obj("plugin-opts")
            if (clashPlugin != "v2ray-plugin" && clashPlugin != "obfs") {
                ParseDiag.error("plugin", vtr(R.string.validation_ss_clash_plugin), clashPlugin)
            } else if (clashPlugin == "v2ray-plugin") {
                plugin = "v2ray-plugin"
                val items = ArrayList<String>()
                if (opts.bool("tls")) items.add("tls")
                opts.string("host").takeIf { it.isNotEmpty() }?.let { items.add("host=$it") }
                opts.string("path").takeIf { it.isNotEmpty() }?.let { items.add("path=$it") }
                opts.string("mode").takeIf { it.isNotEmpty() }?.let { items.add("mode=$it") }
                if (opts.bool("mux")) items.add("mux")
                plugin_opts = items.joinToString(";")
            } else {
                plugin = "obfs-local"
                val items = ArrayList<String>()
                opts.string("mode").takeIf { it.isNotEmpty() }?.let { items.add("obfs=$it") }
                opts.string("host").takeIf { it.isNotEmpty() }?.let { items.add("obfs-host=$it") }
                plugin_opts = items.joinToString(";")
            }
        }
        multiplex.parseFromClash(proxy)
        return true
    }

    /** shadowsocks::ParseFromJson (shadowsocks.cpp): also the DB loader, so only an import normalizes the names. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "shadowsocks") return false
        super.parseFromJson(obj)
        if (obj.contains("method")) method = obj.string("method")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("plugin")) plugin = obj.string("plugin")
        if (obj.contains("plugin_opts")) plugin_opts = obj.string("plugin_opts")
        if (ParseDiag.active()) {
            method = shadowsocksMethodName(method)
            plugin = shadowsocksPluginName(plugin)
        }
        if (obj.contains("udp_over_tcp")) {
            if (obj.isBool("udp_over_tcp")) uot = obj.bool("udp_over_tcp")
            if (obj.isObject("udp_over_tcp")) uot = obj.obj("udp_over_tcp").bool("enabled")
        }
        if (obj.contains("multiplex")) multiplex.parseFromJson(obj.obj("multiplex"))
        return true
    }

    /** shadowsocks::ParseFromSIP008 (shadowsocks.cpp): one entry of a SIP008 subscription document. */
    fun parseFromSip008(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        super.parseFromJson(obj)
        if (obj.contains("remarks")) name = obj.string("remarks")
        if (obj.contains("method")) method = shadowsocksMethodName(obj.string("method"))
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("plugin")) plugin = shadowsocksPluginName(obj.string("plugin"))
        if (obj.contains("plugin_opts")) plugin_opts = obj.string("plugin_opts").trim()
        if (obj.contains("uot")) {
            if (obj.isBool("uot")) uot = obj.bool("uot")
            if (obj.isObject("uot")) uot = obj.obj("uot").bool("enabled")
        }
        if (obj.contains("multiplex")) multiplex.parseFromJson(obj.obj("multiplex"))
        return server.isNotEmpty()
    }

    /** shadowsocks::ExportToLink (shadowsocks.cpp). */
    override fun exportToLink(): String {
        val url = LinkBuilder("ss")
        if (method.startsWith("2022-")) {
            url.setUserName(method)
            if (password.isNotEmpty()) url.setPassword(password)
        } else {
            // legacy SIP002 user-info: base64url WITH padding
            url.setUserName(Base64Strict.encode("$method:$password", urlSafe = true, padding = true))
        }
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        if (plugin.isNotEmpty()) {
            var pluginString = plugin
            if (plugin_opts.isNotEmpty()) pluginString += ";$plugin_opts"
            url.addQueryItemPercentEncoded("plugin", LinkCodec.percentEncodeAll(pluginString))
        }
        if (uot) url.addQueryItem("uot", "1")
        url.addQueryItems(multiplex.exportToLink())
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** shadowsocks::ExportToJson (shadowsocks.cpp). */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "shadowsocks"
        obj.merge(baseExportToJson())
        if (method.isNotEmpty()) obj["method"] = method
        if (password.isNotEmpty()) obj["password"] = password
        if (plugin.isNotEmpty()) obj["plugin"] = plugin
        if (plugin_opts.isNotEmpty()) obj["plugin_opts"] = plugin_opts
        if (uot) obj["udp_over_tcp"] = uot
        val muxObj = multiplex.exportToJson()
        if (muxObj.isNotEmpty()) obj["multiplex"] = muxObj
        return obj
    }

    /** shadowsocks::Build (shadowsocks.cpp): a "name;opts" plugin is split into the two fields first (mutating the profile). */
    override fun build(ctx: BuildContext): BuildResult {
        if (plugin.contains(";")) {
            plugin_opts = QtStrings.substrAfter(plugin, ";")
            plugin = QtStrings.substrBefore(plugin, ";")
        }
        val obj = JsonObject()
        obj["type"] = "shadowsocks"
        obj.merge(baseBuild(ctx))
        if (method.isNotEmpty()) obj["method"] = method
        if (password.isNotEmpty()) obj["password"] = password
        if (plugin.isNotEmpty()) obj["plugin"] = plugin
        if (plugin_opts.isNotEmpty()) obj["plugin_opts"] = plugin_opts
        if (uot) obj["udp_over_tcp"] = uot
        val muxObj = multiplex.build(ctx)
        if (muxObj.isNotEmpty()) obj["multiplex"] = muxObj
        return BuildResult(obj)
    }

    /** shadowsocks::DisplayType (shadowsocks.cpp). */
    override fun displayType(): String = "Shadowsocks"

    /** shadowsocks::Validate (shadowsocks.cpp). */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        val methodMessage = Validation.oneOf(method, shadowsocksMethods, Empty.Required)
        if (methodMessage.isNotEmpty()) {
            issues.addIssue(Severity.Error, "method", methodMessage, method)
        } else {
            issues.addIssue(Severity.Error, "password", ShadowsocksChecks.shadowsocksPassword(method, password))
        }
        // build() still splits a legacy "name;opts" plugin value
        val pluginName = QtStrings.substrBefore(plugin, ";")
        val opts = if (plugin.contains(';')) QtStrings.substrAfter(plugin, ";") else plugin_opts
        val pluginMessage = Validation.oneOf(pluginName, shadowsocksPlugins)
        if (pluginMessage.isNotEmpty()) {
            issues.addIssue(Severity.Error, "plugin", pluginMessage, pluginName)
        } else {
            issues.addIssue(Severity.Error, "plugin_opts", ShadowsocksChecks.shadowsocksPluginOpts(pluginName, opts), opts)
        }
    }

    /** shadowsocks::GetSecurity (shadowsocks.cpp). */
    override fun security(): SecurityInfo {
        if (method.isEmpty() || method == "none") return SecurityInfo("Raw", "", SecurityLevel.None)
        if (method in STREAM_CIPHERS) return SecurityInfo("Weak Cipher", "", SecurityLevel.Weak)
        return SecurityInfo("Encrypted", "", SecurityLevel.Secure)
    }

    companion object {
        // pre-AEAD stream ciphers: no integrity, trivially detectable
        private val STREAM_CIPHERS = setOf(
            "aes-128-ctr", "aes-192-ctr", "aes-256-ctr", "aes-128-cfb", "aes-192-cfb", "aes-256-cfb",
            "rc4-md5", "chacha20-ietf", "xchacha20",
        )
    }
}
