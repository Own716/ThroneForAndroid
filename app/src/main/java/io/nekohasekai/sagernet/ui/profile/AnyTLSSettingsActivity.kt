package io.nekohasekai.sagernet.ui.profile

import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.types.AnyTls
import io.nekohasekai.sagernet.ui.ValidationForm
import io.nekohasekai.sagernet.validation.Validation

class AnyTLSSettingsActivity : BindingSettingsActivity<AnyTls>() {

    override fun createEntity() = AnyTls()
    override val preferencesResource = R.xml.anytls_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("password")
        pbm.text("idle_session_check_interval")
        pbm.text("idle_session_timeout")
        pbm.int("min_idle_session")
        TlsBlock.bind(pbm)
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        numberInput("min_idle_session")
        passwordSummary("password")
        TlsBlock.setup(this, mustTls = true)
    }

    // edit_anytls.cpp: text the binding would read as 0
    override fun ValidationForm.bindValidation() {
        bind("min_idle_session", Validation.Rules.int(0, Int.MAX_VALUE.toLong()))
    }

}
