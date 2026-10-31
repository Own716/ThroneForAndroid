package io.nekohasekai.sagernet.ui.profile

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.ktx.dp2px
import androidx.annotation.PluralsRes
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorProfiles
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SettingsMapper
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.outbound.config.AutoSelectorPlan
import io.nekohasekai.sagernet.outbound.config.AutoSelectorPlanner
import io.nekohasekai.sagernet.outbound.config.AutoSelectorSkip
import io.nekohasekai.sagernet.outbound.types.AutoSelector
import io.nekohasekai.sagernet.outbound.types.AutoSelectorChecks
import io.nekohasekai.sagernet.ui.FormIssue
import io.nekohasekai.sagernet.ui.ValidationForm
import io.nekohasekai.sagernet.validation.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * EditAutoSelector (edit_autoselector.cpp + .ui): the tracked groups, the name filter, balancing, the preferred profile
 * with "Use automatic", the live plan summary and the collapsed Advanced groups with the desktop's limits. Saving
 * goes through the validation form ([bindValidation]) and prunes `pool` to the members that still qualify.
 */
class AutoSelectorSettingsActivity : BindingSettingsActivity<AutoSelector>() {

    companion object {
        /** "Servers from": the checked group ids as a string set; [AutoSelector.gids] is not a binding. */
        const val KEY_GIDS = "gids"
        const val KEY_PIDS = "pids"
        const val KEY_PINNED = "autoselPinned"
        const val KEY_PLAN = "autoselPlan"
        const val KEY_ADVANCED = "autoselAdvanced"
        const val KEY_BALANCE_HINT = "autoselBalanceHint"
        const val STATE_ADVANCED = "autoselAdvancedExpanded"
        val ADVANCED_CATEGORIES =
            listOf("autoselMembership", "autoselHealth", "autoselSwitching", "autoselBalancing", "autoselEndpoints")

        /** The fields refreshPlanSummary reads. */
        val PLAN_KEYS =
            setOf(KEY_GIDS, KEY_PIDS, "nameFilter", "countryFilter", "excludeUnavailable", "poolCap", "buildLimit", "resultValidityMins")
    }

    /** A spin box of edit_autoselector.ui: its range, unit and the text of 0 when it has one. */
    private class IntField(
        val key: String,
        val min: Int,
        val max: Int,
        @PluralsRes val unit: Int = 0,
        @StringRes val zero: Int = 0,
    )

    private val intFields = listOf(
        IntField("buildLimit", 1, AutoSelector.MAX_BUILD_LIMIT, R.plurals.autosel_profiles),
        IntField("poolCap", 1, AutoSelector.MAX_POOL_CAP, R.plurals.autosel_profiles),
        IntField("resultValidityMins", 0, 10080, R.plurals.autosel_minutes, R.string.autosel_always_retest),
        IntField("expected", 1, AutoSelector.MAX_BUILD_LIMIT, R.plurals.autosel_profiles),
        IntField("activeSize", 1, AutoSelector.MAX_BUILD_LIMIT, R.plurals.autosel_profiles),
        IntField("intervalSec", 10, 3600, R.plurals.autosel_seconds),
        IntField("benchIntervalSec", 10, 86400, R.plurals.autosel_seconds),
        IntField("watchIntervalSec", 5, 3600, R.plurals.autosel_seconds),
        IntField("sampling", 2, 60),
        IntField("toleranceMs", 0, 10000, R.plurals.autosel_milliseconds),
        IntField("maxRTTms", 0, 60000, R.plurals.autosel_milliseconds, R.string.autosel_no_limit),
        IntField("dialRetries", 0, 5),
        IntField("balanceIntervalSec", 5, 3600, R.plurals.autosel_seconds),
    )

    override fun createEntity() = AutoSelector().apply { gids = arrayListOf(DataStore.editingGroup) }
    override val preferencesResource = R.xml.autoselector_preferences

    // Like a chain, a selector is not a sing-box outbound the schema could check, and its JSON holds local ids.
    override val supportsRawJson = false

