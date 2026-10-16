package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.SecurityLevel
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.validation.CommonChecks
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Rule
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.acceptOptional
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.vtr
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader

/** Import only: an optional value the core would refuse is dropped with a warning; DB loads and clones keep it. */
private fun ocReadChecked(obj: JsonObject, key: String, current: String, rule: Rule): String {
    if (!obj.contains(key)) return current
    val value = obj.string(key).trim()
    return if (!ParseDiag.active() || acceptOptional(key, rule(value), value)) value else current
}

private fun ocReadCheckedInt(obj: JsonObject, key: String, current: Int, rule: Rule): Int {
    if (!obj.contains(key)) return current
    val value = obj.int(key)
    val text = value.toString()
    return if (!ParseDiag.active() || acceptOptional(key, rule(text), text)) value else current
}

/** The Checks of openconnect.h, shared by the OpenConnect editor and [OpenConnect.validate]. */
object OpenConnectChecks {
    @JvmField
    val FLAVORS: List<String> = listOf("anyconnect", "gp", "fortinet", "f5", "pulse", "nc")

    /** sing-openconnect anyconnect_auth.go, gp_auth.go and pulse_auth.go share this list; the other flavors take anything. */
    @JvmField
    val REPORTED_OS: List<String> = listOf("linux", "linux-64", "win", "mac-intel", "android", "apple-ios")

    @JvmField
    val COMPRESSION_MODES: List<String> = listOf("stateless", "all")

    @JvmField
    val TOKEN_MODES: List<String> = listOf("totp", "hotp", "stoken", "oidc")

    private fun hexDigit(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** The core parses [OpenConnect.composeServer] with net/url and refuses a query or fragment. */
    @JvmStatic
    fun openConnectServerPath(text: String): String {
        val path = text.trim()
        if (path.contains('?') || path.contains('#')) return vtr(R.string.validation_oc_server_path_query)
        var i = path.indexOf('%')
        while (i >= 0) {
            if (i + 2 >= path.length || !hexDigit(path[i + 1]) || !hexDigit(path[i + 2])) {
                return vtr(R.string.validation_oc_server_path_percent)
            }
            i = path.indexOf('%', i + 1)
        }
        return ""
    }

    /** Warning: the core raises a smaller MTU to 576. */
    @JvmStatic
    fun openConnectMtuFloor(text: String): String {
        val value = Validation.toInt(text) ?: 0L
        if (value <= 0 || value >= 576) return ""
        return vtr(R.string.validation_oc_mtu_floor)
    }

    /** sing-openconnect parsePeerFingerprint. */
    @JvmStatic
    fun openConnectFingerprint(line: String): String {
        val value = line.trim()
        if (value.isEmpty()) return ""
        var encoded = value
        var maximum = 40
        var base64 = false
        when {
            value.startsWith("sha1:") -> encoded = value.substring(5)
            value.startsWith("sha256:") -> {
                encoded = value.substring(7)
                maximum = 64
            }

            value.startsWith("pin-sha256:") -> {
                encoded = value.substring(11)
                maximum = 44
                base64 = true
            }

            value.contains(':') -> return vtr(R.string.validation_oc_fingerprint_colons)
        }
        var ok = encoded.length in 4..maximum
        var i = 0
        while (ok && i < encoded.length) {
            val c = encoded[i]
            val isBase64 = c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z' || c == '+' || c == '/' ||
                (c == '=' && i >= encoded.length - 2)
            ok = if (base64) isBase64 else hexDigit(c)
            i++
        }
        if (ok && base64 && encoded.length == maximum) ok = Validation.base64Key(encoded, 32).isEmpty()
        if (ok) return ""
        if (base64) return vtr(R.string.validation_oc_fingerprint_pin)
        return vtr(R.string.validation_oc_fingerprint_hex, maximum.toString())
    }

    @JvmStatic
    fun openConnectReportedOs(flavor: String, os: String): String {
        if (flavor.isNotEmpty() && flavor != "anyconnect" && flavor != "gp" && flavor != "pulse") return ""
        return Validation.oneOf(os, REPORTED_OS)
    }

    /** Warning: sing-openconnect NewClient raises a shorter interval to 2s. */
    @JvmStatic
    fun openConnectDpdFloor(text: String): String {
        val ns = Validation.parseDurationNs(text.trim()) ?: 0L
        if (ns <= 0 || ns >= 2000000000L) return ""
        return vtr(R.string.validation_oc_dpd_floor)
    }

    @JvmStatic
    fun openConnectCertificatePair(certificate: List<String>, certificatePath: String, key: List<String>, keyPath: String): String {
        val hasCertificate = certificate.isNotEmpty() || certificatePath.isNotBlank()
        val hasKey = key.isNotEmpty() || keyPath.isNotBlank()
        if (hasCertificate == hasKey) return ""
        return vtr(if (hasCertificate) R.string.validation_oc_certificate_needs_key else R.string.validation_oc_key_needs_certificate)
    }

    @JvmStatic
    fun openConnectFormEntry(formId: String, submissionKey: String, name: String, value: String, promote: Boolean): String {
        if (submissionKey.isEmpty() && (formId.isEmpty() || name.isEmpty())) return vtr(R.string.validation_oc_form_entry_target)
        if (promote && value.isNotEmpty()) return vtr(R.string.validation_oc_form_entry_promoted)
        return ""
    }

    @JvmStatic
    fun openConnectToken(profile: OpenConnect): String {
        val token = profile.token
        if (token.mode.isEmpty()) return ""
        Validation.oneOf(token.mode, TOKEN_MODES).let { if (it.isNotEmpty()) return it }
        if (token.secret.isNotEmpty() && token.secret_path.isNotEmpty()) return vtr(R.string.validation_oc_token_secret_both)
        if (token.secret.isEmpty() && token.secret_path.isEmpty()) return vtr(R.string.validation_oc_token_secret_missing)
        return ""
    }

    /** Warning: token fields set without a mode, which exportToJson drops together with the whole token. */
    @JvmStatic
    fun openConnectTokenUnused(profile: OpenConnect): String {
        val token = profile.token
        if (token.mode.isNotEmpty()) return ""
        val used = token.secret.isNotEmpty() || token.secret_path.isNotEmpty() || token.pin.isNotEmpty() ||
            token.password.isNotEmpty() || token.device_id.isNotEmpty() || token.counter > 0
        if (!used) return ""
        return vtr(R.string.validation_oc_token_unused)
    }

    /** Warning: only hotp tokens count. */
    @JvmStatic
    fun openConnectTokenCounter(profile: OpenConnect): String {
        val token = profile.token
        if (token.counter <= 0 || token.mode.isEmpty() || token.mode == "hotp") return ""
        return vtr(R.string.validation_oc_token_counter)
    }

    /** build() emits all three as soon as one is set, and the core refuses an empty one. */
    @JvmStatic
    fun openConnectMobile(profile: OpenConnect): String {
        val mobile = profile.mobile
        val set = listOf(mobile.platform_version, mobile.device_type, mobile.device_unique_id).count { it.isNotEmpty() }
        if (set == 0 || set == 3) return ""
        return vtr(R.string.validation_oc_mobile_partial)
    }

    @JvmStatic
    fun openConnectCompression(profile: OpenConnect): String {
        if (!profile.compression_disabled || profile.compression_mode != "all") return ""
        return vtr(R.string.validation_oc_compression_disabled)
    }
}

/**
 * openconnect (include/configs/outbounds/openconnect.h, src/configs/outbounds/openconnect.cpp): a sing-box
 * endpoint. The core takes a single HTTPS URL, which is stored split into [server], [serverPort] and
 * [server_path] ([setServerUrl]) and rebuilt by [composeServer]. The share "link" is an openconnect command line,
 * an openconnect config file or an AnyConnect XML profile ([parseOpenConnectProfile]).
 */
class OpenConnect : Outbound("openconnect") {

    /** OpenConnectToken (openconnect.h). */
    class Token {
        @JvmField var mode: String = ""
        @JvmField var secret: String = ""
        @JvmField var secret_path: String = ""
        @JvmField var pin: String = ""
        @JvmField var password: String = ""
        @JvmField var device_id: String = ""
        @JvmField var counter: Long = 0

