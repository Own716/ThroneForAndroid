package io.nekohasekai.sagernet.ui.profile

import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.outbound.types.XrayVless
import io.nekohasekai.sagernet.ui.ValidationForm
import io.nekohasekai.sagernet.validation.CommonChecks
import io.nekohasekai.sagernet.validation.Validation

/** The Xray-core VLESS outbound: uuid / encryption / flow, the Xray stream settings and the Xray mux. */
class XrayVlessSettingsActivity : BindingSettingsActivity<XrayVless>() {

    override fun createEntity() = XrayVless()
    override val preferencesResource = R.xml.xray_vless_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("uuid")
        pbm.text("encryption")
        pbm.text("flow")
        pbm.text("$STREAM.network")
        pbm.text("$STREAM.security")

        pbm.text("$STREAM.ws.host")
        pbm.text("$STREAM.ws.path")
        pbm.int("$STREAM.ws.ed")
        pbm.int("$STREAM.ws.heartbeatPeriod")
        pbm.text("$STREAM.ws.headers")

        pbm.text("$STREAM.httpupgrade.host")
        pbm.text("$STREAM.httpupgrade.path")
        pbm.int("$STREAM.httpupgrade.ed")
        pbm.text("$STREAM.httpupgrade.headers")

        pbm.text("$STREAM.grpc.serviceName")
        pbm.text("$STREAM.grpc.authority")
        pbm.bool("$STREAM.grpc.multiMode")

        pbm.text("$STREAM.xhttp.host")
        pbm.text("$STREAM.xhttp.path")
        pbm.text("$STREAM.xhttp.mode")
        pbm.text("$STREAM.xhttp.headers")
        pbm.text("$STREAM.xhttp.xPaddingBytes")
        pbm.bool("$STREAM.xhttp.noGRPCHeader")
        pbm.bool("$STREAM.xhttp.noSSEHeader")
        pbm.text("$STREAM.xhttp.scMaxEachPostBytes")
        pbm.text("$STREAM.xhttp.scMinPostsIntervalMs")
        pbm.int("$STREAM.xhttp.scMaxBufferedPosts")
        pbm.text("$STREAM.xhttp.scStreamUpServerSecs")
        pbm.text("$STREAM.xhttp.maxConcurrency")
        pbm.text("$STREAM.xhttp.maxConnections")
        pbm.text("$STREAM.xhttp.cMaxReuseTimes")
        pbm.text("$STREAM.xhttp.hMaxRequestTimes")
        pbm.text("$STREAM.xhttp.hMaxReusableSecs")
        pbm.int("$STREAM.xhttp.hKeepAlivePeriod")
        pbm.text("$STREAM.xhttp.downloadSettings")

        pbm.text("$STREAM.tls.serverName")
        pbm.text("$STREAM.tls.alpn")
        pbm.text("$STREAM.tls.fingerprint")
        pbm.text("$STREAM.tls.pinnedPeerCertSha256")
        pbm.text("$STREAM.tls.verifyPeerCertByName")
        pbm.text("$STREAM.tls.echConfigList")
        pbm.text("$STREAM.tls.cipherSuites")

        pbm.text("$STREAM.reality.serverName")
        pbm.text("$STREAM.reality.fingerprint")
        pbm.text("$STREAM.reality.password")
        pbm.text("$STREAM.reality.shortId")
        pbm.text("$STREAM.reality.spiderX")

        pbm.tri("multiplex.enabled", "multiplex.useDefault")
        pbm.int("multiplex.concurrency")
        pbm.int("multiplex.xudpConcurrency")

        jsonObjectText("$STREAM.finalmask", { streamSetting.finalmask }, { streamSetting.finalmask = it })
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        passwordSummary("uuid")
        numberInput(
            "$STREAM.ws.ed", "$STREAM.ws.heartbeatPeriod", "$STREAM.httpupgrade.ed",
            "$STREAM.xhttp.scMaxBufferedPosts", "$STREAM.xhttp.hKeepAlivePeriod",
            "multiplex.concurrency", "multiplex.xudpConcurrency",
        )
        multilineInput(
            "$STREAM.ws.headers", "$STREAM.httpupgrade.headers", "$STREAM.xhttp.headers", "$STREAM.tls.alpn",
        )
        // empty inherits the uTLS preset (xrayStreamSetting.cpp:312, 377)
        presetMenuSummary("$STREAM.tls.fingerprint", DataStore.utlsFingerprint)
        presetMenuSummary("$STREAM.reality.fingerprint", DataStore.utlsFingerprint)
        cipherSuitesInput("$STREAM.tls.cipherSuites", ":")
        presetTriSummary("multiplex.enabled", DataStore.xrayMuxDefaultOn)
        presetIntSummary("multiplex.concurrency", DataStore.xrayMuxConcurrency)

