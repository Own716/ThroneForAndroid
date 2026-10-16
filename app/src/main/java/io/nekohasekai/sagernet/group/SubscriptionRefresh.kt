package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.GroupSort
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SettingsMapper
import io.nekohasekai.sagernet.database.SubUserInfo
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.`import`.ImportReport
import io.nekohasekai.sagernet.outbound.`import`.ProfileImport
import io.nekohasekai.sagernet.validation.vplural
import io.nekohasekai.sagernet.validation.vtr

/** GroupUpdater::refresh, importDocuments and afterUrlTest (GroupUpdater.cpp:465-683) over the Room data layer. */
internal object SubscriptionRefresh {

    const val AUTOSELECTOR = "autoselector"

    /** kInsertChunk (GroupUpdater.cpp:23). */
    private const val INSERT_CHUNK = 500

    private val BOM = Char(0xFEFF)

    /** [reported]: the import box went out, so the caller's own count line is skipped (MwArg::Quiet). */
    class ImportResult(@JvmField val gid: Long, @JvmField val count: Int, @JvmField val reported: Boolean = false)

    /**
     * GroupUpdater::Surface: where an update's problems go: the log only (automatic), the diff popup (manual
     * refresh), or a report box (a new subscription's first update).
     */
    enum class Surface { Log, Diff, Report }

    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    /** notice (GroupUpdater.cpp:166-176): a list of 1000 names or more collapses to one count line. */
    fun notice(names: List<String>, prefix: String, action: String): String {
        if (names.size >= 1000) return "$prefix $action ${names.size}\n"
        val sb = StringBuilder()
        for (name in names) sb.append(prefix).append(' ').append(name).append('\n')
        return sb.toString()
    }

    /** reportTitle (GroupUpdater.cpp). */
    private fun reportTitle(surface: Surface, groupName: String): String =
        if (surface == Surface.Report) vtr(R.string.validation_import_title_new, groupName) else vtr(R.string.validation_import_title_update, groupName)

    /** logImportReport (GroupUpdater.cpp). */
    private fun logImportReport(report: ImportReport) = Logs.w(report.text())

