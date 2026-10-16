package io.nekohasekai.sagernet.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutDiagnosticsBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import io.nekohasekai.sagernet.ktx.safeSnackbar
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ui.SaveDocument
import io.nekohasekai.sagernet.widget.applyListInsets
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Settings › Diagnostics, the desktop's Diagnostics tab (DiagnosticsTab.cpp): a 30 s pprof capture of the core with
 * optional lock contention, execution trace and logs; the zip is shared or saved instead of shown in a folder.
 */
class DiagnosticsFragment : Fragment(R.layout.layout_diagnostics) {

    private var binding: LayoutDiagnosticsBinding? = null
    private var ticker: Job? = null
    private var statusColors: ColorStateList? = null
    private val listener: () -> Unit = { refresh() }

    private val saveArchive = registerForActivityResult(SaveDocument(DiagnosticsCapture.MIME)) { uri ->
        val file = DiagnosticsCapture.lastFile
        if (uri == null || file == null) return@registerForActivityResult
        val context = requireContext().applicationContext
        runOnIoDispatcher {
            val error = try {
                context.contentResolver.openOutputStream(uri)!!.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                null
            } catch (e: Exception) {
                Logs.w(e)
                e.readableMessage
            }
            onMainDispatcher {
                safeSnackbar(
                    if (error == null) context.getString(R.string.diagnostics_exported)
                    else context.getString(R.string.diagnostics_save_failed, file.name, error)
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = LayoutDiagnosticsBinding.bind(view)
        this.binding = binding
        view.applyListInsets()
        statusColors = binding.diagnosticsStatus.textColors

        val options = DiagnosticsCapture.options
        binding.diagnosticsContention.isChecked = options.contention
        binding.diagnosticsTrace.isChecked = options.trace
        binding.diagnosticsIncludeLogs.isChecked = options.includeLogs
        binding.diagnosticsHideSensitive.isChecked = DataStore.logExportRedact

        binding.diagnosticsIncludeLogs.setOnCheckedChangeListener { _, _ -> refresh() }
        binding.diagnosticsHideSensitive.setOnCheckedChangeListener { _, checked -> DataStore.logExportRedact = checked }
        binding.diagnosticsStart.setOnClickListener {
            DiagnosticsCapture.start(
                DiagnosticsCapture.Options(
                    contention = binding.diagnosticsContention.isChecked,
                    trace = binding.diagnosticsTrace.isChecked,
                    includeLogs = binding.diagnosticsIncludeLogs.isChecked,
                )
            )
        }
        binding.diagnosticsStop.setOnClickListener { DiagnosticsCapture.stop() }
        binding.diagnosticsShare.setOnClickListener { savedArchive()?.let(::share) }
        binding.diagnosticsSave.setOnClickListener {
            savedArchive()?.let { startFilesForResult(saveArchive, it.name) }
        }
    }

    override fun onStart() {
        super.onStart()
        DiagnosticsCapture.addListener(listener)
        refresh()
    }

    override fun onStop() {
        DiagnosticsCapture.removeListener(listener)
        stopTicker()
        super.onStop()
    }

    override fun onDestroyView() {
        stopTicker()
        binding = null
        super.onDestroyView()
    }

    private fun savedArchive(): File? = DiagnosticsCapture.lastFile?.takeIf { it.isFile }

    private fun refresh() {
        val b = binding ?: return
        val busy = DiagnosticsCapture.capturing
        b.diagnosticsContention.isEnabled = !busy
        b.diagnosticsTrace.isEnabled = !busy
        b.diagnosticsIncludeLogs.isEnabled = !busy
        b.diagnosticsHideSensitive.isEnabled = !busy && b.diagnosticsIncludeLogs.isChecked
        b.diagnosticsStart.isEnabled = !busy
        b.diagnosticsProgress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        b.diagnosticsStop.isVisible = busy
        b.diagnosticsStop.isEnabled = !DiagnosticsCapture.stopping

        val last = savedArchive()
        b.diagnosticsShare.isEnabled = !busy && last != null
        b.diagnosticsSave.isEnabled = !busy && last != null

        val error = DiagnosticsCapture.lastError
        when {
            busy -> showProgress()
            error != null -> setStatus(error, true)
            last != null -> setStatus(
                getString(R.string.diagnostics_saved, last.name, Formatter.formatFileSize(requireContext(), last.length())),
                false,
            )

            else -> setStatus("", false)
        }

        if (busy && ticker == null) {
            ticker = viewLifecycleOwner.lifecycleScope.launch {
                while (isActive) {
                    delay(TICK_MS)
                    showProgress()
                }
            }
        } else if (!busy) {
            stopTicker()
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun showProgress() {
        val b = binding ?: return
        if (!DiagnosticsCapture.capturing) return
        val elapsed = DiagnosticsCapture.elapsedMs
        val total = DiagnosticsCapture.CAPTURE_MS
        val finishing = elapsed >= total || DiagnosticsCapture.stopping
        val bar = b.diagnosticsProgress
        // The indicator switches mode only while hidden.
        if (bar.isIndeterminate != finishing) {
            bar.visibility = View.INVISIBLE
            bar.isIndeterminate = finishing
            bar.visibility = View.VISIBLE
        }
        if (finishing) {
            setStatus(getString(R.string.diagnostics_finishing), false)
        } else {
            bar.setProgressCompat((elapsed * 100 / total).toInt(), false)
            setStatus(getString(R.string.diagnostics_recording, clockText(total - elapsed)), false)
        }
    }

    private fun setStatus(text: String, error: Boolean) {
        val status = binding?.diagnosticsStatus ?: return
        status.text = text
        if (error) {
            status.setTextColor(requireContext().getColorAttr(R.attr.colorError))
        } else {
            statusColors?.let { status.setTextColor(it) }
        }
    }

    private fun share(file: File) {
        val context = requireContext()
        val uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".cache", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(DiagnosticsCapture.MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        try {
            startActivity(Intent.createChooser(send, getString(R.string.abc_shareactionprovider_share_with)))
        } catch (_: ActivityNotFoundException) {
            snackbar(R.string.share_target_missing).show()
        }
    }

    private companion object {
        const val TICK_MS = 250L

        fun clockText(ms: Long): String {
            val secs = (ms.coerceAtLeast(0) + 999) / 1000
            return "%d:%02d".format(secs / 60, secs % 60)
        }
    }
}
