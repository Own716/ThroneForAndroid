package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.common.Tls
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.LinkBuilder
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.vtr

/** The Checks of shadowtls.h. */
object ShadowTlsChecks {
    /** ShadowTlsPassword: the core reads version 0 as 1; v2 and v3 authenticate with the server's password. */
    @JvmStatic
    fun shadowTlsPassword(version: Int, password: String): String {
        if (version < 2 || password.isNotEmpty()) return ""
        return vtr(R.string.validation_shadowtls_password)
    }
}

/** shadowtls (include/configs/outbounds/shadowtls.h, src/configs/outbounds/shadowtls.cpp). */
class ShadowTls : Outbound("shadowtls") {
    @JvmField var version: Int = 1
    @JvmField var password: String = ""
    @JvmField var tls: Tls = Tls()

    override fun hasTls(): Boolean = true
    override fun mustTls(): Boolean = true
    override fun getTls(): Tls = tls

    /** shadowtls::ParseFromLink: the password is the URL password (`shadowtls://:password@host`); a version outside 0-3 refuses the link. */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)
        val raw = q.value("version").trim()
        if (raw.isNotEmpty()) {
            val parsed = Validation.toInt(raw)
            if (parsed == null || parsed < 0 || parsed > 3) {
                ParseDiag.error("version", vtr(R.string.validation_shadowtls_version), raw)
                return false
            }
            version = parsed.toInt()
        }
        password = url.password
        tls.parseFromLink(url)
        tls.enabled = true
        if (serverPort == 0) serverPort = 443
        return server.isNotEmpty()
    }

    /** shadowtls::ParseFromJson: the version as QVariant::toInt, so "2" reads as 2. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "shadowtls") return false
        super.parseFromJson(obj)
        if (obj.contains("version")) version = obj.variantInt("version")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("tls")) tls.parseFromJson(obj.obj("tls"))
        return true
    }

    /** shadowtls.cpp:40-57: `version` is always written. */
    override fun exportToLink(): String {
        val url = LinkBuilder("shadowtls")
        if (version > 1 && password.isNotEmpty()) url.setPassword(password)
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        url.addQueryItem("version", version.toString())
        url.addQueryItems(tls.exportToLink())
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** shadowtls.cpp:59-68. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "shadowtls"
        obj.merge(baseExportToJson())
        obj["version"] = version
        if (version > 1 && password.isNotEmpty()) obj["password"] = password
        if (tls.enabled) obj["tls"] = tls.exportToJson()
        return obj
    }

    /** shadowtls.cpp:70-75. */
    override fun exportIdentity(): JsonObject {
        val obj = super.exportIdentity()
        obj["version"] = version
        return obj
    }

    /** shadowtls.cpp:77-86. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "shadowtls"
        obj.merge(baseBuild(ctx))
        obj["version"] = version
        if (version > 1 && password.isNotEmpty()) obj["password"] = password
        if (tls.enabled) obj["tls"] = tls.build(ctx)
        return BuildResult(obj)
    }

    /** shadowtls.cpp:88-91. */
    override fun displayType(): String = "ShadowTLS"

    /** shadowtls::Validate. */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        if (version < 0 || version > 3) {
            issues.addIssue(Severity.Error, "version", vtr(R.string.validation_shadowtls_version), version.toString())
        } else {
            issues.addIssue(Severity.Error, "password", ShadowTlsChecks.shadowTlsPassword(version, password))
        }
    }
}