        /** OpenConnectToken::ParseFromJson. */
        fun parseFromJson(obj: JsonObject): Boolean {
            if (obj.isEmpty()) return false
            if (obj.contains("mode")) mode = obj.string("mode").trim()
            if (obj.contains("secret")) secret = obj.string("secret")
            if (obj.contains("secret_path")) secret_path = obj.string("secret_path")
            if (obj.contains("pin")) pin = obj.string("pin")
            if (obj.contains("password")) password = obj.string("password")
            if (obj.contains("device_id")) device_id = obj.string("device_id")
            if (obj.contains("counter")) counter = obj.integer("counter")
            return true
        }

        /** OpenConnectToken::ExportToJson. */
        fun exportToJson(): JsonObject {
            val obj = JsonObject()
            if (mode.isEmpty()) return obj
            obj["mode"] = mode
            if (secret.isNotEmpty()) obj["secret"] = secret
            if (secret_path.isNotEmpty()) obj["secret_path"] = secret_path
            if (pin.isNotEmpty()) obj["pin"] = pin
            if (password.isNotEmpty()) obj["password"] = password
            if (device_id.isNotEmpty()) obj["device_id"] = device_id
            if (counter > 0) obj["counter"] = counter
            return obj
        }

        /** OpenConnectToken::Build. */
        fun build(ctx: BuildContext): JsonObject = exportToJson()
    }

    /** OpenConnectMobile (openconnect.h). */
    class Mobile {
        @JvmField var platform_version: String = ""
        @JvmField var device_type: String = ""
        @JvmField var device_unique_id: String = ""

        /** OpenConnectMobile::ParseFromJson. */
        fun parseFromJson(obj: JsonObject): Boolean {
            if (obj.isEmpty()) return false
            if (obj.contains("platform_version")) platform_version = obj.string("platform_version")
            if (obj.contains("device_type")) device_type = obj.string("device_type")
            if (obj.contains("device_unique_id")) device_unique_id = obj.string("device_unique_id")
            return true
        }

        /** OpenConnectMobile::ExportToJson. */
        fun exportToJson(): JsonObject {
            val obj = JsonObject()
            if (platform_version.isEmpty() && device_type.isEmpty() && device_unique_id.isEmpty()) return obj
            obj["platform_version"] = platform_version
            obj["device_type"] = device_type
            obj["device_unique_id"] = device_unique_id
            return obj
        }

        /** OpenConnectMobile::Build. */
        fun build(ctx: BuildContext): JsonObject = exportToJson()
    }

    /** OpenConnectWrapper (openconnect.h): the shared shape of the `csd` and `hip` objects. */
    class Wrapper {
        @JvmField var wrapper_path: String = ""

        /** OpenConnectWrapper::ParseFromJson. */
        fun parseFromJson(obj: JsonObject): Boolean {
            if (obj.isEmpty()) return false
            if (obj.contains("wrapper_path")) wrapper_path = obj.string("wrapper_path")
            return true
        }

        /** OpenConnectWrapper::ExportToJson. */
        fun exportToJson(): JsonObject {
            val obj = JsonObject()
            if (wrapper_path.isNotEmpty()) obj["wrapper_path"] = wrapper_path
            return obj
        }

        /** OpenConnectWrapper::Build. */
        fun build(ctx: BuildContext): JsonObject = exportToJson()
    }

    /** OpenConnectTNCCCertificate (openconnect.h). */
    class TnccCertificate {
        @JvmField var certificate: MutableList<String> = ArrayList()
        @JvmField var certificate_path: String = ""

        /** OpenConnectTNCCCertificate::ParseFromJson. */
        fun parseFromJson(obj: JsonObject): Boolean {
            if (obj.isEmpty()) return false
            if (obj.contains("certificate")) certificate = VpnFileImport.listable(obj, "certificate")
            if (obj.contains("certificate_path")) certificate_path = obj.string("certificate_path")
            return true
        }

        /** OpenConnectTNCCCertificate::ExportToJson. */
        fun exportToJson(): JsonObject {
            val obj = JsonObject()
            if (certificate.isNotEmpty()) obj["certificate"] = JsonValues.stringArray(certificate)
            if (certificate_path.isNotEmpty()) obj["certificate_path"] = certificate_path
            return obj
        }

        /** OpenConnectTNCCCertificate::Build. */
        fun build(ctx: BuildContext): JsonObject = exportToJson()
    }

    /** OpenConnectTNCC (openconnect.h). */
    class Tncc {
        /** wrapper_path conflicts with every other field here. */
        @JvmField var wrapper_path: String = ""
        @JvmField var device_id: String = ""
        @JvmField var user_agent: String = ""
        @JvmField var machine_identification_enabled: Boolean = false
        @JvmField var certificates: MutableList<TnccCertificate> = ArrayList()

        /** OpenConnectTNCC::ParseFromJson. */
        fun parseFromJson(obj: JsonObject): Boolean {
            if (obj.isEmpty()) return false
            if (obj.contains("wrapper_path")) wrapper_path = obj.string("wrapper_path")
            if (obj.contains("device_id")) device_id = obj.string("device_id")
            if (obj.contains("user_agent")) user_agent = obj.string("user_agent")
            if (obj.contains("machine_identification_enabled")) machine_identification_enabled = obj.bool("machine_identification_enabled")
            if (obj.contains("certificates")) {
                certificates.clear()
                for (item in obj.array("certificates")) {
                    val cert = TnccCertificate()
                    if (cert.parseFromJson(item as? JsonObject ?: JsonObject())) certificates.add(cert)
                }
            }
            return true
        }

        /** OpenConnectTNCC::ExportToJson. */
        fun exportToJson(): JsonObject {
            val obj = JsonObject()
            if (wrapper_path.isNotEmpty()) obj["wrapper_path"] = wrapper_path
            if (device_id.isNotEmpty()) obj["device_id"] = device_id
            if (user_agent.isNotEmpty()) obj["user_agent"] = user_agent
            if (machine_identification_enabled) obj["machine_identification_enabled"] = true
            if (certificates.isNotEmpty()) {
                val certs = JsonArray()
                for (cert in certificates) {
                    val item = cert.exportToJson()
                    if (item.isNotEmpty()) certs.add(item)
                }
                if (certs.isNotEmpty()) obj["certificates"] = certs
            }
            return obj
        }

        /** OpenConnectTNCC::Build. */
        fun build(ctx: BuildContext): JsonObject = exportToJson()
    }

    /** OpenConnectFortinetHostCheck (openconnect.h). */
    class FortinetHostCheck {
        @JvmField var hostcheck: String = ""
        @JvmField var check_virtual_desktop: String = ""

        /** OpenConnectFortinetHostCheck::ParseFromJson. */
        fun parseFromJson(obj: JsonObject): Boolean {
            if (obj.isEmpty()) return false
            if (obj.contains("hostcheck")) hostcheck = obj.string("hostcheck")
            if (obj.contains("check_virtual_desktop")) check_virtual_desktop = obj.string("check_virtual_desktop")
            return true
        }

        /** OpenConnectFortinetHostCheck::ExportToJson: an empty hostcheck disables the feature, so the rest is meaningless alone. */
        fun exportToJson(): JsonObject {
            val obj = JsonObject()
            if (hostcheck.isEmpty()) return obj
            obj["hostcheck"] = hostcheck
            if (check_virtual_desktop.isNotEmpty()) obj["check_virtual_desktop"] = check_virtual_desktop
            return obj
        }

        /** OpenConnectFortinetHostCheck::Build. */
        fun build(ctx: BuildContext): JsonObject = exportToJson()
    }

    /** OpenConnectFormEntry (openconnect.h). */
    class FormEntry {
        @JvmField var form_id: String = ""
        @JvmField var submission_key: String = ""
        @JvmField var name: String = ""
        @JvmField var value: String = ""
        @JvmField var promote: Boolean = false

