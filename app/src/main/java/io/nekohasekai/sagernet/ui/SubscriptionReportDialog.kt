package io.nekohasekai.sagernet.ui

import android.content.Context
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.group.SubscriptionClient
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.needReload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The change window of a manual subscription update (GroupUpdater's MessageBoxScrollable "Change of <group>"),
 * and the observer that shows the queue's reports while the main window is started.
 */
object SubscriptionReportDialog {

    fun show(context: Context, title: CharSequence, text: String) {
        val body = text.trim().ifEmpty { context.getString(R.string.grp_nothing) }
        val textView = TextView(context).apply {
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2)
            setText(body)
            setTextIsSelectable(true)
            setPadding(dp2px(24), dp2px(8), dp2px(24), dp2px(8))
        }
        val scroll = NestedScrollView(context).apply { addView(textView) }
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.action_copy) { _, _ -> SagerNet.trySetPrimaryClip(body) }
            .show()
    }

    /**
     * While [activity] is started: popup reports open the change window; errors of the requests made from the UI and
     * untitled status lines (the result of "Add profiles to this group") show a snackbar, a refresh that re-pointed
     * routes of the running config the reload hint. Titled reports without a
     * popup (automatic refreshes, URL-test follow-ups) are only logged, as on the desktop.
     */
    fun observe(activity: MainActivity) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SubscriptionClient.reports.collect { report -> handle(activity, report) }
            }
        }
    }

    private suspend fun handle(activity: MainActivity, report: SubscriptionClient.Report) {
        when {
            report.reload -> activity.needReload()

            report.error -> {
                val message = report.text.ifBlank { report.title }
                val groupName = withContext(Dispatchers.IO) { GroupRepo.get(report.gid)?.displayName() }
                activity.snackbar(if (groupName != null) "$groupName: $message" else message).show()
            }

            report.popup -> {
                val title = report.title.ifBlank {
                    val groupName = withContext(Dispatchers.IO) { GroupRepo.get(report.gid)?.displayName() }
                    activity.getString(R.string.grp_change_of, groupName.orEmpty())
                }
                show(activity, title, report.text)
            }

            report.title.isEmpty() && report.text.isNotBlank() -> activity.snackbar(report.text).show()
        }
    }
}
