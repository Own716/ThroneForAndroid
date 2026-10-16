package io.nekohasekai.sagernet.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sagernet.database.preference.SettingsStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.Rule
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.vplural
import io.nekohasekai.sagernet.validation.vtr

/**
 * Validation::FormIssue: one problem as the save dialog lists it once; [key] is the row that shows it ("" for none)
 * and [related] the other rows a cross-field problem marks.
 */
data class FormIssue(
    @JvmField val severity: Severity,
    @JvmField val label: String,
    @JvmField val message: String,
    @JvmField val key: String = "",
    @JvmField val related: List<String> = emptyList(),
)

/**
 * Validation::Form (ValidationForm.cpp) over a preference screen. A bound field is checked when its stored value
 * changes (the desktop's focus-out, never per keystroke), [markAll] marks everything after a load, and [validateAll]
 * is the save gate: it marks every issue, scrolls to the first error and lists the problems in one dialog. Problems
 * show under the row's summary, in the error colour or amber for warnings. Hidden, disabled and [Field.onlyIf]-inactive
 * fields are skipped and warnings never block. Screens that save on every change use [Field.gate] instead, which
 * refuses a value its error rule rejects.
 *
 * Register everything, then call [start] once the screen's own setup (summary providers, change listeners) is done:
 * marks wrap the summary provider a row has, and gates wrap its change listener.
 */
class ValidationForm(private val fragment: PreferenceFragmentCompat) : OnPreferenceDataStoreChangeListener {

    class Field internal constructor(
        internal val key: String,
        internal val rule: Rule,
        internal val lines: Boolean,
    ) {
        internal var labelText: String? = null
        internal var active: (() -> Boolean)? = null
        internal var severity = Severity.Error
        internal var trim = true
        internal var gated = false
        internal var pure = false
        internal var lastText: String? = null
        internal var lastMessage = ""

        /** The dialog's label; the row's title by default. */
        fun label(text: String) = apply { labelText = text }

        /** Field::when: checked only while [predicate] holds. */
        fun onlyIf(predicate: () -> Boolean) = apply { active = predicate }

        fun warning() = apply { severity = Severity.Warning }

        /** The rule sees the text untrimmed (passwords). */
        fun raw() = apply { trim = false }

        /** An error refuses the new value at commit, for screens without a save action. */
        fun gate() = apply { gated = true }

        /** The rule reads nothing but the text, so its message is kept until the text changes. */
        fun cached() = apply { pure = true }
    }

    private class CrossRule(val keys: List<String>, val severity: Severity, val label: String?, val check: () -> String)

    /** An issue and the rows it marks: a cross rule marks every active participant but is listed once. */
    private class Found(val issue: FormIssue, val marks: List<String>)

    private val fields = ArrayList<Field>()
    private val rules = ArrayList<CrossRule>()
    private val sources = ArrayList<() -> List<FormIssue>>()
    private val aliases = HashMap<String, String>()
    private val touched = HashSet<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var showAll = false
    private var started = false
    private var refreshQueued = false

    /** What each row shows now, and for rows without a summary provider the summary they had before their mark. */
    private val rendered = HashMap<String, List<FormIssue>>()
    private val staticBase = HashMap<String, CharSequence?>()
    private val staticMarked = HashMap<String, CharSequence?>()

    /** Called with the row of the first error before the save gate scrolls to it, e.g. to expand a collapsed group. */
    var revealer: ((String) -> Unit)? = null

    /** Rows the screen keeps in a collapsed group: true counts them as shown, so they are checked and [revealer] opens it. */
    var collapsed: ((Preference) -> Boolean)? = null

    fun bind(key: String, rule: Rule): Field = Field(key, rule, false).also { fields.add(it) }

    /** One rule per non-blank line; the message names the first bad line, like Form::bindLines. */
    fun bindLines(key: String, perLine: Rule): Field = Field(key, perLine, true).also { fields.add(it) }