        /** OpenConnectFormEntry::ParseFromJson. */
        fun parseFromJson(obj: JsonObject): Boolean {
            if (obj.isEmpty()) return false
            if (obj.contains("form_id")) form_id = obj.string("form_id")
            if (obj.contains("submission_key")) submission_key = obj.string("submission_key")
            if (obj.contains("name")) name = obj.string("name")
            if (obj.contains("value")) value = obj.string("value")
            if (obj.contains("promote")) promote = obj.bool("promote")
            return true
        }

        /** OpenConnectFormEntry::ExportToJson. */
        fun exportToJson(): JsonObject {
            val obj = JsonObject()
            if (submission_key.isEmpty() && (form_id.isEmpty() || name.isEmpty())) return obj
            if (form_id.isNotEmpty()) obj["form_id"] = form_id
            if (submission_key.isNotEmpty()) obj["submission_key"] = submission_key
            if (name.isNotEmpty()) obj["name"] = name
            if (value.isNotEmpty()) obj["value"] = value
            if (promote) obj["promote"] = true
            return obj
        }

        /** OpenConnectFormEntry::Build. */
        fun build(ctx: BuildContext): JsonObject = exportToJson()
    }

    /** OpenConnectTLS (openconnect.h); not the sing-box TLS block, so [hasTls] stays false. */
    class Tls {
        @JvmField var insecure: Boolean = false
        @JvmField var server_name: String = ""
        @JvmField var certificate_authority: MutableList<String> = ArrayList()
        @JvmField var client_certificate: MutableList<String> = ArrayList()
        @JvmField var client_key: MutableList<String> = ArrayList()
        @JvmField var client_key_password: String = ""

        /** Each *_path conflicts with its inline value. */
        @JvmField var peer_fingerprint: MutableList<String> = ArrayList()
        @JvmField var system_trust_disabled: Boolean = false
        @JvmField var certificate_authority_path: String = ""
        @JvmField var client_certificate_path: String = ""
        @JvmField var client_key_path: String = ""
        @JvmField var mca_certificate: MutableList<String> = ArrayList()
        @JvmField var mca_certificate_path: String = ""
        @JvmField var mca_key: MutableList<String> = ArrayList()
        @JvmField var mca_key_path: String = ""
        @JvmField var mca_key_password: String = ""

        /** OpenConnectTLS::ParseFromJson. */
        fun parseFromJson(obj: JsonObject): Boolean {
            if (obj.isEmpty()) return false
            if (obj.contains("insecure")) insecure = obj.bool("insecure")
            if (obj.contains("server_name")) server_name = obj.string("server_name")
            if (obj.contains("peer_fingerprint")) peer_fingerprint = VpnFileImport.listable(obj, "peer_fingerprint")
            if (obj.contains("system_trust_disabled")) system_trust_disabled = obj.bool("system_trust_disabled")
            if (obj.contains("certificate_authority")) certificate_authority = VpnFileImport.listable(obj, "certificate_authority")
            if (obj.contains("certificate_authority_path")) certificate_authority_path = obj.string("certificate_authority_path")
            if (obj.contains("client_certificate")) client_certificate = VpnFileImport.listable(obj, "client_certificate")
            if (obj.contains("client_certificate_path")) client_certificate_path = obj.string("client_certificate_path")
            if (obj.contains("client_key")) client_key = VpnFileImport.listable(obj, "client_key")
            if (obj.contains("client_key_path")) client_key_path = obj.string("client_key_path")
            if (obj.contains("client_key_password")) client_key_password = obj.string("client_key_password")
            if (obj.contains("mca_certificate")) mca_certificate = VpnFileImport.listable(obj, "mca_certificate")
            if (obj.contains("mca_certificate_path")) mca_certificate_path = obj.string("mca_certificate_path")
            if (obj.contains("mca_key")) mca_key = VpnFileImport.listable(obj, "mca_key")
            if (obj.contains("mca_key_path")) mca_key_path = obj.string("mca_key_path")
            if (obj.contains("mca_key_password")) mca_key_password = obj.string("mca_key_password")
            return true
        }

        /** OpenConnectTLS::ExportToJson. */
        fun exportToJson(): JsonObject {
            val obj = JsonObject()
            if (insecure) obj["insecure"] = true
            if (server_name.isNotEmpty()) obj["server_name"] = Hosts.toAceHost(server_name)
            if (peer_fingerprint.isNotEmpty()) obj["peer_fingerprint"] = JsonValues.stringArray(peer_fingerprint)
            if (system_trust_disabled) obj["system_trust_disabled"] = true
            if (certificate_authority.isNotEmpty()) obj["certificate_authority"] = JsonValues.stringArray(certificate_authority)
            if (certificate_authority_path.isNotEmpty()) obj["certificate_authority_path"] = certificate_authority_path
            if (client_certificate.isNotEmpty()) obj["client_certificate"] = JsonValues.stringArray(client_certificate)
            if (client_certificate_path.isNotEmpty()) obj["client_certificate_path"] = client_certificate_path
            if (client_key.isNotEmpty()) obj["client_key"] = JsonValues.stringArray(client_key)
            if (client_key_path.isNotEmpty()) obj["client_key_path"] = client_key_path
            if (client_key_password.isNotEmpty()) obj["client_key_password"] = client_key_password
            if (mca_certificate.isNotEmpty()) obj["mca_certificate"] = JsonValues.stringArray(mca_certificate)
            if (mca_certificate_path.isNotEmpty()) obj["mca_certificate_path"] = mca_certificate_path
            if (mca_key.isNotEmpty()) obj["mca_key"] = JsonValues.stringArray(mca_key)
            if (mca_key_path.isNotEmpty()) obj["mca_key_path"] = mca_key_path
            if (mca_key_password.isNotEmpty()) obj["mca_key_password"] = mca_key_password
            return obj
        }

        /** OpenConnectTLS::Build: the core reads each fingerprint line verbatim, so they go out trimmed. */
        fun build(ctx: BuildContext): JsonObject {
            val obj = exportToJson()
            if (peer_fingerprint.isNotEmpty()) {
                val fingerprints = peer_fingerprint.map { it.trim() }.filter { it.isNotEmpty() }
                if (fingerprints.isEmpty()) {
                    obj.remove("peer_fingerprint")
                } else {
                    obj["peer_fingerprint"] = JsonValues.stringArray(fingerprints)
                }
            }
            return obj
        }
    }

    @JvmField var flavor: String = ""
    @JvmField var username: String = ""
    @JvmField var password: String = ""
    @JvmField var auth_group: String = ""
    @JvmField var server_path: String = ""
    @JvmField var mtu: Int = 0
    @JvmField var tls: Tls = Tls()
    /** Throne-only, never sent to the core (openconnect.h); Android has no OTP store, so it only round-trips. */
    @JvmField var otp_profile_id: Int = -1
    @JvmField var only_advertised_routes: Boolean = true
    @JvmField var tunnel_dns: String = TUNNEL_DNS_PREFER

    @JvmField var cookie: String = ""
    @JvmField var token: Token = Token()
    @JvmField var reported_os: String = ""
    @JvmField var user_agent: String = ""
    @JvmField var version: String = ""
    @JvmField var local_hostname: String = ""
    @JvmField var mobile: Mobile = Mobile()
    @JvmField var csd: Wrapper = Wrapper()
    @JvmField var hip: Wrapper = Wrapper()
    @JvmField var tncc: Tncc = Tncc()
    @JvmField var fortinet_host_check: FortinetHostCheck = FortinetHostCheck()
    @JvmField var form_entries: MutableList<FormEntry> = ArrayList()
    @JvmField var no_udp: Boolean = false
    @JvmField var dtls_local_port: Int = 0
    @JvmField var compression_disabled: Boolean = false
    @JvmField var compression_mode: String = ""
    @JvmField var ipv6_disabled: Boolean = false
    @JvmField var http_keepalive_disabled: Boolean = false
    @JvmField var xml_post_disabled: Boolean = false
    @JvmField var external_auth_disabled: Boolean = false
    @JvmField var password_authentication_disabled: Boolean = false
    @JvmField var tcp_keep_alive_enabled: Boolean = false
    @JvmField var pfs: Boolean = false
    @JvmField var base_mtu: Int = 0
    @JvmField var dpd_interval: String = ""
    @JvmField var reconnect_timeout: String = ""
    @JvmField var trojan_interval: String = ""
    @JvmField var queue_length: Int = 0
    @JvmField var allow_insecure_crypto: Boolean = false
    @JvmField var system: Boolean = false
    /** sing-box "name" (system interface name); [name] is the tag (openconnect.h). */
    @JvmField var interface_name: String = ""
    @JvmField var udp_timeout: String = ""
    @JvmField var udp_mapping: String = ""
    @JvmField var udp_filtering: String = ""
    @JvmField var udp_nat_max: Int = 0