    init {
        pbm.text("name")
        pbm.text("nameFilter")
        pbm.bool("balance")
        pbm.text("countryFilter")
        pbm.bool("excludeUnavailable")
        pbm.bool("interruptOnSwitch")
        pbm.text("balanceMode")
        pbm.text("testURL")
        pbm.text("connectivityURL")
        for (field in intFields) pbm.int(field.key)
    }

    /** The pin as edited: "Use automatic" clears it at once, like the desktop. */
    private var pinnedId = -1L
    private var advancedExpanded = false
    private var balanceOn = false
    private var balanceMode = "rotate"
    private var planJob: Job? = null
    private var fragment: PreferenceFragmentCompat? = null

    /** Every group id, archived ones included; at least one checked group must be among them. */
    private var knownGroups: Set<Long> = emptySet()

    /** Every group id in tab order: the order checked groups are saved in. */
    @Volatile
    private var groupOrder: List<Long> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        advancedExpanded = savedInstanceState?.getBoolean(STATE_ADVANCED) ?: false
        super.onCreate(savedInstanceState)
        supportActionBar?.setTitle(R.string.autosel_title)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ADVANCED, advancedExpanded)
    }

    override fun AutoSelector.init() {
        pbm.writeToCacheAll(this)
        DataStore.profileCacheStore.putStringSet(KEY_GIDS, gids.mapTo(LinkedHashSet()) { it.toString() })
        DataStore.profileCacheStore.putStringSet(KEY_PIDS, pids.mapTo(LinkedHashSet()) { it.toString() })
    }

    override fun AutoSelector.serialize() {
        pbm.fromCacheAll(this)
        gids = checkedGroups(gids)
        pids = checkedPids()
        nameFilter = nameFilter.trim()
        countryFilter = countryFilter.trim()
        testURL = testURL.trim()
        connectivityURL = connectivityURL.trim()
        pinnedID = pinnedId
        val id = DataStore.editingId
        // The runtime writes these while the editor is open (after a start and after a ranking).
        if (id > 0) AutoSelectorProfiles.load(id)?.let { stored ->
            pool = stored.pool
            poolRankedAt = stored.poolRankedAt
            lastBuilt = stored.lastBuilt
            lastBuiltAt = stored.lastBuiltAt
            history = stored.history
        }
        normalize()
        // The pool is a ranking, so a filter change can leave entries that no longer qualify.
        val eligible = AutoSelectorPlanner(SettingsMapper.selectorStore()).rankingCandidates(id, this).toHashSet()
        pool = pool.filterTo(ArrayList()) { it in eligible }
    }

    /** serialize() runs the planner and reads the database, so Validate gets the screen's fields on a plain copy. */
    override fun validationOutbound(): AutoSelector {
        val copy = createEntity()
        copy.parseFromJson(ensureEditingOutbound().exportToJson())
        pbm.fromCacheAll(copy)
        copy.gids = checkedGroups(copy.gids)
        copy.pids = checkedPids()
        copy.nameFilter = copy.nameFilter.trim()
        copy.countryFilter = copy.countryFilter.trim()
        copy.testURL = copy.testURL.trim()
        copy.connectivityURL = copy.connectivityURL.trim()
        return copy
    }

    /** edit_autoselector.cpp bindValidation plus onEnd's name check; AutoSelector.validate covers the filters and URLs. */
    override fun ValidationForm.bindValidation() {
        bind("name") { if (it.isEmpty()) getString(R.string.autosel_name_empty) else "" }
        // The form reads a row's text, which a multi-select does not have, so the rule reads the checked set itself.
        bind(KEY_GIDS) { _ ->
            if (checkedIds().any { it in knownGroups } || checkedPids().isNotEmpty()) ""
            else getString(R.string.autosel_select_group_or_node)
        }
        // Rows of the collapsed Advanced section are skipped by rules, so these always run and reveal the section.
        addIssueSource {
            fun value(key: String) = DataStore.profileCacheStore.getString(key)?.trim()?.toIntOrNull() ?: 0
            val buildLimit = value("buildLimit")
            val interval = value("intervalSec")
            val activeSize = value("activeSize")
            listOfNotNull(
                crossIssue("buildLimit", "poolCap", AutoSelectorChecks.autoSelectorBuildLimit(buildLimit, value("poolCap"))),
                crossIssue("benchIntervalSec", "intervalSec", AutoSelectorChecks.autoSelectorBenchInterval(value("benchIntervalSec"), interval)),
                crossIssue("watchIntervalSec", "intervalSec", AutoSelectorChecks.autoSelectorWatchInterval(value("watchIntervalSec"), interval)),
                crossIssue("activeSize", "expected", AutoSelectorChecks.autoSelectorReadyCount(activeSize, value("expected"))),
                crossIssue("activeSize", "buildLimit", AutoSelectorChecks.autoSelectorCloseCount(activeSize, buildLimit)),
            )
        }
        revealer = { key ->
            val section = fragment?.findPreference<Preference>(key)?.parent?.key
            if (!advancedExpanded && section != null && section in ADVANCED_CATEGORIES) {
                advancedExpanded = true
                refreshAdvanced()
            }
        }
    }

    private fun crossIssue(key: String, other: String, message: String): FormIssue? {
        if (message.isEmpty()) return null
        val label = fragment?.findPreference<Preference>(key)?.title?.toString() ?: key
        return FormIssue(Severity.Error, label, message, key, listOf(other))
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        fragment = this
        val stored = ensureEditingOutbound()
        pinnedId = stored.pinnedID
        // edit_autoselector.cpp onStart: a stored bad value in the collapsed section is shown, not hidden.
        if (AutoSelectorChecks.autoSelectorCountries(stored.countryFilter).isNotEmpty() ||
            AutoSelectorChecks.autoSelectorUrl(stored.testURL).isNotEmpty() ||
            AutoSelectorChecks.autoSelectorUrl(stored.connectivityURL).isNotEmpty()
        ) advancedExpanded = true
        setupGroups()
        setupCustomNodes()
        setupTexts()
        for (field in intFields) setupInt(field)
        // The change listeners run before the new value is stored, so the state comes from their arguments.
        onSwitch("balance") {
            balanceOn = it
            refreshBalance()
        }
        onMenu("balanceMode") {
            balanceMode = it
            refreshBalance()
        }
        findPreference<Preference>(KEY_PINNED)?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(this@AutoSelectorSettingsActivity)
                .setTitle(R.string.autosel_pinned)
                .setMessage(R.string.autosel_use_automatic_tip)
                .setPositiveButton(R.string.autosel_use_automatic) { _, _ -> releasePin() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
        findPreference<Preference>(KEY_ADVANCED)?.setOnPreferenceClickListener {
            advancedExpanded = !advancedExpanded
            refreshAdvanced()
            true
        }
        refreshAdvanced()
        refreshPinned()
        refreshPlan()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        super.onPreferenceDataStoreChanged(store, key)
        if (key in PLAN_KEYS) runOnMainDispatcher { refreshPlan() }
    }

    override fun onDestroy() {
        fragment = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------------------------ rows

    /** "Servers from": non-archived groups in tab order; a checked group outside that list stays listed, so it can be unchecked. */
    private fun PreferenceFragmentCompat.setupGroups() {
        val pref = findPreference<MultiSelectListPreference>(KEY_GIDS) ?: return
        val groups = GroupRepo.all()
        knownGroups = groups.mapTo(HashSet()) { it.id }
        groupOrder = groups.map { it.id }
        val checked = checkedIds()
        val entries = ArrayList<CharSequence>()
        val values = ArrayList<CharSequence>()
        for (group in groups) {
            if (group.archive && group.id !in checked) continue
            entries.add(if (group.archive) getString(R.string.autosel_group_archived, group.displayName()) else group.displayName())
            values.add(group.id.toString())
        }
        for (id in checkedGroups(ensureEditingOutbound().gids)) {
            if (id in knownGroups) continue
            entries.add(getString(R.string.autosel_group_missing, id))
            values.add(id.toString())
        }
        pref.entries = entries.toTypedArray()
        pref.entryValues = values.toTypedArray()
        pref.summaryProvider = Preference.SummaryProvider<MultiSelectListPreference> { p ->
            val chosen = p.values
            p.entryValues.indices.filter { p.entryValues[it].toString() in chosen }
                .joinToString(getString(R.string.autosel_list_separator)) { p.entries[it] }
                .ifEmpty { getString(R.string.autosel_groups_none) }
        }
    }

    /** The ids checked in "Servers from". */
    private fun checkedIds(): Set<Long> = DataStore.profileCacheStore.getStringSet(KEY_GIDS).orEmpty()
        .mapNotNullTo(HashSet()) { text -> text.trim().toLongOrNull()?.takeIf { it >= 0 } }

    /** The checked groups in tab order; a checked id no group has any more follows, in its [previous] order. */
    private fun checkedGroups(previous: List<Long>): MutableList<Long> {
        val checked = checkedIds()
        val order = groupOrder.ifEmpty { GroupRepo.all().map { it.id } }
        val out = order.filterTo(ArrayList()) { it in checked }
        for (id in previous + checked.sorted()) if (id in checked && id !in out) out.add(id)
        return out
    }

    private val selectProfileForAdd =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val id = result.data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L) ?: 0L
                if (id > 0) {
                    addCustomNode(id)
                }
            }
        }

    private fun checkedPids(): ArrayList<Long> = DataStore.profileCacheStore.getStringSet(KEY_PIDS).orEmpty()
        .mapNotNullTo(ArrayList()) { text -> text.trim().toLongOrNull()?.takeIf { it >= 0 } }

    private fun savePids(list: List<Long>) {
        DataStore.profileCacheStore.putStringSet(KEY_PIDS, list.mapTo(LinkedHashSet()) { it.toString() })
        val pref = fragment?.findPreference<Preference>(KEY_PIDS)
        if (pref != null) updateCustomNodesSummary(pref)
        runOnMainDispatcher { refreshPlan() }
    }

    private fun addCustomNode(id: Long) {
        val current = checkedPids()
        if (id in current) {
            Snackbar.make(findViewById(android.R.id.content), R.string.autosel_custom_nodes_already_added, Snackbar.LENGTH_SHORT).show()
            return
        }
        current.add(id)
        savePids(current)
        val name = ProfileManager.getProfile(id)?.displayName() ?: id.toString()
        Snackbar.make(findViewById(android.R.id.content), getString(R.string.autosel_custom_nodes_added, name), Snackbar.LENGTH_SHORT).show()
        showCustomNodesDialog()
    }

    private fun PreferenceFragmentCompat.setupCustomNodes() {
        val pref = findPreference<Preference>(KEY_PIDS) ?: return
        updateCustomNodesSummary(pref)
        pref.setOnPreferenceClickListener {
            showCustomNodesDialog()
            true
        }
    }

    private fun updateCustomNodesSummary(pref: Preference) {
        val pids = checkedPids()
        if (pids.isEmpty()) {
            pref.summary = getString(R.string.autosel_custom_nodes_summary_none)
        } else {
            lifecycleScope.launch {
                val names = withContext(Dispatchers.IO) {
                    pids.map { id ->
                        ProfileManager.getProfile(id)?.displayName() ?: getString(R.string.autosel_node_missing, id)
                    }
                }
                val preview = names.take(3).joinToString(getString(R.string.autosel_list_separator))
                val suffix = if (names.size > 3) "..." else ""
                pref.summary = getString(R.string.autosel_custom_nodes_summary, pids.size, "$preview$suffix")
            }
        }
    }

    private fun showCustomNodesDialog() {
        val pids = checkedPids()
        val nodeItems = ArrayList<Pair<Long, String>>()
        for (pid in pids) {
            val entity = ProfileManager.getProfile(pid)
            if (entity != null) {
                val groupName = GroupRepo.get(entity.groupId)?.displayName() ?: ""
                nodeItems.add(Pair(pid, "${entity.displayName()}  (${entity.displayType()} · $groupName)"))
            } else {
                nodeItems.add(Pair(pid, getString(R.string.autosel_node_missing, pid)))
            }
        }

        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(R.string.autosel_custom_nodes)

        if (nodeItems.isEmpty()) {
            builder.setMessage(R.string.autosel_custom_nodes_empty)
            builder.setPositiveButton(R.string.autosel_custom_nodes_add) { _, _ ->
                showUnifiedNodePicker()
            }
            builder.setNegativeButton(android.R.string.cancel, null)
            builder.show()
            return
        }

        val displayItems = nodeItems.map { "✕  ${it.second}" }.toTypedArray()
        builder.setItems(displayItems) { _, which ->
            val target = nodeItems[which]
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.autosel_custom_node_remove_title)
                .setMessage(getString(R.string.autosel_custom_node_remove_confirm, target.second))
                .setPositiveButton(R.string.delete) { _, _ ->
                    val updated = checkedPids().filter { it != target.first }
                    savePids(updated)
                    Snackbar.make(findViewById(android.R.id.content), R.string.autosel_custom_nodes_removed, Snackbar.LENGTH_SHORT).show()
                    showCustomNodesDialog()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        builder.setPositiveButton(R.string.autosel_custom_nodes_add) { _, _ ->
            showUnifiedNodePicker()
        }
        builder.setNeutralButton(R.string.autosel_custom_nodes_clear) { _, _ ->
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.autosel_custom_nodes_clear)
                .setMessage(R.string.autosel_custom_nodes_clear_confirm)
                .setPositiveButton(R.string.delete) { _, _ ->
                    savePids(emptyList())
                    Snackbar.make(findViewById(android.R.id.content), R.string.autosel_custom_nodes_cleared, Snackbar.LENGTH_SHORT).show()
                    showCustomNodesDialog()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        builder.setNegativeButton(android.R.string.ok, null)
        builder.show()
    }

    private fun showUnifiedNodePicker() {
        val groups = GroupRepo.all().filter { !it.archive }
        val allProfiles = SagerDatabase.proxyDao.getAll().filter {
            it.type != "autoselector" && it.type != "chain" && it.id != DataStore.editingId
        }
        if (allProfiles.isEmpty()) {
            Snackbar.make(findViewById(android.R.id.content), R.string.autosel_no_nodes_available, Snackbar.LENGTH_SHORT).show()
            return
        }

        val groupMap = groups.associateBy { it.id }
        val profilesByGroup = allProfiles.groupBy { it.groupId }
        val availableGroups = groups.filter { (profilesByGroup[it.id]?.size ?: 0) > 0 }

        val selected = checkedPids().toLinkedHashSet()
        var currentGid = -1L
        var filterText = ""

        fun getFilteredList(): List<ProxyEntity> {
            val base = if (currentGid == -1L) allProfiles else (profilesByGroup[currentGid] ?: emptyList())
            if (filterText.isBlank()) return base
            return base.filter { it.displayName().contains(filterText, ignoreCase = true) }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(16), dp2px(10), dp2px(16), 0)
        }

        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
        }
        val chipLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        chipScroll.addView(chipLayout)
        container.addView(chipScroll)

        val searchEdit = EditText(this).apply {
            hint = getString(R.string.autosel_picker_search_hint)
            setSingleLine(true)
            textSize = 14f
            setPadding(dp2px(8), dp2px(10), dp2px(8), dp2px(10))
        }
        container.addView(searchEdit)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp2px(4), 0, dp2px(6))
        }
        val selectAllBtn = TextView(this).apply {
            text = getString(R.string.autosel_picker_select_all)
            setTextColor(ContextCompat.getColor(this@AutoSelectorSettingsActivity, R.color.material_blue_500))
            textSize = 13f
            setPadding(0, dp2px(4), dp2px(16), dp2px(4))
        }
        val deselectAllBtn = TextView(this).apply {
            text = getString(R.string.autosel_picker_deselect_all)
            setTextColor(ContextCompat.getColor(this@AutoSelectorSettingsActivity, R.color.material_blue_500))
            textSize = 13f
            setPadding(dp2px(8), dp2px(4), dp2px(16), dp2px(4))
        }
        actionRow.addView(selectAllBtn)
        actionRow.addView(deselectAllBtn)
        container.addView(actionRow)

        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@AutoSelectorSettingsActivity)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp2px(320)
            )
        }
        container.addView(recyclerView)

        var currentItems = getFilteredList()
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val itemView = layoutInflater.inflate(R.layout.layout_rule_set_item, parent, false)
                return object : RecyclerView.ViewHolder(itemView) {}
            }

            override fun getItemCount(): Int = currentItems.size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = currentItems[position]
                val check = holder.itemView.findViewById<MaterialCheckBox>(R.id.check)
                val name = holder.itemView.findViewById<TextView>(R.id.name)
                val source = holder.itemView.findViewById<TextView>(R.id.source)

                check.isChecked = item.id in selected
                name.text = item.displayName()
                val gName = groupMap[item.groupId]?.displayName() ?: ""
                source.text = "${item.displayType()} · $gName"

                val toggle = {
                    if (item.id in selected) {
                        selected.remove(item.id)
                    } else {
                        selected.add(item.id)
                    }
                    check.isChecked = item.id in selected
                }
                holder.itemView.setOnClickListener { toggle() }
                check.setOnClickListener { toggle() }
            }
        }
        recyclerView.adapter = adapter

        val groupButtons = ArrayList<Pair<Long, TextView>>()
        fun refreshChips() {
            for ((gid, tv) in groupButtons) {
                val isCurrent = gid == currentGid
                val gd = GradientDrawable().apply {
                    cornerRadius = dp2px(14).toFloat()
                    if (isCurrent) {
                        setColor(0xFF1976D2.toInt())
                    } else {
                        setColor(0x1F888888.toInt())
                    }
                }
                tv.background = gd
                tv.setTextColor(if (isCurrent) Color.WHITE else 0xFF444444.toInt())
            }
        }

        fun updateList() {
            currentItems = getFilteredList()
            adapter.notifyDataSetChanged()
        }

        val allBtn = TextView(this).apply {
            text = "${getString(R.string.autosel_picker_all_groups)} (${allProfiles.size})"
            textSize = 13f
            setPadding(dp2px(12), dp2px(6), dp2px(12), dp2px(6))
            setOnClickListener {
                currentGid = -1L
                refreshChips()
                updateList()
            }
        }
        groupButtons.add(Pair(-1L, allBtn))
        chipLayout.addView(allBtn)

        for (g in availableGroups) {
            val count = profilesByGroup[g.id]?.size ?: 0
            val gBtn = TextView(this).apply {
                text = "${g.displayName()} ($count)"
                textSize = 13f
                setPadding(dp2px(12), dp2px(6), dp2px(12), dp2px(6))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp2px(6)
                }
                layoutParams = lp
                setOnClickListener {
                    currentGid = g.id
                    refreshChips()
                    updateList()
                }
            }
            groupButtons.add(Pair(g.id, gBtn))
            chipLayout.addView(gBtn)
        }
        refreshChips()

        searchEdit.addTextChangedListener {
            filterText = it?.toString().orEmpty().trim()
            updateList()
        }

        selectAllBtn.setOnClickListener {
            for (item in currentItems) selected.add(item.id)
            adapter.notifyDataSetChanged()
        }
        deselectAllBtn.setOnClickListener {
            for (item in currentItems) selected.remove(item.id)
            adapter.notifyDataSetChanged()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.autosel_picker_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                savePids(selected.toList())
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.autosel_custom_nodes_summary, selected.size, ""),
                    Snackbar.LENGTH_SHORT
                ).show()
                showCustomNodesDialog()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun PreferenceFragmentCompat.setupTexts() {
        fun summary(key: String, empty: () -> CharSequence) {
            findPreference<EditTextPreference>(key)?.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                it.text?.trim().orEmpty().ifEmpty { empty().toString() }
            }
        }
        summary("nameFilter") { getString(R.string.autosel_name_filter_empty) }
        summary("countryFilter") { getString(R.string.autosel_country_filter_empty) }
        summary("testURL") { getString(R.string.autosel_test_url_empty, DataStore.testUrl) }
        summary("connectivityURL") {
            val direct = DataStore.directTestUrl
            if (direct.isBlank()) getString(R.string.autosel_connectivity_url_none)
            else getString(R.string.autosel_connectivity_url_empty, direct)
        }
    }

    /** A spin box: numbers only, out-of-range input clamped, the unit and the special 0 text in the summary. */
    private fun PreferenceFragmentCompat.setupInt(field: IntField) {
        val pref = findPreference<EditTextPreference>(field.key) ?: return
        pref.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        pref.setOnPreferenceChangeListener { preference, newValue ->
            val typed = (newValue as? String)?.trim()?.toIntOrNull()
            val current = (preference as EditTextPreference).text?.trim()?.toIntOrNull() ?: field.min
            val clamped = (typed ?: current).coerceIn(field.min, field.max)
            if (clamped.toString() == newValue) return@setOnPreferenceChangeListener true
            preference.text = clamped.toString()
            false
        }
        pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
            val value = it.text?.trim()?.toIntOrNull() ?: 0
            when {
                value == 0 && field.zero != 0 -> getString(field.zero)
                field.unit != 0 -> resources.getQuantityString(field.unit, value, value)
                else -> value.toString()
            }
        }
    }

    /** updateBalanceEnabled (cpp:220-240). */
    private fun refreshBalance() {
        val fragment = fragment ?: return
        val on = balanceOn
        val rotating = on && balanceMode != "connection"
        fragment.findPreference<Preference>("balanceMode")?.isEnabled = on
        fragment.findPreference<Preference>("balanceIntervalSec")?.isEnabled = rotating
        fragment.findPreference<Preference>(KEY_BALANCE_HINT)?.summary = getString(
            when {
                !on -> R.string.autosel_balance_hint_off
                rotating -> R.string.autosel_balance_hint_rotate
                else -> R.string.autosel_balance_hint_connection
            }
        )
    }

    private fun refreshAdvanced() {
        val fragment = fragment ?: return
        for (key in ADVANCED_CATEGORIES) fragment.findPreference<PreferenceCategory>(key)?.isVisible = advancedExpanded
        fragment.findPreference<Preference>(KEY_ADVANCED)?.apply {
            summary = getString(if (advancedExpanded) R.string.autosel_advanced_hide else R.string.autosel_advanced_show)
            setIcon(if (advancedExpanded) R.drawable.ic_baseline_expand_less_24 else R.drawable.ic_baseline_expand_more_24)
        }
        form?.refresh()
    }

    /** refreshPinnedRow (cpp:205-218): shown only while a member is pinned. */
    private fun refreshPinned() {
        val row = fragment?.findPreference<Preference>(KEY_PINNED) ?: return
        row.isVisible = pinnedId >= 0
        if (pinnedId < 0) return
        val id = pinnedId
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching { ProfileManager.getProfile(id)?.outbound?.displayName() }.getOrNull()
            }
            if (pinnedId == id) row.summary = getString(
                R.string.autosel_pinned_summary, name ?: getString(R.string.autosel_pinned_missing)
            )
        }
    }

    /** Unlike the rest of the form, releasing the pin is written at once, and a running core releases its own copy. */
    private fun releasePin() {
        pinnedId = -1
        refreshPinned()
        val id = DataStore.editingId
        runOnDefaultDispatcher {
            try {
                ensureEditingOutbound().pinnedID = -1
                if (id <= 0) return@runOnDefaultDispatcher
                val stored = AutoSelectorProfiles.load(id) ?: return@runOnDefaultDispatcher
                if (stored.pinnedID >= 0) {
                    stored.pinnedID = -1
                    AutoSelectorProfiles.save(id, stored)
                }
                val release = Intent(Action.AUTO_SELECTOR_AUTOMATIC).putExtra(Action.EXTRA_PROFILE_ID, id)
                sendBroadcast(release.setPackage(packageName))
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ plan

    /** refreshPlanSummary (cpp:242-295) on a throwaway copy: planning normalises what it gets. */
    private fun refreshPlan() {
        val row = fragment?.findPreference<Preference>(KEY_PLAN) ?: return
        val store = DataStore.profileCacheStore
        val preview = AutoSelector()
        preview.parseFromJson(ensureEditingOutbound().exportToJson())
        preview.gids = checkedGroups(preview.gids)
        preview.pids = checkedPids()
        preview.nameFilter = store.getString("nameFilter").orEmpty().trim()
        preview.countryFilter = store.getString("countryFilter").orEmpty().trim()
        preview.excludeUnavailable = store.getBoolean("excludeUnavailable", true)
        store.getString("poolCap")?.trim()?.toIntOrNull()?.let { preview.poolCap = it }
        store.getString("buildLimit")?.trim()?.toIntOrNull()?.let { preview.buildLimit = it }
        store.getString("resultValidityMins")?.trim()?.toIntOrNull()?.let { preview.resultValidityMins = it }
        val selectorId = DataStore.editingId
        planJob?.cancel()
        planJob = lifecycleScope.launch {
            delay(150)
            val plan = withContext(Dispatchers.IO) {
                runCatching { AutoSelectorPlanner(SettingsMapper.selectorStore()).plan(selectorId, preview) }
                    .onFailure { Logs.w(it) }.getOrNull()
            } ?: return@launch
            row.summary = describe(plan)
        }
    }

    private fun describe(plan: AutoSelectorPlan): String {
        if (!plan.ok) {
            return getString(
                when {
                    plan.groups.isNotEmpty() -> R.string.autosel_error_no_members
                    plan.missingGroups > 0 -> R.string.autosel_error_no_group
                    else -> R.string.autosel_error_no_tracked_group
                }
            )
        }
        val lines = ArrayList<String>()
        lines.add(
            if (plan.groups.size > 1) {
                getString(R.string.autosel_plan_counts_groups, plan.eligible, plan.membersInGroup, plan.groups.size, plan.build.size)
            } else {
                getString(R.string.autosel_plan_counts, plan.eligible, plan.membersInGroup, plan.build.size)
            }
        )
        if (plan.missingGroups > 0) {
            lines.add(resources.getQuantityString(R.plurals.autosel_plan_missing_groups, plan.missingGroups, plan.missingGroups))
        }
        if (plan.skipped.isNotEmpty()) {
            val reasons = plan.skipped.joinToString(getString(R.string.autosel_list_separator)) { (skip, count) ->
                getString(R.string.autosel_plan_skip_item, count, getString(skipText(skip)))
            }
            lines.add(getString(R.string.autosel_plan_skipped, reasons))
        }
        if (plan.keptUnavailable > 0) lines.add(getString(R.string.autosel_plan_kept_unavailable, plan.keptUnavailable))
        if (plan.truncated) lines.add(getString(R.string.autosel_plan_truncated, plan.poolCapUsed))
        if (plan.rankedByTest > 0) lines.add(getString(R.string.autosel_plan_reused, plan.rankedByTest))
        if (plan.needsRanking) lines.add(getString(R.string.autosel_plan_needs_ranking))
        return lines.joinToString(" ")
    }

    /** AutoSelectorSkipReason (AutoSelectorPlan.cpp:225-243). */
    @StringRes
    private fun skipText(skip: AutoSelectorSkip): Int = when (skip) {
        AutoSelectorSkip.Missing -> R.string.autosel_skip_missing
        AutoSelectorSkip.MetaType -> R.string.autosel_skip_meta
        AutoSelectorSkip.CoreTransitions -> R.string.autosel_skip_core_transitions
        AutoSelectorSkip.ExtraCore -> R.string.autosel_skip_extra_core
        AutoSelectorSkip.FullConfig -> R.string.autosel_skip_full_config
        AutoSelectorSkip.Malformed -> R.string.autosel_skip_malformed
        AutoSelectorSkip.Tailscale -> R.string.autosel_skip_tailscale
        AutoSelectorSkip.ManagementEndpoint -> R.string.autosel_skip_management
        AutoSelectorSkip.NameFilter -> R.string.autosel_skip_name
        AutoSelectorSkip.CountryFilter -> R.string.autosel_skip_country
        AutoSelectorSkip.Unavailable -> R.string.autosel_skip_unavailable
        AutoSelectorSkip.XrayFullChained -> R.string.autosel_skip_xray_chained
        AutoSelectorSkip.Unsupported -> R.string.autosel_skip_unsupported
    }
}
