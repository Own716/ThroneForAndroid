package io.nekohasekai.sagernet.ui.profile

import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.outbound.common.XrayChecks
import io.nekohasekai.sagernet.outbound.common.XrayXDrive
import io.nekohasekai.sagernet.outbound.common.normalizeXrayNetwork
import io.nekohasekai.sagernet.outbound.types.XrayVless
import io.nekohasekai.sagernet.outbound.types.XrayVlessChecks
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

        pbm.text(XDRIVE_SERVICE)
        pbm.text("$STREAM.xdrive.remoteFolder")
        pbm.text("$STREAM.xdrive.frontAddress")
        pbm.int(XDRIVE_FRONT_PORT)
        for ((name, _) in XrayXDrive.TUNING) pbm.int("$STREAM.xdrive.$name")

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

        XrayMuxBlock.bind(pbm)

        jsonObjectText("$STREAM.finalmask", { streamSetting.finalmask }, { streamSetting.finalmask = it })
        jsonObjectText(XDRIVE_TEMPLATE, { streamSetting.xdrive.templateSpec }, { streamSetting.xdrive.templateSpec = it })
    }

    // dialog_edit_profile_xdrive.cpp: one secrets list backs the Google Drive rows and the template's list, and an
    // unset port or tuning value shows as the Xray default.
    override fun XrayVless.writeUnboundToCache() {
        val store = DataStore.profileCacheStore
        val secrets = streamSetting.xdrive.secrets
        GOOGLE_DRIVE_SECRETS.forEachIndexed { i, key -> store.putString(key, secrets.getOrElse(i) { "" }) }
        store.putString(XDRIVE_SECRETS, secrets.joinToString("\n"))
        for (key in listOf(XDRIVE_FRONT_PORT) + XrayXDrive.TUNING.map { "$STREAM.xdrive.${it.first}" }) {
            if (store.getString(key)?.trim() == "0") store.putString(key, "")
        }
    }

    override fun XrayVless.readUnboundFromCache() {
        if (normalizeXrayNetwork(streamSetting.network) != "xdrive") return
        val xdrive = streamSetting.xdrive
        // saveXrayXDrive trims these: a pasted folder ID with a trailing space would find nothing on the Drive
        xdrive.service = xdrive.service.trim()
        xdrive.remoteFolder = xdrive.remoteFolder.trim()
        xdrive.frontAddress = xdrive.frontAddress.trim()
        xdrive.secrets = secretsFromUi(xdrive.service, xdrive.secrets)
    }

    /** xdriveSecretsFromUi (dialog_edit_profile_xdrive.cpp): the rows [service] shows; any other service keeps [stored]. */
    private fun secretsFromUi(service: String, stored: List<String>): MutableList<String> {
        val store = DataStore.profileCacheStore
        return when (service) {
            "template" -> store.getString(XDRIVE_SECRETS).orEmpty().split('\n').map { it.trim() }
                .filterTo(ArrayList()) { it.isNotEmpty() }
            // Xray takes exactly these three, so entries a list carried past them are dropped.
            "google-drive" -> GOOGLE_DRIVE_SECRETS.mapTo(ArrayList()) { store.getString(it).orEmpty().trim() }
            else -> ArrayList(stored)
        }
    }

    private fun cached(key: String) = DataStore.profileCacheStore.getString(key)?.trim().orEmpty()

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        passwordSummary("uuid")
        numberInput(
            "$STREAM.ws.ed", "$STREAM.ws.heartbeatPeriod", "$STREAM.httpupgrade.ed",
            "$STREAM.xhttp.scMaxBufferedPosts", "$STREAM.xhttp.hKeepAlivePeriod",
        )
        multilineInput(
            "$STREAM.ws.headers", "$STREAM.httpupgrade.headers", "$STREAM.xhttp.headers", "$STREAM.tls.alpn",
        )
        // empty inherits the uTLS preset (xrayStreamSetting.cpp:312, 377)
        presetMenuSummary("$STREAM.tls.fingerprint", DataStore.utlsFingerprint)
        presetMenuSummary("$STREAM.reality.fingerprint", DataStore.utlsFingerprint)
        cipherSuitesInput("$STREAM.tls.cipherSuites", ":")
        XrayMuxBlock.setup(this)

        val ws = findPreference<PreferenceCategory>("xrayWsCategory")
        val httpUpgrade = findPreference<PreferenceCategory>("xrayHttpUpgradeCategory")
        val grpc = findPreference<PreferenceCategory>("xrayGrpcCategory")
        val xhttp = findPreference<PreferenceCategory>("xrayXhttpCategory")
        val xdrive = findPreference<PreferenceCategory>("xrayXDriveCategory")
        val xdriveTuning = findPreference<PreferenceCategory>("xrayXDriveTuningCategory")
        onMenu("$STREAM.network") { network ->
            ws?.isVisible = network == "ws"
            httpUpgrade?.isVisible = network == "httpupgrade"
            grpc?.isVisible = network == "grpc"
            xhttp?.isVisible = network == "xhttp"
            xdrive?.isVisible = network == "xdrive"
            xdriveTuning?.isVisible = network == "xdrive"
        }

        // dialog_edit_profile_xdrive.cpp updateXrayXDriveControls
        findPreference<ListPreference>(XDRIVE_SERVICE)?.keepUnlistedValue()
        onMenu(XDRIVE_SERVICE) { service ->
            setVisible(service == "google-drive", *GOOGLE_DRIVE_SECRETS)
            setVisible(service == "template", XDRIVE_SECRETS, XDRIVE_TEMPLATE)
            setVisible(service != "local", "$STREAM.xdrive.frontAddress", XDRIVE_FRONT_PORT)
        }
        passwordSummary(*GOOGLE_DRIVE_SECRETS, XDRIVE_SECRETS)
        multilineInput(XDRIVE_SECRETS)
        portInput(XDRIVE_FRONT_PORT)
        presetTextSummary(XDRIVE_FRONT_PORT, "", "443")
        for ((name, xrayDefault) in XDRIVE_TUNING_DEFAULTS) {
            numberInput("$STREAM.xdrive.$name")
            presetTextSummary("$STREAM.xdrive.$name", "", xrayDefault)
        }

        val tls = findPreference<PreferenceCategory>("xrayTlsCategory")
        val reality = findPreference<PreferenceCategory>("xrayRealityCategory")
        onMenu("$STREAM.security") { security ->
            tls?.isVisible = security == "tls"
            reality?.isVisible = security == "reality"
        }

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
        when (field) {
            "id" -> return "uuid"
            "mux" -> return "multiplex.enabled"
            "mux.concurrency", "mux.maxConnections", "mux.xudpConcurrency", "mux.xudpProxyUDP443" ->
                return "multiplex." + field.removePrefix("mux.")
            // only XDRIVE uses the stream's own port: its front port
            "streamSettings.port" -> return XDRIVE_FRONT_PORT
            "streamSettings.xdriveSettings.secrets" ->
                return if (cached(XDRIVE_SERVICE) == "template") XDRIVE_SECRETS else GOOGLE_DRIVE_SECRETS[0]
            "streamSettings.xdriveSettings.template" -> return XDRIVE_TEMPLATE
        }
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

        // dialog_edit_profile_xdrive.cpp bindXrayXDrive, edit_xrayvless.cpp; the cross rules mark every row they read
        for ((name, _) in XrayXDrive.TUNING) bind("$STREAM.xdrive.$name", XrayChecks::xrayXDriveTuning)
        bind(XDRIVE_FRONT_PORT, XrayChecks::xrayXDriveFrontPort)
        addRule(GOOGLE_DRIVE_SECRETS.toList() + XDRIVE_SECRETS + XDRIVE_SERVICE) {
            // only google-drive checks its secrets, and it never keeps the stored ones
            val service = cached(XDRIVE_SERVICE)
            XrayChecks.xrayXDriveSecrets(service, secretsFromUi(service, emptyList()))
        }
        addRule(listOf("encryption", "$STREAM.network")) {
            XrayVlessChecks.xrayXDriveEncryption(cached("encryption"), cached("$STREAM.network"))
        }
        XrayMuxBlock.bindValidation(this)
        addRule(listOf("multiplex.enabled", "$STREAM.network")) {
            XrayVlessChecks.xrayXDriveMux(cached("multiplex.enabled").toIntOrNull() ?: 0, cached("$STREAM.network"))
        }
    }

    companion object {
        private const val STREAM = "streamSetting"
        private const val XDRIVE_SERVICE = "$STREAM.xdrive.service"
        private const val XDRIVE_SECRETS = "$STREAM.xdrive.secrets"
        private const val XDRIVE_TEMPLATE = "$STREAM.xdrive.template"
        private const val XDRIVE_FRONT_PORT = "$STREAM.xdrive.frontPort"

        /** No fields of their own: secrets[0..2] while the service is google-drive. */
        private val GOOGLE_DRIVE_SECRETS = arrayOf(
            "$STREAM.xdrive.clientId", "$STREAM.xdrive.clientSecret", "$STREAM.xdrive.refreshToken",
        )

        /** dialog_edit_profile_xdrive.cpp xdriveTuningEdits: what 0 leaves Xray at. */
        private val XDRIVE_TUNING_DEFAULTS = listOf(
            "segmentBytes" to "524288",
            "flushIntervalMs" to "20",
            "pollIntervalMs" to "50",
            "maxPollIntervalMs" to "500",
            "eagerWindowMs" to "2000",
            "holeTimeoutMs" to "30000",
            "sessionTtlSeconds" to "300",
            "concurrency" to "8",
        )

        private val XRAY_SECTIONS = mapOf(
            "tlsSettings" to "tls",
            "realitySettings" to "reality",
            "xhttpSettings" to "xhttp",
            "splithttpSettings" to "xhttp",
            "wsSettings" to "ws",
            "httpupgradeSettings" to "httpupgrade",
            "grpcSettings" to "grpc",
            "xdriveSettings" to "xdrive",
        )
    }

}
