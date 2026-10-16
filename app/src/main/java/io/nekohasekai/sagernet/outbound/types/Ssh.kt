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
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.link.Base64Strict
import io.nekohasekai.sagernet.outbound.link.LinkBuilder
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.vtr

/** sing-box listables also take a bare string as one item. */
private fun sshListableItems(obj: JsonObject, key: String): MutableList<String> =
    if (obj.isString(key)) mutableListOf(obj.string(key)) else obj.array(key).strings()

/** Older editor saves split on ',' untrimmed, so a trailing comma stored an empty item the core rejects. */
private fun sshNonBlankItems(obj: JsonObject, key: String): MutableList<String> =
    sshListableItems(obj, key).map { it.trim() }.filterTo(ArrayList()) { it.isNotEmpty() }

/** The algorithm name a wire-format public key starts with, or "" when the text is not padded base64 of one. */
private fun sshKeyBlobType(text: String): String {
    if (text.isEmpty() || text.length % 4 != 0) return ""
    val blob = Base64Strict.decode(text) ?: return ""
    if (blob.size < 4) return ""
    var length = 0L
    for (k in 0 until 4) length = (length shl 8) or (blob[k].toLong() and 0xFFL)
    if (length == 0L || length > blob.size - 4) return ""
    return String(blob, 4, length.toInt(), Charsets.ISO_8859_1)
}

/** The link carries each list item as its own base64 text, joined by '-'. */
private fun sshLinkItems(raw: String, field: String): List<String> {
    val items = ArrayList<String>()
    for (item in QtStrings.splitSkipEmpty(raw, "-")) {
        // exportToLink omits the padding
        val padding = (4 - item.toByteArray(Charsets.UTF_8).size % 4) % 4
        val decoded = Base64Strict.decode(item + "=".repeat(padding))
        if (decoded != null && decoded.isNotEmpty()) {
            items.add(String(decoded, Charsets.UTF_8).trim())
        } else {
            ParseDiag.warn(field, vtr(R.string.validation_ssh_link_item_base64), item)
        }
    }
    return items
}

/** The Checks of ssh.h. */
object SshChecks {
    private val PUBLIC_KEY = Regex("^(\\S+\\s+)?(ssh-|ecdsa-sha2-|sk-)\\S+\\s+AAAA")

    /** x/crypto ssh.ParsePrivateKey reads PEM blocks: RSA, EC, DSA, PKCS#8 and OpenSSH private keys. */
    @JvmStatic
    fun sshPrivateKey(text: String): String {
        val key = text.trim()
        if (key.isEmpty() || (key.contains("-----BEGIN ") && key.contains("PRIVATE KEY-----"))) return ""
        if (PUBLIC_KEY.containsMatchIn(key)) return vtr(R.string.validation_ssh_public_key)
        if (key.startsWith("PuTTY-User-Key-File")) return vtr(R.string.validation_ssh_putty_key)
        return vtr(R.string.validation_ssh_private_key)
    }

    /**
     * One authorized_keys line: [options] type base64 [comment], the type matching the one inside the key
     * (x/crypto ssh.ParseAuthorizedKey, after at most one options field, a known_hosts host list too).
     */
    @JvmStatic
    fun sshHostKey(item: String): String {
        val line = item.trim()
        fun typed(text: String): Boolean {
            val fields = text.trim().split(Regex("\\s+"))
            return fields.size >= 2 && sshKeyBlobType(fields[1]) == fields[0]
        }
        if (typed(line)) return ""
        var quoted = false
        var end = 0
        while (end < line.length) {
            val c = line[end]
            if (!quoted && (c == ' ' || c == '\t')) break
            if (c == '"' && (end == 0 || line[end - 1] != '\\')) quoted = !quoted
            end++
        }
        if (end < line.length && typed(line.substring(end))) return ""
        return vtr(R.string.validation_ssh_host_key)
    }

    /** A warning: servers refuse a version line without the SSH-2.0- prefix. */
    @JvmStatic
    fun sshClientVersion(text: String): String {
        val version = text.trim()
        if (version.isEmpty() || version.startsWith("SSH-2.0-")) return ""
        return vtr(R.string.validation_ssh_client_version)
    }

