package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.databinding.LayoutAddEntityBinding
import io.nekohasekai.sagernet.databinding.LayoutProfileBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.types.Chain
import io.nekohasekai.sagernet.outbound.types.Custom
import io.nekohasekai.sagernet.ui.FormIssue
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.ui.ValidationForm
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.vplural
import io.nekohasekai.sagernet.widget.applyInsetPadding
import io.nekohasekai.sagernet.widget.applyListInsets
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.proxy.PreferenceBindingManager

class ChainSettingsActivity : ProfileSettingsActivity<Chain>(R.layout.layout_chain_settings) {

    override fun createEntity() = Chain()
    override val supportsRawJson = false

    private val pbm = PreferenceBindingManager().apply { text("name") }

    private companion object {
        const val KEY_CHAIN_IDS = "chainProxyIds"

        /** The row above the hop list that carries the list's problems (name_preferences.xml). */
        const val KEY_HOPS = "chainHops"
    }

    val proxyList = ArrayList<ProxyEntity>()

    /** Stored hops left out on load because their profile no longer exists. */
    private var droppedHops = 0
    private var hopsLoaded = false

    override fun issueKey(field: String): String? = if (field == "list") KEY_HOPS else null

    /** edit_chain.cpp bindValidation. */
    override fun ValidationForm.bindValidation() {
        addIssueSource {
            if (!hopsLoaded) return@addIssueSource emptyList()
            val label = getString(R.string.validation_chain_hops)
            val issues = ArrayList<FormIssue>()
            hopsError().takeIf { it.isNotEmpty() }?.let { issues.add(FormIssue(Severity.Error, label, it, KEY_HOPS)) }
            if (droppedHops > 0) {
                issues.add(FormIssue(Severity.Warning, label, vplural(R.plurals.validation_chain_dropped, droppedHops), KEY_HOPS))
            }
            issues
        }
    }

    private fun hopsChanged() {
        form?.revalidate(KEY_HOPS)
    }

    private fun refreshForm() {
        form?.refresh()
    }

    private fun storedHopIds(): List<Long> = (DataStore.profileCacheStore.getString(KEY_CHAIN_IDS) ?: "").split(",")
        .mapNotNull { it.takeIf { it.isNotBlank() }?.toLongOrNull() }

    /** Until the hops are loaded (off the main thread) the stored ids stand in for them, so a chain does not flash "empty". */
    override fun validationOutbound(): Chain {
        if (hopsLoaded) return super.validationOutbound()
        val copy = createEntity()
        copy.parseFromJson(ensureEditingOutbound().exportToJson())
        pbm.fromCacheAll(copy)
        copy.list = ArrayList(storedHopIds())
        return copy
    }

    private fun hopsError(): String {
        if (proxyList.isEmpty()) return getString(R.string.validation_chain_empty)
        // The picker only refuses circular references; the chain builder refuses these too.
        for ((i, hop) in proxyList.withIndex()) {
            when (hop.type) {
                "chain" -> return getString(R.string.validation_chain_hop_chain, i + 1)
                "autoselector" -> return getString(R.string.validation_chain_hop_autoselector, i + 1)
            }
        }
        return scanError(proxyList.map { it.outbound })
    }

    /**
     * edit_chain.cpp editChainScanError: generate.cpp's chain scan over the build order, which reads the list
     * bottom-up. The extra-core rules are left out, Android has no extra cores.
     */
    private fun scanError(topFirst: List<Outbound>): String {
        val count = topFirst.size
        var xrayFullConfigs = 0
        var xrayFullConfigIndex = -1
        var xrayHops = 0
        var transitions = 0
        var customFullConfig = false
        var inXray = false
        for (k in 0 until count) {
            val hop = topFirst[count - 1 - k]
            val xray = hop.isXray()
            if (xray != inXray) transitions++
            inXray = xray
            if (hop.isXrayFullConfig()) {
                xrayFullConfigs++
                xrayFullConfigIndex = k
            }
            if (xray) xrayHops++
            if ((hop as? Custom)?.isFullConfig() == true) customFullConfig = true
        }
        if (customFullConfig) return getString(R.string.validation_chain_custom_full_config)
        if (xrayFullConfigs > 1) return getString(R.string.validation_chain_xray_full_configs)
        if (xrayFullConfigs > 0 && xrayHops > 0) return getString(R.string.validation_chain_xray_full_config_mixed)
        // A custom Xray config is reached through its local socks port, so nothing can come after it.
        if (xrayFullConfigs == 1 && count > 1 && xrayFullConfigIndex != count - 1) {
            return getString(R.string.validation_chain_xray_full_config_last)
        }
        if (transitions > 2) return getString(R.string.validation_chain_transitions)
        return ""
    }

    override fun Chain.init() {
        pbm.writeToCacheAll(this)
        DataStore.profileCacheStore.putString(KEY_CHAIN_IDS, list.joinToString(","))
    }

