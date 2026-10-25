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
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Cliente torrent pensado para leer, no para compartir:
 *
 * - Al abrir un manga el torrent se agrega sin pedir ningún archivo, para ir encontrando peers
 *   antes de que se toque un capítulo.
 * - Solo hay un torrent activo a la vez y solo se piden los archivos de los capítulos abiertos.
 * - Las páginas se leen por rangos de bytes: se marcan con deadline las piezas que cubren ese
 *   rango (libtorrent las pide a varios peers a la vez) y el resto del capítulo sigue bajando
 *   en orden de fondo.
 * - Tras unos minutos sin lecturas el torrent sale de la sesión y el cliente se apaga.
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
    private var currentTorrent: String? = null
    private val wantedFiles = HashMap<String, MutableSet<Int>>()
    private val waiters = ConcurrentHashMap<String, Int>()
    private val infos = ConcurrentHashMap<String, TorrentInfo>()

    @Volatile
    private var lastActivity = System.currentTimeMillis()

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "nyaa-torrent").apply { isDaemon = true }
    }.apply {
        scheduleWithFixedDelay({ runCatching { stopIfIdle() } }, 1, 1, TimeUnit.MINUTES)
    }

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

    fun isComplete(torrentId: String, info: TorrentInfo, index: Int) =
        doneMarker(torrentId, index).exists() && localFile(torrentId, info, index).exists()

    /** Agrega el torrent sin pedir archivos para que vaya buscando peers. No bloquea. */
    fun warmUp(torrentId: String, bytes: ByteArray) {
        scheduler.execute {
            runCatching { synchronized(lock) { handle(torrentId, info(torrentId, bytes)) } }
        }
    }

    /**
     * Marca [indices] como parte de lo que se está leyendo: bajan en orden de fondo con
     * prioridad normal, y los demás archivos del torrent quedan ignorados.
     */
    fun want(torrentId: String, bytes: ByteArray, indices: Collection<Int>) {
        val info = info(torrentId, bytes)
        val pending = indices.filter { !isComplete(torrentId, info, it) }
        if (pending.isEmpty()) return
        synchronized(lock) {
            val handle = handle(torrentId, info)
            val wanted = wantedFiles.getOrPut(torrentId) { mutableSetOf() }
            pending.filter { wanted.add(it) }.forEach { handle.filePriority(it, Priority.DEFAULT) }
            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            handle.resume()
        }
    }

    /** Lee [length] bytes desde [offset] del archivo [index]; espera solo las piezas de ese rango. */
    fun read(torrentId: String, bytes: ByteArray, index: Int, offset: Long, length: Int): ByteArray {
        val info = info(torrentId, bytes)
        val file = localFile(torrentId, info, index)
        if (!isComplete(torrentId, info, index)) {
            want(torrentId, bytes, listOf(index))
            val pieces = piecesFor(info, index, offset, length.toLong())
            waitForPieces(torrentId, info, pieces, urgent = true)
            markIfComplete(torrentId, info, index)
        }
        touch(torrentId)
        return RandomAccessFile(file, "r").use { raf ->
            ByteArray(length).also {
                raf.seek(offset)
                raf.readFully(it)
            }
        }
    }

    /** Pide con anticipación las piezas de un rango (la página siguiente) sin esperar. */
    fun prefetch(torrentId: String, bytes: ByteArray, index: Int, offset: Long, length: Long, delayMs: Int) {
        val info = info(torrentId, bytes)
        if (isComplete(torrentId, info, index)) return
        synchronized(lock) {
            val handle = session?.find(info.infoHash())?.takeIf { it.isValid } ?: return
            piecesFor(info, index, offset, length).filter { !handle.havePiece(it) }.forEachIndexed { i, piece ->
                handle.setPieceDeadline(piece, delayMs + i * PIECE_DEADLINE_STEP_MS)
            }
        }
    }

    /** Espera a que el archivo [index] esté completo (PDF, imágenes sueltas) y lo devuelve. */
    fun awaitFile(torrentId: String, bytes: ByteArray, index: Int): File {
        val info = info(torrentId, bytes)
        if (!isComplete(torrentId, info, index)) {
            want(torrentId, bytes, listOf(index))
            val size = info.files().fileSize(index)
            waitForPieces(torrentId, info, piecesFor(info, index, 0, size), urgent = true)
            markIfComplete(torrentId, info, index)
        }
        touch(torrentId)
        return localFile(torrentId, info, index)
    }

    private fun piecesFor(info: TorrentInfo, index: Int, offset: Long, length: Long): IntRange {
        val files = info.files()
        val size = files.fileSize(index)
        val start = files.fileOffset(index) + offset.coerceIn(0, size)
        val end = files.fileOffset(index) + (offset + length).coerceIn(0, size) - 1
        val pieceLength = info.pieceLength().toLong()
        return (start / pieceLength).toInt()..(maxOf(start, end) / pieceLength).toInt()
    }

    private fun waitForPieces(torrentId: String, info: TorrentInfo, pieces: IntRange, urgent: Boolean) {
        waiters.merge(torrentId, 1, Int::plus)
        try {
            var handle = synchronized(lock) { handle(torrentId, info) }
            if (urgent) {
                synchronized(lock) {
                    pieces.filter { !handle.havePiece(it) }.forEachIndexed { i, piece ->
                        handle.piecePriority(piece, Priority.TOP_PRIORITY)
                        handle.setPieceDeadline(piece, i * PIECE_DEADLINE_STEP_MS)
                    }
                }
            }
            var lastDone = -1L
            var lastChange = System.currentTimeMillis()
            while (true) {
                if (!handle.isValid) handle = synchronized(lock) { handle(torrentId, info) }
                if (pieces.all { handle.havePiece(it) }) return
                val status = handle.status()
                val done = status.totalDone()
                val now = System.currentTimeMillis()
                if (done != lastDone) {
                    lastDone = done
                    lastChange = now
                } else if (now - lastChange > stallTimeoutMs()) {
                    throw IOException(
                        t(
                            "La descarga no avanza (${status.numSeeds()} seeders, ${status.numPeers()} peers conectados). " +
                                "Puede que el torrent no tenga gente compartiendo.",
                            "The download is not progressing (${status.numSeeds()} seeders, ${status.numPeers()} peers connected). " +
                                "The torrent may have nobody sharing it.",
                        ),
                    )
                }
                lastActivity = now
                Thread.sleep(POLL_MS)
            }
        } finally {
            waiters.merge(torrentId, -1, Int::plus)
        }
    }

    /**
     * Devuelve el torrent dentro de la sesión, agregándolo si hace falta. Llamar con [lock].
     * Cualquier otro torrent sale de la sesión para que toda la red vaya al que se está leyendo.
     */
    private fun handle(torrentId: String, info: TorrentInfo): TorrentHandle {
        lastActivity = System.currentTimeMillis()
        val sm = session ?: startSession().also { session = it }
        if (currentTorrent != null && currentTorrent != torrentId && (waiters[currentTorrent!!] ?: 0) <= 0) {
            removeFromSession(sm, currentTorrent!!)
        }
        currentTorrent = torrentId

        sm.find(info.infoHash())?.takeIf { it.isValid }?.let { return it }

        val dir = File(baseDir, torrentId).apply { mkdirs() }
        val wanted = wantedFiles[torrentId].orEmpty()
        val priorities = Array(info.numFiles()) { if (it in wanted) Priority.DEFAULT else Priority.IGNORE }
        sm.download(info, dir, null, priorities, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)

        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            sm.find(info.infoHash())?.takeIf { it.isValid }?.let { return it }
            Thread.sleep(100)
        }
        throw IOException(t("libtorrent no pudo agregar el torrent", "libtorrent could not add the torrent"))
    }

    private fun removeFromSession(sm: SessionManager, torrentId: String) {
        infos[torrentId]?.let { info -> sm.find(info.infoHash())?.takeIf { it.isValid }?.let { sm.remove(it) } }
        wantedFiles.remove(torrentId)
    }

    private fun markIfComplete(torrentId: String, info: TorrentInfo, index: Int) {
        val handle = synchronized(lock) { session?.find(info.infoHash())?.takeIf { it.isValid } } ?: return
        if (handle.fileProgress()[index] >= info.files().fileSize(index)) {
            handle.flushCache()
            doneMarker(torrentId, index).apply { parentFile?.mkdirs() }.createNewFile()
            scheduler.execute { runCatching { trim() } }
        }
    }

    private fun touch(torrentId: String) {
        lastActivity = System.currentTimeMillis()
        File(baseDir, torrentId).setLastModified(lastActivity)
    }

    private fun stopIfIdle() {
        synchronized(lock) {
            val sm = session ?: return
            if (waiters.values.any { it > 0 }) return
            if (System.currentTimeMillis() - lastActivity < TimeUnit.MINUTES.toMillis(IDLE_STOP_MINUTES)) return
            currentTorrent?.let { removeFromSession(sm, it) }
            currentTorrent = null
            runCatching { sm.stop() }
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

    private fun doneMarker(torrentId: String, index: Int) = File(File(baseDir, torrentId), ".ok-$index")

    /** Borra los torrents bajados más viejos si se pasa del espacio configurado. */
    private fun trim() {
        val dirs = baseDir.listFiles()?.filter { it.isDirectory } ?: return
        val sizes = dirs.associateWith { dir -> dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
        var total = sizes.values.sum()
        val limit = maxBytes()
        for (dir in dirs.sortedBy { it.lastModified() }) {
            if (total <= limit) break
            synchronized(lock) {
                if (dir.name == currentTorrent) return@synchronized
                total -= sizes.getValue(dir)
                dir.deleteRecursively()
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            baseDir.listFiles()?.filter { it.isDirectory && it.name != currentTorrent }?.forEach { it.deleteRecursively() }
        }
    }

    companion object {
        private const val IDLE_STOP_MINUTES = 5L
        private const val POLL_MS = 100L
        private const val PIECE_DEADLINE_STEP_MS = 150
    }
}