    /** A warning: the core still connects, trying the "none" method. */
    @JvmStatic
    fun sshAuthentication(password: String, privateKey: String, privateKeyPath: String): String {
        if (password.isNotEmpty() || privateKey.isNotBlank() || privateKeyPath.isNotBlank()) return ""
        return vtr(R.string.validation_ssh_no_auth)
    }

    /** A warning: the core reads the inline key and ignores the file. */
    @JvmStatic
    fun sshKeySources(privateKey: String, privateKeyPath: String): String {
        if (privateKey.isBlank() || privateKeyPath.isBlank()) return ""
        return vtr(R.string.validation_ssh_key_sources)
    }
}

/** ssh (include/configs/outbounds/ssh.h, src/configs/outbounds/ssh.cpp). */
class Ssh : Outbound("ssh") {
    @JvmField var user: String = ""
    @JvmField var password: String = ""
    @JvmField var private_key: String = ""
    @JvmField var private_key_path: String = ""
    @JvmField var private_key_passphrase: String = ""
    @JvmField var host_key: MutableList<String> = ArrayList()
    @JvmField var host_key_algorithms: MutableList<String> = ArrayList()
    @JvmField var client_version: String = ""

    /** The core dials port 22 when none is set. */
    override fun serverPortOptional(): Boolean = true

