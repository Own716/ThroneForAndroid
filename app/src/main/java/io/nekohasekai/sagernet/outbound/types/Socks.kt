package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.import.ClashProxy
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.Base64Strict
import io.nekohasekai.sagernet.outbound.link.LinkBuilder
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.vtr

/** The Checks of socks.h. */
object SocksChecks {
    /** SOCKS4 sends only a user ID, and SOCKS5 sends the password only together with a user name. */
    @JvmStatic
    fun socksAuth(version: Int, username: String, password: String): String {
        if (password.isEmpty()) return ""
        if (version == 4) return vtr(R.string.validation_socks_v4_password)
        if (username.isEmpty()) return vtr(R.string.validation_socks_username_missing)
        return ""
    }
}

/** socks (include/configs/outbounds/socks.h, src/configs/outbounds/socks.cpp). */
class Socks : Outbound("socks") {
    @JvmField var username: String = ""
    @JvmField var password: String = ""
    @JvmField var version: Int = 5
    /** With version 4: SOCKS4a, the server resolves host names (plain SOCKS4 makes the core resolve them). */
    @JvmField var socks4a: Boolean = false
    @JvmField var uot: Boolean = false

    /** parseSocksVersion (socks.cpp): build emits SOCKS5 for anything but 4, which is also the core's default. */
    private fun parseVersion(text: String) {
        val value = text.trim().lowercase()
        socks4a = value == "4a"
        version = if (socks4a || value == "4") 4 else 5
        if (value.isNotEmpty() && version == 5 && value != "5") {
            ParseDiag.warn("version", vtr(R.string.validation_socks_version_unknown), text.trim())
        }
    }

    /** socks::ParseFromLink (socks.cpp). */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)
        if (q.has("version")) {
            parseVersion(q.value("version"))
        } else {
            if (url.scheme == "socks4" || url.scheme == "socks4a") version = 4
            if (url.scheme == "socks5") version = 5
            socks4a = url.scheme == "socks4a"
        }
        if (url.password.isNotEmpty() || url.userName.isNotEmpty()) {
            username = url.userName
            password = url.password
            // v2rayN base64-encodes "user:pass" into the username; a plain name like "user" is valid base64 too
            if (password.isEmpty() && username.isNotEmpty()) {
                val decoded = Base64Strict.decodeToString(username)
                if (decoded.contains(':')) {
                    username = QtStrings.substrBefore(decoded, ":")
                    password = QtStrings.substrAfter(decoded, ":")
                }
            }
        }
        if (q.has("uot")) uot = q.value("uot") == "true" || QtStrings.toInt(q.value("uot")) > 0
        if (serverPort == 0) serverPort = 1080
        return server.isNotEmpty()
    }

    /** socks::ParseFromClash (socks.cpp). */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "socks5") return false
        baseParseFromClash(proxy)
        username = proxy.string("username")
        password = proxy.string("password")
        return true
    }

    /** socks::ParseFromJson (socks.cpp): `version` is a number or the "4" / "4a" text [exportToJson] writes. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "socks") return false
        super.parseFromJson(obj)
        if (obj.contains("username")) username = obj.string("username")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("version")) parseVersion(obj.variantString("version"))
        if (obj.contains("uot")) uot = obj.bool("uot")
        if (obj.contains("udp_over_tcp")) {
            if (obj.isBool("udp_over_tcp")) uot = obj.bool("udp_over_tcp")
            if (obj.isObject("udp_over_tcp")) uot = obj.obj("udp_over_tcp").bool("enabled")
        }
        return true
    }

    /** socks::ExportToLink (socks.cpp). */
    override fun exportToLink(): String {
        val url = LinkBuilder(if (version != 4) "socks5" else if (socks4a) "socks4a" else "socks4")
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        if (username.isNotEmpty()) url.setUserName(username)
        if (password.isNotEmpty()) url.setPassword(password)
        if (uot) url.addQueryItem("uot", "1")
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** socks::ExportToJson (socks.cpp). */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "socks"
        obj.merge(baseExportToJson())
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password
        if (version == 4) obj["version"] = if (socks4a) "4a" else "4"
        if (uot) obj["uot"] = uot
        return obj
    }

    /** socks::Build (socks.cpp): the core's UoT option is udp_over_tcp. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "socks"
        obj.merge(baseBuild(ctx))
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password
        if (version == 4) obj["version"] = if (socks4a) "4a" else "4"
        if (uot) obj["udp_over_tcp"] = true
        return BuildResult(obj)
    }

    /** socks::DisplayType (socks.cpp). */
    override fun displayType(): String = "Socks"

    /** socks::Validate (socks.cpp). */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        issues.addIssue(Severity.Warning, "password", SocksChecks.socksAuth(version, username, password))
    }
}
