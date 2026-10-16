package io.nekohasekai.sagernet.ui.settings

import android.os.Build
import android.os.SystemClock
import android.text.format.Formatter
import android.widget.Toast
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.CoreServiceClient
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.json.JsonWriter
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.update.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.LogExport
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The performance profile (src/sys/CoreDiagnostics.cpp) on the main-process side: owns the capture so it outlives the
 * Diagnostics screen, writes the attachments and has :bg, where the core runs, capture and zip
 * (ICoreService.captureDiagnostics). State is read and changed on the main thread only.
 */
object DiagnosticsCapture {

    const val CAPTURE_MS = 30_000
    const val MIME = "application/zip"
    const val SHARE_DIR = "diagnostics"
    private const val WORK_DIR = "diagnostics-attachments"
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    private const val STOP_RESEND_MS = 300L

    data class Options(val contention: Boolean = false, val trace: Boolean = false, val includeLogs: Boolean = false)

    var options = Options()
        private set
    var capturing = false
        private set
    var stopping = false
        private set
    var lastFile: File? = null
        private set
    var lastError: String? = null
        private set
    private var startedAt = 0L
    private var generation = 0

    private val listeners = LinkedHashSet<() -> Unit>()

    /** While a listener is registered (the screen is showing), completion is not announced with a toast. */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    val elapsedMs: Long get() = if (capturing) SystemClock.elapsedRealtime() - startedAt else 0L

    fun start(options: Options) {
        if (capturing) return
        this.options = options
        generation++
        capturing = true
        stopping = false
        lastError = null
        startedAt = SystemClock.elapsedRealtime()
        changed()
        runOnIoDispatcher {
            val outcome = runCatching { capture(options) }
            onMainDispatcher { complete(outcome) }
        }
    }

    fun stop() {
        if (!capturing || stopping) return
        stopping = true
        changed()
        val current = generation
        // The core only takes a stop once its window is open (after the attachments, the foreground and the capture
        // lock); an earlier one is dropped, so it is sent again until the capture returns.
        runOnIoDispatcher {
            while (onMainDispatcher { capturing && stopping && generation == current }) {
                try {
                    CoreServiceClient.call { it.stopDiagnostics() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        if (capturing && generation == current) {
                            stopping = false
                            changed()
                        }
                    }
                    break
                }
                delay(STOP_RESEND_MS)
            }
        }
    }

    private fun complete(outcome: Result<File>) {
        capturing = false
        stopping = false
        val notice = outcome.fold(onSuccess = { file ->
            lastFile = file
            lastError = null
            Logs.i("diagnostics: performance profile saved to ${file.path}")
            app.getString(R.string.diagnostics_notice_saved, Formatter.formatFileSize(app, file.length()))
        }, onFailure = { e ->
            val error = e.readableMessage
            lastError = error
            Logs.w("diagnostics: performance profile failed: $error")
            error
        })
        val showing = listeners.isNotEmpty()
        changed()
        if (!showing) Toast.makeText(app, notice, Toast.LENGTH_LONG).show()
    }

    private fun changed() {
        for (listener in listeners.toList()) listener()
    }

    private suspend fun capture(options: Options): File {
        val dir = File(app.cacheDir, SHARE_DIR).apply { mkdirs() }
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { if (now - it.lastModified() > MAX_AGE_MS) it.delete() }
        val work = File(app.cacheDir, WORK_DIR).apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val attachments = ArrayList<String>()
            attachments.add(File(work, "client.json").apply { writeText(clientInfo()) }.path)
            if (options.includeLogs) {
                try {
                    val log = LogExport.build(app, DataStore.logExportRedact, false)
                    val target = File(work, "throne.log")
                    if (!log.renameTo(target)) {
                        log.copyTo(target, overwrite = true)
                        log.delete()
                    }
                    attachments.add(target.path)
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }
            val output = archiveFile(dir)
            val coreError = try {
                CoreServiceClient.call {
                    it.captureDiagnostics(
                        CAPTURE_MS, options.contention, options.trace, attachments.toTypedArray(), output.path
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
                error(app.getString(R.string.diagnostics_core_unreachable))
            }
            if (!coreError.isNullOrEmpty()) error(app.getString(R.string.diagnostics_core_failed, coreError))
            if (!output.isFile || output.length() == 0L) {
                output.delete()
                error(app.getString(R.string.diagnostics_empty))
            }
            return output
        } finally {
            work.deleteRecursively()
        }
    }

    private fun archiveFile(dir: File): File {
        val stem = "throne-profile-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var file = File(dir, "$stem.zip")
        var n = 2
        while (file.exists()) file = File(dir, "$stem-${n++}.zip")
        return file
    }

    /** clientInfo (CoreDiagnostics.cpp): the build, the device and the few settings that shape the core's load. */
    private fun clientInfo(): String {
        val store = DataStore.configurationStore
        val state = DataStore.serviceState
        val profileType = if (state.started) {
            runCatching { ProfileManager.getProfile(DataStore.currentProfile)?.type }.getOrNull()
        } else {
            null
        }
        return JsonWriter.writeIndented(
            jsonObjectOf(
                "throne_version" to SagerNet.appVersionNameForDisplay,
                "version_code" to BuildConfig.VERSION_CODE,
                "build_type" to if (BuildConfig.DEBUG) "debug" else "release",
                "core_ref" to BuildConfig.THRONE_CORE_REF,
                "os" to "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "cpu_abi" to (UpdateChecker.installedAbi(app) ?: "unknown"),
                "supported_abis" to JsonValues.stringArray(Build.SUPPORTED_ABIS.toList()),
                "service_state" to state.name.lowercase(Locale.ROOT),
                "proxy_running" to state.started,
                "running_profile_type" to profileType.orEmpty(),
                "tun" to (DataStore.serviceMode == Key.MODE_VPN),
                "enable_stats" to SettingsRegistry.ENABLE_STATS.read(store),
                "resolve_domain_strategy" to SettingsRegistry.DOMAIN_STRATEGY.read(store),
                "fake_dns" to SettingsRegistry.FAKEDNS.read(store),
                "enable_tun_routing" to SettingsRegistry.ENABLE_TUN_ROUTING.read(store),
            )
        )
    }
}
