package io.nekohasekai.sagernet.update

import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BulletSpan
import android.text.style.ClickableSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.TextView
import io.nekohasekai.sagernet.ktx.launchCustomTab

/**
 * CHANGELOG.md (the desktop's format: `#### <version>` sections, newest first) as update release notes. CI publishes a
 * version's section as its release body (buildScript/release_notes.sh); the updater reads the file at the offered tag.
 */
object ReleaseNotes {

    private const val HEADING = "#### "

    /** Sections after [installed] up to [target]; a stable release's own section covers its pre-releases. */
    fun select(changelog: String, installed: String, target: String): String {
        val current = Semver.parse(installed) ?: return ""
        val offered = Semver.parse(target) ?: return ""
        val withPreReleases = offered.pre.isNotEmpty()

        val sections = ArrayList<String>()
        var heading: String? = null
        val body = ArrayList<String>()
        fun flush() {
            val version = heading?.let(Semver::parse) ?: return
            val text = body.joinToString("\n").trim()
            if (text.isEmpty() || Semver.compare(version, current) <= 0 || Semver.compare(version, offered) > 0) return
            if (version.pre.isNotEmpty() && !withPreReleases) return
            sections += "$HEADING$heading\n\n$text"
        }
        for (line in changelog.lineSequence().map { it.removeSuffix("\r") }) {
            if (line.startsWith(HEADING)) {
                flush()
                heading = line.substring(HEADING.length).trim()
                body.clear()
            } else {
                body += line
            }
        }
        flush()
        return sections.joinToString("\n\n")
    }

    private val atxHeading = Regex("""^#{1,6}\s+""")
    private val inline = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\((https?://[^)\s]+)\)""")

    /**
     * The subset CHANGELOG.md uses: headings, `*`/`-` bullets, bold, code and http(s) links, styled for [view]. Links
     * are plain [ClickableSpan]s because the view's autoLink would drop URLSpans.
     */
    fun render(markdown: String, view: TextView): CharSequence {
        val density = view.resources.displayMetrics.density
        val out = SpannableStringBuilder()
        var gap = false
        for (raw in markdown.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) {
                gap = out.isNotEmpty()
                continue
            }
            if (out.isNotEmpty()) out.append(if (gap) "\n\n" else "\n")
            gap = false
            val start = out.length
            val heading = atxHeading.find(line)
            when {
                heading != null -> {
                    appendInline(out, line.substring(heading.range.last + 1).trim())
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(1.15f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                line.startsWith("* ") || line.startsWith("- ") -> {
                    appendInline(out, line.substring(2).trim())
                    val bulletGap = (8 * density).toInt()
                    val bullet = if (Build.VERSION.SDK_INT >= 28) {
                        BulletSpan(bulletGap, view.currentTextColor, (2 * density).toInt())
                    } else {
                        BulletSpan(bulletGap)
                    }
                    out.setSpan(bullet, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                else -> appendInline(out, line)
            }
        }
        return out
    }

    private fun appendInline(out: SpannableStringBuilder, text: String) {
        var last = 0
        for (match in inline.findAll(text)) {
            out.append(text, last, match.range.first)
            last = match.range.last + 1
            val start = out.length
            val (bold, code, label, url) = match.destructured
            when {
                bold.isNotEmpty() -> {
                    appendInline(out, bold)
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                code.isNotEmpty() -> {
                    out.append(code)
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                else -> {
                    out.append(label)
                    out.setSpan(LinkSpan(url), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        out.append(text, last, text.length)
    }

    private class LinkSpan(private val url: String) : ClickableSpan() {
        override fun onClick(widget: View) {
            widget.context.launchCustomTab(url)
        }
    }
}
