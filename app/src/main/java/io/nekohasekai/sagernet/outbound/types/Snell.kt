package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.import.ClashProxy
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.SecurityLevel
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.LinkBuilder
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.validation.Empty
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.vtr

/** snell.h snellV6Modes. */
val snellV6Modes = listOf("", "default", "unshaped", "unsafe-raw")

/** snell.h snellNetworks. */
val snellNetworks = listOf("", "tcp", "udp")

/** The Checks of snell.h. */
object SnellChecks {
    /** SnellUserKey: the core counts the user key in UTF-8 bytes. */
    @JvmStatic
    fun snellUserKey(userkey: String): String {
        val bytes = userkey.toByteArray(Charsets.UTF_8).size
        if (bytes <= 255) return ""
        return vtr(R.string.validation_snell_userkey_length, bytes.toString())
    }
}

/** snell (include/configs/outbounds/snell.h, src/configs/outbounds/snell.cpp). */
class Snell : Outbound("snell") {
    @JvmField var version: Int = 4
    @JvmField var psk: String = ""
    @JvmField var userkey: String = ""
    @JvmField var reuse: Boolean = false
    @JvmField var network: String = ""

    /** v4 only */
    @JvmField var obfs_mode: String = ""
    @JvmField var obfs_host: String = ""

    /** v6 only */
    @JvmField var mode: String = ""