    /** openconnect::ParseFromLink: the "link" is an openconnect command line, config file or AnyConnect profile. */
    override fun parseFromLink(link: String): Boolean = parseOpenConnectProfile(link, null)

    /** openconnect::ParseFromJson: the DB loader too, so only an import drops values. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "openconnect") return false
        super.parseFromJson(obj)
        if (obj.contains("server")) setServerUrl(obj.string("server"))
        if (obj.contains("server_path")) server_path = obj.string("server_path")

        if (obj.contains("flavor")) flavor = obj.string("flavor").trim()
        if (obj.contains("username")) username = obj.string("username")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("auth_group")) auth_group = obj.string("auth_group")
        if (obj.contains("cookie")) cookie = obj.string("cookie")
        if (obj.contains("token")) token.parseFromJson(obj.obj("token"))
        reported_os = ocReadChecked(obj, "reported_os", reported_os) { OpenConnectChecks.openConnectReportedOs(flavor, it) }
        if (obj.contains("user_agent")) user_agent = obj.string("user_agent")
        if (obj.contains("version")) version = obj.string("version")
        if (obj.contains("local_hostname")) local_hostname = obj.string("local_hostname")
        if (obj.contains("mobile")) mobile.parseFromJson(obj.obj("mobile"))
        if (obj.contains("csd")) csd.parseFromJson(obj.obj("csd"))
        if (obj.contains("hip")) hip.parseFromJson(obj.obj("hip"))
        if (obj.contains("tncc")) tncc.parseFromJson(obj.obj("tncc"))
        if (obj.contains("fortinet_host_check")) fortinet_host_check.parseFromJson(obj.obj("fortinet_host_check"))
        if (obj.contains("no_udp")) no_udp = obj.bool("no_udp")
        dtls_local_port = ocReadCheckedInt(obj, "dtls_local_port", dtls_local_port) { if (it == "0") "" else Validation.port(it) }
        if (obj.contains("compression_disabled")) compression_disabled = obj.bool("compression_disabled")
        compression_mode = ocReadChecked(obj, "compression_mode", compression_mode) {
            Validation.oneOf(it, OpenConnectChecks.COMPRESSION_MODES)
        }
        if (obj.contains("ipv6_disabled")) ipv6_disabled = obj.bool("ipv6_disabled")
        if (obj.contains("http_keepalive_disabled")) http_keepalive_disabled = obj.bool("http_keepalive_disabled")
        if (obj.contains("xml_post_disabled")) xml_post_disabled = obj.bool("xml_post_disabled")
        if (obj.contains("external_auth_disabled")) external_auth_disabled = obj.bool("external_auth_disabled")
        if (obj.contains("password_authentication_disabled")) password_authentication_disabled = obj.bool("password_authentication_disabled")
        if (obj.contains("tcp_keep_alive_enabled")) tcp_keep_alive_enabled = obj.bool("tcp_keep_alive_enabled")
        if (obj.contains("pfs")) pfs = obj.bool("pfs")
        val mtuRule: Rule = { Validation.int(it, 0, 65535) }
        mtu = ocReadCheckedInt(obj, "mtu", mtu, mtuRule)
        base_mtu = ocReadCheckedInt(obj, "base_mtu", base_mtu, mtuRule)
        val durationRule: Rule = { Validation.duration(it) }
        dpd_interval = ocReadChecked(obj, "dpd_interval", dpd_interval, durationRule)
        reconnect_timeout = ocReadChecked(obj, "reconnect_timeout", reconnect_timeout, durationRule)
        trojan_interval = ocReadChecked(obj, "trojan_interval", trojan_interval, durationRule)
        if (obj.contains("queue_length")) queue_length = obj.int("queue_length")
        if (obj.contains("allow_insecure_crypto")) allow_insecure_crypto = obj.bool("allow_insecure_crypto")
        if (obj.contains("tls")) tls.parseFromJson(obj.obj("tls"))
        if (obj.contains("form_entries")) {
            form_entries.clear()
            for (item in obj.array("form_entries")) {
                val entry = FormEntry()
                if (entry.parseFromJson(item as? JsonObject ?: JsonObject())) form_entries.add(entry)
            }
        }
        if (obj.contains("system")) system = obj.bool("system")
        if (obj.contains("name")) interface_name = obj.string("name")
        udp_timeout = ocReadChecked(obj, "udp_timeout", udp_timeout, durationRule)
        if (obj.contains("udp_mapping")) udp_mapping = obj.string("udp_mapping")
        if (obj.contains("udp_filtering")) udp_filtering = obj.string("udp_filtering")
        if (obj.contains("udp_nat_max")) udp_nat_max = obj.int("udp_nat_max")

        if (obj.contains("otp_profile_id")) otp_profile_id = obj.int("otp_profile_id")
        if (obj.contains("only_advertised_routes")) only_advertised_routes = obj.bool("only_advertised_routes")
        // Profiles saved before the mode existed carry the two checkboxes it replaced.
        if (obj.contains("tunnel_dns")) tunnel_dns = obj.string("tunnel_dns")
        else if (obj.bool("block_outside_dns")) tunnel_dns = TUNNEL_DNS_STRICT
        else if (obj.contains("use_tunnel_dns") && !obj.bool("use_tunnel_dns")) tunnel_dns = TUNNEL_DNS_NONE
        return true
    }

    /**
     * openconnect::Validate. The desktop's reminder about an unbound {otp} placeholder is left out: Android never
     * substitutes it.
     */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        fun report(field: String, message: String, value: String = "", severity: Severity = Severity.Error) =
            issues.addIssue(severity, field, message, value)

        report("flavor", Validation.oneOf(flavor, OpenConnectChecks.FLAVORS), flavor)
        report("server_path", OpenConnectChecks.openConnectServerPath(server_path), server_path)
        val mtuText = mtu.toString()
        report("mtu", Validation.int(mtuText, 0, 65535), mtuText)
        report("mtu", OpenConnectChecks.openConnectMtuFloor(mtuText), mtuText, Severity.Warning)
        val baseMtuText = base_mtu.toString()
        report("base_mtu", Validation.int(baseMtuText, 0, 65535), baseMtuText)
        report("base_mtu", OpenConnectChecks.openConnectMtuFloor(baseMtuText), baseMtuText, Severity.Warning)
        if (dtls_local_port != 0) {
            val portText = dtls_local_port.toString()
            report("dtls_local_port", Validation.port(portText), portText)
        }
        report("reported_os", OpenConnectChecks.openConnectReportedOs(flavor, reported_os), reported_os)
        report("compression_mode", Validation.oneOf(compression_mode, OpenConnectChecks.COMPRESSION_MODES), compression_mode)
        report("dpd_interval", Validation.duration(dpd_interval), dpd_interval)
        report("dpd_interval", OpenConnectChecks.openConnectDpdFloor(dpd_interval), dpd_interval, Severity.Warning)
        report("reconnect_timeout", Validation.duration(reconnect_timeout), reconnect_timeout)
        report("trojan_interval", Validation.duration(trojan_interval), trojan_interval)
        report("udp_timeout", Validation.duration(udp_timeout), udp_timeout)
        report("tls.server_name", CommonChecks.serverName(tls.server_name), tls.server_name)
        for (line in tls.peer_fingerprint) {
            val message = OpenConnectChecks.openConnectFingerprint(line)
            if (message.isNotEmpty()) {
                report("tls.peer_fingerprint", message, line)
                break
            }
        }

