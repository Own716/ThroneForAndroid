package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app

/**
 * Android-only (#94): route rules and default outbounds name a server by profile id, so a refresh that replaces a node
 * (a new identity, "clear servers before updating", a filter) would leave them pointing at nothing. They follow the
 * group's member of the same name instead, when exactly one member has it.
 */
internal object SubscriptionRouteRebind {

    /** The names of the [members] some route profile references, taken before the refresh deletes any of them. */
    fun referencedNames(members: List<ProxyEntity>): Map<Long, String> {
        val referenced = SagerDatabase.routeDao.referencedOutboundIds().toHashSet()
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
        SagerDatabase.instance.runInTransaction {
            for (old in orphans) {
                val name = names.getValue(old)
                if (name in ambiguous) continue
                val target = byName[name] ?: continue
                val count = dao.replaceRuleOutbound(old, target) + dao.replaceDefaultOutbound(old, target)
                if (count > 0) movedIds.add(old)
                rebound += count
            }
        }
        if (rebound > 0) Logs.i(app.getString(R.string.subs_routes_rebound, rebound))
        if (movedIds.any { ProfileManager.runningUses(it) }) SubscriptionQueue.reloadNeeded(gid)
    }
}
