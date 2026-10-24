package moe.matsuri.nb4a.utils

import androidx.preference.PreferenceDataStore
import androidx.room.InvalidationTracker
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

// Shared by both processes: every line is an O_APPEND write on one inode. At the size cap the newest half is kept in
// place under a file lock (both processes may hit the cap; the loser re-checks the size), so a process that opened
// the file earlier keeps appending to it. A new :bg process keeps the previous session in core.log.prev.
object CoreLog {

    private const val FILE_NAME = "core.log"
    private const val PREVIOUS_FILE_NAME = "core.log.prev"
    const val DEFAULT_SIZE_KB = 512
    const val MIN_SIZE_KB = 50
    const val MAX_SIZE_KB = 10 * 1024
    private const val SETTINGS_TABLE = "settings"

    private val lock = Any()
    private var stream: FileOutputStream? = null
    // Local time like the desktop's log.
    private val timestamp = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US)

    val file: File get() = File(app.filesDir, FILE_NAME)
    val previousFile: File get() = File(app.filesDir, PREVIOUS_FILE_NAME)

    @Volatile
    private var cachedLevel: String? = null

    @Volatile
    private var cachedSizeKb = 0

    private var watching = false

    private val refreshTicket = AtomicLong()
    private var appliedTicket = 0L

    /** The stored log_level, cached for every line written; null while the settings cannot be read (JVM tests). */
    val logLevel: String?
        get() = cachedLevel ?: run {
            refreshSettings()
            cachedLevel
        }

    /**
     * Re-reads log_level and logBufSize. The first read starts watching the settings table, so a change made in
     * either process reaches the cache.
     */
    fun refreshSettings() {
        // Overlapping refreshes (tracker callbacks run concurrently) may finish out of order: the one that started
        // last read the freshest value, so an older read never overwrites it. The DB read stays outside the monitor.
        val ticket = refreshTicket.incrementAndGet()
        val (level, sizeKb) = runCatching { DataStore.logLevel to DataStore.logBufSize }.getOrNull() ?: return
        synchronized(this) {
            if (ticket > appliedTicket) {
                appliedTicket = ticket
                cachedSizeKb = sizeKb
                cachedLevel = level
            }
            if (watching) return
            watching = true
        }
        DataStore.configurationStore.registerChangeListener(object : OnPreferenceDataStoreChangeListener {
            override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
                if (key == SettingsRegistry.LOG_LEVEL.key || key == Key.LOG_BUF_SIZE) refreshSettings()
            }
        })
        // The listener only hears this process; the table observer also hears the other one and bulk restores.
        runOnIoDispatcher {
            runCatching {
                SagerDatabase.instance.invalidationTracker.addObserver(
                    object : InvalidationTracker.Observer(arrayOf(SETTINGS_TABLE)) {
                        override fun onInvalidated(tables: Set<String>) = refreshSettings()
                    }
                )
            }
        }
    }

    /** logBufSize KB (the long-press setting) within [MIN_SIZE_KB]..[MAX_SIZE_KB], [DEFAULT_SIZE_KB] while unset. */
    private fun maxSizeBytes(): Long {
        val kb = cachedSizeKb
        return (if (kb <= 0) DEFAULT_SIZE_KB else kb.coerceIn(MIN_SIZE_KB, MAX_SIZE_KB)) * 1024L
    }

    // "panic" keeps the file empty, the way the old "none" level did.
    private fun enabled(): Boolean = logLevel != "panic"

    private fun stream(): FileOutputStream {
        stream?.let { return it }
        return FileOutputStream(file, true).also { stream = it }
    }

    // The core colours its lines for terminals; the Log screen and exports are plain text.
    private val ansiColor = Regex("\u001B\\[[0-9;]*m")

    fun write(line: String) {
        if (!enabled()) return
        val stamp = synchronized(timestamp) { timestamp.format(Date()) }
        val text = if (line.indexOf('\u001B') >= 0) ansiColor.replace(line, "") else line
        val bytes = "$stamp $text\n".toByteArray()
        synchronized(lock) {
            runCatching {
                val out = stream()
                val max = maxSizeBytes()
                if (out.channel.size() + bytes.size > max) rotate(out.channel, max, bytes.size)
                out.write(bytes)
            }.onFailure { stream = null }
        }
    }

    private fun rotate(channel: FileChannel, max: Long, incoming: Int) {
        val fileLock = channel.lock()
        try {
            val size = channel.size()
            if (size + incoming <= max) return
            val keep = minOf(size, max / 2).toInt()
            val tail = ByteArray(keep)
            RandomAccessFile(file, "r").use {
                it.seek(size - keep)
                it.readFully(tail)
            }
            val start = if (keep.toLong() == size) 0 else tail.indexOf('\n'.code.toByte()) + 1
            channel.truncate(0)
            channel.write(ByteBuffer.wrap(tail, start, keep - start))
        } finally {
            fileLock.release()
        }
    }

    /** A new :bg process: the previous session moves to core.log.prev, so a crashed service's log survives. */
    fun newSession() {
        refreshSettings()
        synchronized(lock) {
            runCatching {
                val channel = stream().channel
                val fileLock = channel.lock()
                try {
                    if (channel.size() > 0) {
                        file.copyTo(previousFile, overwrite = true)
                        channel.truncate(0)
                    }
                } finally {
                    fileLock.release()
                }
            }.onFailure { stream = null }
        }
    }

    /** Clears both the current and the previous session. */
    fun clear() {
        synchronized(lock) {
            runCatching { stream().channel.truncate(0) }.onFailure { stream = null }
            runCatching { previousFile.delete() }
        }
    }

    fun read(maxBytes: Long): ByteArray = read(file, maxBytes)

    fun readPrevious(): ByteArray = read(previousFile, 0)

    private fun read(target: File, maxBytes: Long): ByteArray {
        return try {
            val length = target.length()
            FileInputStream(target).use { input ->
                if (maxBytes !in 1 until length) return input.readBytes()
                input.skip(length - maxBytes)
                val tail = input.readBytes()
                // The cut lands mid-line: start at the next full line.
                val start = tail.indexOf('\n'.code.toByte()) + 1
                tail.copyOfRange(start, tail.size)
            }
        } catch (e: Exception) {
            e.stackTraceToString().toByteArray()
        }
    }
}