    /**
     * refresh and applyRefresh (GroupUpdater.cpp); [notifyErrors]: fetch failures also go to the callbacks. The parse
     * report goes to the log, and with [Surface.Diff] / [Surface.Report] to a popup.
     */
    suspend fun refresh(gid: Long, surface: Surface, notifyErrors: Boolean) {
        val group = GroupRepo.get(gid) ?: return
        if (group.archive) return
        val options = group.subOptions
        val url = group.url.trim()

        val fetched = SubscriptionFetch.fetch(url, group.name, RequestIdentity.resolve(group))
        if (fetched.error != null) {
            if (surface == Surface.Report) {
                // The group is stored before the download: a failed one is fixed through its own TLS, HTTP and User Agent settings.
                val report = ImportReport()
                report.addFatal(vtr(R.string.validation_import_fetch_failed, fetched.error))
                report.addFatal(vtr(R.string.validation_import_first_download_failed, group.name))
                SubscriptionQueue.report(gid, reportTitle(surface, group.name), report.summary(), true)
            } else if (notifyErrors) {
                SubscriptionQueue.error(gid, fetched.error)
            }
            return
        }
        val current = GroupRepo.get(gid) ?: return
        if (current.url.trim() != url) {
            Logs.w(str(R.string.subs_url_changed, group.name))
            return
        }
        // A body without a single profile is far likelier a broken or blocked response than an emptied subscription:
        // abort before the first write (no deletion, no update time). Parsing writes nothing, so it runs first.
        val result = parse(fetched.body)
        val parsed = result.outbounds
        if (parsed.isEmpty()) {
            val probe = result.report()
            if (looksLikeWebPage(fetched.body)) probe.addFatal(str(R.string.subs_web_page))
            // Panels answer a device or quota limit with an empty list and an announcement saying why.
            val info = fetched.subInfo
            if (info.valid) {
                if (info.announce.isNotEmpty()) probe.addFatal(str(R.string.subs_announcement_from, group.name, info.announce))
                saveMetadata(group, info)
            }
            val message = str(R.string.subs_empty_aborted)
            probe.addFatal(message)
            Logs.w("${group.name}: " + probe.text())
            if (surface != Surface.Log) SubscriptionQueue.report(gid, reportTitle(surface, group.name), probe.text(), true)
            else if (notifyErrors) SubscriptionQueue.error(gid, message)
            return
        }
        // Android-only (#53): filtered servers never enter the sink, so the diff deletes the ones already stored.
        val servers = SubscriptionNameFilter.apply(parsed, options, group.name)
        if (servers.isEmpty()) {
            if (fetched.subInfo.valid) saveMetadata(group, fetched.subInfo)
            val message = str(R.string.subs_filtered_all_aborted, parsed.size)
            Logs.w("${group.name}: $message")
            if (notifyErrors) SubscriptionQueue.error(gid, message)
            return
        }

        val metadata = saveMetadata(group, fetched.subInfo)

        // Auto selectors are local state, not servers the remote sent: keep them out of the diff.
        val sticky = ProfileManager.members(gid).withIndex()
            .filter { it.value.type == AUTOSELECTOR }
            .map { it.index to it.value.id }
        val stickyIds = sticky.mapTo(HashSet()) { it.second }
        fun members(): List<ProxyEntity> = ProfileManager.members(gid).filter { it.id !in stickyIds }

        val membersBefore = ProfileManager.memberIds(gid)
        val routeNames = SubscriptionRouteRebind.referencedNames(members())
        // Deleted for good, for the route rules that named them.
        val removed = ArrayList<Long>()

        // Ids a running auto selector can no longer trust: deleted, or same id with new settings.
        val disturbed = ArrayList<Long>()
        var cleared = false
        if (DataStore.subClear) {
            Logs.i(str(R.string.subs_clearing))
            var doomed = members()
            if (options.keepWorking) doomed = doomed.filter { !it.isWorking() }
            val outcome = ProfileManager.batchDeleteProfiles(doomed.map { it.id })
            if (!outcome.ok) {
                val message = str(R.string.subs_db_error_retry)
                Logs.e(message)
                SubscriptionQueue.error(gid, message)
                return
            }
            disturbed.addAll(outcome.deleted)
            removed.addAll(outcome.deleted)
            // A survivor still belongs to the subscription: fall through to the diff.
            cleared = members().isEmpty()
        }

        val old = ArrayList<OldEntry>()
        val working = HashSet<Long>()
        if (!cleared) {
            for (profile in members()) {
                old.add(OldEntry(profile.id, contentKey(profile), identityKey(profile), profile.outbound.displayTypeAndName()))
                if (options.keepWorking && profile.isWorking()) working.add(profile.id)
            }
        }
        val index = ContentIndex(old)
        val sink = ImportSink(gid, if (cleared) null else index)

        Logs.i(">>>>>>>> " + str(R.string.subs_processing))
        for (outbound in servers) sink.add(outbound)
        sink.flush()
        Logs.i(">>>>>>>> " + str(R.string.subs_process_complete))
        val report = result.report()
        report.setImported(sink.stored())

        var changeText: String
        if (cleared) {
            changeText = if (sink.entries.size >= 1000) {
                "[+] ${sink.entries.size} profiles\n"
            } else {
                sink.entries.joinToString("") { "[+] ${it.display}\n" }
            }
        } else {
            val plan = SubscriptionReconcile.reconcile(old, sink.entries, index)
            for ((oldId, newId) in plan.updates) {
                val newEnt = ProfileManager.getProfile(newId)
                if (newEnt != null && ProfileManager.getProfile(oldId) != null) {
                    // The old row keeps its id, test results and traffic.
                    SagerDatabase.proxyDao.updateOutbound(oldId, newEnt.type, newEnt.name, newEnt.outboundJson)
                }
                disturbed.add(oldId)
            }

            val stale = ArrayList<Long>()
            val keptWorking = ArrayList<Long>()
            for (id in plan.stale) (if (id in working) keptWorking else stale).add(id)

            val previousOrder = ProfileManager.memberIds(gid)
            val order = ArrayList(plan.order)
            for ((position, id) in sticky) order.add(minOf(position, order.size), id)

            val outcome = ProfileManager.batchDeleteProfiles(stale)
            if (!outcome.ok) dbError(gid)
            disturbed.addAll(outcome.deleted)
            removed.addAll(outcome.deleted)

            // Survivors of the deletion (the running profile, working ones) return to their previous position.
            fun restore(id: Long): Boolean {
                if (id in order) return false
                val position = previousOrder.indexOf(id)
                order.add(if (position < 0) order.size else minOf(position, order.size), id)
                return true
            }
            val noticeKept = StringBuilder()
            for (id in outcome.kept) {
                if (!restore(id)) continue
                ProfileManager.getProfile(id)?.let {
                    noticeKept.append("[=] ").append(it.outbound.displayTypeAndName()).append('\n')
                }
            }
            val workingNames = ArrayList<String>()
            for (id in keptWorking) {
                if (!restore(id)) continue
                ProfileManager.getProfile(id)?.let { workingNames.add(it.outbound.displayTypeAndName()) }
            }
            ProfileManager.setOrder(gid, order)

            var deletedNames: List<String> = plan.deleted
            if (keptWorking.isNotEmpty()) {
                val doomed = stale.toHashSet()
                deletedNames = old.filter { it.id in doomed }.map { it.display }
            }

            changeText = "\n" + str(
                R.string.subs_change_summary,
                plan.added.size, notice(plan.added, "[+]", "added"),
                plan.updates.size, notice(plan.updated, "[~]", "updated"),
                deletedNames.size, notice(deletedNames, "[-]", "deleted"),
            )
            if (noticeKept.isNotEmpty()) changeText += "\n" + str(R.string.subs_still_in_use, noticeKept.toString())
            if (workingNames.isNotEmpty()) {
                changeText += "\n" + str(R.string.subs_working_kept, notice(workingNames, "[=]", "kept"))
            }
            if (plan.added.isEmpty() && plan.updates.isEmpty() && deletedNames.isEmpty() && keptWorking.isEmpty()) {
                changeText = str(R.string.subs_nothing)
            }
        }

        val filteredOut = parsed.size - servers.size
        if (filteredOut > 0) changeText += "\n" + str(R.string.subs_filtered_out, filteredOut) + "\n"
        val filtered = SubscriptionFilters.removeFlagged(gid, members(), options)
        changeText += filtered.report
        disturbed.addAll(filtered.deleted)
        removed.addAll(filtered.deleted)

        SubscriptionRouteRebind.rebind(gid, removed, routeNames)
        // The subscription's own order replaced the group's above.
        if (disturbed.isNotEmpty() || ProfileManager.memberIds(gid) != membersBefore) GroupSort.resortLater(gid)
        // Stamped only once the update went through, so an interrupted one is retried instead of counting as fresh.
        if (!urlChanged(gid, url)) {
            SagerDatabase.groupDao.setSubscriptionInfo(gid, System.currentTimeMillis() / 1000, metadata)
            GroupRepo.postUpdate(gid)
        }

        Logs.i("<<<<<<<< " + str(R.string.subs_change_of_log, group.name) + "\n" + changeText)
        if (report.hasProblems()) logImportReport(report)
        val diffPopup = surface == Surface.Diff && DataStore.subShowChangePopup
        var diffBody = changeText.trim().ifEmpty { str(R.string.subs_nothing) }
        if (diffPopup && report.hasProblems()) diffBody += "\n\n" + vtr(R.string.validation_import_problems) + "\n" + report.text()
        SubscriptionQueue.report(gid, str(R.string.subs_change_of, group.name), diffBody, diffPopup)
        if (!diffPopup && surface != Surface.Log && report.hasProblems()) {
            SubscriptionQueue.report(gid, reportTitle(surface, group.name), report.text(), true)
        }
        SubscriptionQueue.groupChanged(gid, disturbed)

        if (options.urlTest) SubscriptionQueue.requestUrlTest(gid, members().map { it.id })
    }

