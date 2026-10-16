package io.nekohasekai.sagernet.outbound.import

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.vplural
import io.nekohasekai.sagernet.validation.vtr

/**
 * ParseReport (SubscriptionParser.hpp): one per parsed item that was refused or carries issues. [source] is never the
 * raw link, which holds secrets. A [document] report is about the whole document (no proxies list, broken JSON), not
 * one profile: it counts neither as failed nor as warned.
 */
class ParseReport(
    @JvmField val source: String,
    @JvmField val imported: Boolean,
    @JvmField val issues: List<Issue>,
    @JvmField val document: Boolean = false,
)

/**
 * ImportReport (include/configs/sub/ImportReport.hpp, src/configs/sub/ImportReport.cpp): aggregates per-item parse
 * reports; identical (severity, field, message) issues collapse into one line with a count and up to three examples.
 */
class ImportReport {

    private class Group(val severity: Severity, val field: String, val message: String) {
        var count = 0
        val examples = ArrayList<String>()
    }

    private var importedCount = 0
    private var failedCount = 0
    private var warnedCount = 0
    val imported: Int get() = importedCount
    val failed: Int get() = failedCount
    val warned: Int get() = warnedCount
    private val fatal = ArrayList<String>()
    private val groups = ArrayList<Group>()
    private val groupIndex = HashMap<String, Int>()

    fun add(report: ParseReport) {
        if (!report.document) {
            if (!report.imported) failedCount++
            if (report.imported && report.issues.isNotEmpty()) warnedCount++
        }
        for (issue in report.issues) {
            // A document-level error is why the import fell short, so it joins the summary's reasons.
            if (report.document && issue.severity == Severity.Error) {
                val text = if (issue.field.isEmpty()) issue.message else issue.field + ": " + issue.message
                addFatal(if (report.source.isEmpty()) text else report.source + ": " + text)
                continue
            }
            val key = "${issue.severity.ordinal}\u001f${issue.field}\u001f${issue.message}"
            val index = groupIndex.getOrPut(key) {
                groups.add(Group(issue.severity, issue.field, issue.message))
                groups.size - 1
            }
            val group = groups[index]
            group.count++
            if (group.examples.size >= EXAMPLES) continue
            var example = report.source
            if (issue.value.isNotEmpty()) {
                val value = "\"" + elide(issue.value) + "\""
                example = if (example.isEmpty()) value else "$example ($value)"
            }
            if (example.isNotEmpty() && example !in group.examples) group.examples.add(example)
        }
    }

    fun addAll(reports: List<ParseReport>) {
        for (report in reports) add(report)
    }

    fun addFatal(reason: String) {
        val text = reason.trim()
        if (text.isNotEmpty() && text !in fatal) fatal.add(text)
    }

    fun setImported(count: Int) {
        importedCount = count
    }

    fun hasProblems(): Boolean = failedCount > 0 || warnedCount > 0 || fatal.isNotEmpty() || groups.isNotEmpty()

    /** A refused item, a fatal reason or an Error issue. */
    fun hasErrors(): Boolean = failedCount > 0 || fatal.isNotEmpty() || groups.any { it.severity == Severity.Error }

    /** "Imported 12 profiles. 3 could not be imported. 5 imported with warnings." then one fatal reason per line. */
    fun summary(): String {
        val text = StringBuilder(vplural(R.plurals.validation_import_imported, importedCount))
        if (failedCount > 0) text.append(' ').append(vplural(R.plurals.validation_import_failed, failedCount))
        if (warnedCount > 0) text.append(' ').append(vplural(R.plurals.validation_import_warned, warnedCount))
        for (reason in fatal) text.append('\n').append(reason)
        return text.toString()
    }

    /** Grouped lines, errors first: "[error] port: Must be a port number from 1 to 65535. (3×, e.g. a, b, c)". */
    @JvmOverloads
    fun details(maxLines: Int = 50): String {
        val lines = ArrayList<String>()
        fun line(g: Group): String {
            var text = if (g.field.isEmpty()) g.message else g.field + ": " + g.message
            val examples = g.examples.joinToString(", ")
            text = when {
                g.count > 1 && g.examples.isNotEmpty() -> vtr(R.string.validation_import_count_examples, text, g.count.toString(), examples)
                g.count > 1 -> vtr(R.string.validation_import_count, text, g.count.toString())
                g.examples.isNotEmpty() -> vtr(R.string.validation_import_examples, text, examples)
                else -> text
            }
            val tag = vtr(if (g.severity == Severity.Error) R.string.validation_import_tag_error else R.string.validation_import_tag_warning)
            return "$tag $text"
        }
        for (severity in listOf(Severity.Error, Severity.Warning)) {
            for (group in groups) {
                if (group.severity != severity) continue
                if (lines.size >= maxLines) break
                lines.add(line(group))
            }
        }
        val more = groups.size - lines.size
        if (more > 0) lines.add(vplural(R.plurals.validation_import_more, more))
        return lines.joinToString("\n")
    }

    /** ShowImportReport's box text: the summary, then the details after a blank line. */
    fun text(): String {
        val details = details()
        return if (details.isEmpty()) summary() else summary() + "\n\n" + details
    }

    private companion object {
        const val EXAMPLES = 3
        const val VALUE_CHARS = 64

        fun elide(text: String): String = if (text.length > VALUE_CHARS) text.take(VALUE_CHARS) + "…" else text
    }
}
