package io.nekohasekai.sagernet.ui.profile

import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.outbound.types.Naive
import io.nekohasekai.sagernet.outbound.types.NaiveChecks
import io.nekohasekai.sagernet.ui.ValidationForm
import io.nekohasekai.sagernet.ui.json.ProfileJson
import io.nekohasekai.sagernet.validation.CommonChecks
import io.nekohasekai.sagernet.validation.Validation

class NaiveSettingsActivity : BindingSettingsActivity<Naive>() {

    override fun createEntity() = Naive()
    override val preferencesResource = R.xml.naive_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("username")
        pbm.text("password")
        pbm.bool("quic")
        pbm.text("congestion_control")
        pbm.int("insecure_concurrency")
        pbm.bool("uot")
        // naive reads only server name, certificates, ECH and the fragment flag of the TLS block
        pbm.text("tls.server_name")
        pbm.text("tls.certificate")
        pbm.tri("tls.fragment", "tls.fragment_unspecified")
        pbm.bool("tls.ech.enabled")
        pbm.text("tls.ech.config")
        pbm.text("tls.ech.serverName")
        pbm.text("tls.ech.resolver")
    }

    /** The header text init() wrote and the list it shows; null on a recreated screen until first read. */
    private var loaded: Pair<String, List<String>>? = null

    // edit_naive.cpp: the extra headers are edited as Name="value" text (Configs::getHeadersString / parseHeaderPairs)
    override fun Naive.init() {
        pbm.writeToCacheAll(this)
        DataStore.profileCacheStore.putString(KEY_RAW_JSON, ProfileJson.text(this))
        val text = headersText(extra_headers)
        loaded = text to ArrayList(extra_headers)
        DataStore.profileCacheStore.putString("extra_headers", text)
    }

    override fun Naive.serialize() {
        pbm.fromCacheAll(this)
        extra_headers = ArrayList(headersOf(DataStore.profileCacheStore.getString("extra_headers").orEmpty()))
    }

    /**
     * Configs::getHeadersString, one header per line. The desktop shows nothing for an unpaired list (the old Android
     * editor left them), so saving dropped every header; its last item is shown on its own instead, which the form flags.
     */
    private fun headersText(headers: List<String>): String =
        headers.chunked(2).joinToString("\n") { if (it.size == 2) "${it[0]}=\"${it[1]}\"" else "\"${it[0]}\"" }

    private fun loadedHeaders(): Pair<String, List<String>> =
        loaded ?: ensureEditingOutbound().extra_headers.let { headersText(it) to ArrayList(it) }.also { loaded = it }

    /**
     * The headers [text] stands for. The text format has no escape for a quote inside a value (parseHeaderPairs), so
     * until the text is edited it stands for the list it was made from, not for what it parses to.
     */
    private fun headersOf(text: String): List<String> {
        val (shown, list) = loadedHeaders()
        return if (text.trim() == shown.trim()) list else headerPairs(text)
    }

    /** A line break separates headers like a space. */
    private fun headerPairs(text: String) = CommonChecks.parseHeaderPairs(text.replace('\n', ' '))

    override fun issueKey(field: String): String? = if (field == "quic_congestion_control") "congestion_control" else null

    override fun ValidationForm.bindValidation() {
        bind("extra_headers") { text ->
            val (shown, list) = loadedHeaders()
            // An unchanged paired list is kept as it is; an unpaired one sends no header at all, so it stays reported.
            if (text == shown.trim() && list.size % 2 == 0) "" else NaiveChecks.naiveExtraHeaders(text.replace('\n', ' '))
        }
        bind("extra_headers") { NaiveChecks.naiveExtraHeaderNames(headersOf(it)) }.warning()
        bind("insecure_concurrency", Validation.Rules.int(0, Int.MAX_VALUE.toLong()))
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        numberInput("insecure_concurrency")
        passwordSummary("password")
        multilineInput("extra_headers", "tls.certificate", "tls.ech.config")
        echInputs("tls.ech")
        onSwitch("tls.ech.enabled") { setVisible(it, "tls.ech.config", "tls.ech.queryTarget") }
        // limited TLS: only the custom (dialer-level) fragment reaches naive (dialog_edit_profile.cpp:765)
        if (DataStore.fragmentImplementation == "custom") {
            presetTriSummary("tls.fragment", DataStore.fragmentDefaultOn)
        } else {
            findPreference<Preference>("tls.fragment")?.isEnabled = false
            fixedSummary("tls.fragment", R.string.preset_fragment_custom_only)
        }
    }

}
