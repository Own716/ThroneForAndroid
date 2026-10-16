package io.nekohasekai.sagernet.ui.profile

import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.outbound.types.OpenConnect
import io.nekohasekai.sagernet.outbound.types.OpenConnectChecks
import io.nekohasekai.sagernet.ui.ValidationForm
import io.nekohasekai.sagernet.validation.Validation

/**
 * OpenConnect endpoint: the primary fields on the screen, the full option set through the Raw JSON preference. The
 * desktop's Advanced dialog and table checks run in OpenConnect.validate; issues of fields without a row are listed at save.
 */
class OpenConnectSettingsActivity : BindingSettingsActivity<OpenConnect>() {

    override fun createEntity() = OpenConnect()
    override val preferencesResource = R.xml.openconnect_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("server_path")
        pbm.text("flavor")
        pbm.text("username")
        pbm.text("password")
        pbm.text("auth_group")
        pbm.int("mtu")
        pbm.text("tls.server_name")
        pbm.bool("tls.insecure")
        pbm.text("tls.certificate_authority")
        pbm.text("tls.client_certificate")
        pbm.text("tls.client_key")
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        numberInput("mtu")
        passwordSummary("password")
        multilineInput("tls.certificate_authority", "tls.client_certificate", "tls.client_key")
    }

    /** edit_openconnect.cpp bindValidation: everything else it binds is checked by OpenConnect.validate. */
    override fun ValidationForm.bindValidation() {
        bind("mtu", Validation.Rules.int(0, 65535))
        // The reported OS is set in Raw JSON, so the protocol row says why the pair fails.
        addRule(listOf("flavor")) {
            val os = ensureEditingOutbound().reported_os
            val flavor = DataStore.profileCacheStore.getString("flavor").orEmpty().trim()
            if (OpenConnectChecks.openConnectReportedOs(flavor, os).isEmpty()) ""
            else getString(R.string.validation_oc_flavor_reported_os, os)
        }
    }

}
