package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorProfiles
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app

/**
 * Android-only (#94): route rules, default outbounds, and auto-selector candidate pools name a server by profile id,
 * so a refresh that replaces a node (a new identity, "clear servers before updating", a filter) would leave them pointing
 * at nothing. They follow the group's member of the same name instead, when exactly one member has it.
 */
internal object SubscriptionRouteRebind {

    /** The names of the [members] some route profile or auto-selector references, taken before the refresh deletes any of them. */
    fun referencedNames(members: List<ProxyEntity>): Map<Long, String> {
        val referenced = SagerDatabase.routeDao.referencedOutboundIds().toHashSet()
        for (sid in AutoSelectorProfiles.ids()) {
            val sel = AutoSelectorProfiles.load(sid) ?: continue
            referenced.addAll(sel.pids)
            if (sel.pinnedID > 0) referenced.add(sel.pinnedID)
            referenced.addAll(sel.pool)
        }
        if (referenced.isEmpty()) return emptyMap()
        val names = HashMap<Long, String>()
        for (profile in members) {
            if (profile.id in referenced) names[profile.id] = profile.displayName()
        }
        return names
    }

    /**
     * Re-points the references to the [removed] ids of group [gid]; [names] is [referencedNames]' result. The running
     * service keeps the old node until reloaded, so the UI is asked for its reload hint when the running config used one.
     */
    fun rebind(gid: Long, removed: Collection<Long>, names: Map<Long, String>) {
        val orphans = removed.filter { !names[it].isNullOrBlank() }
        if (orphans.isEmpty()) return
        val byName = HashMap<String, Long>()
        val ambiguous = HashSet<String>()
        for (profile in ProfileManager.members(gid)) {
            if (profile.type == SubscriptionRefresh.AUTOSELECTOR) continue
            val name = profile.displayName()
            if (byName.put(name, profile.id) != null) ambiguous.add(name)
        }
        val dao = SagerDatabase.routeDao
        var rebound = 0
        val movedIds = ArrayList<Long>()

        val selectorIds = AutoSelectorProfiles.ids()
        val selectors = selectorIds.mapNotNull { sid ->
            val sel = AutoSelectorProfiles.load(sid) ?: return@mapNotNull null
            sid to sel
        }
        val changedSelectors = HashSet<Long>()

        SagerDatabase.instance.runInTransaction {
            for (old in orphans) {
                val name = names.getValue(old)
                if (name in ambiguous) continue
                val target = byName[name] ?: continue
                val count = dao.replaceRuleOutbound(old, target) + dao.replaceDefaultOutbound(old, target)
                if (count > 0) movedIds.add(old)
                rebound += count

                for ((sid, sel) in selectors) {
                    var changed = false
                    if (old in sel.pids) {
                        for (i in sel.pids.indices) {
                            if (sel.pids[i] == old) sel.pids[i] = target
                        }
                        sel.pids = sel.pids.distinct().toMutableList()
                        changed = true
                    }
                    if (sel.pinnedID == old) {
                        sel.pinnedID = target
                        changed = true
                    }
                    if (old in sel.pool) {
                        for (i in sel.pool.indices) {
                            if (sel.pool[i] == old) sel.pool[i] = target
                        }
                        sel.pool = sel.pool.distinct().toMutableList()
                        changed = true
                    }
                    if (changed) {
                        changedSelectors.add(sid)
                    }
                }
            }

            for ((sid, sel) in selectors) {
                if (sid in changedSelectors) {
                    AutoSelectorProfiles.save(sid, sel)
                    if (DataStore.editingId == sid) {
                        DataStore.profileCacheStore.putStringSet(
                            "pids",
                            sel.pids.mapTo(LinkedHashSet()) { it.toString() }
                        )
                    }
                }
            }
        }
        if (rebound > 0 || changedSelectors.isNotEmpty()) {
            Logs.i(app.getString(R.string.subs_routes_rebound, rebound + changedSelectors.size))
        }
        if (movedIds.any { ProfileManager.runningUses(it) }) SubscriptionQueue.reloadNeeded(gid)
    }
}
