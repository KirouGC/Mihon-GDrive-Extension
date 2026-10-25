package eu.kanade.tachiyomi.extension.all.googledrive

import eu.kanade.tachiyomi.lib.archive.ArchiveKind
import eu.kanade.tachiyomi.lib.archive.NaturalOrder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Un manga encontrado al recorrer las carpetas.
 *
 * [key] es "folder:<id>[:<resourceKey>]" para una carpeta con capítulos (CBZ/PDF o subcarpetas
 * de imágenes), "file:<tipo>:<id>[:<resourceKey>]" para un CBZ/PDF suelto, o
 * "imgdir:<id>[:<resourceKey>]" para una carpeta de imágenes suelta; los dos últimos son
 * mangas de un solo capítulo.
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

    private class Node(val ref: DriveRef, val path: String, val isRoot: Boolean, val children: List<DriveItem>)

    private fun crawl(roots: List<DriveRef>): List<MangaEntry> {
        val nodes = ConcurrentHashMap<String, Node>()
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
                    nodes[folder.ref.id] = Node(folder.ref, folder.path, folder.isRoot, children)
                    children.filter { it.isFolder && visited.add(it.id) }.forEach { sub ->
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

        val results = mutableMapOf<String, MangaEntry>()
        nodes.values.forEach { classify(it, nodes, results) }
        return results.values.sortedWith(compareBy(NaturalOrder) { it.title })
    }

    /**
     * Decide qué es cada carpeta: un manga cuyos capítulos son sus CBZ/PDF y sus subcarpetas de
     * imágenes, o (en la carpeta raíz junto a carpetas de series) mangas sueltos de un capítulo.
     */
    private fun classify(node: Node, nodes: Map<String, Node>, results: MutableMap<String, MangaEntry>) {
        if (isImageChapter(node.children)) {
            // Una carpeta de imágenes es un capítulo de su carpeta padre; solo si es la raíz es un manga.
            if (node.isRoot) {
                val key = "imgdir:" + refKey(node.ref)
                results[key] = MangaEntry(key, node.path.ifEmpty { "Google Drive" }, node.path, pickCover(node.children, emptyList(), emptyList()), 0, 1)
            }
            return
        }

        val archives = node.children.filter { it.kind != null }
        val subfolders = node.children.filter { it.isFolder }
        val imageSubfolders = subfolders.filter { sub -> nodes[sub.id]?.let { isImageChapter(it.children) } == true }
        if (archives.isEmpty() && imageSubfolders.isEmpty()) return

        if (node.isRoot && subfolders.size > imageSubfolders.size) {
            // Archivos y carpetas de imágenes sueltos junto a carpetas de series: cada uno es su propio manga.
            archives.forEach { file ->
                val key = "file:${file.kind!!.code}:" + refKey(DriveRef(file.id, file.resourceKey))
                results[key] = MangaEntry(
                    key = key,
                    title = file.name.substringBeforeLast('.'),
                    path = file.name,
                    cover = file.takeIf { it.kind == ArchiveKind.PDF }?.let { DriveApi.thumbnailUrl(DriveRef(it.id, it.resourceKey)) },
                    modified = file.modified,
                    chapters = 1,
                )
            }
            imageSubfolders.forEach { sub ->
                val key = "imgdir:" + refKey(DriveRef(sub.id, sub.resourceKey))
                results[key] = MangaEntry(
                    key = key,
                    title = sub.name,
                    path = sub.name,
                    cover = pickCover(nodes[sub.id]!!.children, emptyList(), emptyList()),
                    modified = sub.modified,
                    chapters = 1,
                )
            }
            return
        }

        val key = "folder:" + refKey(node.ref)
        val title = node.path.substringAfterLast('/').ifEmpty { node.path }
        val firstChapterImages = imageSubfolders.minWithOrNull(compareBy(NaturalOrder) { it.name })
            ?.let { nodes[it.id]?.children }.orEmpty()
        results[key] = MangaEntry(
            key = key,
            title = title.ifEmpty { (archives.firstOrNull()?.name?.substringBeforeLast('.')) ?: "Google Drive" },
            path = node.path,
            cover = pickCover(node.children, archives, firstChapterImages),
            modified = (archives + imageSubfolders).maxOf { it.modified },
            chapters = archives.size + imageSubfolders.size,
        )
    }

    private fun refKey(ref: DriveRef) = ref.id + (ref.resourceKey?.let { ":$it" } ?: "")

    private fun pickCover(children: List<DriveItem>, files: List<DriveItem>, chapterImages: List<DriveItem>): String? {
        val images = children.filter { it.isImage && !it.isFolder }
        val cover = images.firstOrNull { COVER_NAMES.matches(it.name) }
            ?: images.minWithOrNull(compareBy(NaturalOrder) { it.name })
            ?: files.filter { it.kind == ArchiveKind.PDF }.minWithOrNull(compareBy(NaturalOrder) { it.name })
            ?: chapterImages.filter { it.isImage && !it.isFolder }.minWithOrNull(compareBy(NaturalOrder) { it.name })
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

        /**
         * Una carpeta es un capítulo de imágenes si solo tiene imágenes (sin CBZ/PDF ni subcarpetas)
         * y no es únicamente una portada.
         */
        fun isImageChapter(children: List<DriveItem>): Boolean {
            if (children.any { it.isFolder || it.kind != null }) return false
            val images = children.filter { it.isImage }
            return images.isNotEmpty() && !(images.size == 1 && COVER_NAMES.matches(images[0].name))
        }
    }
}
