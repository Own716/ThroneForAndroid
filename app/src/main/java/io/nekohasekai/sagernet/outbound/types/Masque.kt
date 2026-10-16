package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.import.ClashProxy
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.SecurityLevel
import io.nekohasekai.sagernet.outbound.common.QuicFields
import io.nekohasekai.sagernet.outbound.common.Tls
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.validation.Empty
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.acceptOptional
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.jsonStringList
import io.nekohasekai.sagernet.validation.vtr

private val masqueHttpVersions = listOf("0", "2", "3")

/** The Checks of masque.h. */
object MasqueChecks {
    /** Base64 of DER; the core decodes it with Go's strict StdEncoding, padding included. */
    @JvmStatic
    fun masqueKey(text: String, empty: Empty): String {
        val t = text.trim()
        if (t.isEmpty()) return if (empty == Empty.Required) Validation.required(t) else ""
        if (t.length % 4 == 0 && Validation.base64(t).isEmpty()) return ""
        return vtr(R.string.validation_masque_key)
    }
}

/**
 * masque (include/configs/outbounds/masque.h, src/configs/outbounds/masque.cpp): a sing-box endpoint.
 * No share link exists.
 */
class Masque : Outbound("masque") {
    @JvmField var private_key: String = ""
    @JvmField var peer_public_key: String = ""
    @JvmField var address: MutableList<String> = ArrayList()
    @JvmField var mtu: Int = 1280
    /** 0 = HTTP/3 falling back to HTTP/2, 2 = HTTP/2, 3 = HTTP/3 (masque.h:14-15). */
    @JvmField var http_version: Int = 0
    @JvmField var disable_version_fallback: Boolean = false
    @JvmField var tls: Tls = Tls()
    @JvmField var quic: QuicFields = QuicFields()

    /** masque.h:21-26. */
    init {
        serverPort = 443
        tls.enabled = true
        tls.server_name = "consumer-masque.cloudflareclient.com"
    }

    override fun hasTls(): Boolean = true
    override fun mustTls(): Boolean = true
    override fun hasQuic(): Boolean = true
    override fun getTls(): Tls = tls
    override fun getQuic(): QuicFields = quic

    /** The core dials 443 when the port is 0. */
    override fun serverPortOptional(): Boolean = true

