package eu.kanade.tachiyomi.extension.all.googledrive

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Un elemento (archivo o carpeta) dentro de una carpeta de Drive. */
data class DriveItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long = -1,
    val modified: Long = 0,
    val resourceKey: String? = null,
) {
    val isFolder get() = mimeType == FOLDER_MIME

    val kind: FileKind? get() = FileKind.of(name, mimeType)

    val isImage: Boolean
        get() = mimeType.startsWith("image/") || IMAGE_EXTENSIONS.any { name.endsWith(".$it", ignoreCase = true) }
}

enum class FileKind(val code: String) {
    CBZ("cbz"),
    PDF("pdf"),
    ;

    companion object {
        fun of(name: String, mimeType: String): FileKind? {
            val lower = name.lowercase(Locale.ROOT)
            return when {
                lower.endsWith(".cbz") || lower.endsWith(".zip") -> CBZ
                lower.endsWith(".pdf") || mimeType == "application/pdf" -> PDF
                else -> null
            }
        }

        fun fromCode(code: String) = entries.first { it.code == code }
    }
}

const val FOLDER_MIME = "application/vnd.google-apps.folder"
private const val SHORTCUT_MIME = "application/vnd.google-apps.shortcut"

val IMAGE_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp", "jxl", "heic")

/** Referencia a una carpeta o archivo de Drive tal como la pega el usuario. */
data class DriveRef(val id: String, val resourceKey: String?) {
    companion object {
        private val ID_PATTERNS = listOf(
            Regex("""/folders/([\w-]{10,})"""),
            Regex("""/file/d/([\w-]{10,})"""),
            Regex("""/d/([\w-]{10,})"""),
            Regex("""[?&]id=([\w-]{10,})"""),
        )
        private val BARE_ID = Regex("""^[\w-]{10,}$""")
        private val RESOURCE_KEY = Regex("""[?&]resourcekey=([\w-]+)""")

        fun parse(input: String): DriveRef? {
            val text = input.trim()
            if (text.isEmpty()) return null
            val id = ID_PATTERNS.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) }
                ?: text.takeIf { BARE_ID.matches(it) }
                ?: return null
            return DriveRef(id, RESOURCE_KEY.find(text)?.groupValues?.get(1))
        }
    }
}

/**
 * Acceso de solo lectura a carpetas públicas ("cualquier persona con el enlace").
 *
 * Con clave de API se usa la Drive API v3, que pagina sin límite y resuelve accesos directos.
 * Sin clave se lee la vista embebida de la carpeta (drive.google.com/embeddedfolderview).
 */
