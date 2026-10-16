package io.nekohasekai.sagernet.ui.profile

import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.outbound.types.Shadowsocks
import io.nekohasekai.sagernet.ui.json.ProfileJson

class ShadowsocksSettingsActivity : BindingSettingsActivity<Shadowsocks>() {

    override fun createEntity() = Shadowsocks()
    override val preferencesResource = R.xml.shadowsocks_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("method")
        pbm.text("password")
        pbm.text("plugin")
        pbm.text("plugin_opts")
        pbm.bool("uot")
        MuxBlock.bind(pbm)
    }

    override fun Shadowsocks.init() {
        pbm.writeToCacheAll(this)
        DataStore.profileCacheStore.putString(KEY_RAW_JSON, ProfileJson.text(this))
        // edit_shadowsocks.cpp: older entries may hold the obfs-local plugin under its simple-obfs or Clash name
        val pluginName = plugin.trim()
        if (pluginName == "simple-obfs" || pluginName == "obfs") DataStore.profileCacheStore.putString("plugin", "obfs-local")
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        passwordSummary("password")
        MuxBlock.setup(this)
    }

}
