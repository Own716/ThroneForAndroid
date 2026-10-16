package io.nekohasekai.sagernet.ui.profile

import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.outbound.types.Custom
import io.nekohasekai.sagernet.ui.json.engine.SchemaStore
import moe.matsuri.nb4a.ui.EditConfigPreference

/** A raw JSON profile in one of the four custom subtypes (sing-box / Xray, outbound / full config). */
class CustomSettingsActivity : BindingSettingsActivity<Custom>() {

    override fun createEntity() = Custom().apply { subtype = Custom.CUSTOM_OUTBOUND }
    override val preferencesResource = R.xml.custom_preferences
    override val supportsRawJson = false

    init {
        pbm.text("name")
        pbm.text("subtype")
        pbm.text("config", Key.SERVER_CONFIG)
    }

    /**
     * edit_custom.cpp binds the config text to Checks::CustomConfig, which Custom.validate runs on the untrimmed text;
     * every issue but the subtype's is about the config, nested Xray paths included.
     */
    override fun issueKey(field: String): String? = if (field == "subtype") null else Key.SERVER_CONFIG

    /** Custom.validate parses the whole config; only the subtype and the config change what it finds. */
    override fun validationInputs(): Any = DataStore.profileCacheStore.let {
        listOf(it.getString("subtype"), it.getString(Key.SERVER_CONFIG))
    }

    private var editConfigPreference: EditConfigPreference? = null

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        editConfigPreference = findPreference<EditConfigPreference>(Key.SERVER_CONFIG)?.apply {
            schemaProvider = { schemaRoots(DataStore.profileCacheStore.getString("subtype")) }
        }
    }

    override fun onResume() {
        super.onResume()
        editConfigPreference?.notifyChanged()
    }

    /** edit_custom.cpp:88-100: sing-box outbound or endpoint, sing-box config, nothing for the Xray subtypes. */
    private fun schemaRoots(subtype: String?): List<String> = when (subtype) {
        Custom.CUSTOM_OUTBOUND -> listOf(SchemaStore.OUTBOUND, SchemaStore.ENDPOINT)
        Custom.CUSTOM_FULL_CONFIG -> listOf(SchemaStore.CONFIG)
        else -> emptyList()
    }

}