    /**
     * importDocuments (GroupUpdater.cpp): a plain import into [gid] (<= 0: the current group). With problems the
     * import box goes out as a popup report; [emptyReason] replaces the generic "nothing found" when nothing is stored.
     */
    suspend fun importDocuments(gid: Long, documents: List<String>, emptyReason: String = ""): ImportResult {
        val target = if (gid > 0) gid else GroupRepo.currentId()
        val sink = ImportSink(target, null)
        val report = ImportReport()
        Logs.i(">>>>>>>> " + str(R.string.subs_processing))
        for (document in documents) {
            val result = parse(document)
            report.addAll(result.reports)
            for (outbound in result.outbounds) sink.add(outbound)
        }
        sink.flush()
        Logs.i(">>>>>>>> " + str(R.string.subs_process_complete))
        val count = sink.stored()
        report.setImported(count)
        val lost = sink.entries.size - count
        if (lost > 0) report.addFatal(vplural(R.plurals.validation_import_not_saved, lost))
        if (count == 0) {
            if (emptyReason.isNotEmpty()) report.addFatal(emptyReason)
            else if (!report.hasProblems()) report.addFatal(vtr(R.string.validation_import_none))
        }
        if (!report.hasProblems()) {
            Logs.i(str(R.string.subs_imported, count))
            return ImportResult(target, count)
        }
        // The logged summary already carries the count.
        logImportReport(report)
        SubscriptionQueue.report(target, vtr(R.string.validation_import_title), report.text(), true)
        return ImportResult(target, count, reported = true)
    }

