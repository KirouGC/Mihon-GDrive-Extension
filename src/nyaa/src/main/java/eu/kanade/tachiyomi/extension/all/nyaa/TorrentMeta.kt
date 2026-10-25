package eu.kanade.tachiyomi.extension.all.nyaa

import eu.kanade.tachiyomi.lib.archive.ArchiveKind
import eu.kanade.tachiyomi.lib.archive.NaturalOrder
import eu.kanade.tachiyomi.lib.archive.isImageName
import java.io.IOException

/** Un archivo dentro del torrent; [path] incluye la carpeta raíz, igual que en libtorrent. */
data class TorrentFile(val path: String, val size: Long)

class TorrentMeta(val name: String, val files: List<TorrentFile>, val created: Long) {

    /**
     * Lo que se puede leer como capítulo: cada CBZ/PDF, y cada carpeta que tenga imágenes
     * sueltas (muy común en los torrents "raw").
     */
    fun chapters(): List<ChapterRef> {
        val archives = files.filter { ArchiveKind.of(it.path) != null }
            .map { ChapterRef(ChapterRef.ARCHIVE, it.path) }
        val imageDirs = files.filter { isImageName(it.path) }
            .map { it.path.substringBeforeLast('/', "") }
            .distinct()
            .map { ChapterRef(ChapterRef.IMAGE_DIR, it) }
        return (archives + imageDirs).sortedWith(compareByDescending(NaturalOrder) { it.path })
    }

    /** Nombre legible: la ruta sin la carpeta raíz del torrent ni la extensión. */
    fun displayName(ref: ChapterRef): String {
        val relative = ref.path.removePrefix("$name/")
        val clean = if (ref.kind == ChapterRef.ARCHIVE) relative.substringBeforeLast('.') else relative
        return clean.ifEmpty { name }
    }

    companion object {
        fun parse(bytes: ByteArray): TorrentMeta {
            val root = Bencode.decode(bytes) as? Map<*, *> ?: throw IOException("Archivo .torrent inválido")
            val info = root["info"] as? Map<*, *> ?: throw IOException("El .torrent no tiene información de archivos")
            val name = info.text("name.utf-8") ?: info.text("name") ?: "torrent"
            val created = ((root["creation date"] as? Long) ?: 0L) * 1000

            val files = mutableListOf<TorrentFile>()
            val list = info["files"] as? List<*>
            val tree = info["file tree"] as? Map<*, *>
            when {
                list != null -> list.forEach { entry ->
                    val file = entry as? Map<*, *> ?: return@forEach
                    if ((file.text("attr") ?: "").contains('p')) return@forEach // archivos de relleno
                    val parts = (file["path.utf-8"] ?: file["path"]) as? List<*> ?: return@forEach
                    val path = parts.joinToString("/") { (it as ByteArray).toString(Charsets.UTF_8) }
                    files += TorrentFile("$name/$path", file["length"] as? Long ?: 0)
                }
                tree != null -> walkTree(tree, name, files)
                else -> files += TorrentFile(name, info["length"] as? Long ?: 0)
            }
            return TorrentMeta(name, files, created)
        }

        // Torrents v2: {"carpeta": {"archivo": {"": {"length": N}}}}
        private fun walkTree(node: Map<*, *>, prefix: String, out: MutableList<TorrentFile>) {
            for ((key, value) in node) {
                val name = (key as? String) ?: continue
                val child = value as? Map<*, *> ?: continue
                val leaf = child[""] as? Map<*, *>
                if (leaf != null) {
                    out += TorrentFile("$prefix/$name", leaf["length"] as? Long ?: 0)
                } else {
                    walkTree(child, "$prefix/$name", out)
                }
            }
        }

        private fun Map<*, *>.text(key: String) = (this[key] as? ByteArray)?.toString(Charsets.UTF_8)
    }
}

/** Qué hay que bajar para un capítulo: un archivo CBZ/PDF o las imágenes de una carpeta. */
data class ChapterRef(val kind: String, val path: String) {
    companion object {
        const val ARCHIVE = "a"
        const val IMAGE_DIR = "d"
    }
}

/** Decodificador mínimo de bencode: Long, ByteArray, List y Map con claves String. */
object Bencode {
    fun decode(bytes: ByteArray): Any = Reader(bytes).read()

    private class Reader(val data: ByteArray) {
        var pos = 0

        fun read(): Any = when (val c = data[pos].toInt().toChar()) {
            'i' -> {
                val end = indexOf('e', pos)
                String(data, pos + 1, end - pos - 1, Charsets.US_ASCII).toLong().also { pos = end + 1 }
            }
            'l' -> {
                pos++
                val list = mutableListOf<Any>()
                while (data[pos].toInt().toChar() != 'e') list += read()
                pos++
                list
            }
            'd' -> {
                pos++
                val map = LinkedHashMap<String, Any>()
                while (data[pos].toInt().toChar() != 'e') {
                    val key = (read() as ByteArray).toString(Charsets.UTF_8)
                    map[key] = read()
                }
                pos++
                map
            }
            in '0'..'9' -> {
                val colon = indexOf(':', pos)
                val length = String(data, pos, colon - pos, Charsets.US_ASCII).toInt()
                data.copyOfRange(colon + 1, colon + 1 + length).also { pos = colon + 1 + length }
            }
            else -> throw IOException("Bencode inválido en la posición $pos ('$c')")
        }

        private fun indexOf(c: Char, from: Int): Int {
            var i = from
            while (data[i].toInt().toChar() != c) i++
            return i
        }
    }
}