        val ws = findPreference<PreferenceCategory>("xrayWsCategory")
        val httpUpgrade = findPreference<PreferenceCategory>("xrayHttpUpgradeCategory")
        val grpc = findPreference<PreferenceCategory>("xrayGrpcCategory")
        val xhttp = findPreference<PreferenceCategory>("xrayXhttpCategory")
        onMenu("$STREAM.network") { network ->
            ws?.isVisible = network == "ws"
            httpUpgrade?.isVisible = network == "httpupgrade"
            grpc?.isVisible = network == "grpc"
            xhttp?.isVisible = network == "xhttp"
        }

        val tls = findPreference<PreferenceCategory>("xrayTlsCategory")
        val reality = findPreference<PreferenceCategory>("xrayRealityCategory")
        onMenu("$STREAM.security") { security ->
            tls?.isVisible = security == "tls"
            reality?.isVisible = security == "reality"
        }

        onMenu("multiplex.enabled") { setVisible(it == "1", "multiplex.concurrency", "multiplex.xudpConcurrency") }

        // dialog_edit_profile_xray.cpp, edit_xrayvless.cpp: a stored value the menu lacks is shown, and flagged by
        // validate, instead of being rewritten on save; a flow matching an entry but for case is canonicalised.
        findPreference<ListPreference>("$STREAM.security")?.keepUnlistedValue()
        val flowMenu = findPreference<ListPreference>("flow")
        if (flowMenu != null) {
            val flow = flowMenu.value.orEmpty().trim()
            val listed = flowMenu.entryValues.firstOrNull { it.toString().equals(flow, ignoreCase = true) }?.toString()
            if (listed != null) {
                if (listed != flowMenu.value) flowMenu.value = listed
            } else if (flow.equals("none", ignoreCase = true)) {
                flowMenu.value = ""
            } else {
                flowMenu.keepUnlistedValue()
            }
        }
    }

    /** xrayStreamSetting::Validate names the Xray JSON paths (streamSettings.tlsSettings.serverName …); rows are streamSetting.tls.*. */
    override fun issueKey(field: String): String? {
        if (field == "id") return "uuid"
        if (!field.startsWith("streamSettings.")) return null
        var rest = field.removePrefix("streamSettings.")
        val section = XRAY_SECTIONS[rest.substringBefore('.')]
        if (section != null) {
            rest = rest.substringAfter('.', "")
            if (section == "xhttp") {
                rest = rest.removePrefix("extra.").removePrefix("xmux.")
                // the download leg's address, port and network are all edited in its JSON row
                if (rest.startsWith("downloadSettings", ignoreCase = true)) rest = "downloadSettings"
            }
            rest = "$section.$rest"
        }
        val key = "$STREAM.$rest"
        return key.takeIf { k -> pbm.items.any { it.cacheName == k } }
    }

    // dialog_edit_profile_xray.cpp bindXrayStream / bindXrayXHTTP: what validate cannot see in the parsed model.
    override fun ValidationForm.bindValidation() {
        for (key in listOf("$STREAM.ws.host", "$STREAM.httpupgrade.host", "$STREAM.xhttp.host", "$STREAM.grpc.authority")) {
            bind(key) { CommonChecks.hostHeader(it, false) }.warning()
        }
        for (key in listOf("$STREAM.ws.headers", "$STREAM.httpupgrade.headers", "$STREAM.xhttp.headers")) bind(key, ::headerLines)
        for (key in listOf("$STREAM.ws.ed", "$STREAM.httpupgrade.ed")) bind(key, Validation.Rules.int(0, Int.MAX_VALUE.toLong()))
        bind("$STREAM.xhttp.scMaxBufferedPosts", Validation.Rules.int(0, Long.MAX_VALUE))
        bind("$STREAM.xhttp.hKeepAlivePeriod", Validation.Rules.int(Long.MIN_VALUE, Long.MAX_VALUE))
    }

    companion object {
        private const val STREAM = "streamSetting"

        private val XRAY_SECTIONS = mapOf(
            "tlsSettings" to "tls",
            "realitySettings" to "reality",
            "xhttpSettings" to "xhttp",
            "splithttpSettings" to "xhttp",
            "wsSettings" to "ws",
            "httpupgradeSettings" to "httpupgrade",
            "grpcSettings" to "grpc",
        )
    }

}
