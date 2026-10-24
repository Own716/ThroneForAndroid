package io.nekohasekai.sagernet.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {

    private val changelog = listOf(
        "Preamble that belongs to no version.",
        "",
        "#### 2.1.0-pre.3",
        "",
        "* Pre 3",
        "",
        "#### 2.1.0-pre.2",
        "",
        "* Pre 2",
        "",
        "#### 2.1.0-pre.1",
        "",
        "* Pre 1",
        "",
        "#### 2.0.5",
        "",
        "* Five **1**",
        "",
        "**1**:",
        "Footnote.",
        "",
        "#### 2.0.5-pre.1",
        "",
        "* Five pre",
        "",
        "#### 2.0.4",
        "",
        "",
        "#### 2.0.3",
        "",
        "* Three",
        "",
    ).joinToString("\r\n")

    @Test
    fun stableTargetSkipsPreReleasesAndEmptySections() {
        assertEquals(
            "#### 2.0.5\n\n* Five **1**\n\n**1**:\nFootnote.",
            ReleaseNotes.select(changelog, "2.0.3", "v2.0.5"),
        )
    }

    @Test
    fun preReleaseTargetIncludesSectionsUpToIt() {
        assertEquals(
            "#### 2.1.0-pre.2\n\n* Pre 2\n\n#### 2.1.0-pre.1\n\n* Pre 1",
            ReleaseNotes.select(changelog, "2.0.5", "v2.1.0-pre.2"),
        )
    }

    @Test
    fun previewBuildSeesItsOwnRelease() {
        assertEquals(
            "#### 2.0.5\n\n* Five **1**\n\n**1**:\nFootnote.",
            ReleaseNotes.select(changelog, "2.0.5-pre.12", "v2.0.5"),
        )
    }

    @Test
    fun unparsableVersionsGiveNothing() {
        assertEquals("", ReleaseNotes.select(changelog, "", "v2.0.5"))
        assertEquals("", ReleaseNotes.select(changelog, "2.0.3", "latest"))
        assertEquals("", ReleaseNotes.select(changelog, "2.0.5", "v2.0.5"))
    }
}