        report("tls.certificate_authority", OpenVpnChecks.vpnMaterialConflict(tls.certificate_authority, tls.certificate_authority_path))
        report("tls.client_certificate", OpenVpnChecks.vpnMaterialConflict(tls.client_certificate, tls.client_certificate_path))
        report("tls.client_key", OpenVpnChecks.vpnMaterialConflict(tls.client_key, tls.client_key_path))
        report("tls.mca_certificate", OpenVpnChecks.vpnMaterialConflict(tls.mca_certificate, tls.mca_certificate_path))
        report("tls.mca_key", OpenVpnChecks.vpnMaterialConflict(tls.mca_key, tls.mca_key_path))
        report(
            "tls.client_certificate",
            OpenConnectChecks.openConnectCertificatePair(tls.client_certificate, tls.client_certificate_path, tls.client_key, tls.client_key_path),
        )
        report(
            "tls.mca_certificate",
            OpenConnectChecks.openConnectCertificatePair(tls.mca_certificate, tls.mca_certificate_path, tls.mca_key, tls.mca_key_path),
        )
        for ((i, certificate) in tncc.certificates.withIndex()) {
            report("tncc.certificates[$i]", OpenVpnChecks.vpnMaterialConflict(certificate.certificate, certificate.certificate_path))
        }
        report("token", OpenConnectChecks.openConnectToken(this), token.mode)
        report("token", OpenConnectChecks.openConnectTokenUnused(this), severity = Severity.Warning)
        report("token.counter", OpenConnectChecks.openConnectTokenCounter(this), severity = Severity.Warning)
        report("mobile", OpenConnectChecks.openConnectMobile(this))
        report("compression_mode", OpenConnectChecks.openConnectCompression(this), compression_mode)
        for ((i, entry) in form_entries.withIndex()) {
            report(
                "form_entries[$i]",
                OpenConnectChecks.openConnectFormEntry(entry.form_id, entry.submission_key, entry.name, entry.value, entry.promote),
            )
        }
    }

    /** openconnect::ExportToJson. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "openconnect"
        obj.merge(baseExportToJson())
        if (server_path.isNotEmpty()) obj["server_path"] = server_path

        if (flavor.isNotEmpty()) obj["flavor"] = flavor
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password
        if (auth_group.isNotEmpty()) obj["auth_group"] = auth_group
        if (cookie.isNotEmpty()) obj["cookie"] = cookie
        val tokenObj = token.exportToJson()
        if (tokenObj.isNotEmpty()) obj["token"] = tokenObj
        if (reported_os.isNotEmpty()) obj["reported_os"] = reported_os
        if (user_agent.isNotEmpty()) obj["user_agent"] = user_agent
        if (version.isNotEmpty()) obj["version"] = version
        if (local_hostname.isNotEmpty()) obj["local_hostname"] = local_hostname
        val mobileObj = mobile.exportToJson()
        if (mobileObj.isNotEmpty()) obj["mobile"] = mobileObj
        val csdObj = csd.exportToJson()
        if (csdObj.isNotEmpty()) obj["csd"] = csdObj
        val hipObj = hip.exportToJson()
        if (hipObj.isNotEmpty()) obj["hip"] = hipObj
        val tnccObj = tncc.exportToJson()
        if (tnccObj.isNotEmpty()) obj["tncc"] = tnccObj
        val fortinetObj = fortinet_host_check.exportToJson()
        if (fortinetObj.isNotEmpty()) obj["fortinet_host_check"] = fortinetObj
        if (no_udp) obj["no_udp"] = true
        if (dtls_local_port > 0) obj["dtls_local_port"] = dtls_local_port
        if (compression_disabled) obj["compression_disabled"] = true
        if (compression_mode.isNotEmpty()) obj["compression_mode"] = compression_mode
        if (ipv6_disabled) obj["ipv6_disabled"] = true
        if (http_keepalive_disabled) obj["http_keepalive_disabled"] = true
        if (xml_post_disabled) obj["xml_post_disabled"] = true
        if (external_auth_disabled) obj["external_auth_disabled"] = true
        if (password_authentication_disabled) obj["password_authentication_disabled"] = true
        if (tcp_keep_alive_enabled) obj["tcp_keep_alive_enabled"] = true
        if (pfs) obj["pfs"] = true
        if (mtu > 0) obj["mtu"] = mtu
        if (base_mtu > 0) obj["base_mtu"] = base_mtu
        if (dpd_interval.isNotEmpty()) obj["dpd_interval"] = dpd_interval
        if (reconnect_timeout.isNotEmpty()) obj["reconnect_timeout"] = reconnect_timeout
        if (trojan_interval.isNotEmpty()) obj["trojan_interval"] = trojan_interval
        if (queue_length > 0) obj["queue_length"] = queue_length
        if (allow_insecure_crypto) obj["allow_insecure_crypto"] = true
        val tlsObj = tls.exportToJson()
        if (tlsObj.isNotEmpty()) obj["tls"] = tlsObj
        if (form_entries.isNotEmpty()) {
            val entries = JsonArray()
            for (entry in form_entries) {
                val item = entry.exportToJson()
                if (item.isNotEmpty()) entries.add(item)
            }
            if (entries.isNotEmpty()) obj["form_entries"] = entries
        }
        if (system) obj["system"] = true
        if (interface_name.isNotEmpty()) obj["name"] = interface_name
        if (udp_timeout.isNotEmpty()) obj["udp_timeout"] = udp_timeout
        if (udp_mapping.isNotEmpty()) obj["udp_mapping"] = udp_mapping
        if (udp_filtering.isNotEmpty()) obj["udp_filtering"] = udp_filtering
        if (udp_nat_max > 0) obj["udp_nat_max"] = udp_nat_max

        if (otp_profile_id >= 0) obj["otp_profile_id"] = otp_profile_id
        obj["only_advertised_routes"] = only_advertised_routes
        obj["tunnel_dns"] = tunnel_dns
        return obj
    }

    /** openconnect::Build. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "openconnect"
        if (name.isNotEmpty()) obj["tag"] = name
        obj.merge(dial.build(ctx))
        val url = composeServer()
        if (url.isNotEmpty()) obj["server"] = url

        // Android has no OTP store: otp_profile_id only round-trips and the stored credentials reach the core as they are.
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password

        if (flavor.isNotEmpty()) obj["flavor"] = flavor
        if (auth_group.isNotEmpty()) obj["auth_group"] = auth_group
        if (cookie.isNotEmpty()) obj["cookie"] = cookie
        val tokenObj = token.build(ctx)
        if (tokenObj.isNotEmpty()) obj["token"] = tokenObj
        if (reported_os.isNotEmpty()) obj["reported_os"] = reported_os
        if (user_agent.isNotEmpty()) obj["user_agent"] = user_agent
        if (version.isNotEmpty()) obj["version"] = version
        if (local_hostname.isNotEmpty()) obj["local_hostname"] = local_hostname
        val mobileObj = mobile.build(ctx)
        if (mobileObj.isNotEmpty()) obj["mobile"] = mobileObj
        val csdObj = csd.build(ctx)
        if (csdObj.isNotEmpty()) obj["csd"] = csdObj
        val hipObj = hip.build(ctx)
        if (hipObj.isNotEmpty()) obj["hip"] = hipObj
        val tnccObj = tncc.build(ctx)
        if (tnccObj.isNotEmpty()) obj["tncc"] = tnccObj
        val fortinetObj = fortinet_host_check.build(ctx)
        if (fortinetObj.isNotEmpty()) obj["fortinet_host_check"] = fortinetObj
        if (no_udp) obj["no_udp"] = true
        if (dtls_local_port > 0) obj["dtls_local_port"] = dtls_local_port
        if (compression_disabled) obj["compression_disabled"] = true
        if (compression_mode.isNotEmpty()) obj["compression_mode"] = compression_mode
        if (ipv6_disabled) obj["ipv6_disabled"] = true
        if (http_keepalive_disabled) obj["http_keepalive_disabled"] = true
        if (xml_post_disabled) obj["xml_post_disabled"] = true
        if (external_auth_disabled) obj["external_auth_disabled"] = true
        if (password_authentication_disabled) obj["password_authentication_disabled"] = true
        if (tcp_keep_alive_enabled) obj["tcp_keep_alive_enabled"] = true
        if (pfs) obj["pfs"] = true
        if (mtu > 0) obj["mtu"] = mtu
        if (base_mtu > 0) obj["base_mtu"] = base_mtu
        if (dpd_interval.isNotEmpty()) obj["dpd_interval"] = dpd_interval
        if (reconnect_timeout.isNotEmpty()) obj["reconnect_timeout"] = reconnect_timeout
        if (trojan_interval.isNotEmpty()) obj["trojan_interval"] = trojan_interval
        if (queue_length > 0) obj["queue_length"] = queue_length
        if (allow_insecure_crypto) obj["allow_insecure_crypto"] = true
        val tlsObj = tls.build(ctx)
        if (tlsObj.isNotEmpty()) obj["tls"] = tlsObj
        if (form_entries.isNotEmpty()) {
            val entries = JsonArray()
            for (entry in form_entries) {
                val item = entry.build(ctx)
                if (item.isEmpty()) continue
                entries.add(item)
            }
            if (entries.isNotEmpty()) obj["form_entries"] = entries
        }
        if (system) obj["system"] = true
        if (interface_name.isNotEmpty()) obj["name"] = interface_name
        if (udp_timeout.isNotEmpty()) obj["udp_timeout"] = udp_timeout
        if (udp_mapping.isNotEmpty()) obj["udp_mapping"] = udp_mapping
        if (udp_filtering.isNotEmpty()) obj["udp_filtering"] = udp_filtering
        if (udp_nat_max > 0) obj["udp_nat_max"] = udp_nat_max
        return BuildResult(obj)
    }

    /** openconnect::ComposeServer: `host[:port unless 443][/path]`. */
    fun composeServer(): String {
        if (server.isEmpty()) return ""
        var url = OcImport.wrapIpv6Host(server)
        if (serverPort > 0 && serverPort != OcImport.DEFAULT_PORT) url += ":$serverPort"
        if (server_path.isNotEmpty()) {
            url += if (server_path.startsWith("/")) server_path else "/$server_path"
        }
        return url
    }

