package io.nekohasekai.sagernet.ui.profile

import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.outbound.types.Snell

/** Snell v4 (obfs) and v6 (mode); the core speaks no other client version. */
class SnellSettingsActivity : BindingSettingsActivity<Snell>() {

    override fun createEntity() = Snell()
    override val preferencesResource = R.xml.snell_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.int("version")
        pbm.text("psk")
        pbm.text("userkey")
        pbm.text("network")
        pbm.text("obfs_mode")
        pbm.text("obfs_host")
        pbm.text("mode")
        pbm.bool("reuse")
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        passwordSummary("psk", "userkey")
        onMenu("version") { version ->
            val v6 = version == "6"
            setVisible(!v6, "obfs_mode", "obfs_host")
            setVisible(v6, "mode")
        }
    }

    /** Validate's "ignored" warnings for rows the version hides go to the version menu. */
    override fun issueKey(field: String): String? {
        val v6 = DataStore.profileCacheStore.getString("version")?.trim() == "6"
        return if ((field == "obfs_mode" && v6) || (field == "mode" && !v6)) "version" else null
    }

}
