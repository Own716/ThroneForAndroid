package io.nekohasekai.sagernet.bg

import androidx.core.app.NotificationCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.readableMessage
import io.throneproj.mobile.DiagnosticsRequest
import io.throneproj.mobile.Mobile
import java.io.File

/**
 * :bg side of ICoreService.captureDiagnostics (core/mobile/diagnostics.go): the core profiles the process it runs in,
 * which is this one. Keeps the service in the foreground for the window, so the capture survives the app going to the
 * background while the user reproduces a problem.
 */
object CoreDiagnostics {

    private const val FOREGROUND_REASON = "diagnostics"

    /** Blocks the binder thread for the window; returns "" or the error. */
    fun capture(
        durationMs: Int,
        contention: Boolean,
        trace: Boolean,
        attachmentPaths: Array<String>,
        outputPath: String,
    ): String = try {
        val request = DiagnosticsRequest()
        request.durationMs = durationMs
        request.contention = contention
        request.trace = trace
        for (path in attachmentPaths) {
            val file = File(path)
            if (file.isFile) request.addAttachment(file.name, file.readBytes())
        }
        CoreForeground.acquire(FOREGROUND_REASON, notification())
        try {
            Mobile.captureDiagnostics(request, outputPath)
        } finally {
            CoreForeground.release(FOREGROUND_REASON)
        }
        ""
    } catch (e: Throwable) {
        Logs.w(e)
        e.readableMessage
    }

    fun stop() = Mobile.stopDiagnostics()

    private fun notification() = NotificationCompat.Builder(app, CoreForeground.CHANNEL)
        .setSmallIcon(R.drawable.ic_throne_tile)
        .setContentTitle(app.getString(R.string.app_name))
        .setContentText(app.getString(R.string.diagnostics_notification))
        .setProgress(0, 0, true)
        .setOngoing(true)
        .setSilent(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setContentIntent(SagerNet.configureIntent(app))
        .build()
}