    /** masque::Validate (masque.cpp). */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        issues.addIssue(Severity.Error, "private_key", MasqueChecks.masqueKey(private_key, Empty.Required))
        issues.addIssue(Severity.Error, "peer_public_key", MasqueChecks.masqueKey(peer_public_key, Empty.Allowed))
        if (address.isEmpty()) {
            issues.addIssue(Severity.Error, "address", vtr(R.string.validation_masque_address_required))
        } else {
            issues.addIssue(
                Severity.Error, "address",
                Validation.eachItem(address.joinToString(","), Validation.Split.Comma, Validation.Rules.cidr()),
            )
        }
        val version = http_version.toString()
        issues.addIssue(Severity.Error, "http_version", Validation.oneOf(version, masqueHttpVersions), version)
    }

    /** masque::ParseFromClash (masque.cpp): mihomo never falls back between carriers, so `h2` pins HTTP/2 and anything else HTTP/3. */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "masque") return false
        baseParseFromClash(proxy)
        if (serverPort == 0) serverPort = 443
        private_key = proxy.string("private-key")
        peer_public_key = proxy.string("public-key")
        address.addAll(hostPrefixed(listOf(proxy.string("ip"), proxy.string("ipv6"))))
        val clashMtu = proxy.int("mtu")
        if (clashMtu > 0) mtu = clashMtu
        val sni = proxy.string("sni")
        if (sni.isNotEmpty()) tls.server_name = sni
        tls.insecure = proxy.bool("skip-cert-verify")
        tls.enabled = true
        if (proxy.string("network") == "h2") {
            http_version = 2
            disable_version_fallback = false
        } else {
            http_version = 3
            disable_version_fallback = true
        }
        return private_key.isNotEmpty()
    }

    /** masque::ParseFromJson (masque.cpp): an import drops local addresses that are not CIDR prefixes. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "masque") return false
        super.parseFromJson(obj)
        if (obj.contains("private_key")) private_key = obj.string("private_key")
        if (obj.contains("peer_public_key")) peer_public_key = obj.string("peer_public_key")
        // Listable: the core accepts a bare string as well as an array.
        if (obj.contains("address")) address = hostPrefixed(jsonStringList(obj["address"], "address"))
        if (ParseDiag.active()) address = address.filterTo(ArrayList()) { acceptOptional("address", Validation.cidr(it), it) }
        if (obj.contains("mtu")) mtu = obj.int("mtu")
        if (obj.contains("http_version")) {
            val version = obj.int("http_version")
            if (!ParseDiag.active() || acceptOptional("http_version", Validation.oneOf(version.toString(), masqueHttpVersions), version.toString())) {
                http_version = version
            }
        }
        if (obj.contains("disable_version_fallback")) disable_version_fallback = obj.bool("disable_version_fallback")
        if (obj.contains("tls")) {
            val tlsObject = obj.obj("tls")
            tls.parseFromJson(tlsObject)
            // An absent SNI means the server address (as in sing-box), not the constructor's WARP default.
            if (!tlsObject.contains("server_name")) tls.server_name = ""
        }
        tls.enabled = true
        quic.parseFromJson(obj)
        return true
    }

    /** masque.cpp:66-69. */
    override fun exportToLink(): String = ""

    /** masque.cpp:71-85. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "masque"
        obj.merge(baseExportToJson())
        if (private_key.isNotEmpty()) obj["private_key"] = private_key
        if (peer_public_key.isNotEmpty()) obj["peer_public_key"] = peer_public_key
        if (address.isNotEmpty()) obj["address"] = JsonValues.stringArray(address)
        if (mtu > 0) obj["mtu"] = mtu
        if (http_version != 0) obj["http_version"] = http_version
        if (disable_version_fallback) obj["disable_version_fallback"] = true
        obj["tls"] = tls.exportToJson()
        obj.merge(quic.exportToJson())
        return obj
    }

    /** masque.cpp:87-93: WARP identities share one server address; the key is what tells them apart. */
    override fun exportIdentity(): JsonObject {
        val obj = super.exportIdentity()
        obj["private_key"] = private_key
        return obj
    }

    /** masque.cpp:95-116. */
    override fun build(ctx: BuildContext): BuildResult {
        if (private_key.isEmpty()) return BuildResult(JsonObject(), "${displayTypeAndName()}: the private key is empty")
        if (address.isEmpty()) return BuildResult(JsonObject(), "${displayTypeAndName()}: no address is set")

        tls.enabled = true
        val obj = JsonObject()
        obj["type"] = "masque"
        if (name.isNotEmpty()) obj["tag"] = name
        obj.merge(baseBuild(ctx))
        obj["private_key"] = private_key
        if (peer_public_key.isNotEmpty()) obj["peer_public_key"] = peer_public_key
        obj["address"] = JsonValues.stringArray(address)
        if (mtu > 0) obj["mtu"] = mtu
        if (http_version != 0) obj["http_version"] = http_version
        if (disable_version_fallback) obj["disable_version_fallback"] = true
        // REALITY, the only TLS build error, is dropped here.
        val tlsObject = tls.build(ctx)
        tlsObject.remove("reality")
        obj["tls"] = tlsObject
        obj.merge(quic.build(ctx))
        return BuildResult(obj)
    }

    /** masque.cpp:118-121. */
    override fun displayType(): String = "MASQUE"

    /** masque.cpp:123-128: the peer key pins the server certificate, which the core enforces even when insecure is set. */
    override fun security(): SecurityInfo {
        if (peer_public_key.isNotEmpty()) return SecurityInfo("TLS", "", SecurityLevel.Secure)
        return securityFromTls("")
    }

    /** masque.cpp:130-133. */
    override fun isEndpoint(): Boolean = true

    companion object {
        /** masque::HostPrefixed (masque.cpp): bare addresses get /32 or /128, the core needs a prefix; blanks are dropped. */
        @JvmStatic
        fun hostPrefixed(addresses: List<String>): MutableList<String> {
            val result = ArrayList<String>()
            for (raw in addresses) {
                val address = raw.trim()
                if (address.isEmpty()) continue
                result.add(if (address.contains('/')) address else address + (if (address.contains(':')) "/128" else "/32"))
            }
            return result
        }
    }
}
