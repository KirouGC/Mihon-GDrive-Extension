package eu.kanade.tachiyomi.extension.all.nyaa

import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Cliente torrent mínimo: baja solo los archivos pedidos de un torrent y lo saca de la sesión
 * en cuanto terminan, para no quedarse compartiendo. La sesión se apaga sola si nadie la usa.
 */
class TorrentEngine(
    private val loadNative: () -> Unit,
    private val baseDir: File,
    private val uploadLimitKb: () -> Int,
    private val stallTimeoutMs: () -> Long,
    private val maxBytes: () -> Long,
) {
    private val lock = Any()
    private var session: SessionManager? = null
    private val activeRequests = HashMap<String, Int>()
    private val infos = ConcurrentHashMap<String, TorrentInfo>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "nyaa-torrent").apply { isDaemon = true } }
    private var idleStop: ScheduledFuture<*>? = null

    fun info(torrentId: String, bytes: ByteArray): TorrentInfo = infos.getOrPut(torrentId) {
        loadNative()
        TorrentInfo(bytes)
    }

    fun localFile(torrentId: String, info: TorrentInfo, index: Int) = File(File(baseDir, torrentId), info.files().filePath(index))

    /** Índice de cada archivo del torrent por su ruta (sin archivos de relleno). */
    fun indexByPath(info: TorrentInfo): Map<String, Int> {
        val files = info.files()
        return (0 until files.numFiles()).filter { !files.padFileAt(it) }.associateBy { files.filePath(it) }
    }

    /** Baja (si hace falta) los archivos [indices] y devuelve sus rutas locales. Bloquea hasta terminar. */
    fun download(torrentId: String, bytes: ByteArray, indices: List<Int>): List<File> {
        val info = info(torrentId, bytes)
        val dir = File(baseDir, torrentId)
        val result = indices.map { localFile(torrentId, info, it) }
        val pending = indices.filter { !doneMarker(dir, it).exists() || !localFile(torrentId, info, it).exists() }
        if (pending.isEmpty()) {
            dir.setLastModified(System.currentTimeMillis())
            return result
        }

        val handle = synchronized(lock) {
            idleStop?.cancel(false)
            activeRequests[torrentId] = (activeRequests[torrentId] ?: 0) + 1
            try {
                val sm = session ?: startSession().also { session = it }
                val existing = sm.find(info.infoHash())?.takeIf { it.isValid }
                if (existing != null) {
                    pending.forEach { existing.filePriority(it, Priority.DEFAULT) }
                    existing.resume()
                    existing
                } else {
                    dir.mkdirs()
                    val priorities = Array(info.numFiles()) { if (it in pending) Priority.DEFAULT else Priority.IGNORE }
                    sm.download(info, dir, null, priorities, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                    waitForHandle(sm, info)
                }
            } catch (e: Throwable) {
                release(torrentId)
                throw e
            }
        }

        try {
            waitForFiles(handle, info, pending)
            pending.forEach { doneMarker(dir, it).createNewFile() }
        } finally {
            synchronized(lock) { release(torrentId) }
        }
        dir.setLastModified(System.currentTimeMillis())
        trim()
        return result
    }

    private fun waitForHandle(sm: SessionManager, info: TorrentInfo): TorrentHandle {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            sm.find(info.infoHash())?.takeIf { it.isValid }?.let { return it }
            Thread.sleep(200)
        }
        throw IOException("libtorrent no pudo agregar el torrent")
    }

    private fun waitForFiles(handle: TorrentHandle, info: TorrentInfo, indices: List<Int>) {
        val files = info.files()
        var lastDone = -1L
        var lastChange = System.currentTimeMillis()
        while (true) {
            val progress = handle.fileProgress()
            if (indices.all { progress[it] >= files.fileSize(it) }) break
            val done = indices.sumOf { progress[it] }
            val now = System.currentTimeMillis()
            if (done > lastDone) {
                lastDone = done
                lastChange = now
            } else if (now - lastChange > stallTimeoutMs()) {
                val status = handle.status()
                throw IOException(
                    "La descarga no avanza (${status.numSeeds()} seeders, ${status.numPeers()} peers). " +
                        "Puede que el torrent no tenga gente compartiendo.",
                )
            }
            Thread.sleep(500)
        }
        // Que libtorrent termine de escribir en disco lo que ya bajó.
        handle.flushCache()
        Thread.sleep(500)
    }

    /** Llamar con [lock] tomado. Saca el torrent de la sesión cuando ya nadie espera archivos de él. */
    private fun release(torrentId: String) {
        val remaining = (activeRequests[torrentId] ?: 1) - 1
        if (remaining > 0) {
            activeRequests[torrentId] = remaining
            return
        }
        activeRequests.remove(torrentId)
        val sm = session ?: return
        infos[torrentId]?.let { info -> sm.find(info.infoHash())?.takeIf { it.isValid }?.let { sm.remove(it) } }
        if (activeRequests.isEmpty()) {
            idleStop = scheduler.schedule({ stopIfIdle() }, IDLE_STOP_MINUTES, TimeUnit.MINUTES)
        }
    }

    private fun stopIfIdle() {
        synchronized(lock) {
            if (activeRequests.isNotEmpty()) return
            session?.let { runCatching { it.stop() } }
            session = null
        }
    }

    private fun startSession(): SessionManager {
        loadNative()
        val settings = SettingsPack()
            .uploadRateLimit(uploadLimitKb() * 1024)
            .connectionsLimit(200)
        return SessionManager(false).apply { start(SessionParams(settings)) }
    }

    fun applyUploadLimit() {
        synchronized(lock) { session?.uploadRateLimit(uploadLimitKb() * 1024) }
    }

    private fun doneMarker(dir: File, index: Int) = File(dir, ".ok-$index")

    /** Borra los torrents bajados más viejos si se pasa del espacio configurado. */
    private fun trim() {
        val dirs = baseDir.listFiles()?.filter { it.isDirectory } ?: return
        val sizes = dirs.associateWith { dir -> dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
        var total = sizes.values.sum()
        val limit = maxBytes()
        for (dir in dirs.sortedBy { it.lastModified() }) {
            if (total <= limit) break
            synchronized(lock) {
                if (dir.name in activeRequests) return@synchronized
                total -= sizes.getValue(dir)
                dir.deleteRecursively()
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            baseDir.listFiles()?.filter { it.isDirectory && it.name !in activeRequests }?.forEach { it.deleteRecursively() }
        }
    }

    companion object {
        private const val IDLE_STOP_MINUTES = 2L
    }
}