    /** Form::addRule: runs while any of [keys] is active and marks the active ones. */
    fun addRule(keys: List<String>, severity: Severity = Severity.Error, label: String? = null, check: () -> String) {
        rules.add(CrossRule(keys, severity, label, check))
    }

    fun addIssueSource(source: () -> List<FormIssue>) {
        sources.add(source)
    }

    /** Places issues on desktop [field] at the row [key] whenever the screen has it, after the editor's own alias. */
    fun addAlias(field: String, key: String) {
        aliases[field] = key
    }

    /** Where a model issue that no row shows can be fixed (the "Edit as JSON" action); named in its label. */
    var unplacedFix: String? = null

    /** Model issues ([Issue.field] is the desktop key), placed on rows by [alias] first and then by [keyFor]. */
    fun addModelIssues(collect: () -> List<Issue>, alias: (String) -> String? = { null }) {
        addIssueSource {
            val keys = screenKeys()
            collect().map { issue ->
                val key = keyFor(issue.field, keys, alias)
                FormIssue(issue.severity, if (key.isEmpty()) unplacedLabel(issue.field) else titleOf(key), issue.message, key)
            }
        }
    }

    private fun unplacedLabel(field: String): String {
        if (field.isBlank()) return field
        val fix = unplacedFix ?: return fieldLabel(field)
        return vtr(R.string.validation_form_unplaced, fieldLabel(field), fix)
    }