class DriveApi(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val apiKey: () -> String,
) {

    fun listFolder(folder: DriveRef): List<DriveItem> {
        val key = apiKey()
        return if (key.isNotBlank()) listWithApi(folder, key) else listWithEmbeddedView(folder)
    }

    private fun listWithApi(folder: DriveRef, key: String): List<DriveItem> {
        val items = mutableListOf<DriveItem>()
        var pageToken: String? = null
        do {
            val url = API_FILES.toHttpUrl().newBuilder()
                .addQueryParameter("q", "'${folder.id}' in parents and trashed = false")
                .addQueryParameter("fields", API_FIELDS)
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("supportsAllDrives", "true")
                .addQueryParameter("includeItemsFromAllDrives", "true")
                .addQueryParameter("key", key)
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val request = Request.Builder().url(url).headers(apiHeaders(folder)).build()
            val json = client.newCall(request).execute().use { response ->
                JSONObject(response.bodyOrThrow())
            }
            val files = json.optJSONArray("files")
            for (i in 0 until (files?.length() ?: 0)) {
                val file = files!!.getJSONObject(i)
                var id = file.getString("id")
                var mime = file.optString("mimeType")
                var resourceKey = file.optString("resourceKey").ifEmpty { null }
                if (mime == SHORTCUT_MIME) {
                    val target = file.optJSONObject("shortcutDetails") ?: continue
                    id = target.optString("targetId")
                    if (id.isEmpty()) continue
                    mime = target.optString("targetMimeType")
                    resourceKey = target.optString("targetResourceKey").ifEmpty { null }
                }
                items += DriveItem(
                    id = id,
                    name = file.optString("name"),
                    mimeType = mime,
                    size = file.optString("size").toLongOrNull() ?: -1,
                    modified = parseDate(file.optString("modifiedTime")),
                    resourceKey = resourceKey,
                )
            }
            pageToken = json.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)
        return items
    }

    private fun listWithEmbeddedView(folder: DriveRef): List<DriveItem> {
        val url = "https://drive.google.com/embeddedfolderview".toHttpUrl().newBuilder()
            .addQueryParameter("id", folder.id)
            .apply { folder.resourceKey?.let { addQueryParameter("resourcekey", it) } }
            .build()
        val html = client.newCall(Request.Builder().url(url).headers(headers).build()).execute()
            .use { it.bodyOrThrow() }
        val document = Jsoup.parse(html, url.toString())
        return document.select("div.flip-entry").mapNotNull { entry ->
            val id = entry.id().removePrefix("entry-").ifEmpty { return@mapNotNull null }
            val name = entry.selectFirst(".flip-entry-title")?.text() ?: return@mapNotNull null
            val href = entry.selectFirst("a")?.attr("href").orEmpty()
            val mime = when {
                "/folders/" in href -> FOLDER_MIME
                name.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
                else -> ""
            }
            DriveItem(id = id, name = name, mimeType = mime, resourceKey = RESOURCE_KEY_PARAM.find(href)?.groupValues?.get(1))
        }
    }

    /** Pide el contenido de un archivo. La respuesta debe cerrarla quien llama. */
    fun download(file: DriveRef): Response {
        val key = apiKey()
        if (key.isNotBlank()) {
            val url = "$API_FILES/${file.id}".toHttpUrl().newBuilder()
                .addQueryParameter("alt", "media")
                .addQueryParameter("supportsAllDrives", "true")
                .addQueryParameter("key", key)
                .build()
            val response = client.newCall(Request.Builder().url(url).headers(apiHeaders(file)).build()).execute()
            if (response.isSuccessful) return response
            // Algunos archivos públicos (p. ej. marcados por el antivirus) no se pueden bajar
            // por la API; se intenta igual por la descarga web.
            response.close()
        }
        return downloadFromWeb(file)
    }

    private fun downloadFromWeb(file: DriveRef): Response {
        val url = "https://drive.usercontent.google.com/download".toHttpUrl().newBuilder()
            .addQueryParameter("id", file.id)
            .addQueryParameter("export", "download")
            .addQueryParameter("confirm", "t")
            .apply { file.resourceKey?.let { addQueryParameter("resourcekey", it) } }
            .build()
        val response = client.newCall(Request.Builder().url(url).headers(headers).build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("Drive respondió HTTP ${response.code} al descargar ${file.id}")
        }
        if (response.header("Content-Type").orEmpty().startsWith("text/html")) {
            // Página de "no se puede analizar en busca de virus": reenviar su formulario.
            val html = response.use { it.body.string() }
            val form = Jsoup.parse(html, url.toString()).selectFirst("form#download-form, form[action*=download]")
                ?: throw IOException("Drive no permite descargar este archivo (¿está compartido por enlace?)")
            val confirmUrl = form.absUrl("action").toHttpUrl().newBuilder().apply {
                form.select("input[type=hidden]").forEach { addQueryParameter(it.attr("name"), it.attr("value")) }
            }.build()
            val confirmed = client.newCall(Request.Builder().url(confirmUrl).headers(headers).build()).execute()
            if (!confirmed.isSuccessful || confirmed.header("Content-Type").orEmpty().startsWith("text/html")) {
                confirmed.close()
                throw IOException("Drive no permite descargar este archivo (límite de descargas o permisos)")
            }
            return confirmed
        }
        return response
    }

    private fun apiHeaders(ref: DriveRef): Headers = headers.newBuilder().apply {
        ref.resourceKey?.let { set("X-Goog-Drive-Resource-Keys", "${ref.id}/$it") }
    }.build()

    private fun Response.bodyOrThrow(): String {
        val text = body.string()
        if (!isSuccessful) {
            val message = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
            throw IOException("Google Drive: HTTP $code${message?.let { " - $it" } ?: ""}")
        }
        return text
    }

    private fun parseDate(value: String): Long {
        if (value.isEmpty()) return 0
        return runCatching { DATE_FORMAT.get()!!.parse(value)?.time ?: 0 }.getOrDefault(0)
    }

    companion object {
        private const val API_FILES = "https://www.googleapis.com/drive/v3/files"
        private const val API_FIELDS =
            "nextPageToken,files(id,name,mimeType,size,modifiedTime,resourceKey," +
                "shortcutDetails(targetId,targetMimeType,targetResourceKey))"
        private val RESOURCE_KEY_PARAM = Regex("""[?&]resourcekey=([\w-]+)""")

        private val DATE_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }

        fun thumbnailUrl(ref: DriveRef, width: Int = 600): String =
            "https://drive.google.com/thumbnail?id=${ref.id}&sz=w$width" +
                (ref.resourceKey?.let { "&resourcekey=$it" } ?: "")
    }
}