    /** snell::ParseFromLink. */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid || url.host.isEmpty()) return false
        val q = url.query
        super.parseFromLink(url)
        psk = url.userName
        if (psk.isEmpty() && q.has("psk")) psk = q.valueFully("psk")

        if (q.has("version")) version = QtStrings.toInt(q.value("version"))
        if (!supportedVersion(version)) return false

        if (q.has("userkey")) userkey = q.valueFully("userkey")
        if (q.has("reuse")) {
            val raw = q.value("reuse")
            reuse = raw.isEmpty() || (raw != "0" && !raw.equals("false", ignoreCase = true))
        }
        if (q.has("network")) network = importNetwork(q.value("network"))
        if (q.has("obfs")) obfs_mode = importObfsMode(q.value("obfs"))
        if (q.has("obfs-host")) obfs_host = q.valueFully("obfs-host").trim()
        if (q.has("mode")) mode = q.value("mode").trim()
        return true
    }

    /** snell::ParseFromClash: legacy entries without a version keep the default; unsupported obfs modes are rejected. */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "snell") return false
        baseParseFromClash(proxy)
        psk = proxy.string("psk")
        val clashVersion = proxy.int("version")
        if (clashVersion > 0) version = clashVersion
        if (!supportedVersion(version)) return false
        val obfsOpts = proxy.obj("obfs-opts")
        obfs_mode = importObfsMode(obfsOpts.string("mode"))
        if (!supportedObfsMode(obfs_mode)) return false
        obfs_host = obfsOpts.string("host")
        if (!proxy.bool("udp")) network = "tcp"
        return true
    }

    /** snell::ParseFromJson: `network` may be a list; an import normalises network, obfs_mode and mode. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "snell") return false
        super.parseFromJson(obj)
        if (obj.contains("version")) version = obj.int("version")
        if (!supportedVersion(version)) return false
        if (obj.contains("psk")) psk = obj.string("psk")
        if (obj.contains("userkey")) userkey = obj.string("userkey")
        if (obj.contains("reuse")) reuse = obj.bool("reuse")
        if (obj.contains("network")) {
            network = if (obj.isArray("network")) {
                importNetwork(obj.array("network").strings().joinToString(","))
            } else {
                obj.string("network")
            }
        }
        if (obj.contains("obfs_mode")) obfs_mode = obj.string("obfs_mode")
        if (obj.contains("obfs_host")) obfs_host = obj.string("obfs_host")
        if (obj.contains("mode")) mode = obj.string("mode")
        if (ParseDiag.active()) {
            network = importNetwork(network)
            obfs_mode = importObfsMode(obfs_mode)
            mode = mode.trim()
        }
        return true
    }

    /** snell.cpp:79-104; the psk goes into the user name (see Mieru.exportToLink for the empty-value rule). */
    override fun exportToLink(): String {
        val url = LinkBuilder("snell")
        if (psk.isNotEmpty()) url.setUserName(psk)
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name

        url.addQueryItem("version", version.toString())
        if (userkey.isNotEmpty()) url.addQueryItem("userkey", userkey)
        if (reuse) url.addQueryItem("reuse", "1")
        if (network.isNotEmpty()) url.addQueryItem("network", network)
        if (version == 6) {
            if (mode.isNotEmpty()) url.addQueryItem("mode", mode)
        } else {
            if (obfs_mode.isNotEmpty()) url.addQueryItem("obfs", obfs_mode)
            if (obfs_host.isNotEmpty()) url.addQueryItem("obfs-host", obfs_host)
        }
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** snell.cpp:106-124. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "snell"
        obj.merge(baseExportToJson())
        writeFields(obj)
        return obj
    }

    /** snell.cpp:126-143. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "snell"
        obj.merge(baseBuild(ctx))
        writeFields(obj)
        return BuildResult(obj)
    }

    // The core unmarshals strictly: a key that belongs to the other version fails the whole outbound.
    private fun writeFields(obj: JsonObject) {
        obj["version"] = version
        if (psk.isNotEmpty()) obj["psk"] = psk
        if (userkey.isNotEmpty()) obj["userkey"] = userkey
        if (reuse) obj["reuse"] = true
        if (network.isNotEmpty()) obj["network"] = network
        if (version == 6) {
            if (mode.isNotEmpty()) obj["mode"] = mode
        } else {
            if (obfs_mode.isNotEmpty()) obj["obfs_mode"] = obfs_mode
            if (obfs_host.isNotEmpty()) obj["obfs_host"] = obfs_host
        }
    }

    /** snell.cpp:145-148. */
    override fun displayType(): String = "Snell"

    /** snell.cpp:150-153. */
    override fun security(): SecurityInfo = SecurityInfo("Encrypted", "", SecurityLevel.Secure)

    /** snell::Validate: only the chosen version's options are built, so the other version's are warnings. */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        issues.addIssue(Severity.Error, "psk", Validation.required(psk))
        issues.addIssue(Severity.Error, "userkey", SnellChecks.snellUserKey(userkey))
        issues.addIssue(Severity.Error, "network", Validation.oneOf(network, snellNetworks), network)
        if (version == 6) {
            issues.addIssue(Severity.Error, "mode", Validation.oneOf(mode, snellV6Modes), mode)
            if ((obfs_mode.isNotEmpty() && !obfs_mode.equals("none", ignoreCase = true)) || obfs_host.isNotEmpty()) {
                issues.addIssue(Severity.Warning, "obfs_mode", vtr(R.string.validation_snell_v6_obfs_ignored))
            }
        } else {
            val obfsMessage = Validation.oneOf(obfs_mode, OBFS_MODES, Empty.Allowed, ignoreCase = true)
            if (obfsMessage.isNotEmpty()) {
                issues.addIssue(Severity.Error, "obfs_mode", obfsMessage, obfs_mode)
            } else {
                val obfs = obfs_mode.trim().lowercase()
                if (obfs_host.isNotBlank() && obfs != "http" && obfs != "tls") {
                    issues.addIssue(Severity.Warning, "obfs_host", vtr(R.string.validation_snell_obfs_host_ignored), obfs_host)
                }
            }
            if (mode.isNotEmpty()) issues.addIssue(Severity.Warning, "mode", vtr(R.string.validation_snell_mode_ignored), mode)
        }
    }

    companion object {
        /** supportedVersion (snell.cpp): the core registers a v4 and a v6 client only; no outbound exists for v1-v3 / v5. */
        @JvmStatic
        fun supportedVersion(version: Int): Boolean {
            if (version == 4 || version == 6) return true
            ParseDiag.error("version", vtr(R.string.validation_snell_version), version.toString())
            return false
        }

        /** snell.h snellObfsModes. */
        @JvmField
        val OBFS_MODES: List<String> = listOf("", "none", "http", "tls")

        /** supportedObfsMode (snell.cpp): Clash also offers shadow-tls / restls / jls obfs, which the core's Snell client lacks. */
        @JvmStatic
        fun supportedObfsMode(mode: String): Boolean {
            if (mode in OBFS_MODES) return true
            ParseDiag.error("obfs", vtr(R.string.validation_snell_obfs_unsupported), mode)
            return false
        }

        /** snellImportNetwork: tcp, udp or a list of them; both means the empty default. */
        private fun importNetwork(raw: String): String {
            val items = Validation.items(raw.lowercase(), Validation.Split.Comma)
            if (items.all { it == "tcp" || it == "udp" }) {
                return if ("tcp" in items && "udp" in items) "" else items.firstOrNull() ?: ""
            }
            ParseDiag.warn("network", vtr(R.string.validation_snell_network_unknown), raw.trim())
            return ""
        }

        /** snellImportObfsMode: the core reads obfuscation modes case-insensitively; the editor lists them in lower case. */
        private fun importObfsMode(raw: String): String = raw.trim().lowercase()
    }
}
