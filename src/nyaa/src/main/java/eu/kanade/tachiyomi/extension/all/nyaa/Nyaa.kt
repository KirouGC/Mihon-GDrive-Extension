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
import eu.kanade.tachiyomi.lib.archive.I18n
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
import java.util.concurrent.ConcurrentHashMap
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

    private val i18n = I18n { preferences.getString(I18n.PREF_KEY, I18n.AUTO)!! }.also { Texts.i18n = it }

    private val cacheRoot by lazy { File(application.cacheDir, "nyaa") }

    private val reader by lazy {
        ArchiveReader({ preferences.getString(PREF_PDF_WIDTH, "1440")!!.toIntOrNull() ?: 1440 }, i18n)
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
                    cells.getOrNull(3)?.text()?.let { t("Tamaño: ", "Size: ") + it },
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
        t("Categoría", "Category"),
        arrayOf(
            t("Toda Literature", "All Literature") to CATEGORY_ALL,
            t("Traducido al inglés", "English-translated") to "3_1",
            t("Traducido a otro idioma", "Non-English-translated") to "3_2",
            t("Raw (sin traducir)", "Raw") to "3_3",
        ),
    )

    private class QualityFilter : ValueFilter(
        t("Filtro", "Filter"),
        arrayOf(t("Todos", "No filter") to "0", t("Sin remakes", "No remakes") to "1", t("Solo trusted", "Trusted only") to "2"),
    )

    private class SortFilter : ValueFilter(
        t("Ordenar por", "Sort by"),
        arrayOf(
            t("Más recientes", "Most recent") to "id",
            "Seeders" to "seeders",
            t("Descargas", "Downloads") to "downloads",
            t("Tamaño", "Size") to "size",
        ),
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
                listOf(
                    "File size" to t("Tamaño", "Size"),
                    "Seeders" to "Seeders",
                    "Leechers" to "Leechers",
                    "Completed" to t("Descargas", "Downloads"),
                    "Date" to t("Fecha", "Date"),
                )
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
        val bytes = torrentBytes(torrentId)
        // Empezar a buscar peers ya, mientras el usuario elige capítulo.
        runCatching { engine.warmUp(torrentId, bytes) }
        val meta = TorrentMeta.parse(bytes)
        val chapters = meta.chapters()
        if (chapters.isEmpty()) throw IOException(t("Este torrent no tiene archivos CBZ, CBR, PDF ni imágenes", "This torrent has no CBZ, CBR, PDF or image files"))
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
            ?: throw IOException(t("Nyaa no tiene el archivo .torrent de este torrent", "Nyaa has no .torrent file for this torrent") + " (HTTP ${response.code})")
        if (bytes.firstOrNull() != 'd'.code.toByte()) throw IOException(t("Nyaa no devolvió un archivo .torrent válido", "Nyaa did not return a valid .torrent file"))
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

        if (kind == ChapterRef.ARCHIVE) {
            val index = byPath[chapterPath] ?: throw IOException(t("No se encontró en el torrent: ", "Not found in the torrent: ") + chapterPath)
            when (ArchiveKind.of(chapterPath)) {
                ArchiveKind.CBZ -> {
                    // Solo hace falta el final del archivo (el índice del ZIP); las imágenes se
                    // bajan a medida que se leen y el resto del CBZ sigue bajando de fondo.
                    engine.want(torrentId, bytes, listOf(index))
                    val zip = zipIndex(torrentId, bytes, index)
                    prefetchZip(torrentId, bytes, index, zip, 0)
                    List(zip.entries.size) { i -> Page(i, imageUrl = LocalPageInterceptor.pageUrl(LocalPageInterceptor.ZIP, torrentId, index, i)) }
                }
                ArchiveKind.PDF, ArchiveKind.CBR -> {
                    // Un PDF o un RAR no se pueden leer a medias: se bajan enteros, con toda la red enfocada en ellos.
                    val archiveKind = ArchiveKind.of(chapterPath)!!
                    val file = engine.awaitFile(torrentId, bytes, index)
                    val pageKind = if (archiveKind == ArchiveKind.PDF) LocalPageInterceptor.PDF else LocalPageInterceptor.RAR
                    List(reader.pageCount(archiveKind, file)) { i ->
                        Page(i, imageUrl = LocalPageInterceptor.pageUrl(pageKind, torrentId, index, i))
                    }
                }
                null -> throw IOException(t("Formato no soportado: ", "Unsupported format: ") + chapterPath)
            }
        } else {
            val images = byPath.filterKeys { it.substringBeforeLast('/', "") == chapterPath && isImageName(it) }
                .toSortedMap(NaturalOrder)
            if (images.isEmpty()) throw IOException(t("No hay imágenes en ", "No images in ") + chapterPath)
            engine.want(torrentId, bytes, images.values)
            images.values.mapIndexed { i, index -> Page(i, imageUrl = LocalPageInterceptor.imageUrl(torrentId, index)) }
        }
    }

    override fun imageRequest(page: Page): Request = Request.Builder().url(page.imageUrl!!).headers(headers).build()

    private val zipIndexes = ConcurrentHashMap<String, StreamingZip>()

    private fun zipIndex(torrentId: String, bytes: ByteArray, index: Int): StreamingZip = zipIndexes.getOrPut("$torrentId/$index") {
        val size = engine.info(torrentId, bytes).files().fileSize(index)
        StreamingZip.open(size) { offset, length -> engine.read(torrentId, bytes, index, offset, length) }
    }

    /** Pide por adelantado las piezas de las páginas que siguen a [page]. */
    private fun prefetchZip(torrentId: String, bytes: ByteArray, index: Int, zip: StreamingZip, page: Int) {
        zip.entries.drop(page).take(PREFETCH_PAGES).forEachIndexed { i, entry ->
            val range = zip.approximateRange(entry)
            engine.prefetch(torrentId, bytes, index, range.first, range.last - range.first + 1, i * 400)
        }
    }

    /** Lo llama [LocalPageInterceptor]; si el archivo se borró de la caché se vuelve a bajar. */
    private fun servePage(kind: String, torrentId: String, fileIndex: Int, page: Int?): Pair<ByteArray, String> {
        val bytes = torrentBytes(torrentId)
        return when (kind) {
            LocalPageInterceptor.ZIP -> {
                val zip = zipIndex(torrentId, bytes, fileIndex)
                val entry = zip.entries.getOrNull(page!!) ?: throw IOException(t("Página $page inexistente", "Page $page does not exist"))
                prefetchZip(torrentId, bytes, fileIndex, zip, page + 1)
                zip.read(entry) { offset, length -> engine.read(torrentId, bytes, fileIndex, offset, length) } to imageMime(entry.name)
            }
            LocalPageInterceptor.PDF -> reader.page(ArchiveKind.PDF, engine.awaitFile(torrentId, bytes, fileIndex), page!!)
            LocalPageInterceptor.RAR -> reader.page(ArchiveKind.CBR, engine.awaitFile(torrentId, bytes, fileIndex), page!!)
            else -> {
                val info = engine.info(torrentId, bytes)
                val size = info.files().fileSize(fileIndex)
                engine.read(torrentId, bytes, fileIndex, 0, size.toInt()) to imageMime(info.files().fileName(fileIndex))
            }
        }
    }

    // ============================== Ajustes ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = I18n.PREF_KEY
            title = t("Idioma / Language", "Language / Idioma")
            entries = I18n.ENTRIES
            entryValues = I18n.VALUES
            summary = t("%s\nVuelve a abrir esta pantalla para ver el cambio.", "%s\nReopen this screen to see the change.")
            setDefaultValue(I18n.AUTO)
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_UPLOAD_KB
            title = t("Límite de subida", "Upload limit")
            summary = t(
                "Mientras baja un capítulo el teléfono también comparte con otros. Con un límite muy bajo la descarga puede ir más lenta.\n%s",
                "While a chapter downloads the phone also shares it with others. A very low limit can slow the download down.\n%s",
            )
            entries = arrayOf("10 KB/s", "50 KB/s", "100 KB/s", "500 KB/s", t("Sin límite", "Unlimited"))
            entryValues = arrayOf("10", "50", "100", "500", "0")
            setDefaultValue("100")
            setOnPreferenceChangeListener { _, _ ->
                Thread { Thread.sleep(200); engine.applyUploadLimit() }.start()
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_STALL_SECONDS
            title = t("Cancelar si la descarga no avanza en", "Cancel if the download stalls for")
            entries = arrayOf(t("1 minuto", "1 minute"), t("3 minutos", "3 minutes"), t("10 minutos", "10 minutes"), t("30 minutos", "30 minutes"))
            entryValues = arrayOf("60", "180", "600", "1800")
            summary = "%s"
            setDefaultValue("180")
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_CACHE_MB
            title = t("Espacio para capítulos bajados", "Space for downloaded chapters")
            entries = arrayOf("512 MB", "1 GB", "2 GB", "5 GB", "10 GB")
            entryValues = arrayOf("512", "1024", "2048", "5120", "10240")
            summary = "%s"
            setDefaultValue("2048")
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_PDF_WIDTH
            title = t("Calidad de las páginas PDF", "PDF page quality")
            entries = arrayOf(
                t("Baja (1080 px)", "Low (1080 px)"),
                t("Media (1440 px)", "Medium (1440 px)"),
                t("Alta (2048 px)", "High (2048 px)"),
                t("Muy alta (2560 px)", "Very high (2560 px)"),
            )
            entryValues = arrayOf("1080", "1440", "2048", "2560")
            summary = "%s"
            setDefaultValue("1440")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN
            title = t("Dirección de Nyaa", "Nyaa address")
            summary = t("Por defecto $DEFAULT_DOMAIN. Cámbiala solo si usas un espejo.", "Default $DEFAULT_DOMAIN. Change it only if you use a mirror.")
            dialogTitle = title
            setDefaultValue(DEFAULT_DOMAIN)
        }.also(screen::addPreference)

        Preference(screen.context).apply {
            title = t("Borrar capítulos descargados", "Clear downloaded chapters")
            summary = t(
                "Elimina los archivos bajados por torrent (no los de la sección Descargas de Mihon).",
                "Deletes the files downloaded by torrent (not the ones in Mihon's Downloads).",
            )
            setOnPreferenceClickListener {
                Thread {
                    reader.closeAll()
                    zipIndexes.clear()
                    engine.clear()
                }.start()
                Toast.makeText(application, t("Caché borrada", "Cache cleared"), Toast.LENGTH_SHORT).show()
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
        private const val PREFETCH_PAGES = 3

        private const val PREF_UPLOAD_KB = "upload_kb"
        private const val PREF_STALL_SECONDS = "stall_seconds"
        private const val PREF_CACHE_MB = "cache_mb"
        private const val PREF_PDF_WIDTH = "pdf_width"
        private const val PREF_DOMAIN = "domain"

        private val IMAGE_URL = Regex("""(https?://\S+?\.(?:jpe?g|png|webp|gif))""", RegexOption.IGNORE_CASE)
    }
}
