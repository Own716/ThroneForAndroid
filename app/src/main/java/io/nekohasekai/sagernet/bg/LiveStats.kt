package io.nekohasekai.sagernet.bg

import android.os.Process
import android.os.SystemClock
import io.nekohasekai.sagernet.aidl.LiveStatsBatch
import io.throneproj.mobile.Instance
import kotlin.random.Random

/** Fixed-capacity ring of points (desktop SeriesBuffer): one time and [fields] values per point; index 0 is the oldest. */
class LiveSeries(val capacity: Int, val fields: Int) {

    private val times = LongArray(capacity)
    private val values = FloatArray(capacity * fields)
    private var head = 0

    var size = 0
        private set

    private fun slot(i: Int): Int = (head + i).let { if (it >= capacity) it - capacity else it }

    fun time(i: Int): Long = times[slot(i)]

    fun value(field: Int, i: Int): Float = values[slot(i) * fields + field]

    val latestTime: Long get() = if (size == 0) 0L else time(size - 1)

    fun push(t: Long, source: FloatArray, offset: Int = 0) {
        val s: Int
        if (size == capacity) {
            s = head
            head = slot(1)
        } else {
            s = slot(size)
            size++
        }
        times[s] = t
        source.copyInto(values, s * fields, offset, offset + fields)
    }

    /** The first point at or after [t]. */
    fun lowerBound(t: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (time(mid) < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun clear() {
        head = 0
        size = 0
    }
}

/**
 * The live graphs' history in the service process (desktop TrafficLooper -> MainWindow::add_traffic_point): a point per
 * traffic tick of the running core, so a graph opened later starts with the last [CAPACITY] seconds. Profile starts,
 * switches and stops are kept as markers, which also bound the stopped stretches the graph hatches.
 */
object LiveStatsRecorder {

    const val CAPACITY = 600

    /** A tick this late follows a pause (screen off): its rates average the pause, so it only restarts the series. */
    private const val GAP_MS = 2500L

    /** A start within this of a restart in place is the switch the desktop marks; later, the stop stands on its own. */
    private const val SWITCH_MS = 30_000L

    /** The longest graph window plus a minute. */
    private const val MARKER_AGE_MS = 11 * 60_000L
    private const val MAX_MARKERS = 100

    private class Marker(val t: Long, val kind: Int, val text: String)

    private val session = Random.nextLong(1L, Long.MAX_VALUE)
    private val series = LiveSeries(CAPACITY, LiveStatsBatch.FIELDS)
    private val point = FloatArray(LiveStatsBatch.FIELDS)
    private val markers = ArrayList<Marker>()
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private var serial = 0L
    private var lastTick = 0L
    private var lastCpuMs = 0L
    private var restartedAt = 0L

    /** One traffic tick of the running core: rates in bytes per second. */
    @Synchronized
    fun record(proxyDown: Long, proxyUp: Long, directDown: Long, directUp: Long, box: Instance?) {
        val now = SystemClock.elapsedRealtime()
        val cpuMs = Process.getElapsedCpuTime()
        val wall = now - lastTick
        val gap = lastTick == 0L || wall > GAP_MS || wall <= 0L
        val lastCpu = lastCpuMs
        lastTick = now
        lastCpuMs = cpuMs
        if (gap) return
        val status = box?.let { runCatching { it.status() }.getOrNull() }
        point[LiveStatsBatch.PROXY_DOWN] = proxyDown.toFloat()
        point[LiveStatsBatch.PROXY_UP] = proxyUp.toFloat()
        point[LiveStatsBatch.DIRECT_DOWN] = directDown.toFloat()
        point[LiveStatsBatch.DIRECT_UP] = directUp.toFloat()
        point[LiveStatsBatch.CPU] = (cpuMs - lastCpu).coerceAtLeast(0L) * 100f / wall / cores
        point[LiveStatsBatch.MEMORY] = status?.memory?.takeIf { it > 0L }?.toFloat() ?: Float.NaN
        point[LiveStatsBatch.CONNECTIONS] = status?.connectionsIn?.toFloat() ?: Float.NaN
        series.push(now, point)
        serial++
    }

    @Synchronized
    fun started(name: String) {
        val now = SystemClock.elapsedRealtime()
        settleRestart(now)
        val switched = restartedAt > 0L
        restartedAt = 0L
        addMarker(now, if (switched) LiveStatsBatch.MARKER_SWITCHED else LiveStatsBatch.MARKER_STARTED, name)
    }

    /** [restart]: the core is replaced in place (a switch or a reload), which the next start marks instead. */
    @Synchronized
    fun stopped(restart: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (restartedAt > 0L) addMarker(restartedAt, LiveStatsBatch.MARKER_STOPPED, "")
        restartedAt = 0L
        if (restart) restartedAt = now else addMarker(now, LiveStatsBatch.MARKER_STOPPED, "")
    }

    /** The points after [after] of [session]; all of them for another session. */
    @Synchronized
    fun batch(session: Long, after: Long): LiveStatsBatch {
        val now = SystemClock.elapsedRealtime()
        settleRestart(now)
        pruneMarkers(now)
        val since = if (session == this.session) after else 0L
        val count = (serial - since).coerceIn(0L, series.size.toLong()).toInt()
        val first = series.size - count
        val fields = LiveStatsBatch.FIELDS
        val values = FloatArray(count * fields)
        for (i in 0 until count) {
            for (f in 0 until fields) values[i * fields + f] = series.value(f, first + i)
        }
        return LiveStatsBatch(
            session = this.session,
            serial = serial,
            times = LongArray(count) { series.time(first + it) },
            values = values,
            markerTimes = LongArray(markers.size) { markers[it].t },
            markerKinds = IntArray(markers.size) { markers[it].kind },
            markerTexts = markers.map { it.text },
        )
    }

    /** A restart whose start never came (it failed) was a stop after all. */
    private fun settleRestart(now: Long) {
        if (restartedAt > 0L && now - restartedAt > SWITCH_MS) {
            addMarker(restartedAt, LiveStatsBatch.MARKER_STOPPED, "")
            restartedAt = 0L
        }
    }

    /** A stop stays while its stopped stretch (up to the next start, or still open) reaches into the age limit. */
    private fun pruneMarkers(now: Long) {
        while (markers.isNotEmpty() && now - markers[0].t > MARKER_AGE_MS) {
            if (markers[0].kind == LiveStatsBatch.MARKER_STOPPED) {
                val end = markers.firstOrNull { it.kind != LiveStatsBatch.MARKER_STOPPED } ?: return
                if (now - end.t <= MARKER_AGE_MS) return
            }
            markers.removeAt(0)
        }
    }

    private fun addMarker(t: Long, kind: Int, text: String) {
        markers.add(Marker(t, kind, text))
        if (markers.size > MAX_MARKERS) markers.removeAt(0)
    }
}