    override fun Chain.serialize() {
        pbm.fromCacheAll(this)
        list = ArrayList(proxyList.map { it.id })
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.name_preferences)
        pbm.setPreferenceFragment(this)
    }

    lateinit var configurationList: RecyclerView
    lateinit var configurationAdapter: ProxiesAdapter
    lateinit var layoutManager: LinearLayoutManager

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar!!.setTitle(R.string.chain_settings)
        configurationList = findViewById(R.id.configuration_list)
        configurationList.applyListInsets()
        layoutManager = FixedLinearLayoutManager(configurationList)
        configurationList.layoutManager = layoutManager
        configurationAdapter = ProxiesAdapter()
        configurationList.adapter = configurationAdapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is ProfileHolder) {
                super.getSwipeDirs(recyclerView, viewHolder)
            } else 0

            override fun getDragDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is ProfileHolder) {
                super.getDragDirs(recyclerView, viewHolder)
            } else 0

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                return if (target !is ProfileHolder) false else {
                    configurationAdapter.move(
                        viewHolder.bindingAdapterPosition, target.bindingAdapterPosition
                    )
                    true
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                configurationAdapter.remove(viewHolder.bindingAdapterPosition)
            }

        }).attachToRecyclerView(configurationList)
    }

    override fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        // the profile list below takes the bottom inset
        listView.applyInsetPadding(horizontal = true)
        view.rootView.findViewById<RecyclerView>(R.id.recycler_view).apply {
            (layoutParams ?: LinearLayout.LayoutParams(-1, -2)).apply {
                height = -2
                layoutParams = this
            }
        }

        runOnDefaultDispatcher {
            configurationAdapter.reload()
        }
    }

    inner class ProxiesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        suspend fun reload() {
            val idList = storedHopIds()
            var dropped = 0
            if (idList.isNotEmpty()) {
                val profiles = ProfileManager.getProfiles(idList).map { it.id to it }.toMap()
                for (id in idList) {
                    val profile = profiles[id]
                    if (profile == null) dropped++ else proxyList.add(profile)
                }
            }
            onMainDispatcher {
                droppedHops = dropped
                hopsLoaded = true
                notifyDataSetChanged()
                refreshForm()
            }
        }

        fun move(from: Int, to: Int) {
            val toMove = proxyList[to - 1]
            proxyList[to - 1] = proxyList[from - 1]
            proxyList[from - 1] = toMove
            notifyItemMoved(from, to)
            DataStore.dirty = true
            hopsChanged()
        }

        fun remove(index: Int) {
            proxyList.removeAt(index - 1)
            notifyItemRemoved(index)
            DataStore.dirty = true
            hopsChanged()
        }

        override fun getItemId(position: Int): Long {
            return if (position == 0) 0 else proxyList[position - 1].id
        }

        override fun getItemViewType(position: Int): Int {
            return if (position == 0) 0 else 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                AddHolder(LayoutAddEntityBinding.inflate(layoutInflater, parent, false))
            } else {
                ProfileHolder(LayoutProfileBinding.inflate(layoutInflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is AddHolder) {
                holder.bind()
            } else if (holder is ProfileHolder) {
                holder.bind(proxyList[position - 1])
            }
        }

        override fun getItemCount(): Int {
            return proxyList.size + 1
        }

    }

    fun testProfileAllowed(profile: ProxyEntity): Boolean {
        if (profile.id == DataStore.editingId) return false

        for (entity in proxyList) {
            if (testProfileContains(entity, profile)) return false
        }

        return true
    }

    fun testProfileContains(profile: ProxyEntity, anotherProfile: ProxyEntity): Boolean {
        if (!profile.isChain() || !anotherProfile.isChain()) return false
        if (profile.id == anotherProfile.id) return true
        val proxies = profile.requireChain().list
        if (proxies.contains(anotherProfile.id)) return true
        if (proxies.isNotEmpty()) {
            for (entity in ProfileManager.getProfiles(proxies)) {
                if (testProfileContains(entity, anotherProfile)) {
                    return true
                }
            }
        }
        return false
    }

    var replacing = 0

    val selectProfileForAdd =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { (resultCode, data) ->
            if (resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
                DataStore.dirty = true

                val profile = ProfileManager.getProfile(
                    data!!.getLongExtra(
                        ProfileSelectActivity.EXTRA_PROFILE_ID, 0
                    )
                )!!

                if (!testProfileAllowed(profile)) {
                    onMainDispatcher {
                        MaterialAlertDialogBuilder(this@ChainSettingsActivity).setTitle(R.string.circular_reference)
                            .setMessage(R.string.circular_reference_sum)
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                } else {
                    configurationList.post {
                        if (replacing != 0) {
                            proxyList[replacing - 1] = profile
                            configurationAdapter.notifyItemChanged(replacing)
                        } else {
                            proxyList.add(profile)
                            configurationAdapter.notifyItemInserted(proxyList.size)
                        }
                        hopsChanged()
                    }
                }
            }
        }

    inner class AddHolder(val binding: LayoutAddEntityBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.root.setOnClickListener {
                replacing = 0
                selectProfileForAdd.launch(
                    Intent(
                        this@ChainSettingsActivity, ProfileSelectActivity::class.java
                    )
                )
            }
        }
    }

    inner class ProfileHolder(binding: LayoutProfileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        val profileName = binding.profileName
        val profileType = binding.profileType
        val trafficText: TextView = binding.trafficText
        val editButton = binding.edit
        val shareLayout = binding.share

        fun bind(proxyEntity: ProxyEntity) {

            profileName.text = proxyEntity.displayName()
            profileType.text = proxyEntity.displayType()
            profileType.setTextColor(getProtocolColor(proxyEntity.type))

            val rx = proxyEntity.rx
            val tx = proxyEntity.tx

            val showTraffic = rx + tx != 0L
            trafficText.isVisible = showTraffic
            if (showTraffic) {
                trafficText.text = itemView.context.getString(
                    R.string.traffic,
                    Formatter.formatFileSize(itemView.context, tx),
                    Formatter.formatFileSize(itemView.context, rx)
                )
            }

            editButton.setOnClickListener {
                replacing = bindingAdapterPosition
                selectProfileForAdd.launch(Intent(
                    this@ChainSettingsActivity, ProfileSelectActivity::class.java
                ).apply {
                    putExtra(ProfileSelectActivity.EXTRA_SELECTED, proxyEntity)
                })
            }

            shareLayout.isVisible = false
        }

    }

}
