package eu.kanade.tachiyomi.extension.all.nyaa

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.lib.archive.ArchiveKind
import eu.kanade.tachiyomi.lib.archive.ArchiveReader
import eu.kanade.tachiyomi.lib.archive.NaturalOrder
import eu.kanade.tachiyomi.lib.archive.imageMime
import eu.kanade.tachiyomi.lib.archive.isImageName
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import rx.Observable
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class Nyaa : HttpSource(), ConfigurableSource {

    override val name = "Nyaa (Literature)"

    override val lang = "all"

    override val supportsLatest = true

    private val application: Application by lazy {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Application
    }

    // Mihon guarda la pantalla de ajustes de la fuente en "source_<id>".
    private val preferences: SharedPreferences by lazy {
        application.getSharedPreferences("source_$id", Context.MODE_PRIVATE)
    }

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN, DEFAULT_DOMAIN)!!.trim().trimEnd('/').ifEmpty { DEFAULT_DOMAIN }

    private val cacheRoot by lazy { File(application.cacheDir, "nyaa") }

    private val reader by lazy {
        ArchiveReader { preferences.getString(PREF_PDF_WIDTH, "1440")!!.toIntOrNull() ?: 1440 }
    }

    private val engine by lazy {
        TorrentEngine(
            loadNative = { NativeLoader.ensureLoaded(application, PACKAGE) },
            baseDir = File(cacheRoot, "data"),
            uploadLimitKb = { preferences.getString(PREF_UPLOAD_KB, "100")!!.toIntOrNull() ?: 100 },
            stallTimeoutMs = { TimeUnit.SECONDS.toMillis(preferences.getString(PREF_STALL_SECONDS, "180")!!.toLongOrNull() ?: 180) },
            maxBytes = { (preferences.getString(PREF_CACHE_MB, "2048")!!.toLongOrNull() ?: 2048L) * 1024 * 1024 },
        )
    }

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor(LocalPageInterceptor(::servePage))
            .readTimeout(30, TimeUnit.MINUTES)
            .build()
    }

    // ============================== Catálogo ==============================

    override fun popularMangaRequest(page: Int) = searchRequest(page, "", CATEGORY_ALL, "0", "seeders")

    override fun popularMangaParse(response: Response) = parseSearch(response)

    override fun latestUpdatesRequest(page: Int) = searchRequest(page, "", CATEGORY_ALL, "0", "id")

    override fun latestUpdatesParse(response: Response) = parseSearch(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.value ?: CATEGORY_ALL
        val quality = filters.filterIsInstance<QualityFilter>().firstOrNull()?.value ?: "0"
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.value ?: "id"
        return searchRequest(page, query, category, quality, sort)
    }

    override fun searchMangaParse(response: Response) = parseSearch(response)

    private fun searchRequest(page: Int, query: String, category: String, quality: String, sort: String): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("f", quality)
            .addQueryParameter("c", category)
            .addQueryParameter("q", query.trim())
            .addQueryParameter("s", sort)
            .addQueryParameter("o", "desc")
            .apply { if (page > 1) addQueryParameter("p", page.toString()) }
            .build()
        return Request.Builder().url(url).headers(headers).build()
    }

    private fun parseSearch(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string(), response.request.url.toString())
        val rows = document.select("table.torrent-list > tbody > tr")
        val mangas = rows.mapNotNull { row ->
            val cells = row.select("> td")
            val link = cells.getOrNull(1)?.select("a[href^=/view/]")?.lastOrNull { !it.hasClass("comments") }
                ?: return@mapNotNull null
            SManga.create().apply {
                url = link.attr("href").substringBefore('#')
                title = link.attr("title").ifEmpty { link.text() }
                description = listOfNotNull(
                    cells.getOrNull(3)?.text()?.let { "Tamaño: $it" },
                    cells.getOrNull(5)?.text()?.let { "Seeders: $it" },
                ).joinToString(" · ")
                update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            }
        }
        return MangasPage(mangas, rows.size >= RESULTS_PER_PAGE)
    }

    override fun getFilterList() = FilterList(
        CategoryFilter(),
        QualityFilter(),
        SortFilter(),
    )

    private open class ValueFilter(name: String, private val options: Array<Pair<String, String>>) :
        Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        val value get() = options[state].second
    }

    private class CategoryFilter : ValueFilter(
        "Categoría",
        arrayOf(
            "Toda Literature" to CATEGORY_ALL,
            "Traducido al inglés" to "3_1",
            "Traducido a otro idioma" to "3_2",
            "Raw (sin traducir)" to "3_3",
        ),
    )

    private class QualityFilter : ValueFilter(
        "Filtro",
        arrayOf("Todos" to "0", "Sin remakes" to "1", "Solo trusted" to "2"),
    )

    private class SortFilter : ValueFilter(
        "Ordenar por",
        arrayOf("Más recientes" to "id", "Seeders" to "seeders", "Descargas" to "downloads", "Tamaño" to "size"),
    )

    // ============================== Manga ==============================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body.string(), response.request.url.toString())
        val info = infoRows(document)
        val descriptionText = document.selectFirst("#torrent-description")?.wholeText()?.trim().orEmpty()
        return SManga.create().apply {
            title = document.selectFirst(".panel-heading .panel-title")?.text()?.trim().orEmpty()
            author = info["Submitter"]
            genre = info["Category"]?.replace(" - ", ", ")
            thumbnail_url = IMAGE_URL.find(descriptionText)?.groupValues?.get(1)
            description = buildString {
                listOf("File size" to "Tamaño", "Seeders" to "Seeders", "Leechers" to "Leechers", "Completed" to "Descargas", "Date" to "Fecha")
                    .forEach { (key, label) -> info[key]?.let { append(label).append(": ").append(it).append('\n') } }
                if (descriptionText.isNotEmpty() && descriptionText != "#### No description.") {
                    append('\n').append(descriptionText)
                }
            }.trim()
            status = SManga.UNKNOWN
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            initialized = true
        }
    }

    private fun infoRows(document: Document): Map<String, String> {
        val result = mutableMapOf<String, String>()
        document.select(".panel-body > .row").forEach { row ->
            val cols = row.select("> div")
            var i = 0
            while (i < cols.size - 1) {
                val label = cols[i].text().removeSuffix(":").trim()
                if (label.isNotEmpty()) result[label] = cols[i + 1].text().trim()
                i += 2
            }
        }
        return result
    }

    // ============================== Capítulos ==============================

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val torrentId = torrentIdOf(manga.url)
        val meta = TorrentMeta.parse(torrentBytes(torrentId))
        val chapters = meta.chapters()
        if (chapters.isEmpty()) throw IOException("Este torrent no tiene archivos CBZ, PDF ni imágenes")
        chapters.map { ref ->
            SChapter.create().apply {
                url = "$torrentId/${ref.kind}/${URLEncoder.encode(ref.path, "UTF-8")}"
                name = meta.displayName(ref)
                date_upload = meta.created
            }
        }
    }

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/view/${chapter.url.substringBefore('/')}"

    private fun torrentIdOf(mangaUrl: String) = mangaUrl.substringAfter("/view/").substringBefore('/').substringBefore('?')

    /** El .torrent de Nyaa (o, si solo hay magnet, los metadatos pedidos por DHT). Se guarda en caché. */
    private fun torrentBytes(torrentId: String): ByteArray {
        val file = File(cacheRoot, "torrents/$torrentId.torrent")
        if (file.isFile) return file.readBytes()

        val response = network.client.newCall(Request.Builder().url("$baseUrl/download/$torrentId.torrent").headers(headers).build()).execute()
        val bytes = response.use { if (it.isSuccessful) it.body.bytes() else null }
            ?: throw IOException("Nyaa no tiene el archivo .torrent de este torrent (HTTP ${response.code})")
        if (bytes.firstOrNull() != 'd'.code.toByte()) throw IOException("Nyaa no devolvió un archivo .torrent válido")
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return bytes
    }

    // ============================== Páginas ==============================

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val (torrentId, kind, path) = chapter.url.split('/', limit = 3)
        val chapterPath = URLDecoder.decode(path, "UTF-8")
        val bytes = torrentBytes(torrentId)
        val info = engine.info(torrentId, bytes)
        val byPath = engine.indexByPath(info)

        when (kind) {
            ChapterRef.ARCHIVE -> {
                val index = byPath[chapterPath] ?: throw IOException("No se encontró $chapterPath en el torrent")
                val file = engine.download(torrentId, bytes, listOf(index)).single()
                val archive = ArchiveKind.of(chapterPath) ?: throw IOException("Formato no soportado: $chapterPath")
                List(reader.pageCount(archive, file)) { i ->
                    Page(i, imageUrl = LocalPageInterceptor.archivePageUrl(torrentId, index, i))
                }
            }
            else -> {
                val images = byPath.filterKeys { it.substringBeforeLast('/', "") == chapterPath && isImageName(it) }
                    .toSortedMap(NaturalOrder)
                if (images.isEmpty()) throw IOException("No hay imágenes en $chapterPath")
                engine.download(torrentId, bytes, images.values.toList())
                images.values.mapIndexed { i, index -> Page(i, imageUrl = LocalPageInterceptor.imageUrl(torrentId, index)) }
            }
        }
    }

    override fun imageRequest(page: Page): Request = Request.Builder().url(page.imageUrl!!).headers(headers).build()

    /** Lo llama [LocalPageInterceptor]; vuelve a bajar el archivo si se borró de la caché. */
    private fun servePage(torrentId: String, fileIndex: Int, page: Int?): Pair<ByteArray, String> {
        val bytes = torrentBytes(torrentId)
        val file = engine.download(torrentId, bytes, listOf(fileIndex)).single()
        return if (page == null) {
            file.readBytes() to imageMime(file.name)
        } else {
            val kind = ArchiveKind.of(file.name) ?: throw IOException("Formato no soportado: ${file.name}")
            reader.page(kind, file, page)
        }
    }

    // ============================== Ajustes ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_UPLOAD_KB
            title = "Límite de subida"
            summary = "Mientras baja un capítulo el teléfono también comparte con otros. Con un límite muy bajo la descarga puede ir más lenta.\n%s"
            entries = arrayOf("10 KB/s", "50 KB/s", "100 KB/s", "500 KB/s", "Sin límite")
            entryValues = arrayOf("10", "50", "100", "500", "0")
            setDefaultValue("100")
            setOnPreferenceChangeListener { _, _ ->
                Thread { Thread.sleep(200); engine.applyUploadLimit() }.start()
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_STALL_SECONDS
            title = "Cancelar si la descarga no avanza en"
            entries = arrayOf("1 minuto", "3 minutos", "10 minutos", "30 minutos")
            entryValues = arrayOf("60", "180", "600", "1800")
            summary = "%s"
            setDefaultValue("180")
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_CACHE_MB
            title = "Espacio para capítulos bajados"
            entries = arrayOf("512 MB", "1 GB", "2 GB", "5 GB", "10 GB")
            entryValues = arrayOf("512", "1024", "2048", "5120", "10240")
            summary = "%s"
            setDefaultValue("2048")
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_PDF_WIDTH
            title = "Calidad de las páginas PDF"
            entries = arrayOf("Baja (1080 px)", "Media (1440 px)", "Alta (2048 px)", "Muy alta (2560 px)")
            entryValues = arrayOf("1080", "1440", "2048", "2560")
            summary = "%s"
            setDefaultValue("1440")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN
            title = "Dirección de Nyaa"
            summary = "Por defecto $DEFAULT_DOMAIN. Cámbiala solo si usas un espejo."
            dialogTitle = title
            setDefaultValue(DEFAULT_DOMAIN)
        }.also(screen::addPreference)

        Preference(screen.context).apply {
            title = "Borrar capítulos descargados"
            summary = "Elimina los archivos bajados por torrent (no los de la sección Descargas de Mihon)."
            setOnPreferenceClickListener {
                Thread {
                    reader.closeAll()
                    engine.clear()
                }.start()
                Toast.makeText(application, "Caché borrada", Toast.LENGTH_SHORT).show()
                true
            }
        }.also(screen::addPreference)
    }

    // Mihon no llega a usar estos métodos porque las funciones fetch* están sobrescritas.
    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()
    override fun pageListParse(response: Response) = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    companion object {
        private const val PACKAGE = "eu.kanade.tachiyomi.extension.all.nyaa"
        private const val DEFAULT_DOMAIN = "https://nyaa.si"
        private const val CATEGORY_ALL = "3_0"
        private const val RESULTS_PER_PAGE = 75

        private const val PREF_UPLOAD_KB = "upload_kb"
        private const val PREF_STALL_SECONDS = "stall_seconds"
        private const val PREF_CACHE_MB = "cache_mb"
        private const val PREF_PDF_WIDTH = "pdf_width"
        private const val PREF_DOMAIN = "domain"

        private val IMAGE_URL = Regex("""(https?://\S+?\.(?:jpe?g|png|webp|gif))""", RegexOption.IGNORE_CASE)
    }
}