    /**
     * ssh::ParseFromLink (ssh.cpp): no default port; keys travel base64-encoded (`-` separates list items), user and
     * password come from the query or else the standard ssh://user:password@host user-info.
     */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)
        if (q.has("user")) user = q.valueFully("user")
        if (q.has("password")) password = q.valueFully("password")
        val privateKeyB64 = q.value("private_key")
        if (privateKeyB64.isNotEmpty()) private_key = String(Base64Strict.decodeLenient(privateKeyB64), Charsets.UTF_8)
        if (q.has("private_key_path")) private_key_path = q.valueFully("private_key_path")
        if (q.has("private_key_passphrase")) private_key_passphrase = q.valueFully("private_key_passphrase")
        host_key.addAll(sshLinkItems(q.value("host_key"), "host_key"))
        host_key_algorithms.addAll(sshLinkItems(q.value("host_key_algorithms"), "host_key_algorithms"))
        if (q.has("client_version")) client_version = q.valueFully("client_version").trim()
        if (!q.has("user") && url.userName.isNotEmpty()) user = url.userName
        if (!q.has("password") && url.password.isNotEmpty()) password = url.password
        return server.isNotEmpty()
    }

    /** ssh::ParseFromClash (ssh.cpp). */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "ssh") return false
        baseParseFromClash(proxy)
        user = proxy.string("username")
        password = proxy.string("password")
        val key = proxy.string("private-key")
        if (key.isNotEmpty()) private_key = key
        val passphrase = proxy.string("private-key-passphrase")
        if (passphrase.isNotEmpty()) private_key_passphrase = passphrase
        proxy.strings("host-key").map { it.trim() }.filterTo(host_key) { it.isNotEmpty() }
        proxy.strings("host-key-algorithms").map { it.trim() }.filterTo(host_key_algorithms) { it.isNotEmpty() }
        return true
    }

    /** ssh::ParseFromJson (ssh.cpp): the core joins private_key lines with '\n'. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "ssh") return false
        super.parseFromJson(obj)
        if (obj.contains("user")) user = obj.string("user")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("private_key")) private_key = sshListableItems(obj, "private_key").joinToString("\n")
        if (obj.contains("private_key_path")) private_key_path = obj.string("private_key_path")
        if (obj.contains("private_key_passphrase")) private_key_passphrase = obj.string("private_key_passphrase")
        if (obj.contains("host_key")) host_key = sshNonBlankItems(obj, "host_key")
        if (obj.contains("host_key_algorithms")) host_key_algorithms = sshNonBlankItems(obj, "host_key_algorithms")
        if (obj.contains("client_version")) client_version = obj.string("client_version")
        return true
    }

    /** ssh::ExportToLink (ssh.cpp). */
    override fun exportToLink(): String {
        val url = LinkBuilder("ssh")
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        if (user.isNotEmpty()) url.addQueryItem("user", user)
        if (password.isNotEmpty()) url.addQueryItem("password", password)
        if (private_key.isNotEmpty()) url.addQueryItem("private_key", Base64Strict.encode(private_key, padding = false))
        if (private_key_path.isNotEmpty()) url.addQueryItem("private_key_path", private_key_path)
        if (private_key_passphrase.isNotEmpty()) url.addQueryItem("private_key_passphrase", private_key_passphrase)
        if (host_key.isNotEmpty()) url.addQueryItem("host_key", host_key.joinToString("-") { Base64Strict.encode(it, padding = false) })
        if (host_key_algorithms.isNotEmpty()) url.addQueryItem("host_key_algorithms", host_key_algorithms.joinToString("-") { Base64Strict.encode(it, padding = false) })
        if (client_version.isNotEmpty()) url.addQueryItem("client_version", client_version)
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** ssh::ExportToJson (ssh.cpp). */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "ssh"
        obj.merge(baseExportToJson())
        if (user.isNotEmpty()) obj["user"] = user
        if (password.isNotEmpty()) obj["password"] = password
        if (private_key.isNotEmpty()) obj["private_key"] = private_key
        if (private_key_path.isNotEmpty()) obj["private_key_path"] = private_key_path
        if (private_key_passphrase.isNotEmpty()) obj["private_key_passphrase"] = private_key_passphrase
        if (host_key.isNotEmpty()) obj["host_key"] = JsonValues.stringArray(host_key)
        if (host_key_algorithms.isNotEmpty()) obj["host_key_algorithms"] = JsonValues.stringArray(host_key_algorithms)
        if (client_version.isNotEmpty()) obj["client_version"] = client_version
        return obj
    }

    /** ssh::Build (ssh.cpp). */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "ssh"
        obj.merge(baseBuild(ctx))
        if (user.isNotEmpty()) obj["user"] = user
        if (password.isNotEmpty()) obj["password"] = password
        if (private_key.isNotEmpty()) obj["private_key"] = private_key
        if (private_key_path.isNotEmpty()) obj["private_key_path"] = private_key_path
        if (private_key_passphrase.isNotEmpty()) obj["private_key_passphrase"] = private_key_passphrase
        if (host_key.isNotEmpty()) obj["host_key"] = JsonValues.stringArray(host_key)
        if (host_key_algorithms.isNotEmpty()) obj["host_key_algorithms"] = JsonValues.stringArray(host_key_algorithms)
        if (client_version.isNotEmpty()) obj["client_version"] = client_version
        return BuildResult(obj)
    }

    /** ssh::DisplayType (ssh.cpp). */
    override fun displayType(): String = "SSH"

    /** ssh::GetSecurity (ssh.cpp): without a host key the core accepts whatever key the server presents. */
    override fun security(): SecurityInfo {
        if (host_key.isEmpty()) return SecurityInfo("Unverified Host Key", "", SecurityLevel.Weak)
        return SecurityInfo("Encrypted", "", SecurityLevel.Secure)
    }

    /** ssh::Validate (ssh.cpp): keys and passwords are never echoed; host keys are public. */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        issues.addIssue(Severity.Warning, "auth", SshChecks.sshAuthentication(password, private_key, private_key_path))
        issues.addIssue(Severity.Error, "private_key", SshChecks.sshPrivateKey(private_key))
        issues.addIssue(Severity.Warning, "private_key", SshChecks.sshKeySources(private_key, private_key_path))
        for (key in host_key) {
            val message = SshChecks.sshHostKey(key)
            if (message.isNotEmpty()) {
                issues.addIssue(Severity.Error, "host_key", message, key)
                break
            }
        }
        issues.addIssue(Severity.Warning, "client_version", SshChecks.sshClientVersion(client_version), client_version)
    }
}
