package io.nekohasekai.sagernet.ui.profile

import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.types.Ssh

class SSHSettingsActivity : BindingSettingsActivity<Ssh>() {

    override fun createEntity() = Ssh()
    override val preferencesResource = R.xml.ssh_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("user")
        pbm.text("password")
        pbm.text("private_key")
        pbm.text("private_key_passphrase")
        pbm.text("host_key")
        pbm.text("host_key_algorithms")
        pbm.text("client_version")
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        passwordSummary("password", "private_key_passphrase")
        multilineInput("private_key", "host_key", "host_key_algorithms")
    }

    /** ssh::Validate files the missing-authentication warning under "auth"; edit_ssh.cpp anchors it on the password. */
    override fun issueKey(field: String): String? = if (field == "auth") "password" else null

}