    /** afterUrlTest (GroupUpdater.cpp:669-683). */
    suspend fun afterUrlTest(gid: Long) {
        val group = GroupRepo.get(gid) ?: return
        if (group.archive) return
        val options = group.subOptions
        if (!options.urlTest || (!options.removeUnavailable && !options.sortByLatency)) return

        val result = SubscriptionFilters.removeUnavailableAndSort(group, options)
        if (result.report.isNotEmpty()) {
            val heading = str(R.string.subs_after_url_test, group.name)
            Logs.i("<<<<<<<< $heading${result.report}")
            SubscriptionQueue.report(gid, heading.removeSuffix(":"), result.report.trim(), false)
        }
        SubscriptionQueue.groupChanged(gid, result.deleted)
    }

    /** GroupUpdater.cpp:850-853: an edit of the group's URL meanwhile (or its deletion) makes the answer stale. */
    private fun urlChanged(gid: Long, url: String): Boolean = GroupRepo.get(gid)?.url?.trim() != url

    /** looksLikeWebPage (GroupUpdater.cpp:84-87): a CAPTCHA, bot check or block page instead of a subscription. */
    internal fun looksLikeWebPage(body: String): Boolean {
        val start = body.indexOfFirst { !it.isWhitespace() && it != BOM }
        if (start < 0) return false
        return body.startsWith("<!doctype html", start, ignoreCase = true) || body.startsWith("<html", start, ignoreCase = true)
    }

    /**
     * Stores what the answer said about the subscription (no update time) and returns the column text; a changed
     * profile-update-interval moves a group that follows it to another schedule.
     */
    private suspend fun saveMetadata(group: ProxyGroup, info: SubUserInfo): String {
        val metadata = info.toJsonString()
        SagerDatabase.groupDao.setSubscriptionMetadata(group.id, metadata)
        GroupRepo.postUpdate(group.id)
        if (info.serverInterval != group.subInfo.serverInterval && SubscriptionScheduler.followsServerInterval(group)) {
            SubscriptionScheduler.schedule()
        }
        return metadata
    }

    internal fun dbError(gid: Long) {
        val message = str(R.string.subs_db_error_corrupted)
        Logs.e(message)
        SubscriptionQueue.error(gid, message)
    }

    private fun parse(body: String): ProfileImport.Result =
        ProfileImport.parse(body, SettingsMapper.xrayVlessPreference(), SettingsMapper.buildContext())

    /** The stored JSON of a type this build cannot parse is kept as is (re-exporting would drop its fields). */
    private fun contentKey(profile: ProxyEntity): String = SubscriptionReconcile.contentKey(
        if (profile.outbound.invalid) profile.outboundJson else profile.outbound.exportToJson().toCompact()
    )

    private fun identityKey(profile: ProxyEntity): String =
        SubscriptionReconcile.identityKey(profile.type, profile.outbound.exportIdentity().toCompact())

    /** ImportSink (GroupUpdater.cpp:112-154): inserts in chunks, claiming old ids by identical content first. */
    private class ImportSink(private val gid: Long, private val index: ContentIndex?) {
        val entries = ArrayList<NewEntry>()
        private val chunk = ArrayList<Outbound>()

        /** stored (GroupUpdater.cpp): rows that really reached the database, added or reused from the group. */
        fun stored(): Int = entries.count { it.id > 0 }
        private val entryIndex = ArrayList<Int>()

        suspend fun add(outbound: Outbound) {
            val entry = NewEntry(outbound.displayTypeAndName())
            if (index != null) {
                entry.content = SubscriptionReconcile.contentKey(outbound.exportToJson().toCompact())
                index.noteArrival(entry.content)
                entry.contentKnown = index.knows(entry.content)
                val oldId = index.claim(entry.content)
                if (oldId != null) {
                    entry.id = oldId
                    entry.reused = true
                    entries.add(entry)
                    return
                }
                if (!entry.contentKnown) {
                    entry.identity = SubscriptionReconcile.identityKey(outbound.type, outbound.exportIdentity().toCompact())
                }
            }
            entryIndex.add(entries.size)
            entries.add(entry)
            chunk.add(outbound)
            if (chunk.size >= INSERT_CHUNK) flush()
        }

        suspend fun flush() {
            if (chunk.isEmpty()) return
            try {
                val rows = ProfileManager.addProfileBatch(chunk, gid)
                rows.forEachIndexed { i, row -> entries[entryIndex[i]].id = row.id }
            } catch (e: Exception) {
                Logs.w(e)
            }
            chunk.clear()
            entryIndex.clear()
        }
    }
}