    fun start() {
        if (started) return
        started = true
        when (val store = fragment.preferenceManager.preferenceDataStore) {
            is RoomPreferenceDataStore -> store.registerChangeListener(this)
            is SettingsStore -> store.registerChangeListener(this)
        }
        installGates()
        fragment.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) stop()
        })
    }

    private fun stop() {
        started = false
        handler.removeCallbacksAndMessages(null)
        when (val store = fragment.preferenceManager.preferenceDataStore) {
            is RoomPreferenceDataStore -> store.unregisterChangeListener(this)
            is SettingsStore -> store.unregisterChangeListener(this)
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key == Key.PROFILE_DIRTY) return
        handler.post { changed(key) }
    }

    private fun changed(key: String) {
        if (!started) return
        touched.add(key)
        for (rule in rules) if (key in rule.keys) touched.addAll(rule.keys)
        refresh()
    }

    /** Re-checks and shows [key] as if it had just changed. */
    fun revalidate(key: String) = changed(key)

    /** Re-checks the shown marks; call after changing what is visible, enabled or onlyIf()-active. */
    fun refresh() {
        if (refreshQueued) return
        refreshQueued = true
        handler.post {
            refreshQueued = false
            if (started) safely { applyMarks(collect()) }
        }
    }

    /** Inline marks for everything currently invalid, without a dialog (call after loading data). */
    fun markAll() {
        showAll = true
        safely { applyMarks(collect()) }
    }

    fun issues(): List<FormIssue> = collectSafely().map { it.issue }

    /** The save gate: marks every issue and, when there are errors, scrolls to the first and lists them. */
    fun validateAll(): Boolean {
        showAll = true
        val found = collectSafely()
        safely { applyMarks(found) }
        val errors = found.filter { it.issue.severity == Severity.Error }
        if (errors.isEmpty()) return true
        errors.firstNotNullOfOrNull { f -> f.marks.firstOrNull { fragment.findPreference<Preference>(it) != null } }?.let { key ->
            revealer?.invoke(key)
            if (fragment.view != null && fragment.findPreference<Preference>(key)?.isShown == true) fragment.scrollToPreference(key)
        }
        summarize(errors.map { it.issue }, found.filter { it.issue.severity == Severity.Warning }.map { it.issue })
        return false
    }

    // ------------------------------------------------------------------------------------------------ checks

    private fun collectSafely(): List<Found> = try {
        collect()
    } catch (e: Exception) {
        Logs.w(e)
        emptyList()
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    private fun collect(): List<Found> {
        val out = ArrayList<Found>()
        for (field in fields) {
            if (!fieldActive(field)) continue
            val message = evaluate(field, valueOf(field.key))
            if (message.isNotEmpty()) {
                out.add(Found(FormIssue(field.severity, field.labelText ?: titleOf(field.key), message, field.key), listOf(field.key)))
            }
        }
        for (rule in rules) {
            val active = rule.keys.filter(::participantActive)
            if (active.isEmpty()) continue
            val message = rule.check()
            if (message.isNotEmpty()) {
                out.add(Found(FormIssue(rule.severity, rule.label ?: titleOf(active.first()), message, active.first()), active))
            }
        }
        for (source in sources) {
            for (issue in source()) out.add(Found(issue, (listOf(issue.key) + issue.related).filter { it.isNotEmpty() }.distinct()))
        }
        val order = screenOrder()
        val unique = out.distinctBy { Triple(it.issue.severity, it.issue.message, it.marks) }
        // A problem that a cross rule also reports over more rows (a model issue it repeats) is listed once, by the rule.
        return unique.filterNot { f ->
            unique.any { o ->
                o !== f && o.issue.severity == f.issue.severity && o.issue.message == f.issue.message &&
                    o.marks.size > f.marks.size && o.marks.containsAll(f.marks)
            }
        }.sortedBy { f -> f.marks.firstOrNull()?.let { order[it] } ?: Int.MAX_VALUE }
    }

    private fun usable(key: String): Boolean {
        val pref = fragment.findPreference<Preference>(key) ?: return false
        return (pref.isShown || collapsed?.invoke(pref) == true) && pref.isEnabled
    }

    private fun fieldActive(field: Field): Boolean = usable(field.key) && field.active?.invoke() != false

    /** Form's participantSkipped: hidden, disabled, or bound to a field that is onlyIf()-inactive. */
    private fun participantActive(key: String): Boolean =
        usable(key) && fields.none { it.key == key && it.active?.invoke() == false }

    private fun valueOf(key: String): String = when (val pref = fragment.findPreference<Preference>(key)) {
        is EditTextPreference -> pref.text.orEmpty()
        is ListPreference -> pref.value.orEmpty()
        else -> try {
            fragment.preferenceManager.preferenceDataStore?.getString(key, null).orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    private fun evaluate(field: Field, text: String): String {
        if (field.pure && field.lastText == text) return field.lastMessage
        val message = if (field.lines) linesMessage(field, text) else field.rule(if (field.trim) text.trim() else text)
        if (field.pure) {
            field.lastText = text
            field.lastMessage = message
        }
        return message
    }

    private fun linesMessage(field: Field, text: String): String {
        var first = ""
        var count = 0
        text.split('\n').forEachIndexed { i, raw ->
            val line = if (field.trim) raw.trim() else raw
            if (line.isBlank()) return@forEachIndexed
            val message = field.rule(line)
            if (message.isEmpty()) return@forEachIndexed
            if (count++ == 0) first = vtr(R.string.validation_form_line, (i + 1).toString(), elide(line.trim()), message)
        }
        if (count > 1) first += " " + vplural(R.plurals.validation_form_more_lines, count - 1)
        return first
    }

    // ------------------------------------------------------------------------------------------------ rows

    private fun forEachPreference(group: PreferenceGroup, action: (Preference) -> Unit) {
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i)
            action(pref)
            if (pref is PreferenceGroup) forEachPreference(pref, action)
        }
    }

    private fun screenKeys(): List<String> {
        val screen = fragment.preferenceScreen ?: return emptyList()
        val keys = ArrayList<String>()
        forEachPreference(screen) { pref -> pref.key?.let { keys.add(it) } }
        return keys
    }

    private fun screenOrder(): Map<String, Int> = screenKeys().withIndex().associate { it.value to it.index }

    private fun titleOf(key: String): String = fragment.findPreference<Preference>(key)?.title?.toString()?.ifBlank { null } ?: key

    /**
     * The row of a desktop issue [field] on this screen: the editor's [alias], the same key, the key ignoring case and
     * '_' (server_port is serverPort), a dial. or quic. block member, then the field's first sub-key; "" for none.
     */
    fun keyFor(field: String, keys: List<String> = screenKeys(), alias: (String) -> String? = { null }): String {
        alias(field)?.let { return it }
        aliases[field]?.let { if (it in keys) return it }
        if (field in keys) return field
        fun normalized(key: String) = key.replace("_", "").lowercase()
        for (candidate in listOf(field) + BLOCK_PREFIXES.map { "$it$field" }) {
            val wanted = normalized(candidate)
            keys.firstOrNull { normalized(it) == wanted }?.let { return it }
        }
        return keys.firstOrNull { it.startsWith("$field.") }.orEmpty()
    }

    private fun applyMarks(found: List<Found>) {
        val byKey = HashMap<String, MutableList<FormIssue>>()
        for (f in found) {
            if (!showAll && f.marks.none { it in touched }) continue
            for (key in f.marks) {
                val list = byKey.getOrPut(key) { ArrayList() }
                if (list.none { it.message == f.issue.message }) list.add(f.issue)
            }
        }
        for (key in rendered.keys + byKey.keys) {
            val issues = byKey[key].orEmpty()
            if (issues == rendered[key].orEmpty() && markIntact(key)) continue
            render(key, issues)
        }
    }

    /** False when the screen replaced the summary provider or the summary a mark was rendered into. */
    private fun markIntact(key: String): Boolean {
        if (rendered[key].isNullOrEmpty()) return true
        val pref = fragment.findPreference<Preference>(key) ?: return true
        val provider = pref.summaryProvider
        if (provider != null) return provider is MarkedSummary
        return pref.summary?.toString() == staticMarked[key]?.toString()
    }

    private fun render(key: String, issues: List<FormIssue>) {
        val pref = fragment.findPreference<Preference>(key)
        if (pref == null) {
            rendered.remove(key)
            return
        }
        if (issues.isEmpty()) rendered.remove(key) else rendered[key] = issues
        val provider = pref.summaryProvider
        if (provider != null) {
            // Setting the provider again re-renders the row.
            if (provider is MarkedSummary) pref.summaryProvider = provider
            else if (issues.isNotEmpty()) pref.summaryProvider = MarkedSummary(provider)
            return
        }
        val current = pref.summary
        if (!staticMarked.containsKey(key) || current?.toString() != staticMarked[key]?.toString()) staticBase[key] = current
        val base = staticBase[key]
        if (issues.isEmpty()) {
            staticMarked.remove(key)
            staticBase.remove(key)
            pref.summary = base
            return
        }
        val marked = decorate(base, issues)
        staticMarked[key] = marked
        pref.summary = marked
    }

    private inner class MarkedSummary(private val base: Preference.SummaryProvider<*>) : Preference.SummaryProvider<Preference> {
        @Suppress("UNCHECKED_CAST")
        override fun provideSummary(preference: Preference): CharSequence? {
            val text = (base as Preference.SummaryProvider<Preference>).provideSummary(preference)
            return decorate(text, rendered[preference.key].orEmpty())
        }
    }

    private fun decorate(text: CharSequence?, issues: List<FormIssue>): CharSequence? {
        if (issues.isEmpty()) return text
        val context = fragment.context ?: return text
        val out = SpannableStringBuilder()
        if (!text.isNullOrEmpty()) out.append(text)
        for (issue in issues) {
            if (out.isNotEmpty()) out.append('\n')
            val start = out.length
            out.append(issue.message)
            out.setSpan(ForegroundColorSpan(colorOf(context, issue.severity)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    // ------------------------------------------------------------------------------------------------ dialogs

    private fun installGates() {
        for ((key, gated) in fields.filter { it.gated && it.severity == Severity.Error }.groupBy { it.key }) {
            val pref = fragment.findPreference<Preference>(key) ?: continue
            val prior = pref.onPreferenceChangeListener
            pref.setOnPreferenceChangeListener { preference, newValue ->
                val text = newValue as? String ?: newValue?.toString().orEmpty()
                val message = gated.firstNotNullOfOrNull { field ->
                    if (fieldActive(field)) evaluate(field, text).ifEmpty { null } else null
                }
                if (message != null) {
                    showMessage(preference.title?.toString().orEmpty(), message)
                    false
                } else {
                    prior?.onPreferenceChange(preference, newValue) ?: true
                }
            }
        }
    }

    private fun showMessage(title: String, message: String) {
        val context = fragment.context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(title.ifEmpty { vtr(R.string.validation_form_title) })
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun summarize(errors: List<FormIssue>, warnings: List<FormIssue>) {
        showSummary(fragment.context ?: return, errors, warnings)
    }

    companion object {
        /** The dialog has no details pane, so it lists this many per severity before "…and N more". */
        private const val SUMMARY_LINES = 20

        private val BLOCK_PREFIXES = listOf("dial.", "quic.")

        private val CAMEL_BOUNDARY = Regex("([a-z0-9])([A-Z])")

        private val ACRONYMS = mapOf(
            "tls" to "TLS", "utls" to "uTLS", "ech" to "ECH", "sni" to "SNI", "alpn" to "ALPN", "quic" to "QUIC",
            "udp" to "UDP", "tcp" to "TCP", "dns" to "DNS", "ip" to "IP", "ipv4" to "IPv4", "ipv6" to "IPv6",
            "http" to "HTTP", "xhttp" to "XHTTP", "grpc" to "gRPC", "ws" to "WS", "url" to "URL", "id" to "ID",
            "uuid" to "UUID", "mtu" to "MTU", "psk" to "PSK",
        )

        /** A desktop field path in words: "tls.cipher_suites" is "TLS › Cipher suites", "ech.serverName" "ECH › Server name". */
        fun fieldLabel(field: String): String = field.split('.').filter { it.isNotEmpty() }.joinToString(" › ") { part ->
            val name = part.substringBefore('[')
            val words = name.replace(CAMEL_BOUNDARY, "\$1_\$2").split('_', '-').filter { it.isNotEmpty() }
            words.mapIndexed { i, word ->
                ACRONYMS[word.lowercase()] ?: if (i == 0) word.lowercase().replaceFirstChar { it.uppercaseChar() } else word.lowercase()
            }.joinToString(" ") + part.substring(name.length)
        }

        fun issueText(issue: FormIssue): String =
            if (issue.label.isEmpty()) issue.message else vtr(R.string.validation_form_issue, issue.label, issue.message)

        private fun appendBullets(out: StringBuilder, issues: List<FormIssue>) {
            issues.take(SUMMARY_LINES).joinTo(out, "\n") { "• " + issueText(it) }
            if (issues.size > SUMMARY_LINES) out.append('\n').append(vplural(R.plurals.validation_import_more, issues.size - SUMMARY_LINES))
        }

        private fun elide(text: String) = if (text.length > 40) text.take(39) + "…" else text

        fun colorOf(context: Context, severity: Severity): Int =
            if (severity == Severity.Error) MaterialColors.getColor(context, R.attr.colorError, 0xFFB3261E.toInt())
            else ContextCompat.getColor(context, R.color.material_orange_700)

        /** The save gate's dialog: the errors, then the warnings; also for problems not on a preference screen. */
        fun showSummary(context: Context, errors: List<FormIssue>, warnings: List<FormIssue> = emptyList()) {
            val text = StringBuilder(vtr(R.string.validation_form_fix)).append("\n\n")
            appendBullets(text, errors)
            if (warnings.isNotEmpty()) {
                text.append("\n\n").append(vtr(R.string.validation_form_warnings)).append('\n')
                appendBullets(text, warnings)
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.validation_form_title)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
}
