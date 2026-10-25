package eu.kanade.tachiyomi.extension.all.googledrive

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.lib.archive.ArchiveKind
import eu.kanade.tachiyomi.lib.archive.I18n
import eu.kanade.tachiyomi.lib.archive.NaturalOrder
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.io.File
import java.text.Normalizer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GoogleDrive : HttpSource(), ConfigurableSource {

    override val name = "Google Drive"

    override val lang = "all"

    override val baseUrl = "https://drive.google.com"

    override val supportsLatest = true

    private val application: Application by lazy {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Application
    }

    // Mihon guarda la pantalla de ajustes de la fuente en "source_<id>".
    private val preferences: SharedPreferences by lazy {
        application.getSharedPreferences("source_$id", Context.MODE_PRIVATE)
    }

    private val i18n = I18n { preferences.getString(I18n.PREF_KEY, I18n.AUTO)!! }

    private fun t(es: String, en: String) = i18n.t(es, en)

    private val cacheRoot by lazy { File(application.cacheDir, "googledrive") }

    private val api by lazy { DriveApi(network.client, headers, { preferences.getString(PREF_API_KEY, "").orEmpty().trim() }, i18n) }

    private val index by lazy { MangaIndex(api, File(cacheRoot, "index.json")) }

    private val files by lazy {
        FileCache(
            api = api,
            dir = File(cacheRoot, "files"),
            maxBytes = { (preferences.getString(PREF_CACHE_MB, "1024")!!.toLongOrNull() ?: 1024L) * 1024 * 1024 },
            pdfWidth = { preferences.getString(PREF_PDF_WIDTH, "1440")!!.toIntOrNull() ?: 1440 },
            i18n = i18n,
        )
    }

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor(PageInterceptor(files))
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    private val roots: List<DriveRef>
        get() = preferences.getString(PREF_FOLDERS, "").orEmpty()
            .split('\n', ',', ' ')
            .mapNotNull { DriveRef.parse(it) }
            .distinctBy { it.id }

    // ============================== Catálogo ==============================

    override fun fetchPopularManga(page: Int): Observable<MangasPage> =
        fetchSearchManga(page, "", FilterList())

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.fromCallable {
        paginate(loadIndex(force = false).sortedByDescending { it.modified }, page)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        Observable.fromCallable {
            val force = page == 1 && filters.any { it is RescanFilter && it.state }
            val needle = query.normalized()
            val entries = loadIndex(force).filter {
                needle.isEmpty() || it.title.normalized().contains(needle) || it.path.normalized().contains(needle)
            }
            val sorted = when (filters.filterIsInstance<SortFilter>().firstOrNull()?.state) {
                1 -> entries.sortedByDescending { it.modified }
                else -> entries
            }
            paginate(sorted, page)
        }

    override fun getFilterList() = FilterList(
        SortFilter(t("Ordenar por", "Sort by"), arrayOf(t("Nombre", "Name"), t("Más recientes", "Most recent"))),
        RescanFilter(t("Volver a escanear las carpetas de Drive", "Rescan the Drive folders")),
        Filter.Header(t("Volver a escanear tarda más con carpetas grandes.", "Rescanning takes longer with large folders.")),
    )

    private class SortFilter(name: String, values: Array<String>) : Filter.Select<String>(name, values)

    private class RescanFilter(name: String) : Filter.CheckBox(name)

    private fun loadIndex(force: Boolean): List<MangaEntry> {
        val roots = roots
        if (roots.isEmpty()) {
            throw Exception(t("Pega el enlace de tu carpeta de Drive en los ajustes de la extensión", "Paste your Drive folder link in the extension settings"))
        }
        val hours = preferences.getString(PREF_REFRESH_HOURS, "24")!!.toLongOrNull() ?: 24L
        val maxAge = if (hours < 0) -1 else TimeUnit.HOURS.toMillis(hours)
        return index.get(roots, maxAge, force)
    }

    private fun paginate(entries: List<MangaEntry>, page: Int): MangasPage {
        val chunk = entries.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
        return MangasPage(chunk.map { it.toSManga() }, entries.size > page * PAGE_SIZE)
    }

    private fun MangaEntry.toSManga() = SManga.create().apply {
        url = key
        title = this@toSManga.title
        thumbnail_url = cover
        description = describe(this@toSManga)
        update_strategy = if (key.startsWith("folder:")) UpdateStrategy.ALWAYS_UPDATE else UpdateStrategy.ONLY_FETCH_ONCE
        initialized = true
    }

    private fun describe(entry: MangaEntry): String = buildString {
        if (entry.path.isNotEmpty()) append(t("Carpeta: ", "Folder: ")).append(entry.path)
        if (entry.chapters > 1) append("\n").append(entry.chapters).append(t(" archivos", " files"))
    }.trim()

    // ============================== Manga ==============================

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.fromCallable {
        index.find(manga.url)?.toSManga() ?: manga.apply { initialized = true }
    }

    override fun getMangaUrl(manga: SManga): String {
        val parts = manga.url.split(':')
        return when (parts[0]) {
            "folder", "imgdir" -> "$baseUrl/drive/folders/${parts[1]}" + resourceKeyParam(parts.getOrNull(2))
            else -> "$baseUrl/file/d/${parts[2]}/view" + resourceKeyParam(parts.getOrNull(3))
        }
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val parts = manga.url.split(':')
        when (parts[0]) {
            "folder" -> {
                val children = api.listFolder(DriveRef(parts[1], parts.getOrNull(2)))
                val archives = children.filter { it.kind != null }.map { it to it.toSChapter() }
                val imageFolders = imageChapterFolders(children.filter { it.isFolder }).map { it to it.toImageChapter() }
                (archives + imageFolders)
                    .sortedWith(compareByDescending(NaturalOrder) { it.first.name })
                    .map { it.second }
            }
            "imgdir" -> listOf(
                SChapter.create().apply {
                    url = "img:" + manga.url.removePrefix("imgdir:")
                    name = manga.title
                    chapter_number = 1f
                },
            )
            else -> listOf(
                SChapter.create().apply {
                    url = manga.url.removePrefix("file:")
                    name = manga.title
                    chapter_number = 1f
                },
            )
        }
    }

    private fun DriveItem.toSChapter() = SChapter.create().apply {
        url = "${kind!!.code}:$id" + (resourceKey?.let { ":$it" } ?: "")
        name = this@toSChapter.name.substringBeforeLast('.')
        date_upload = modified
    }

    private fun DriveItem.toImageChapter() = SChapter.create().apply {
        url = "img:$id" + (resourceKey?.let { ":$it" } ?: "")
        name = this@toImageChapter.name
        date_upload = modified
    }

    /** De las subcarpetas, las que son capítulos de imágenes (se listan de a varias a la vez). */
    private fun imageChapterFolders(folders: List<DriveItem>): List<DriveItem> {
        if (folders.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(4, folders.size))
        try {
            return pool.invokeAll(folders.map { Callable { it to api.listFolder(DriveRef(it.id, it.resourceKey)) } })
                .map { it.get() }
                .filter { (_, children) -> MangaIndex.isImageChapter(children) }
                .map { it.first }
        } finally {
            pool.shutdownNow()
        }
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val parts = chapter.url.split(':')
        return if (parts[0] == "img") {
            "$baseUrl/drive/folders/${parts[1]}" + resourceKeyParam(parts.getOrNull(2))
        } else {
            "$baseUrl/file/d/${parts[1]}/view" + resourceKeyParam(parts.getOrNull(2))
        }
    }

    // ============================== Páginas ==============================

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val parts = chapter.url.split(':')
        val ref = DriveRef(parts[1], parts.getOrNull(2))
        if (parts[0] == "img") {
            val images = api.listFolder(ref).filter { it.isImage && !it.isFolder }.sortedWith(compareBy(NaturalOrder) { it.name })
            if (images.isEmpty()) throw Exception(t("La carpeta no tiene imágenes", "The folder has no images"))
            return@fromCallable images.mapIndexed { i, image ->
                Page(i, imageUrl = PageInterceptor.imageUrl(DriveRef(image.id, image.resourceKey)).toString())
            }
        }
        val kind = ArchiveKind.fromCode(parts[0])
        val count = files.pageCount(kind, ref)
        List(count) { i -> Page(i, imageUrl = PageInterceptor.pageUrl(kind, ref, i).toString()) }
    }

    override fun imageRequest(page: Page): Request = Request.Builder().url(page.imageUrl!!).headers(headers).build()

    private fun resourceKeyParam(key: String?) = key?.let { "?resourcekey=$it" } ?: ""

    private fun String.normalized(): String =
        Normalizer.normalize(lowercase().trim(), Normalizer.Form.NFD).replace(DIACRITICS, "")

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

        EditTextPreference(screen.context).apply {
            key = PREF_FOLDERS
            title = t("Carpetas de Google Drive", "Google Drive folders")
            summary = t(
                "Enlaces de carpetas compartidas por enlace, uno por línea. Se buscan mangas en todas sus subcarpetas.",
                "Links to folders shared by link, one per line. Manga is searched in all their subfolders.",
            )
            dialogTitle = title
            setDefaultValue("")
            setOnPreferenceChangeListener { _, value ->
                val count = value.toString().split('\n', ',', ' ').count { DriveRef.parse(it) != null }
                toast(if (count == 0) t("No se reconoció ningún enlace de Drive", "No Drive link was recognized") else t("Carpetas: $count. Vuelve a abrir la fuente.", "Folders: $count. Reopen the source."))
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_API_KEY
            title = t("Clave de API de Google (recomendada)", "Google API key (recommended)")
            summary = t(
                "Opcional. Con una clave de la Drive API las carpetas grandes se listan completas y más rápido. " +
                    "Sin clave se usa la vista pública de la carpeta.",
                "Optional. With a Drive API key large folders are listed completely and faster. " +
                    "Without a key the folder's public view is used.",
            )
            dialogTitle = title
            setDefaultValue("")
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_REFRESH_HOURS
            title = t("Volver a escanear automáticamente", "Rescan automatically")
            entries = arrayOf(
                t("Cada hora", "Every hour"),
                t("Cada 6 horas", "Every 6 hours"),
                t("Cada día", "Every day"),
                t("Cada semana", "Every week"),
                t("Solo a mano (filtro)", "Manually only (filter)"),
            )
            entryValues = arrayOf("1", "6", "24", "168", "-1")
            summary = "%s"
            setDefaultValue("24")
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

        ListPreference(screen.context).apply {
            key = PREF_CACHE_MB
            title = t("Espacio para archivos descargados", "Space for downloaded files")
            entries = arrayOf("256 MB", "512 MB", "1 GB", "2 GB", "5 GB")
            entryValues = arrayOf("256", "512", "1024", "2048", "5120")
            summary = "%s"
            setDefaultValue("1024")
        }.also(screen::addPreference)

        Preference(screen.context).apply {
            title = t("Borrar archivos en caché", "Clear cached files")
            summary = t("Elimina los CBZ y PDF ya descargados y el índice de mangas.", "Deletes the downloaded CBZ and PDF files and the manga index.")
            setOnPreferenceClickListener {
                Thread {
                    files.clear()
                    File(cacheRoot, "index.json").delete()
                }.start()
                toast(t("Caché borrada", "Cache cleared"))
                true
            }
        }.also(screen::addPreference)
    }

    private fun toast(text: String) = Toast.makeText(application, text, Toast.LENGTH_SHORT).show()

    // Mihon no llega a usar estos métodos porque las funciones fetch* están sobrescritas.
    override fun popularMangaRequest(page: Int) = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response) = throw UnsupportedOperationException()
    override fun mangaDetailsParse(response: Response) = throw UnsupportedOperationException()
    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()
    override fun pageListParse(response: Response) = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    companion object {
        private const val PAGE_SIZE = 50
        private const val PREF_FOLDERS = "folders"
        private const val PREF_API_KEY = "api_key"
        private const val PREF_REFRESH_HOURS = "refresh_hours"
        private const val PREF_PDF_WIDTH = "pdf_width"
        private const val PREF_CACHE_MB = "cache_mb"
        private val DIACRITICS = Regex("""\p{Mn}+""")
    }
}