    /** openconnect::SetServerUrl: a URL or bare host[:port][/path]; an unparsable value leaves the fields alone. */
    fun setServerUrl(url: String) {
        var text = VpnFileImport.trimmed(url)
        if (text.isEmpty()) return
        if (!text.contains("://")) {
            // A bare IPv6 literal has to be bracketed before QUrl will parse it.
            text = "https://" + OcImport.wrapIpv6Host(text)
        }
        val parsed = LinkParser.parse(text)
        if (!parsed.isValid || parsed.host.isEmpty()) return
        server = parsed.host
        if (parsed.port > 0) serverPort = parsed.port
        server_path = parsed.path
        if (server_path == "/") server_path = ""
    }

    /** openconnect::DisplayAddress. */
    override fun displayAddress(): String = composeServer()

    /** openconnect::DisplayType. */
    override fun displayType(): String = "OpenConnect"

    /** openconnect::GetSecurity: the core still checks a pinned fingerprint when insecure is set. */
    override fun security(): SecurityInfo {
        if (tls.insecure && tls.peer_fingerprint.isEmpty()) return SecurityInfo("Insecure TLS", "", SecurityLevel.Weak)
        return SecurityInfo("TLS", "", SecurityLevel.Secure)
    }

    /** openconnect::IsEndpoint. */
    override fun isEndpoint(): Boolean = true

    /** composeServer leaves the port out, which means the HTTPS default, 443. */
    override fun serverPortOptional(): Boolean = true

    /** openconnect::SupportsCredentialStrip. */
    override fun supportsCredentialStrip(): Boolean = true

    /** openconnect::StripCredentials. */
    override fun stripCredentials() {
        username = ""
        password = ""
        cookie = ""
        otp_profile_id = -1
        // Every field of the soft-token object is secret-bearing, so drop the whole object.
        token = Token()
        tls.client_key.clear()
        tls.client_key_path = ""
        tls.client_key_password = ""
        tls.mca_key.clear()
        tls.mca_key_path = ""
        tls.mca_key_password = ""
        for (entry in form_entries) entry.value = ""
        // Absolute local paths: meaningless on another machine and they carry the OS user name.
        tls.certificate_authority_path = ""
        tls.client_certificate_path = ""
        tls.mca_certificate_path = ""
        token.secret_path = ""
        csd.wrapper_path = ""
        hip.wrapper_path = ""
        tncc.wrapper_path = ""
        for (cert in tncc.certificates) cert.certificate_path = ""
    }

    /** openconnect::StripForeignState: the authenticator binding belongs to another installation, and each wrapper is an executable the core starts during authentication. */
    override fun stripForeignState(issues: MutableList<Issue>) {
        super.stripForeignState(issues)
        if (otp_profile_id >= 0) {
            otp_profile_id = -1
            issues.add(Issue(Severity.Warning, "otp_profile_id", vtr(R.string.validation_oc_otp_removed)))
        }
        if (csd.wrapper_path.isNotEmpty()) {
            issues.add(Issue(Severity.Warning, "csd.wrapper_path", vtr(R.string.validation_oc_csd_wrapper_removed), csd.wrapper_path))
            csd.wrapper_path = ""
        }
        if (hip.wrapper_path.isNotEmpty()) {
            issues.add(Issue(Severity.Warning, "hip.wrapper_path", vtr(R.string.validation_oc_hip_wrapper_removed), hip.wrapper_path))
            hip.wrapper_path = ""
        }
        if (tncc.wrapper_path.isNotEmpty()) {
            issues.add(Issue(Severity.Warning, "tncc.wrapper_path", vtr(R.string.validation_oc_tncc_wrapper_removed), tncc.wrapper_path))
            tncc.wrapper_path = ""
        }
    }

    /**
     * ParseOpenConnectProfile (vpnFileImport.cpp) applied to this profile: an AnyConnect XML profile (first usable
     * host entry), an `openconnect ...` command line, or a config file of `option[=value]` lines. Every note the
     * desktop would show is appended to [problems]; a fatal problem appends its reason last.
     */
    @JvmOverloads
    fun parseOpenConnectProfile(text: String, problems: MutableList<String>? = null): Boolean {
        val notes = ArrayList<String>()
        fun flush() {
            problems?.addAll(notes)
        }
        val body = VpnFileImport.trimmed(text)
        if (body.isEmpty()) {
            notes.add(vtr(R.string.validation_oc_profile_empty))
            flush()
            return false
        }

        if (OcImport.looksLikeXml(body)) {
            val entries = ArrayList<OcImport.HostEntry>()
            if (!OcImport.readAnyConnectHosts(body, entries, notes)) {
                flush()
                return false
            }
            for (entry in entries) {
                if (!OcImport.applyHostEntry(entry, this, notes)) continue
                flush()
                return true
            }
            notes.add(vtr(R.string.validation_oc_anyconnect_no_usable_host))
            flush()
            return false
        }

        tls.peer_fingerprint.clear()
        form_entries.clear()

        val argv = ArrayList<String>()
        if (OcImport.isCommandLine(body)) {
            val joined = StringBuilder()
            for (line in body.split('\n')) {
                var item = VpnFileImport.trimmed(line).replace("\r", "")
                if (item.endsWith('\\')) item = item.dropLast(1)
                joined.append(item)
                joined.append(' ')
            }
            argv.addAll(VpnFileImport.tokenize(joined.toString()))
        } else {
            for (line in body.split('\n')) {
                val tokens = VpnFileImport.tokenize(line)
                if (tokens.isEmpty()) continue
                if (tokens[0].contains('=')) argv.add("--" + tokens[0])
                else if (tokens.size > 1) argv.add("--" + tokens[0] + "=" + tokens.subList(1, tokens.size).joinToString(" "))
                // A lone word that names no option is the bare server address.
                else if (tokens[0] in OcImport.OPTION_HAS_VALUE || tokens[0] in OcImport.FLAGS || tokens[0] in OcImport.IGNORED) argv.add("--" + tokens[0])
                else argv.add(tokens[0])
            }
        }
        if (argv.isEmpty()) {
            notes.add(vtr(R.string.validation_oc_no_options))
            flush()
            return false
        }
        OcImport.walkArgs(argv, this, notes)

        if (server.isEmpty()) {
            notes.add(vtr(R.string.validation_oc_no_server))
            flush()
            return false
        }
        if (name.isEmpty()) name = server
        if (tls.client_certificate.isNotEmpty()) tls.client_certificate_path = ""
        if (tls.client_key.isNotEmpty()) tls.client_key_path = ""
        if (tls.certificate_authority.isNotEmpty()) tls.certificate_authority_path = ""
        if (token.secret.isNotEmpty()) token.secret_path = ""
        flush()
        return true
    }

