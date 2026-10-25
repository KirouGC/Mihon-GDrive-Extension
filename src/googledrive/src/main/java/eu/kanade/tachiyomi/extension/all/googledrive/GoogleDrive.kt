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

    private val cacheRoot by lazy { File(application.cacheDir, "googledrive") }

    private val api by lazy { DriveApi(network.client, headers) { preferences.getString(PREF_API_KEY, "").orEmpty().trim() } }

    private val index by lazy { MangaIndex(api, File(cacheRoot, "index.json")) }

    private val files by lazy {
        FileCache(
            api = api,
            dir = File(cacheRoot, "files"),
            maxBytes = { (preferences.getString(PREF_CACHE_MB, "1024")!!.toLongOrNull() ?: 1024L) * 1024 * 1024 },
            pdfWidth = { preferences.getString(PREF_PDF_WIDTH, "1440")!!.toIntOrNull() ?: 1440 },
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
        SortFilter(),
        RescanFilter(),
        Filter.Header("Volver a escanear tarda más con carpetas grandes."),
    )

    private class SortFilter : Filter.Select<String>("Ordenar por", arrayOf("Nombre", "Más recientes"))

    private class RescanFilter : Filter.CheckBox("Volver a escanear las carpetas de Drive")

    private fun loadIndex(force: Boolean): List<MangaEntry> {
        val roots = roots
        if (roots.isEmpty()) {
            throw Exception("Pega el enlace de tu carpeta de Drive en los ajustes de la extensión")
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
        update_strategy = if (key.startsWith("file:")) UpdateStrategy.ONLY_FETCH_ONCE else UpdateStrategy.ALWAYS_UPDATE
        initialized = true
    }

    private fun describe(entry: MangaEntry): String = buildString {
        if (entry.path.isNotEmpty()) append("Carpeta: ").append(entry.path)
        if (entry.chapters > 1) append("\n").append(entry.chapters).append(" archivos")
    }.trim()

    // ============================== Manga ==============================

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.fromCallable {
        index.find(manga.url)?.toSManga() ?: manga.apply { initialized = true }
    }

    override fun getMangaUrl(manga: SManga): String {
        val parts = manga.url.split(':')
        return when (parts[0]) {
            "folder" -> "$baseUrl/drive/folders/${parts[1]}" + resourceKeyParam(parts.getOrNull(2))
            else -> "$baseUrl/file/d/${parts[2]}/view" + resourceKeyParam(parts.getOrNull(3))
        }
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val parts = manga.url.split(':')
        when (parts[0]) {
            "folder" -> {
                val folder = DriveRef(parts[1], parts.getOrNull(2))
                api.listFolder(folder)
                    .filter { it.kind != null }
                    .sortedWith(compareByDescending(NaturalOrder) { it.name })
                    .map { it.toSChapter() }
            }
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

    override fun getChapterUrl(chapter: SChapter): String {
        val parts = chapter.url.split(':')
        return "$baseUrl/file/d/${parts[1]}/view" + resourceKeyParam(parts.getOrNull(2))
    }

    // ============================== Páginas ==============================

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val parts = chapter.url.split(':')
        val kind = ArchiveKind.fromCode(parts[0])
        val ref = DriveRef(parts[1], parts.getOrNull(2))
        val count = files.pageCount(kind, ref)
        List(count) { i -> Page(i, imageUrl = PageInterceptor.pageUrl(kind, ref, i).toString()) }
    }

    override fun imageRequest(page: Page): Request = Request.Builder().url(page.imageUrl!!).headers(headers).build()

    private fun resourceKeyParam(key: String?) = key?.let { "?resourcekey=$it" } ?: ""

    private fun String.normalized(): String =
        Normalizer.normalize(lowercase().trim(), Normalizer.Form.NFD).replace(DIACRITICS, "")

    // ============================== Ajustes ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_FOLDERS
            title = "Carpetas de Google Drive"
            summary = "Enlaces de carpetas compartidas por enlace, uno por línea. Se buscan mangas en todas sus subcarpetas."
            dialogTitle = title
            setDefaultValue("")
            setOnPreferenceChangeListener { _, value ->
                val count = value.toString().split('\n', ',', ' ').count { DriveRef.parse(it) != null }
                toast(if (count == 0) "No se reconoció ningún enlace de Drive" else "Carpetas: $count. Vuelve a abrir la fuente.")
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_API_KEY
            title = "Clave de API de Google (recomendada)"
            summary = "Opcional. Con una clave de la Drive API las carpetas grandes se listan completas y más rápido. " +
                "Sin clave se usa la vista pública de la carpeta."
            dialogTitle = title
            setDefaultValue("")
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_REFRESH_HOURS
            title = "Volver a escanear automáticamente"
            entries = arrayOf("Cada hora", "Cada 6 horas", "Cada día", "Cada semana", "Solo a mano (filtro)")
            entryValues = arrayOf("1", "6", "24", "168", "-1")
            summary = "%s"
            setDefaultValue("24")
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_PDF_WIDTH
            title = "Calidad de las páginas PDF"
            entries = arrayOf("Baja (1080 px)", "Media (1440 px)", "Alta (2048 px)", "Muy alta (2560 px)")
            entryValues = arrayOf("1080", "1440", "2048", "2560")
            summary = "%s"
            setDefaultValue("1440")
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_CACHE_MB
            title = "Espacio para archivos descargados"
            entries = arrayOf("256 MB", "512 MB", "1 GB", "2 GB", "5 GB")
            entryValues = arrayOf("256", "512", "1024", "2048", "5120")
            summary = "%s"
            setDefaultValue("1024")
        }.also(screen::addPreference)

        Preference(screen.context).apply {
            title = "Borrar archivos en caché"
            summary = "Elimina los CBZ y PDF ya descargados y el índice de mangas."
            setOnPreferenceClickListener {
                Thread {
                    files.clear()
                    File(cacheRoot, "index.json").delete()
                }.start()
                toast("Caché borrada")
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
