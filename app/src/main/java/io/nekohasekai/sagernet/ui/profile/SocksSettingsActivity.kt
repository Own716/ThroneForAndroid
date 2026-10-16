package io.nekohasekai.sagernet.ui.profile

import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.outbound.types.Socks
import io.nekohasekai.sagernet.outbound.types.SocksChecks
import io.nekohasekai.sagernet.ui.ValidationForm
import io.nekohasekai.sagernet.ui.json.ProfileJson
import io.nekohasekai.sagernet.validation.Severity

/** The version menu offers 5, 4 and 4a (version 4 with socks4a), as edit_socks.cpp. */
class SocksSettingsActivity : BindingSettingsActivity<Socks>() {

    override fun createEntity() = Socks()
    override val preferencesResource = R.xml.socks_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("username")
        pbm.text("password")
        pbm.bool("uot")
    }

    override fun Socks.init() {
        pbm.writeToCacheAll(this)
        DataStore.profileCacheStore.putString(KEY_RAW_JSON, ProfileJson.text(this))
        DataStore.profileCacheStore.putString("version", if (version != 4) "5" else if (socks4a) "4a" else "4")
    }

    override fun Socks.serialize() {
        pbm.fromCacheAll(this)
        version = menuVersion()
        socks4a = menuText() == "4a"
    }

    private fun menuText() = DataStore.profileCacheStore.getString("version")?.trim().orEmpty()

    private fun menuVersion() = if (menuText().startsWith("4")) 4 else 5

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        passwordSummary("password")
    }

    override fun ValidationForm.bindValidation() {
        addRule(listOf("password", "username", "version"), Severity.Warning) {
            val store = DataStore.profileCacheStore
            SocksChecks.socksAuth(menuVersion(), store.getString("username").orEmpty().trim(), store.getString("password").orEmpty())
        }
    }

}
