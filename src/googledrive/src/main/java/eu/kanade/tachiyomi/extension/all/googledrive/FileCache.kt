package eu.kanade.tachiyomi.extension.all.googledrive

import eu.kanade.tachiyomi.lib.archive.ArchiveKind
import eu.kanade.tachiyomi.lib.archive.ArchiveReader
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Descarga los CBZ/PDF a la caché del dispositivo y extrae de ellos las páginas.
 *
 * Mihon pide cada página como una imagen por HTTP, así que [PageInterceptor] responde a
 * las URL de páginas con los bytes que devuelve esta clase.
 */
class FileCache(
    private val api: DriveApi,
    private val dir: File,
    private val maxBytes: () -> Long,
    pdfWidth: () -> Int,
) {
    private val locks = ConcurrentHashMap<String, Any>()
    private val reader = ArchiveReader(pdfWidth)

    /** Número de páginas del archivo; lo descarga si hace falta. */
    fun pageCount(kind: ArchiveKind, ref: DriveRef): Int = reader.pageCount(kind, file(ref))

    fun page(kind: ArchiveKind, ref: DriveRef, index: Int): Pair<ByteArray, String> = reader.page(kind, file(ref), index)

    /** Devuelve el archivo local, descargándolo una sola vez aunque lo pidan varias páginas a la vez. */
    private fun file(ref: DriveRef): File {
        val target = File(dir, ref.id)
        synchronized(locks.getOrPut(ref.id) { Any() }) {
            if (target.exists()) {
                target.setLastModified(System.currentTimeMillis())
                return target
            }
            dir.mkdirs()
            val partial = File(dir, "${ref.id}.part")
            api.download(ref).use { response ->
                partial.outputStream().use { out -> response.body.byteStream().copyTo(out) }
            }
            if (!partial.renameTo(target)) throw IOException("No se pudo guardar ${ref.id} en la caché")
            trim(keep = target)
            return target
        }
    }

    private fun trim(keep: File) {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: return
        var total = files.sumOf { it.length() }
        val limit = maxBytes()
        for (old in files.sortedBy { it.lastModified() }) {
            if (total <= limit) break
            if (old == keep) continue
            reader.forget(old)
            total -= old.length()
            old.delete()
        }
    }

    fun clear() {
        reader.closeAll()
        dir.listFiles()?.forEach { it.delete() }
    }
}