    companion object {
        /**
         * ParseAnyConnectXml (vpnFileImport.cpp): an AnyConnect / Cisco Secure Client XML profile lists one host per
         * entry; one profile per usable entry is appended to [out]. True when at least one was produced.
         */
        @JvmStatic
        @JvmOverloads
        fun parseAnyConnectXml(xml: String, out: MutableList<OpenConnect>, problems: MutableList<String>? = null): Boolean {
            val notes = ArrayList<String>()
            val entries = ArrayList<OcImport.HostEntry>()
            if (!OcImport.readAnyConnectHosts(xml, entries, notes)) {
                problems?.addAll(notes)
                return false
            }
            for (entry in entries) {
                val host = OpenConnect()
                if (OcImport.applyHostEntry(entry, host, notes)) out.add(host)
            }
            problems?.addAll(notes)
            return out.isNotEmpty()
        }
    }
}

/** The openconnect half of vpnFileImport.cpp's anonymous namespace (`vpnfiOc*`). */
private object OcImport {
    /** kOpenConnectDefaultPort (openconnect.cpp). */
    const val DEFAULT_PORT = 443

    class HostEntry {
        @JvmField var name: String = ""
        @JvmField var address: String = ""
        @JvmField var group: String = ""
        @JvmField var protocol: String = ""
        @JvmField var fingerprints: MutableList<String> = ArrayList()
    }

    /** WrapIPV6Host (Utils.hpp). */
    fun wrapIpv6Host(host: String): String =
        if (!Hosts.isIpv6Address(host)) host else "[" + host.replace("[", "").replace("]", "") + "]"

    /** vpnfiOcOptionHasValue. */
    val OPTION_HAS_VALUE: Set<String> = setOf(
        "authgroup", "base-mtu", "cafile", "cert-expire-warning", "certificate", "compression", "config",
        "cookie", "csd-user", "csd-wrapper", "dpd", "dtls-local-port", "force-dpd", "force-trojan",
        "form-entry", "gnutls-priority", "http-auth", "interface", "key-password", "key-type",
        "local-hostname", "localname", "mca-certificate", "mca-key", "mca-key-password", "mtu", "os",
        "pid-file", "protocol", "proxy", "proxy-auth", "queue-len", "reconnect-timeout", "resolve", "script",
        "server", "servercert", "setuid", "sni", "sslkey", "token-mode", "token-secret", "user", "useragent",
        "user-agent", "usergroup", "version-string", "xmlconfig",
    )

    /** vpnfiOcIgnored. */
    val IGNORED: Set<String> = setOf(
        "authenticate", "background", "cert-expire-warning", "config", "cookie-on-stdin", "cookieonly",
        "csd-user", "deflate", "dump-http-traffic", "gnutls-debug", "gnutls-priority", "help", "interface",
        "key-password-from-fsid", "key-type", "libproxy", "no-deflate", "no-proxy", "non-inter", "passtos",
        "passwd-on-stdin", "pid-file", "printcookie", "quiet", "resolve", "script", "script-tun", "setuid",
        "syslog", "timestamp", "verbose", "version", "xmlconfig",
    )

    /** vpnfiOcFlags: kept apart so only an unknown option may swallow a detached argument. */
    val FLAGS: Set<String> = setOf(
        "allow-insecure-crypto", "disable-ipv6", "juniper", "no-compression", "no-dtls", "no-external-auth",
        "no-http-keepalive", "no-passwd", "no-system-trust", "no-xmlpost", "pfs",
    )

    /** vpnfiOcLongName. */
    fun longName(shortName: Char): String = when (shortName) {
        'b' -> "background"
        'C' -> "cookie"
        'c' -> "certificate"
        'e' -> "cert-expire-warning"
        'F' -> "form-entry"
        'g' -> "usergroup"
        'h' -> "help"
        'i' -> "interface"
        'k' -> "sslkey"
        'l' -> "syslog"
        'm' -> "mtu"
        'p' -> "key-password"
        'P' -> "proxy"
        'Q' -> "queue-len"
        'q' -> "quiet"
        'S' -> "script-tun"
        's' -> "script"
        't' -> "token-mode"
        'U' -> "setuid"
        'u' -> "user"
        'V' -> "version"
        'v' -> "verbose"
        'x' -> "xmlconfig"
        else -> ""
    }

    /** vpnfiApplyOcOption: a value the endpoint would refuse is dropped with a note ([VpnFileImport.accept]). */
    fun applyOption(out: OpenConnect, name: String, value: String, hasValue: Boolean, notes: MutableList<String>) {
        when (name) {
            "protocol" -> {
                val flavor = value.lowercase()
                if (flavor in OpenConnectChecks.FLAVORS) out.flavor = flavor
                else notes.add(vtr(R.string.validation_oc_protocol_unsupported, value))
            }
            "juniper" -> out.flavor = "nc"
            "server" -> out.setServerUrl(value)
            "user" -> out.username = value
            "authgroup" -> out.auth_group = value
            "usergroup" -> out.server_path = value
            "cookie" -> out.cookie = value
            "certificate" -> out.tls.client_certificate_path = value
            "sslkey" -> out.tls.client_key_path = value
            "key-password" -> out.tls.client_key_password = value
            "mca-certificate" -> out.tls.mca_certificate_path = value
            "mca-key" -> out.tls.mca_key_path = value
            "mca-key-password" -> out.tls.mca_key_password = value
            "cafile" -> out.tls.certificate_authority_path = value
            "servercert" -> if (value.isNotEmpty()) out.tls.peer_fingerprint.add(value)
            "no-system-trust" -> out.tls.system_trust_disabled = true
            "sni" -> out.tls.server_name = value
            "no-dtls" -> out.no_udp = true
            "dtls-local-port" -> if (VpnFileImport.accept(name, value, Validation.port(value), notes)) out.dtls_local_port = QtStrings.toInt(value)
            "compression" -> {
                val mode = value.lowercase()
                if (mode == "none") out.compression_disabled = true
                else if (mode == "stateless" || mode == "all") out.compression_mode = mode
                else notes.add(vtr(R.string.validation_oc_compression_unknown, value))
            }
            "no-compression" -> out.compression_disabled = true
            "disable-ipv6" -> out.ipv6_disabled = true
            "no-http-keepalive" -> out.http_keepalive_disabled = true
            "no-xmlpost" -> out.xml_post_disabled = true
            "no-external-auth" -> out.external_auth_disabled = true
            "no-passwd" -> out.password_authentication_disabled = true
            "pfs" -> out.pfs = true
            "allow-insecure-crypto" -> out.allow_insecure_crypto = true
            "mtu" -> if (VpnFileImport.accept(name, value, Validation.int(value, 0, 65535), notes)) out.mtu = QtStrings.toInt(value)
            "base-mtu" -> if (VpnFileImport.accept(name, value, Validation.int(value, 0, 65535), notes)) out.base_mtu = QtStrings.toInt(value)
            "dpd", "force-dpd" -> {
                val interval = VpnFileImport.duration(name, value, notes)
                // The core raises a shorter interval to 2s itself.
                val ns = Validation.parseDurationNs(interval) ?: 0L
                if (ns > 0 && ns < 2000000000L) {
                    notes.add(vtr(R.string.validation_oc_dpd_raised, name, value))
                    out.dpd_interval = "2s"
                } else {
                    out.dpd_interval = interval
                }
            }
            "reconnect-timeout" -> out.reconnect_timeout = VpnFileImport.duration(name, value, notes)
            "force-trojan" -> out.trojan_interval = VpnFileImport.duration(name, value, notes)
            "queue-len" -> if (VpnFileImport.accept(name, value, Validation.int(value, 0, 2147483647L), notes)) out.queue_length = QtStrings.toInt(value)
            "useragent", "user-agent" -> out.user_agent = value
            "version-string" -> out.version = value
            "local-hostname", "localname" -> out.local_hostname = value
            "os" -> {
                if (value.lowercase() in OpenConnectChecks.REPORTED_OS) out.reported_os = value.lowercase()
                else notes.add(vtr(R.string.validation_oc_reported_os_unknown, value))
            }
            "csd-wrapper" -> out.csd.wrapper_path = value
            "token-mode" -> {
                // OpenConnect's `rsa` is the endpoint's `stoken`.
                val mode = value.lowercase()
                if (mode == "rsa" || mode == "stoken") out.token.mode = "stoken"
                else if (mode == "totp" || mode == "hotp" || mode == "oidc") out.token.mode = mode
                else notes.add(vtr(R.string.validation_oc_token_mode_unsupported, value))
            }
            "token-secret" -> {
                if (value.startsWith('@')) out.token.secret_path = value.substring(1)
                else out.token.secret = value
            }
            "form-entry" -> {
                val colon = value.indexOf(':')
                val equals = value.indexOf('=', colon + 1)
                if (colon < 0 || equals < 0) {
                    notes.add(vtr(R.string.validation_oc_form_entry_format, value))
                    return
                }
                val entry = OpenConnect.FormEntry()
                entry.form_id = value.substring(0, colon)
                entry.name = value.substring(colon + 1, equals)
                entry.value = value.substring(equals + 1)
                out.form_entries.add(entry)
            }
            "proxy", "proxy-auth", "http-auth" -> notes.add(vtr(R.string.validation_oc_proxy_ignored, name))
            else -> {
                if (name in IGNORED) return
                notes.add(vtr(R.string.validation_oc_option_unknown, if (hasValue) "$name=$value" else name))
            }
        }
    }

