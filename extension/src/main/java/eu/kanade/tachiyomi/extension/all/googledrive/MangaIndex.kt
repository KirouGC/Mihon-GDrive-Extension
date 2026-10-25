package eu.kanade.tachiyomi.extension.all.googledrive

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Un manga encontrado al recorrer las carpetas.
 *
 * [key] es "folder:<id>[:<resourceKey>]" para una carpeta con capítulos, o
 * "file:<tipo>:<id>[:<resourceKey>]" para un CBZ/PDF suelto que es un manga de un solo capítulo.
 */
data class MangaEntry(
    val key: String,
    val title: String,
    val path: String,
    val cover: String?,
    val modified: Long,
    val chapters: Int,
)

/** Recorre recursivamente las carpetas raíz y guarda el resultado en disco. */
class MangaIndex(
    private val api: DriveApi,
    private val cacheFile: File,
) {
    @Volatile
    private var snapshot: Snapshot? = null

    private class Snapshot(val roots: String, val time: Long, val entries: List<MangaEntry>)

    fun get(roots: List<DriveRef>, maxAgeMs: Long, force: Boolean): List<MangaEntry> {
        val rootsKey = roots.joinToString("|") { "${it.id}:${it.resourceKey.orEmpty()}" }
        synchronized(this) {
            val current = snapshot ?: readFromDisk()?.also { snapshot = it }
            val fresh = current != null && current.roots == rootsKey &&
                (maxAgeMs < 0 || System.currentTimeMillis() - current.time < maxAgeMs)
            if (fresh && !force) return current!!.entries

            val entries = crawl(roots)
            snapshot = Snapshot(rootsKey, System.currentTimeMillis(), entries).also { writeToDisk(it) }
            return entries
        }
    }

    fun find(key: String): MangaEntry? {
        val current = snapshot ?: synchronized(this) { snapshot ?: readFromDisk()?.also { snapshot = it } }
        return current?.entries?.firstOrNull { it.key == key }
    }

    private fun crawl(roots: List<DriveRef>): List<MangaEntry> {
        val results = ConcurrentHashMap<String, MangaEntry>()
        val visited = ConcurrentHashMap.newKeySet<String>()
        val pool = Executors.newFixedThreadPool(PARALLEL_REQUESTS)
        try {
            data class Pending(val ref: DriveRef, val path: String, val isRoot: Boolean)

            var level = roots.filter { visited.add(it.id) }.map { Pending(it, "", true) }
            var depth = 0
            while (level.isNotEmpty() && depth <= MAX_DEPTH) {
                val listings = pool.invokeAll(level.map { Callable { it to api.listFolder(it.ref) } })
                val next = mutableListOf<Pending>()
                for (future in listings) {
                    val (folder, children) = future.get()
                    val subfolders = children.filter { it.isFolder }
                    val files = children.filter { it.kind != null }
                    collect(folder.ref, folder.path, folder.isRoot, subfolders.isNotEmpty(), children, files, results)
                    subfolders.filter { visited.add(it.id) }.forEach { sub ->
                        val subPath = if (folder.path.isEmpty()) sub.name else "${folder.path}/${sub.name}"
                        next += Pending(DriveRef(sub.id, sub.resourceKey), subPath, false)
                    }
                }
                level = next
                depth++
            }
        } finally {
            pool.shutdownNow()
        }
        return results.values.sortedWith(compareBy(NaturalOrder) { it.title })
    }

    private fun collect(
        folder: DriveRef,
        path: String,
        isRoot: Boolean,
        hasSubfolders: Boolean,
        children: List<DriveItem>,
        files: List<DriveItem>,
        results: MutableMap<String, MangaEntry>,
    ) {
        if (files.isEmpty()) return

        if (isRoot && hasSubfolders) {
            // Archivos sueltos junto a carpetas de series: cada archivo es su propio manga.
            files.forEach { file ->
                val key = "file:${file.kind!!.code}:${file.id}" + (file.resourceKey?.let { ":$it" } ?: "")
                results[key] = MangaEntry(
                    key = key,
                    title = file.name.substringBeforeLast('.'),
                    path = file.name,
                    cover = file.takeIf { it.kind == FileKind.PDF }?.let { DriveApi.thumbnailUrl(DriveRef(it.id, it.resourceKey)) },
                    modified = file.modified,
                    chapters = 1,
                )
            }
            return
        }

        val key = "folder:${folder.id}" + (folder.resourceKey?.let { ":$it" } ?: "")
        val title = path.substringAfterLast('/').ifEmpty { path }
        results[key] = MangaEntry(
            key = key,
            title = title.ifEmpty { files.first().name.substringBeforeLast('.') },
            path = path,
            cover = pickCover(children, files),
            modified = files.maxOf { it.modified },
            chapters = files.size,
        )
    }

    private fun pickCover(children: List<DriveItem>, files: List<DriveItem>): String? {
        val images = children.filter { it.isImage && !it.isFolder }
        val cover = images.firstOrNull { COVER_NAMES.matches(it.name) }
            ?: images.minWithOrNull(compareBy(NaturalOrder) { it.name })
            ?: files.filter { it.kind == FileKind.PDF }.minWithOrNull(compareBy(NaturalOrder) { it.name })
            ?: return null
        return DriveApi.thumbnailUrl(DriveRef(cover.id, cover.resourceKey))
    }

    private fun readFromDisk(): Snapshot? = runCatching {
        if (!cacheFile.exists()) return null
        val json = JSONObject(cacheFile.readText())
        val array = json.getJSONArray("entries")
        val entries = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            MangaEntry(
                key = o.getString("k"),
                title = o.getString("t"),
                path = o.optString("p"),
                cover = o.optString("c").ifEmpty { null },
                modified = o.optLong("m"),
                chapters = o.optInt("n"),
            )
        }
        Snapshot(json.getString("roots"), json.getLong("time"), entries)
    }.getOrNull()

    private fun writeToDisk(snapshot: Snapshot) {
        runCatching {
            val array = JSONArray()
            snapshot.entries.forEach {
                array.put(
                    JSONObject()
                        .put("k", it.key)
                        .put("t", it.title)
                        .put("p", it.path)
                        .put("c", it.cover ?: "")
                        .put("m", it.modified)
                        .put("n", it.chapters),
                )
            }
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(
                JSONObject().put("roots", snapshot.roots).put("time", snapshot.time).put("entries", array).toString(),
            )
        }
    }

    companion object {
        private const val PARALLEL_REQUESTS = 4
        private const val MAX_DEPTH = 15
        private val COVER_NAMES = Regex("""(?i)^(cover|folder|poster|portada)\.\w+$""")
    }
}

/** Orden "natural": "Vol 2" va antes que "Vol 10". */
object NaturalOrder : Comparator<String> {
    private val CHUNK = Regex("""\d+|\D+""")

    override fun compare(a: String, b: String): Int {
        val ca = CHUNK.findAll(a.lowercase()).map { it.value }.toList()
        val cb = CHUNK.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]
            val y = cb[i]
            val cmp = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else {
                x.compareTo(y)
            }
            if (cmp != 0) return cmp
        }
        return ca.size - cb.size
    }
}