    /** vpnfiWalkOcArgs: GNU long options, bundled short options and the positional server. */
    fun walkArgs(argv: List<String>, out: OpenConnect, notes: MutableList<String>) {
        var i = 0
        while (i < argv.size) {
            val token = argv[i]
            if (i == 0 && (token == "openconnect" || token.endsWith("/openconnect") || token.endsWith("openconnect.exe"))) {
                i++
                continue
            }
            if (token.startsWith("--")) {
                var name = token.substring(2)
                var value = ""
                var hasValue = false
                val equals = name.indexOf('=')
                if (equals >= 0) {
                    value = name.substring(equals + 1)
                    name = name.substring(0, equals)
                    hasValue = true
                } else if (name in OPTION_HAS_VALUE && i + 1 < argv.size) {
                    value = argv[++i]
                    hasValue = true
                } else if (name !in FLAGS && name !in IGNORED && i + 2 < argv.size && !argv[i + 1].startsWith('-')) {
                    // Swallow a detached value only while a trailing positional is left.
                    value = argv[++i]
                    hasValue = true
                }
                applyOption(out, name, value, hasValue, notes)
                i++
                continue
            }
            if (token.length > 1 && token.startsWith('-')) {
                var c = 1
                while (c < token.length) {
                    val name = longName(token[c])
                    if (name.isEmpty()) {
                        notes.add(vtr(R.string.validation_oc_option_unknown, "-${token[c]}"))
                        break
                    }
                    if (name !in OPTION_HAS_VALUE) {
                        applyOption(out, name, "", false, notes)
                        c++
                        continue
                    }
                    var value = ""
                    if (c + 1 < token.length) value = token.substring(c + 1)
                    else if (i + 1 < argv.size) value = argv[++i]
                    applyOption(out, name, value, true, notes)
                    break
                }
                i++
                continue
            }
            // `--usergroup` outranks the URL path whichever came first.
            val group = out.server_path
            out.setServerUrl(token)
            if (group.isNotEmpty()) out.server_path = group
            i++
        }
    }

    /** vpnfiLooksLikeXml. */
    fun looksLikeXml(text: String): Boolean =
        text.startsWith("<?xml") || text.contains("<AnyConnectProfile") || text.contains("<ServerList")

    /** vpnfiIsOcCommandLine. */
    fun isCommandLine(text: String): Boolean {
        for (line in text.split('\n')) {
            val item = VpnFileImport.trimmed(line)
            if (item.isEmpty() || item.startsWith('#')) continue
            return item.startsWith("openconnect") || item.startsWith('-')
        }
        return false
    }

    /** QXmlStreamReader::readElementText(IncludeChildElements): every character run below the current element. */
    private fun readElementText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.nextToken()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF, XmlPullParser.IGNORABLE_WHITESPACE ->
                    sb.append(parser.text ?: "")
                XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Premature end of document.")
            }
        }
        return sb.toString()
    }

    /** vpnfiReadAnyConnectHosts; untrusted input: a DTD is the only place entities are declared. */
    fun readAnyConnectHosts(xml: String, out: MutableList<HostEntry>, notes: MutableList<String>): Boolean {
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            try {
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            } catch (e: XmlPullParserException) {
                // local names are then derived from the qualified name below
            }
            parser.setInput(StringReader(xml))
            var current = HostEntry()
            var inEntry = false
            // kxml2 ends a truncated document silently where QXmlStreamReader reports an error, hence the depth count.
            var depth = 0
            while (true) {
                val token = parser.nextToken()
                if (token == XmlPullParser.END_DOCUMENT) {
                    if (depth != 0) throw XmlPullParserException("Premature end of document.")
                    break
                }
                if (token == XmlPullParser.DOCDECL) {
                    notes.add(vtr(R.string.validation_oc_anyconnect_dtd))
                    return false
                }
                if (token == XmlPullParser.END_TAG) depth--
                if (token == XmlPullParser.START_TAG) {
                    val element = (parser.name ?: "").substringAfter(':')
                    if (element.equals("HostEntry", ignoreCase = true)) {
                        depth++
                        current = HostEntry()
                        inEntry = true
                        continue
                    }
                    if (!inEntry) {
                        depth++
                        continue
                    }
                    val text = VpnFileImport.trimmed(readElementText(parser))
                    if (text.isEmpty()) continue
                    if (element.equals("HostName", ignoreCase = true)) current.name = text
                    else if (element.equals("HostAddress", ignoreCase = true)) current.address = text
                    else if (element.equals("UserGroup", ignoreCase = true)) current.group = text
                    else if (element.equals("PrimaryProtocol", ignoreCase = true)) current.protocol = text
                    else if (element.endsWith("CertificateHash", ignoreCase = true)) current.fingerprints.add(text)
                    continue
                }
                if (token == XmlPullParser.END_TAG && inEntry &&
                    (parser.name ?: "").substringAfter(':').equals("HostEntry", ignoreCase = true)
                ) {
                    out.add(current)
                    current = HostEntry()
                    inEntry = false
                }
            }
        } catch (e: XmlPullParserException) {
            notes.add(vtr(R.string.validation_oc_anyconnect_malformed, e.message.orEmpty()))
            return false
        } catch (e: IOException) {
            notes.add(vtr(R.string.validation_oc_anyconnect_malformed, e.message.orEmpty()))
            return false
        }
        if (out.isEmpty()) {
            notes.add(vtr(R.string.validation_oc_anyconnect_no_hosts))
            return false
        }
        return true
    }

    /** vpnfiApplyHostEntry. */
    fun applyHostEntry(entry: HostEntry, out: OpenConnect, notes: MutableList<String>): Boolean {
        val address = if (entry.address.isEmpty()) entry.name else entry.address
        if (address.isEmpty()) {
            notes.add(vtr(R.string.validation_oc_host_no_address))
            return false
        }
        if (entry.protocol.equals("IPsec", ignoreCase = true)) {
            notes.add(vtr(R.string.validation_oc_host_ipsec, address))
            return false
        }
        out.flavor = "anyconnect"
        out.setServerUrl(address)
        if (out.server.isEmpty()) {
            notes.add(vtr(R.string.validation_oc_host_unreadable, address))
            return false
        }
        if (entry.group.isNotEmpty()) out.server_path = entry.group
        out.name = if (entry.name.isEmpty()) address else entry.name
        for (fingerprint in entry.fingerprints) out.tls.peer_fingerprint.add(fingerprint)
        return true
    }
}
